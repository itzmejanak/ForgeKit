package com.forgekit.runtime.bootstrap

import com.forgekit.core.logging.ForgeLogger
import com.forgekit.core.logging.LogCategory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Relocates an extracted Termux prefix by rewriting the bootstrap's build-time
 * prefix to the one ForgeKit actually installed into.
 *
 * Termux packages are built for a fixed prefix and reference it by absolute path:
 * dpkg reads `<prefix>/etc/dpkg/dpkg.cfg.d`, apt reads `<prefix>/etc/apt`, shebangs
 * point at `<prefix>/bin/sh`, and shared libraries carry it in RUNPATH and in string
 * literals. In the v0.119 arm64 bootstrap 556 files contain it, 308 of them ELF.
 * Running that tree from anywhere else means every one of those lookups resolves into
 * another app's data directory and fails with EACCES.
 *
 * Two substitution rules, because the two file kinds have different constraints:
 *  - **ELF**: patched in place, length-preserving (replacement NUL-padded to the
 *    original width). Nothing may move, or every offset in the file would be wrong,
 *    so a replacement LONGER than the original is refused — loudly, naming the file.
 *  - **everything else** (scripts, dpkg metadata, configs): plain byte replacement;
 *    the file may grow or shrink.
 *
 * ForgeKit sizes its prefix to make the device case a pure byte swap:
 * `/data/data/com.forgekit.app/usr` and `/data/data/com.termux/files/usr` are both
 * 31 bytes.
 *
 * Symlink targets cannot be edited in place, so an offending link is recreated.
 */
