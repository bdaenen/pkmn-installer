package com.bennod.pkmninstaller

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bennod.pkmninstaller.apkm.ApkmBundle
import com.bennod.pkmninstaller.apkm.ApkmParser
import com.bennod.pkmninstaller.apkm.ChannelZipSource
import com.bennod.pkmninstaller.install.InstallManager
import com.bennod.pkmninstaller.install.InstallState
import com.bennod.pkmninstaller.install.Preflight
import com.bennod.pkmninstaller.install.PreflightWarning
import com.bennod.pkmninstaller.shizuku.ShizukuManager
import com.bennod.pkmninstaller.shizuku.ShizukuState
import com.bennod.pkmninstaller.shizuku.ShellRunner
import com.bennod.pkmninstaller.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * State of the `.apkm` inspection flow.
 */
sealed interface ParseState {
    data object Idle : ParseState
    data object Loading : ParseState
    data class Success(val fileName: String, val bundle: ApkmBundle) : ParseState
    data class Error(val message: String) : ParseState
}

/**
 * Holds the Shizuku status, the parsed `.apkm` metadata and the install state
 * for the UI.
 *
 * The install itself is owned by [InstallManager] (kept alive by a foreground
 * service), so this ViewModel only observes it and can be recreated freely.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _shizukuState = MutableStateFlow(ShizukuManager.state())
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    private val _shellOutput = MutableStateFlow<String?>(null)
    val shellOutput: StateFlow<String?> = _shellOutput.asStateFlow()

    private val _shellRunning = MutableStateFlow(false)
    val shellRunning: StateFlow<Boolean> = _shellRunning.asStateFlow()

    private val _parseState = MutableStateFlow<ParseState>(ParseState.Idle)
    val parseState: StateFlow<ParseState> = _parseState.asStateFlow()

    val installState: StateFlow<InstallState> = InstallManager.state

    private val _warnings = MutableStateFlow<List<PreflightWarning>>(emptyList())
    val warnings: StateFlow<List<PreflightWarning>> = _warnings.asStateFlow()

    /** Whether the user opted in to installing an older version code (`-d`). */
    private val _allowDowngrade = MutableStateFlow(false)
    val allowDowngrade: StateFlow<Boolean> = _allowDowngrade.asStateFlow()

    private val shellRunner = ShellRunner(
        onConnected = { refreshShizukuState() },
        onDisconnected = { refreshShizukuState() },
    )

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refreshShizukuState()
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                shellRunner.bind()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshShizukuState()
    }

    init {
        ShizukuManager.addPermissionResultListener(permissionListener)
        ShizukuManager.addBinderReceivedListener(binderReceivedListener)
        ShizukuManager.addBinderDeadListener(binderDeadListener)
        refreshShizukuState()
        // Clean up any `pm` session left dangling by a previous crash.
        InstallManager.cleanupStaleSession(application)
    }

    override fun onCleared() {
        ShizukuManager.removePermissionResultListener(permissionListener)
        ShizukuManager.removeBinderReceivedListener(binderReceivedListener)
        ShizukuManager.removeBinderDeadListener(binderDeadListener)
        shellRunner.unbind()
        super.onCleared()
    }

    fun refreshShizukuState() {
        _shizukuState.value = ShizukuManager.state()
    }

    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }

    /** Binds the user service and runs `id` to prove shell identity. */
    fun runShellTest() {
        if (_shellRunning.value) return
        _shellRunning.value = true
        _shellOutput.value = null

        if (!shellRunner.isBound) {
            shellRunner.bind()
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                awaitBound()
                runCatching { shellRunner.run("id") }
            }
            _shellOutput.value = result.fold(
                onSuccess = { r ->
                    buildString {
                        appendLine("exit code: ${r.exitCode}")
                        if (r.stdout.isNotBlank()) appendLine(r.stdout)
                        if (r.stderr.isNotBlank()) appendLine("stderr: ${r.stderr}")
                    }.trim()
                },
                onFailure = { it.message ?: it.toString() },
            )
            _shellRunning.value = false
        }
    }

    /** Parses the picked `.apkm` and publishes the result. */
    fun inspectApkm(uri: Uri) {
        _parseState.value = ParseState.Loading
        _warnings.value = emptyList()
        InstallManager.reset()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    openSource(uri).use { ApkmParser.parse(it) }
                }
            }
            _parseState.value = result.fold(
                onSuccess = { ParseState.Success(displayName(uri), it) },
                onFailure = { ParseState.Error(it.message ?: it.toString()) },
            )
            result.getOrNull()?.let { _warnings.value = preflight(it) }
        }
    }

    /**
     * Installs the currently parsed bundle, spoofing the installer package to
     * `com.android.vending`. The work is handed to [InstallManager], which runs
     * it in a foreground service so it survives backgrounding.
     */
    fun installApkm(uri: Uri) {
        val parsed = _parseState.value as? ParseState.Success ?: return
        if (InstallManager.isRunning) return
        InstallManager.start(
            context = getApplication(),
            uri = uri,
            bundle = parsed.bundle,
            downgrade = _allowDowngrade.value,
        )
    }

    /** Enables/disables downgrade installs (`-d`) and refreshes the warnings. */
    fun setAllowDowngrade(allow: Boolean) {
        _allowDowngrade.value = allow
        (_parseState.value as? ParseState.Success)?.let { _warnings.value = preflight(it.bundle) }
    }

    /** Clears a finished install result. */
    fun resetInstall() = InstallManager.reset()

    /**
     * Computes pre-flight warnings for [bundle]: storage, ABI, min-API and
     * installed-version mismatches. Blocking warnings disable the install button.
     */
    private fun preflight(bundle: ApkmBundle): List<PreflightWarning> {
        val warnings = mutableListOf<PreflightWarning>()

        val free = freeStorageBytes()
        if (free < bundle.totalSize) {
            warnings += PreflightWarning(
                "Not enough free storage: need about ${formatBytes(bundle.totalSize)}, " +
                    "only ${formatBytes(free)} available.",
                blocking = true,
            )
        }

        val deviceAbis = Build.SUPPORTED_ABIS.toSet()
        val arches = bundle.info.arches
        if (arches.isNotEmpty() && arches.none { it in deviceAbis }) {
            warnings += PreflightWarning(
                "This bundle targets ${arches.joinToString(", ")}, but this device " +
                    "supports ${deviceAbis.joinToString(", ")}.",
                blocking = true,
            )
        }

        val minApi = bundle.info.minApi
        if (minApi != null && minApi > Build.VERSION.SDK_INT) {
            warnings += PreflightWarning(
                "This app requires Android API $minApi, but this device runs API " +
                    "${Build.VERSION.SDK_INT}.",
                blocking = true,
            )
        }

        warnings += installedVersionWarning(bundle)
        return warnings
    }

    /**
     * Warns when the bundle would replace an installed app with a different
     * version. A downgrade is blocking unless the user enabled it; a same-version
     * reinstall is only informational.
     */
    private fun installedVersionWarning(bundle: ApkmBundle): List<PreflightWarning> {
        val installed = installedVersionCode(bundle.info.packageName) ?: return emptyList()
        val warning = Preflight.versionWarning(
            installed = installed,
            incoming = bundle.info.versionCode,
            allowDowngrade = _allowDowngrade.value,
        )
        return listOfNotNull(warning)
    }

    /** Returns the installed version code for [packageName], or `null` if absent. */
    private fun installedVersionCode(packageName: String): Long? = runCatching {
        getApplication<Application>().packageManager
            .getPackageInfo(packageName, 0)
            .longVersionCode
    }.getOrNull()

    /** Waits (briefly) for the asynchronous user-service bind to complete. */
    private fun awaitBound() {
        var attempts = 0
        while (!shellRunner.isBound && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }
    }

    private fun openSource(uri: Uri): ChannelZipSource {
        val resolver = getApplication<Application>().contentResolver
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: error("Could not open the selected file")
        // AutoCloseInputStream closes the descriptor when the channel closes.
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return ChannelZipSource(stream.channel)
    }

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

    private fun freeStorageBytes(): Long {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}
