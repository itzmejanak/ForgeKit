package com.forgekit.plugin.registry

import com.forgekit.plugin.api.PluginCapability
import com.forgekit.plugin.api.PluginDescriptor
import com.forgekit.plugin.api.PluginId
import com.forgekit.plugin.api.PluginStatus
import com.forgekit.plugin.api.PluginTrustLevel
import com.forgekit.plugin.api.PluginVersion
import com.forgekit.plugin.manifest.ManifestParser
import com.forgekit.plugin.manifest.PluginManifest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * A git repository carrying `.forge` archives (§58 GitSource).
 *
 * REAL `git` subprocesses — clone into a scratch dir, then scan the
 * worktree exactly like [LocalFileSource] does (same strict manifest
 * parsing, same digest discipline). No jgit, no shims: if `git` is not
 * installed the source says so instead of pretending.
 */
public class GitSource(
    /** Repository URL (any form git itself understands: https, ssh, local path). */
    private val repositoryUrl: String,
    /** Where clones live; one clone per repository is reused across fetches. */
    private val cloneRoot: Path,
    private val parser: ManifestParser = ManifestParser(),
    private val gitBinary: String = "git",
    override val sourceId: String = "git:${repositoryUrl.substringAfterLast('/')}",
) : PluginSource {

    override public val available: Boolean get() = gitPresent()

    private data class Scanned(val file: Path, val manifest: PluginManifest?, val problem: String?, val sha256: String)

    private var scanned: List<Scanned> = emptyList()

    /** Clones (or re-uses) the repository and scans it; returns archive count. */
    public suspend fun refresh(): Int {
        if (!available) throw RegistryError("git is not installed on this device", gitBinary)
        val cloneDir = cloneRoot.resolve(repoDirName())
        if (!Files.isDirectory(cloneDir.resolve(".git"))) {
            Files.createDirectories(cloneRoot)
            git(listOf("clone", "--depth", "1", repositoryUrl, cloneDir.toString()), cloneRoot)
        }
        val result = mutableListOf<Scanned>()
        scanTree(cloneDir, result)
        scanned = result.sortedBy { it.file.toString() }
        return result.size
    }

    override public suspend fun search(query: String): List<PluginDescriptor> {
        if (scanned.isEmpty()) refresh()
        val q = query.trim().lowercase()
        return scanned.filter { it.manifest != null && it.problem == null }
            .map { descriptorOf(it) }
            .filter { d ->
                q.isBlank() || d.id.raw.contains(q) || d.name.lowercase().contains(q) ||
                    d.tags.any { it.lowercase().contains(q) }
            }
    }

    override public suspend fun fetch(id: String, version: String, targetDir: Path): PluginArtifact {
        if (scanned.isEmpty()) refresh()
        val match = scanned.filter { it.manifest?.id == id }
            .sortedWith(compareByDescending { it.manifest!!.version })
            .firstOrNull { version == "latest" || it.manifest!!.version == version }
            ?: throw RegistryError(
                "plugin '$id@$version' not found in git repository",
                repositoryUrl,
            )
        match.problem?.let { throw RegistryError("archive is not a valid .forge package", it) }

        Files.createDirectories(targetDir)
        val target = targetDir.resolve("${id.replace('.', '_')}-${match.manifest!!.version}.forge")
        Files.copy(match.file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return PluginArtifact(
            pluginId = id,
            version = match.manifest!!.version,
            file = target,
            sha256 = match.sha256,
            origin = sourceId,
        )
    }

    // ---- internals ----------------------------------------------------------

    private fun repoDirName(): String {
        val raw = repositoryUrl.substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        return raw.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }

    private fun scanTree(root: Path, out: MutableList<Scanned>) {
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val file = iterator.next()
                val name = file.fileName.toString()
                if (!Files.isRegularFile(file) || !name.endsWith(".forge")) continue
                if (file.toString().contains("/.git/")) continue
                val parsed = runCatching { readManifest(file) }
                out += Scanned(
                    file = file,
                    manifest = parsed.getOrNull(),
                    problem = parsed.exceptionOrNull()?.message,
                    sha256 = sha256Of(file),
                )
            }
        }
    }

    private fun readManifest(file: Path): PluginManifest {
        ZipFile(file.toFile()).use { zip ->
            val entry = zip.getEntry("manifest.json")
                ?: throw RegistryError("archive has no manifest.json", file.toString())
            return parser.parse(zip.getInputStream(entry).readBytes())
        }
    }

    private fun sha256Of(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun descriptorOf(s: Scanned): PluginDescriptor {
        val m = s.manifest!!
        return PluginDescriptor(
            id = PluginId.parse(m.id),
            version = PluginVersion.parse(m.version),
            name = m.name,
            description = m.description,
            status = PluginStatus.READY,
            trust = PluginTrustLevel.SIGNED_UNKNOWN,
            publisherKeyFingerprint = null,
            packageSha256 = s.sha256,
            installedPath = null,
            runtimeType = m.runtime.type,
            runtimeVersionRequirement = m.runtime.version,
            capabilities = m.actions.map { a ->
                PluginCapability(kind = "action", name = a.id, title = a.title, description = a.description)
            },
            requestedPermissions = m.permissions,
            tags = m.tags,
            installedAtMillis = Files.getLastModifiedTime(s.file).toMillis(),
            updatedAtMillis = Files.getLastModifiedTime(s.file).toMillis(),
        )
    }

    private fun gitPresent(): Boolean = try {
        val probe = ProcessBuilder(gitBinary, "--version").start()
        try {
            probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
        } finally {
            probe.destroyForcibly()
        }
    } catch (e: Exception) {
        false
    }

    private fun git(args: List<String>, workingDir: Path) {
        val process = ProcessBuilder(listOf(gitBinary) + args)
            .directory(workingDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString().trim()
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw RegistryError(
                "git ${args.first()} failed (exit=${process.exitValue()})",
                output.take(400).ifBlank { args.joinToString(" ") },
            )
        }
    }
}
