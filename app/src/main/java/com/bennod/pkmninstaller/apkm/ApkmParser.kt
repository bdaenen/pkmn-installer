package com.bennod.pkmninstaller.apkm

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses an `.apkm` bundle (a ZIP containing a base APK, split APKs and an
 * `info.json`) into an [ApkmBundle] install plan.
 */
object ApkmParser {

    private const val INFO_ENTRY = "info.json"
    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

    fun parse(file: File): ApkmBundle = FileZipSource(file).use { parse(it) }

    fun parse(source: ZipSource): ApkmBundle {
        val infoEntry = source.entries.firstOrNull { it.name == INFO_ENTRY }
            ?: error("Not an .apkm bundle: missing $INFO_ENTRY")
        val apkmInfo = source.open(infoEntry).use { parseInfo(it.readBytes()) }

        val apks = source.entries
            .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .map { entry -> toApkEntry(source, entry) }
            .sortedWith(compareByDescending<ApkEntry> { it.isBase }.thenBy { it.entryName })

        require(apks.isNotEmpty()) { "No APK entries found in bundle" }
        require(apks.any { it.isBase }) { "Bundle has no base APK" }

        return ApkmBundle(info = apkmInfo, apks = apks)
    }

    private fun toApkEntry(source: ZipSource, entry: ZipEntryInfo): ApkEntry {
        val isBase = entry.name.equals("base.apk", ignoreCase = true)
        val splitName = readSplitName(source, entry)
            ?: entry.name.removeSuffix(".apk")
        return ApkEntry(
            entryName = entry.name,
            splitName = splitName,
            size = entry.size,
            isBase = isBase,
        )
    }

    private fun readSplitName(source: ZipSource, apkEntry: ZipEntryInfo): String? = runCatching {
        source.open(apkEntry).use { apkStream ->
            readManifestBytes(apkStream)?.let { BinaryXml.readSplitName(it) }
        }
    }.getOrNull()

    private fun readManifestBytes(apkStream: InputStream): ByteArray? {
        ZipInputStream(apkStream).use { nested ->
            var entry = nested.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_ENTRY) {
                    return nested.readBytes()
                }
                entry = nested.nextEntry
            }
        }
        return null
    }

    private fun parseInfo(bytes: ByteArray): ApkmInfo {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        return ApkmInfo(
            packageName = json.getString("pname"),
            versionCode = json.optString("versioncode").toLongOrNull() ?: 0L,
            versionName = json.optString("release_version").ifBlank { null },
            minApi = json.optString("min_api").toIntOrNull(),
            arches = json.optJSONArray("arches")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }.orEmpty(),
            apkmVersion = json.optInt("apkm_version").takeIf { it != 0 },
        )
    }
}
