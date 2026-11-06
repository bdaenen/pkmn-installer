package com.bennod.pkmninstaller.install

/**
 * State of the install flow.
 *
 * This lives outside the ViewModel because the install is owned by
 * [InstallManager] and kept alive by [InstallService], so the UI can be
 * recreated (or the app backgrounded) without losing progress.
 */
sealed interface InstallState {
    data object Idle : InstallState

    data class Running(val progress: InstallProgress) : InstallState

    data class Success(
        val packageName: String,
        val installerPackage: String,
        val verified: String?,
    ) : InstallState

    data class Error(val message: String) : InstallState
}