public class PrefixRewriter(
    private val logger: ForgeLogger = ForgeLogger(LogCategory.RUNTIME, {}),
) {

    /** What one relocation pass changed. */
    public data class Result(
        val filesScanned: Int,
        val filesRewritten: Int,
        val symlinksRewritten: Int,
        val occurrences: Int,
    )

    /** One path substitution to apply across the tree. */
    public data class Rule(val from: String, val to: String) {
        internal val needle: ByteArray = from.toByteArray(Charsets.UTF_8)
        internal val replacement: ByteArray = to.toByteArray(Charsets.UTF_8)
    }

    /**
     * Applies every rule in [rules] to the tree under [root], in a single pass per file.
     *
     * Rules are applied longest-needle-first so that a shorter path that is a prefix of
     * a longer one cannot shadow it.
     *
     * [skip] excludes paths from relocation entirely. This is essential for ForgeKit's OWN
     * relocation scripts, which live inside the tree and embed the compiled path as a literal
     * (a `sed` needle): relocating their body would rewrite that needle to the new prefix and
     * silently turn every future relocation into a no-op.
     *
     * @throws IOException if an ELF binary references a rule's `from` but its `to` is
     *   too long to fit in place — the tree cannot be relocated to those paths.
     */
    @Throws(IOException::class)
    public fun rewrite(root: Path, rules: List<Rule>, skip: (Path) -> Boolean = { false }): Result {
        val active = rules
            .filter { it.from != it.to && it.needle.isNotEmpty() }
            .sortedByDescending { it.needle.size }
        if (active.isEmpty()) return Result(0, 0, 0, 0)
        val shortestNeedle = active.minOf { it.needle.size }

        var scanned = 0
        var rewritten = 0
        var symlinks = 0
        var hits = 0

        Files.walk(root).use { stream ->
            for (path in stream) {
                if (skip(path)) continue
                if (Files.isSymbolicLink(path)) {
                    if (rewriteSymlink(path, active)) symlinks++
                    continue
                }
                if (!Files.isRegularFile(path)) continue
                scanned++
                val size = Files.size(path)
                if (size < shortestNeedle) continue

                val fileHits = rewriteFile(path, size, active)
                if (fileHits > 0) {
                    rewritten++
                    hits += fileHits
                }
            }
        }

        logger.info(
            "PrefixRewriter",
            "relocated runtime: $rewritten/$scanned files and $symlinks symlinks rewritten " +
                "($hits occurrences); rules: " +
                active.joinToString { "'${it.from}' -> '${it.to}'" },
        )
        return Result(scanned, rewritten, symlinks, hits)
    }

    /**
     * Relocates only symbolic links whose target still points into a rule's `from` tree —
     * the links a package manager unpacks verbatim from a `.deb`. Unlike [rewrite] it never
     * opens a regular file, so it is cheap and safe to run on every runtime start.
     *
     * @return number of links re-pointed.
     */
    public fun rewriteSymlinks(root: Path, rules: List<Rule>): Int {
        val active = rules.filter { it.from != it.to && it.from.isNotEmpty() }
        if (active.isEmpty() || !Files.isDirectory(root)) return 0
        var repaired = 0
        Files.walk(root).use { stream ->
            for (path in stream) {
                if (!Files.isSymbolicLink(path)) continue
                val target = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull() ?: continue
                val rule = active.firstOrNull { target == it.from || target.startsWith(it.from + "/") } ?: continue
                Files.delete(path)
                Files.createSymbolicLink(path, Paths.get(rule.to + target.substring(rule.from.length)))
                repaired++
            }
        }
        if (repaired > 0) logger.info("PrefixRewriter", "re-pointed $repaired symlinks into the relocated prefix")
        return repaired
    }

    /**
     * Rewrites one regular file with heap use bounded to [STREAM_CHUNK], regardless of file size.
     * A Termux prefix holds ELF binaries of 100+ MB (e.g. libLLVM.so ~127 MB); reading one whole
     * into the JVM heap blows Android's ~256 MB app heap (OOM during dependency provisioning). So
     * ELF — and any large file — is scanned/patched through a sliding window; only small files
     * take the simple read-everything path.
     */
    private fun rewriteFile(path: Path, size: Long, rules: List<Rule>): Int = when {
        isElf(path) -> patchElfInPlace(path, size, rules)
        size <= STREAM_CHUNK -> rewriteSmallFile(path, rules)
        // Large non-ELF (rare): only pay the whole-file heap cost if a needle is actually present.
        containsAnyNeedle(path, size, rules) -> rewriteSmallFile(path, rules)
        else -> 0
    }

    private fun isElf(path: Path): Boolean {
        val h = ByteArray(4)
        val n = Files.newInputStream(path).use { it.readNBytes(h, 0, 4) }
        return n >= 4 && h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
            h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()
    }

    /** Read-all path for small files: ELF patched in place on the array, text replaced growably. */
    private fun rewriteSmallFile(path: Path, rules: List<Rule>): Int {
        var bytes = Files.readAllBytes(path)
        val elf = bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        var fileHits = 0
        for (rule in rules) {
            if (indexOf(bytes, rule.needle, 0, bytes.size) < 0) continue
            if (elf) {
                if (rule.replacement.size > rule.needle.size) throw elfGrowth(path, rule)
                fileHits += patchInPlacePadded(bytes, rule.needle, rule.replacement)
            } else {
                val (out, count) = replaceGrowable(bytes, rule.needle, rule.replacement)
                bytes = out
                fileHits += count
            }
        }
        if (fileHits == 0) return 0
        // Truncating an existing file keeps its inode and mode, so exec bits survive.
        Files.newOutputStream(path).use { it.write(bytes) }
        return fileHits
    }

    /**
     * Length-preserving ELF patch with bounded memory: slides an [STREAM_CHUNK]+overlap window
     * across the file via RandomAccessFile, patching matches in place (seek+write). Consecutive
     * windows overlap by (longest needle − 1) so a match spanning a boundary is still found; a
     * window only patches matches that START before its overlap tail, so nothing is patched twice.
     * In-place writes keep the inode/mode, so exec bits survive.
     */
    private fun patchElfInPlace(path: Path, size: Long, rules: List<Rule>): Int {
        val maxNeedle = rules.maxOf { it.needle.size }
        val overlap = (maxNeedle - 1).coerceAtLeast(0)
        val bufSize = minOf(size, (STREAM_CHUNK + overlap).toLong()).toInt().coerceAtLeast(maxNeedle)
        val buf = ByteArray(bufSize)
        var count = 0
        java.io.RandomAccessFile(path.toFile(), "rw").use { raf ->
            var start = 0L
            while (start < size) {
                val readLen = minOf(bufSize.toLong(), size - start).toInt()
                raf.seek(start)
                raf.readFully(buf, 0, readLen)
                val ownedEnd = if (start + readLen >= size) readLen else readLen - overlap
                for (rule in rules) {
                    var i = 0
                    while (true) {
                        val at = indexOf(buf, rule.needle, i, readLen)
                        if (at < 0 || at >= ownedEnd) break
                        if (rule.replacement.size > rule.needle.size) throw elfGrowth(path, rule)
                        raf.seek(start + at)
                        raf.write(rule.replacement)
                        val pad = rule.needle.size - rule.replacement.size
                        if (pad > 0) raf.write(ByteArray(pad))
                        // reflect the patch in the window so a later needle can't re-match it
                        rule.replacement.copyInto(buf, at)
                        java.util.Arrays.fill(buf, at + rule.replacement.size, at + rule.needle.size, 0)
                        i = at + rule.needle.size
                        count++
                    }
                }
                if (ownedEnd <= 0) break
                start += ownedEnd
            }
        }
        return count
    }

    /** Bounded chunked scan: does any needle appear anywhere in [path]? */
    private fun containsAnyNeedle(path: Path, size: Long, rules: List<Rule>): Boolean {
        val maxNeedle = rules.maxOf { it.needle.size }
        val overlap = (maxNeedle - 1).coerceAtLeast(0)
        val bufSize = minOf(size, (STREAM_CHUNK + overlap).toLong()).toInt().coerceAtLeast(maxNeedle)
        val buf = ByteArray(bufSize)
        java.io.RandomAccessFile(path.toFile(), "r").use { raf ->
            var start = 0L
            while (start < size) {
                val readLen = minOf(bufSize.toLong(), size - start).toInt()
                raf.seek(start)
                raf.readFully(buf, 0, readLen)
                for (rule in rules) if (indexOf(buf, rule.needle, 0, readLen) >= 0) return true
                if (readLen <= overlap) break
                start += readLen - overlap
            }
        }
        return false
    }

    private fun elfGrowth(path: Path, rule: Rule): IOException = IOException(
        "cannot relocate ELF binary $path: '${rule.to}' is ${rule.replacement.size} bytes but " +
            "must fit in ${rule.needle.size} (patching an ELF in place cannot move any bytes)",
    )

    private fun rewriteSymlink(link: Path, rules: List<Rule>): Boolean {
        val target = runCatching { Files.readSymbolicLink(link).toString() }.getOrNull() ?: return false
        var updated = target
        for (rule in rules) updated = updated.replace(rule.from, rule.to)
        if (updated == target) return false
        Files.delete(link)
        Files.createSymbolicLink(link, Paths.get(updated))
        return true
    }

    /** Length-preserving ELF patch: replacement then NUL padding out to the old width. */
    private fun patchInPlacePadded(data: ByteArray, needle: ByteArray, replacement: ByteArray): Int {
        var count = 0
        var i = indexOf(data, needle, 0)
        while (i >= 0) {
            replacement.copyInto(data, i)
            java.util.Arrays.fill(data, i + replacement.size, i + needle.size, 0)
            count++
            i = indexOf(data, needle, i + needle.size)
        }
        return count
    }

    /** Plain replacement for text-ish files; the result may change length. */
    private fun replaceGrowable(
        data: ByteArray,
        needle: ByteArray,
        replacement: ByteArray,
    ): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream(data.size)
        var count = 0
        var cursor = 0
        while (true) {
            val at = indexOf(data, needle, cursor)
            if (at < 0) break
            out.write(data, cursor, at - cursor)
            out.write(replacement)
            cursor = at + needle.size
            count++
        }
        out.write(data, cursor, data.size - cursor)
        return out.toByteArray() to count
    }

    /** First index of [needle] in `data[from until limit]`, or -1. [limit] lets callers search a
     *  partially-filled window buffer without a copy. */
    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int, limit: Int = data.size): Int {
        val last = limit - needle.size
        var i = from.coerceAtLeast(0)
        outer@ while (i <= last) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private companion object {
        /** Window size for scanning/patching large files — the peak heap per file is ~this. */
        const val STREAM_CHUNK: Int = 8 * 1024 * 1024
    }
}
