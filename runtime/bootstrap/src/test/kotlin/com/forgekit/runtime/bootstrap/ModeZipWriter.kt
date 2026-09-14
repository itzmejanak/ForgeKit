package com.forgekit.runtime.bootstrap

import java.io.Closeable
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * TEST-ONLY zip writer that stores Unix modes in the central directory external
 * attributes — the exact property Termux bootstrap archives rely on.
 *
 * `java.util.zip.ZipOutputStream` cannot set external attributes from Kotlin
 * (the field is package-private), so tests build archives byte-by-byte here:
 * STORED (uncompressed) entries, UTF-8 names, Info-ZIP "version made by = Unix".
 *
 * The produced archives are fully valid zips readable by java.util.zip.ZipFile,
 * which is what [BootstrapExtractor] uses for content — while modes are read by
 * [ZipCentralDirectory].
 */
class ModeZipWriter(
    private val output: OutputStream,
) : Closeable {

    private data class Entry(
        val name: String,
        val data: ByteArray,
        val unixMode: Int,
        val localOffset: Int,
    )

    private val out = output
    private val entries = mutableListOf<Entry>()
    private var offset = 0
    private var finished = false

    fun entry(name: String, content: String, unixMode: Int) {
        entry(name, content.toByteArray(StandardCharsets.UTF_8), unixMode)
    }

    fun entry(name: String, data: ByteArray, unixMode: Int) {
        require(name.isNotEmpty() && !name.startsWith("/")) { "bad entry name: $name" }
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32().apply { update(data) }

        val localHeader = newByteArray(30 + nameBytes.size)
        put32(localHeader, 0, LOCAL_SIG.toInt())
        put16(localHeader, 4, 20)                 // version needed
        put16(localHeader, 6, 0x0800)             // flags: UTF-8 names
        put16(localHeader, 8, 0)                  // method: STORED
        put16(localHeader, 10, 0)                 // mod time
        put16(localHeader, 12, 0)                 // mod date
        put32(localHeader, 14, crc.value.toInt())
        put32(localHeader, 18, data.size)         // compressed size (stored)
        put32(localHeader, 22, data.size)         // uncompressed size
        put16(localHeader, 26, nameBytes.size)
        put16(localHeader, 28, 0)                 // extra len
        nameBytes.copyInto(localHeader, 30)

        val localOffset = offset
        write(localHeader)
        write(data)
        entries += Entry(name, data, unixMode, localOffset)
    }

    fun finish() {
        check(!finished) { "finish() already called" }
        finished = true
        val cdStart = offset
        for (e in entries) {
            val nameBytes = e.name.toByteArray(StandardCharsets.UTF_8)
            val crc = CRC32().apply { update(e.data) }
            val record = newByteArray(46 + nameBytes.size)
            put32(record, 0, CENTRAL_SIG.toInt())
            put16(record, 4, (3 shl 8) or 20)     // version made by: Unix, 2.0
            put16(record, 6, 20)                  // version needed
            put16(record, 8, 0x0800)              // flags: UTF-8 names
            put16(record, 10, 0)                  // method: STORED
            put16(record, 12, 0)                  // mod time
            put16(record, 14, 0)                  // mod date
            put32(record, 16, crc.value.toInt())
            put32(record, 20, e.data.size)
            put32(record, 24, e.data.size)
            put16(record, 28, nameBytes.size)
            put16(record, 30, 0)                  // extra len
            put16(record, 32, 0)                  // comment len
            put16(record, 34, 0)                  // disk number
            put16(record, 36, 0)                  // internal attrs
            put32(record, 38, e.unixMode shl 16) // external attrs: Unix mode
            put32(record, 42, e.localOffset)
            nameBytes.copyInto(record, 46)
            write(record)
        }
        val cdSize = offset - cdStart

        val eocd = newByteArray(22)
        put32(eocd, 0, EOCD_SIG.toInt())
        put16(eocd, 4, 0)
        put16(eocd, 6, 0)
        put16(eocd, 8, entries.size)
        put16(eocd, 10, entries.size)
        put32(eocd, 12, cdSize)
        put32(eocd, 16, cdStart)
        put16(eocd, 20, 0)                        // comment len
        write(eocd)
        out.flush()
    }

    override fun close() {
        if (!finished) finish()
        out.close()
    }

    private fun write(bytes: ByteArray) {
        var p = 0
        while (p < bytes.size) {
            val n = minOf(65536, bytes.size - p)
            out.write(bytes, p, n)
            p += n
            offset += n
        }
    }

    private fun newByteArray(size: Int) = ByteArray(size)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private companion object {
        const val LOCAL_SIG = 0x04034b50L
        const val CENTRAL_SIG = 0x02014b50L
        const val EOCD_SIG = 0x06054b50L
    }
}
