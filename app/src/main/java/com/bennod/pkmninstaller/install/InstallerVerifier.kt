package com.bennod.pkmninstaller.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Result of reading an installed package's install source back from the system.
 *
 * @param installingPackage what `getInstallSourceInfo().getInstallingPackageName()`
 *   returns — the package that performed the install (our spoofed
 *   `com.android.vending`).
 * @param originatingPackage what `getInstallSourceInfo().getOriginatingPackageName()`
 *   returns. On Android 14+ the shell user can no longer set this field, so it is
 *   usually `null` there even when the spoof worked.
 * @param versionCode the installed version code, used to confirm an update landed.
 */
data class InstallSourceInfo(
    val installingPackage: String?,
    val originatingPackage: String?,
    val versionCode: Long?,
)

/**
 * Reads the install source of an installed package **in-app** via
 * [PackageManager.getInstallSourceInfo], rather than shelling out to `dumpsys`.
 *
 * This is the authoritative check for Phase 5: it is what other apps and the
 * system see, and it does not depend on the exact `dumpsys` output format.
 */
object InstallerVerifier {

    /** Returns the install source for [packageName], or `null` if it is not installed. */
    fun read(context: Context, packageName: String): InstallSourceInfo? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, 0)
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName)
        } else {
            @Suppress("DEPRECATION")
            null
        }
        InstallSourceInfo(
            installingPackage = source?.installingPackageName
                ?: legacyInstaller(pm, packageName),
            originatingPackage = source?.originatingPackageName,
            versionCode = info.longVersionCode,
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyInstaller(pm: PackageManager, packageName: String): String? =
        pm.getInstallerPackageName(packageName)
}
