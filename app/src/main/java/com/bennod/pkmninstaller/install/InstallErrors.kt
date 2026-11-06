package com.bennod.pkmninstaller.install

/**
 * Maps raw `pm` failure reasons to clearer, actionable messages.
 *
 * The raw reason is always kept in parentheses so nothing is lost, but the
 * leading sentence explains what the user can do about it.
 */
object InstallErrors {

    private val MESSAGES: List<Pair<String, String>> = listOf(
        "INSTALL_FAILED_VERSION_DOWNGRADE" to
            "A newer version of this app is already installed. Uninstall it first, " +
            "or enable downgrade installs.",
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to
            "An app with the same package but a different signature is installed. " +
            "Uninstall it first.",
        "INSTALL_FAILED_ALREADY_EXISTS" to
            "The app is already installed. Uninstall it first, or install as an update.",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" to
            "Not enough free storage to install this bundle.",
        "INSTALL_FAILED_INVALID_APK" to
            "The bundle is invalid or a split name is wrong. The APK may be corrupt " +
            "or incompatible with this device.",
        "INSTALL_FAILED_NO_MATCHING_ABIS" to
            "This bundle has no native code for this device's CPU architecture.",
        "INSTALL_FAILED_OLDER_SDK" to
            "This app requires a newer Android version than this device runs.",
        "INSTALL_FAILED_VERIFICATION_FAILURE" to
            "The system's package verifier rejected the install.",
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to
            "The APK is not signed.",
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" to
            "The APKs in this bundle are signed with different certificates.",
        "INSTALL_FAILED_USER_RESTRICTED" to
            "Installation is blocked by a device policy or user restriction.",
    )

    /** Returns a friendly message for [reason], falling back to the raw text. */
    fun friendly(reason: String): String {
        val match = MESSAGES.firstOrNull { reason.contains(it.first, ignoreCase = true) }
            ?: return reason
        return "${match.second}\n\n(${reason.trim()})"
    }
}
