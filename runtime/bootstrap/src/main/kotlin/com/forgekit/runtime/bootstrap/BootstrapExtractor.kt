package com.forgekit.runtime.bootstrap

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipFile

/**
 * Mode-preserving, symlink-aware extraction of a Termux bootstrap archive into $PREFIX
 * (ARCHITECTURE §33: extract/bootstrap).
 *
 * Supports BOTH bootstrap generations:
 *  - v0.119+ (apt-android-7): entries at the archive ROOT, modes in the central-directory
 *    external attributes, symlinks declared in a `SYMLINKS.txt` manifest with lines of
 *    the form `<target>←<link>` (paths prefix-relative, target relative to the link's
 *    parent directory). The archive carries an ELF stub prefix (Termux ships it as
 *    libtermux-bootstrap.so) — irrelevant for central-directory-driven reading.
 *  - legacy: everything under one top-level directory (e.g. `bootstrap-aarch64/`),
 *    symlinks encoded as zip entries with S_IFLNK set in the external attributes.
 *
 * Losing exec bits or symlinks breaks the runtime — all behaviors are preserved, and
 * the extractor never silently degrades.
 */
public class BootstrapExtractor {

    /** Outcome of one extraction run. */
    public data class Result(
        val filesWritten: Int,
        val symlinksCreated: Int,
        val bytesWritten: Long,
    )

    /** Extracts [zipFile] into [targetPrefix]. */
    @Throws(IOException::class)
    public fun extract(
        zipFile: Path,
        targetPrefix: Path,
        onProgress: (filesDone: Int) -> Unit = {},
    ): Result {
        Files.createDirectories(targetPrefix)
        var files = 0
        var symlinks = 0
        var bytes = 0L
        val central = ZipCentralDirectory.parse(zipFile)
        ZipFile(zipFile.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()

            // A legacy bootstrap nests everything under one top-level dir. Strip it only
            // when EVERY entry shares the same single first path segment; the v0.119+
            // layout has entries at the root and is extracted as-is.
            val firstSegments = entries.map { it.name.substringBefore('/') }.toSet()
            val topDir = if (firstSegments.size == 1) firstSegments.first().let { "$it/" } else null

            for (entry in entries) {
                if (entry.isDirectory) continue
                val relativeName = strip(entry.name, topDir)
                if (relativeName.isBlank()) continue
                val destination = targetPrefix.resolve(relativeName).normalize()
                if (!destination.startsWith(targetPrefix)) {
                    throw IOException("bootstrap entry escapes target dir: ${entry.name}")
                }
                if (relativeName == SYMLINKS_MANIFEST) {
                    // v0.119+ symlink manifest: create the links it declares; the manifest
                    // itself is bootstrap-internal and is not written into the prefix
                    val manifest = zip.getInputStream(entry).readBytes().decodeToString()
                    symlinks += createSymlinksFromManifest(manifest, targetPrefix)
                    continue
                }
                val mode = central.unixMode(entry.name)
                if (mode != null && (mode and S_IFMT) == S_IFLNK) {
                    // legacy format: symlink entry, content = link target
                    val target = zip.getInputStream(entry).readBytes().decodeToString()
                    createSymlink(target, destination)
                    symlinks++
                } else {
                    destination.parent?.let { Files.createDirectories(it) }
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(destination).use { output ->
                            bytes += input.copyTo(output, 65536)
                        }
                    }
                    mode?.let { applyMode(destination, it and PERMISSION_MASK) }
                    files++
                    if (files % 200 == 0) onProgress(files)
                }
            }
        }
        onProgress(files)
        return Result(files, symlinks, bytes)
    }

    /**
     * Parses the v0.119+ `SYMLINKS.txt` manifest. Each line: `<target>←<link>` —
     * `link` is relative to the prefix, `target` is stored verbatim as the symlink
     * content (resolved relative to the link's parent at access time, POSIX semantics).
     */
    private fun createSymlinksFromManifest(manifest: String, targetPrefix: Path): Int {
        var created = 0
        for (rawLine in manifest.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val arrow = line.indexOf(SYMLINK_ARROW)
            require(arrow > 0) { "malformed SYMLINKS.txt line: $line" }
            val target = line.substring(0, arrow).trim()
            val link = line.substring(arrow + SYMLINK_ARROW.length).trim()
            require(target.isNotEmpty() && link.isNotEmpty()) { "malformed SYMLINKS.txt line: $line" }
            val destination = targetPrefix.resolve(Paths.get(link)).normalize()
            if (!destination.startsWith(targetPrefix)) {
                throw IOException("symlink manifest escapes target dir: $link")
            }
            createSymlink(target, destination)
            created++
        }
        return created
    }

    private fun strip(name: String, topDir: String?): String =
        if (topDir != null && name.startsWith(topDir)) name.removePrefix(topDir) else name

    private fun createSymlink(target: String, destination: Path) {
        if (Files.exists(destination)) Files.delete(destination)
        destination.parent?.let { Files.createDirectories(it) }
        // `target` is already relative to the link's parent directory (both formats);
        // store it verbatim so resolution matches POSIX/Termux semantics.
        try {
            Files.createSymbolicLink(destination, Paths.get(target))
        } catch (_: java.nio.file.FileSystemException) {
            // Filesystems without symlink support: fail loudly — a Termux prefix without
            // symlinks is broken; we never silently degrade.
            throw IOException("symlink unsupported on this filesystem: $destination -> $target")
        }
    }

    private fun applyMode(destination: Path, permBits: Int) {
        val perms = mutableSetOf<PosixFilePermission>()
        if (permBits and 0x100 != 0) perms += PosixFilePermission.OWNER_READ
        if (permBits and 0x080 != 0) perms += PosixFilePermission.OWNER_WRITE
        if (permBits and 0x040 != 0) perms += PosixFilePermission.OWNER_EXECUTE
        if (permBits and 0x020 != 0) perms += PosixFilePermission.GROUP_READ
        if (permBits and 0x010 != 0) perms += PosixFilePermission.GROUP_WRITE
        if (permBits and 0x008 != 0) perms += PosixFilePermission.GROUP_EXECUTE
        if (permBits and 0x004 != 0) perms += PosixFilePermission.OTHERS_READ
        if (permBits and 0x002 != 0) perms += PosixFilePermission.OTHERS_WRITE
        if (permBits and 0x001 != 0) perms += PosixFilePermission.OTHERS_EXECUTE
        runCatching { Files.setPosixFilePermissions(destination, perms) }
    }

    public companion object {
        private const val S_IFMT = 0xF000        // file type mask (0o170000)
        private const val S_IFLNK = 0xA000       // symlink          (0o120000)
        private const val PERMISSION_MASK = 0x1FF // rwxrwxrwx       (0o777)
        private const val SYMLINKS_MANIFEST = "SYMLINKS.txt"
        private const val SYMLINK_ARROW = "←"    // U+2190, Termux manifest separator
    }
}
