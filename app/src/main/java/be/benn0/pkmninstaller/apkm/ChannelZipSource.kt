package be.benn0.pkmninstaller.apkm

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * [ZipSource] backed by a seekable channel (e.g. a SAF file descriptor).
 *
 * `java.util.zip.ZipFile` only accepts a [java.io.File], and copying a multi
 * hundred megabyte `.apkm` into app storage just to read its metadata is
 * wasteful. This class implements the small subset of the ZIP format we need —
 * the end-of-central-directory record and the central directory — so entries can
 * be read by seeking directly to their local header.
 *
 * ZIP64 is not supported; `.apkm` bundles are well below the 4 GB limit.
 */
class ChannelZipSource(private val channel: SeekableByteChannel) : ZipSource {

    private data class CentralEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    private val central: Map<String, CentralEntry>

    override val entries: List<ZipEntryInfo>

    init {
        val eocd = findEndOfCentralDirectory()
        val entryCount = eocd.u16(10)
        val centralSize = eocd.u32(12)
        val centralOffset = eocd.u32(16)

        val directory = readFully(centralOffset, centralSize.toInt())
        val parsed = ArrayList<CentralEntry>(entryCount)
        var pos = 0
        while (pos + 46 <= directory.size) {
            val signature = directory.u32(pos)
            if (signature != CENTRAL_HEADER_SIGNATURE) break
            val method = directory.u16(pos + 10)
            val compressedSize = directory.u32(pos + 20)
            val uncompressedSize = directory.u32(pos + 24)
            val nameLen = directory.u16(pos + 28)
            val extraLen = directory.u16(pos + 30)
            val commentLen = directory.u16(pos + 32)
            val localHeaderOffset = directory.u32(pos + 42)
            val name = String(directory, pos + 46, nameLen, Charsets.UTF_8)
            parsed += CentralEntry(
                name = name,
                method = method,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localHeaderOffset = localHeaderOffset,
            )
            pos += 46 + nameLen + extraLen + commentLen
        }

        central = parsed.associateBy { it.name }
        entries = parsed.map {
            ZipEntryInfo(
                name = it.name,
                size = it.uncompressedSize,
                isDirectory = it.name.endsWith("/"),
            )
        }
    }

    override fun open(entry: ZipEntryInfo): InputStream {
        val centralEntry = central[entry.name] ?: error("Missing entry ${entry.name}")

        val localHeader = readFully(centralEntry.localHeaderOffset, 30)
        require(localHeader.u32(0) == LOCAL_HEADER_SIGNATURE) {
            "Corrupt local header for ${entry.name}"
        }
        val nameLen = localHeader.u16(26)
        val extraLen = localHeader.u16(28)
        val dataStart = centralEntry.localHeaderOffset + 30 + nameLen + extraLen

        val raw = ChannelInputStream(dataStart, centralEntry.compressedSize)
        return when (centralEntry.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> RawInflaterInputStream(raw)
            else -> error("Unsupported compression method ${centralEntry.method}")
        }
    }

    override fun close() = channel.close()

    /** Scans backwards from the end of the archive for the EOCD signature. */
    private fun findEndOfCentralDirectory(): ByteArray {
        val size = channel.size()
        val maxScan = minOf(size, (EOCD_MAX_COMMENT + EOCD_MIN_SIZE).toLong()).toInt()
        val tail = readFully(size - maxScan, maxScan)
        for (i in tail.size - EOCD_MIN_SIZE downTo 0) {
            if (tail.u32(i) == EOCD_SIGNATURE) {
                return tail.copyOfRange(i, i + EOCD_MIN_SIZE)
            }
        }
        error("Not a ZIP archive: end-of-central-directory record not found")
    }

    private fun readFully(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        return buffer.array()
    }

    /** Sequential, length-bounded view over the shared channel. */
    private inner class ChannelInputStream(
        start: Long,
        private val length: Long,
    ) : InputStream() {

        private var remaining = length

        init {
            channel.position(start)
        }

        override fun read(): Int {
            if (remaining <= 0) return -1
            val single = ByteBuffer.allocate(1)
            if (channel.read(single) <= 0) return -1
            remaining--
            return single.array()[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = channel.read(ByteBuffer.wrap(b, off, toRead))
            if (n <= 0) return -1
            remaining -= n
            return n
        }
    }

    /**
     * Inflates a ZIP entry's **raw** deflate stream.
     *
     * ZIP entries store raw deflate data with no zlib wrapper, so the inflater
     * must be created with `nowrap = true`. Using the default
     * [InflaterInputStream] constructor would expect a zlib header and fail with
     * "incorrect header check".
     */
    private class RawInflaterInputStream(input: InputStream) :
        InflaterInputStream(input, Inflater(true)) {

        override fun close() {
            super.close()
            // Release the native inflater resources eagerly.
            inf.end()
        }
    }

    private companion object {
        const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
        const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
        const val EOCD_SIGNATURE = 0x06054b50L
        const val EOCD_MIN_SIZE = 22
        const val EOCD_MAX_COMMENT = 0xFFFF
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
    }
}

private fun ByteArray.u16(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

private fun ByteArray.u32(offset: Int): Long =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
