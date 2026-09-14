package com.forgekit.runtime.bootstrap

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files

/**
 * Reads the ZIP central directory to recover POSIX file modes and symlink flags.
 *
 * `java.util.zip.ZipEntry` does not expose the external file attributes where Info-ZIP
 * (and therefore the Termux bootstrap archives) store the Unix mode bits. This parser
 * walks the central directory records directly — no external dependency, works on JVM
 * and Android alike.
 */
public class ZipCentralDirectory private constructor(
    private val records: List<Record>,
) {

    /** One central directory record. */
    public data class Record(
        val name: String,
        /** Raw external attributes field (mode in the high 16 bits, Info-ZIP convention). */
        val externalAttributes: Long,
        /** Local header offset (for content reads). */
        val localHeaderOffset: Long,
    )

    /** Unix mode bits (S_IFMT + permission bits) or null when absent. */
    public fun unixMode(entryName: String): Int? =
        records.firstOrNull { it.name == entryName }?.let { r ->
            val attr = (r.externalAttributes ushr 16).toInt()
            if (attr == 0) null else attr
        }

    public fun allRecords(): List<Record> = records

    public companion object {
        private const val EOCD_SIG = 0x06054b50L
        private const val CEN_SIG = 0x02014b50L
        private const val MAX_COMMENT = 0xFFFF

        /** Parses the central directory of the zip at [zipFile]. */
        @Throws(IOException::class)
        public fun parse(zipFile: Path): ZipCentralDirectory {
            val bytes = Files.readAllBytes(zipFile)
            val eocd = locateEocd(bytes) ?: throw IOException("zip: end of central directory not found")
            val entryCount = le16(bytes, eocd + 10)
            val cdSize = le32(bytes, eocd + 12).toInt()
            val cdOffsetStored = le32(bytes, eocd + 16).toInt()

            // Archives with prepended data (Termux ships its bootstrap as an ELF .so
            // stub followed by the zip bytes) record offsets relative to the zip's
            // logical start. The central directory physically sits immediately before
            // the EOCD: derive its true position and the delta applied to every
            // stored local header offset.
            val cdActual = eocd - cdSize
            val delta: Int = if (cdActual >= 0 && le32(bytes, cdActual) == CEN_SIG) {
                cdActual - cdOffsetStored
            } else if (cdOffsetStored in 0 until bytes.size && le32(bytes, cdOffsetStored) == CEN_SIG) {
                0 // no prepended data: trust the stored offset
            } else {
                throw IOException("zip: central directory not found")
            }
            var p = cdOffsetStored + delta
            val records = mutableListOf<Record>()
            repeat(entryCount) {
                if (le32(bytes, p) != CEN_SIG) throw IOException("zip: bad central directory signature at $p")
                val nameLen = le16(bytes, p + 28)
                val extraLen = le16(bytes, p + 30)
                val commentLen = le16(bytes, p + 32)
                val externalAttr = le32(bytes, p + 38)
                val localOffset = le32(bytes, p + 42)
                val name = String(bytes, p + 46, nameLen, Charsets.UTF_8)
                records += Record(name, externalAttr.toLong() and 0xFFFFFFFFL, localOffset + delta)
                p += 46 + nameLen + extraLen + commentLen
            }
            return ZipCentralDirectory(records)
        }

        private fun locateEocd(bytes: ByteArray): Int? {
            val minEnd = (bytes.size - 22 - MAX_COMMENT).coerceAtLeast(0)
            var i = bytes.size - 22
            while (i >= minEnd) {
                if (le32(bytes, i) == EOCD_SIG) return i
                i--
            }
            return null
        }

        private fun le16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, off: Int): Long =
            (b[off].toInt() and 0xFF).toLong() or
                ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
                ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
                ((b[off + 3].toInt() and 0xFF).toLong() shl 24)
    }
}
