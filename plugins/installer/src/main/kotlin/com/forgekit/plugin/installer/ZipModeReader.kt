package com.forgekit.plugin.installer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads Unix mode bits from a zip's central directory.
 *
 * `java.util.zip.ZipEntry` does not expose `externalAttributes`, and `.forge`
 * packages built by the forge-builder tool carry exec bits in exactly that
 * field — the same channel Info-ZIP uses. This reader walks EOCD → CD records
 * and extracts the full st_mode (file-type + permission bits) per entry name.
 *
 * Public because the validator shares the need (prohibited-content scan reads
 * the same modes); `runtime/bootstrap` owns a fuller parser for bootstrap
 * archives with prepended-data handling.
 */
public object ZipModeReader {

    /** name → unix mode (0 when the archive carries no mode for an entry). */
    public fun readModes(zipFile: Path): Map<String, Int> {
        val size = Files.size(zipFile).toInt()
        if (size < 22) throw IOException("not a zip: too small ($size bytes)")
        val bytes = Files.readAllBytes(zipFile)

        val eocd = locateEocd(bytes, size) ?: throw IOException("EOCD signature not found")
        val entryCount = u16(bytes, eocd + 10)
        val cdOffset = u32(bytes, eocd + 16)

        val modes = HashMap<String, Int>(entryCount)
        var p = cdOffset.toInt()
        repeat(entryCount) {
            if (p + 46 > bytes.size || u32(bytes, p) != 0x02014b50L) {
                throw IOException("central directory walk failed at record $it")
            }
            val nameLen = u16(bytes, p + 28)
            val extraLen = u16(bytes, p + 30)
            val commentLen = u16(bytes, p + 32)
            val externalAttr = u32(bytes, p + 38)
            val name = String(bytes, p + 46, nameLen, Charsets.UTF_8)
            val mode = ((externalAttr shr 16) and 0xFFFF).toInt()
            if (name.isNotEmpty() && !name.endsWith("/")) modes[name] = mode
            p += 46 + nameLen + extraLen + commentLen
        }
        return modes
    }

    private fun locateEocd(bytes: ByteArray, size: Int): Int? {
        var i = size - 22
        while (i >= 0) {
            if (u32(bytes, i) == 0x06054b50L) return i
            i--
        }
        return null
    }

    private fun u16(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, p: Int): Long =
        (b[p].toInt() and 0xFF).toLong() or
            ((b[p + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[p + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[p + 3].toInt() and 0xFF).toLong() shl 24)
}
