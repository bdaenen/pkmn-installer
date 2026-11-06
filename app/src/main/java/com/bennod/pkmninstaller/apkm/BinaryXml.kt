package com.bennod.pkmninstaller.apkm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal parser for Android binary XML (`AndroidManifest.xml` inside an APK).
 *
 * We only need the `split` attribute of the root `<manifest>` element, so this
 * parser intentionally supports just the subset of the format required for that:
 * the string pool and start-element chunks.
 */
object BinaryXml {

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val UTF8_FLAG = 0x00000100

    /**
     * Returns the value of the `split` attribute on the root `<manifest>` element,
     * or `null` when the manifest has no split (i.e. it is a base APK).
     */
    fun readSplitName(bytes: ByteArray): String? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val fileType = buffer.getShort(0).toInt() and 0xFFFF
        require(fileType == RES_XML_TYPE) { "Not a binary XML file (type=0x${fileType.toString(16)})" }

        var offset = buffer.getShort(2).toInt() and 0xFFFF // header size
        val totalSize = buffer.getInt(4)
        val end = minOf(totalSize, bytes.size)

        var strings: StringPool? = null

        while (offset + 8 <= end) {
            val chunkType = buffer.getShort(offset).toInt() and 0xFFFF
            val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buffer.getInt(offset + 4)

            when (chunkType) {
                RES_STRING_POOL_TYPE -> strings = StringPool.parse(buffer, offset)
                RES_XML_START_ELEMENT_TYPE -> {
                    val pool = strings ?: return null
                    val nameIndex = buffer.getInt(offset + headerSize + 4)
                    val elementName = pool.get(nameIndex)
                    if (elementName == "manifest") {
                        return readSplitAttribute(buffer, offset, headerSize, pool)
                    }
                }
            }

            if (chunkSize <= 0) break
            offset += chunkSize
        }
        return null
    }

    private fun readSplitAttribute(
        buffer: ByteBuffer,
        chunkOffset: Int,
        headerSize: Int,
        pool: StringPool,
    ): String? {
        // Start-element body: lineNumber, comment, ns, name, attributeStart,
        // attributeSize, attributeCount, idIndex, classIndex, styleIndex.
        val body = chunkOffset + headerSize
        val attributeStart = buffer.getShort(body + 8).toInt() and 0xFFFF
        val attributeSize = buffer.getShort(body + 10).toInt() and 0xFFFF
        val attributeCount = buffer.getShort(body + 12).toInt() and 0xFFFF

        var attrOffset = body + attributeStart
        repeat(attributeCount) {
            val nameIndex = buffer.getInt(attrOffset + 4)
            val rawValueIndex = buffer.getInt(attrOffset + 8)
            if (pool.get(nameIndex) == "split") {
                return pool.get(rawValueIndex)
            }
            attrOffset += attributeSize
        }
        return null
    }

    private class StringPool(
        private val strings: List<String>,
    ) {
        fun get(index: Int): String? = strings.getOrNull(index)

        companion object {
            fun parse(buffer: ByteBuffer, offset: Int): StringPool {
                val headerSize = buffer.getShort(offset + 2).toInt() and 0xFFFF
                val stringCount = buffer.getInt(offset + 8)
                val flags = buffer.getInt(offset + 16)
                val stringsStart = buffer.getInt(offset + 20)
                val isUtf8 = (flags and UTF8_FLAG) != 0

                val offsetsBase = offset + headerSize
                val dataBase = offset + stringsStart

                val result = ArrayList<String>(stringCount)
                for (i in 0 until stringCount) {
                    val stringOffset = buffer.getInt(offsetsBase + i * 4)
                    result += readString(buffer, dataBase + stringOffset, isUtf8)
                }
                return StringPool(result)
            }

            private fun readString(buffer: ByteBuffer, offset: Int, isUtf8: Boolean): String {
                var pos = offset
                if (isUtf8) {
                    // UTF-8: char count (1-2 bytes), byte count (1-2 bytes), bytes, NUL.
                    var charCount = buffer.get(pos).toInt() and 0xFF
                    pos++
                    if (charCount and 0x80 != 0) {
                        charCount = ((charCount and 0x7F) shl 8) or (buffer.get(pos).toInt() and 0xFF)
                        pos++
                    }
                    var byteCount = buffer.get(pos).toInt() and 0xFF
                    pos++
                    if (byteCount and 0x80 != 0) {
                        byteCount = ((byteCount and 0x7F) shl 8) or (buffer.get(pos).toInt() and 0xFF)
                        pos++
                    }
                    val bytes = ByteArray(byteCount)
                    for (i in 0 until byteCount) bytes[i] = buffer.get(pos + i)
                    return String(bytes, Charsets.UTF_8)
                } else {
                    // UTF-16: char count (1-2 u16), chars, NUL.
                    var charCount = buffer.getShort(pos).toInt() and 0xFFFF
                    pos += 2
                    if (charCount and 0x8000 != 0) {
                        charCount = ((charCount and 0x7FFF) shl 16) or (buffer.getShort(pos).toInt() and 0xFFFF)
                        pos += 2
                    }
                    val chars = CharArray(charCount)
                    for (i in 0 until charCount) chars[i] = buffer.getShort(pos + i * 2).toInt().toChar()
                    return String(chars)
                }
            }
        }
    }
}
