package com.bennod.pkmninstaller.install

/**
 * A pre-flight warning shown before an install is attempted.
 *
 * @param message the human-readable warning text.
 * @param blocking whether the install should be disabled until it is resolved.
 */
data class PreflightWarning(val message: String, val blocking: Boolean)

/**
 * Pure pre-flight checks that do not depend on the Android framework, so they
 * can be unit-tested.
 */
object Preflight {

    /**
     * Compares the [incoming] version code of a bundle against the [installed]
     * one (or `null` if the app is not installed) and returns the matching
     * warning, if any.
     *
     * A downgrade is blocking unless [allowDowngrade] is set; a same-version
     * reinstall is only informational.
     */
    fun versionWarning(
        installed: Long?,
        incoming: Long,
        allowDowngrade: Boolean,
    ): PreflightWarning? = when {
        installed == null -> null

        incoming < installed && !allowDowngrade -> PreflightWarning(
            "A newer version ($installed) is already installed; this bundle is " +
                "$incoming. Enable \"Allow downgrade\" to install it anyway.",
            blocking = true,
        )

        incoming < installed -> PreflightWarning(
            "Downgrading from $installed to $incoming (allowed).",
            blocking = false,
        )

        incoming == installed -> PreflightWarning(
            "Version $incoming is already installed; this will reinstall it.",
            blocking = false,
        )

        else -> null
    }
}
