package com.bennod.pkmninstaller.apkm

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A read-only view over a ZIP archive.
 *
 * The parser works against this abstraction so it can read an `.apkm` either
 * from a real file ([FileZipSource]) or from a seekable channel obtained from a
 * SAF file descriptor ([ChannelZipSource]) without copying the archive.
 */
interface ZipSource : Closeable {

    val entries: List<ZipEntryInfo>

    fun open(entry: ZipEntryInfo): InputStream
}

/**
 * Metadata for a single ZIP entry.
 *
 * @param size uncompressed size in bytes.
 */
data class ZipEntryInfo(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
)

/** [ZipSource] backed by a [ZipFile] on the local filesystem. */
class FileZipSource(file: File) : ZipSource {

    private val zip = ZipFile(file)

    override val entries: List<ZipEntryInfo> = zip.entries().asSequence()
        .map { ZipEntryInfo(it.name, it.size, it.isDirectory) }
        .toList()

    override fun open(entry: ZipEntryInfo): InputStream {
        val zipEntry = zip.getEntry(entry.name) ?: error("Missing entry ${entry.name}")
        return zip.getInputStream(zipEntry)
    }

    override fun close() = zip.close()
}
