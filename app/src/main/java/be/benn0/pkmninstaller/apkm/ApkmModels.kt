package be.benn0.pkmninstaller.apkm

/**
 * Metadata parsed from the `info.json` entry of an `.apkm` bundle.
 */
data class ApkmInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minApi: Int?,
    val arches: List<String>,
    val apkmVersion: Int?,
)

/**
 * A single APK inside an `.apkm` bundle.
 *
 * @param entryName the ZIP entry name, e.g. `base.apk` or `split_config.arm64_v8a.apk`.
 * @param splitName the split name used by `pm install-write`, e.g. `base` or
 *   `split_config.arm64_v8a`. Read from the APK manifest when possible, otherwise
 *   derived from [entryName].
 * @param size uncompressed size in bytes.
 * @param isBase whether this is the base APK.
 */
data class ApkEntry(
    val entryName: String,
    val splitName: String,
    val size: Long,
    val isBase: Boolean,
)

/**
 * The full install plan derived from an `.apkm` bundle.
 */
data class ApkmBundle(
    val info: ApkmInfo,
    val apks: List<ApkEntry>,
) {
    val totalSize: Long = apks.sumOf { it.size }
}
