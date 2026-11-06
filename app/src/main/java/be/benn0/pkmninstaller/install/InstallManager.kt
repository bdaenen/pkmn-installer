package be.benn0.pkmninstaller.install

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import be.benn0.pkmninstaller.apkm.ApkmBundle
import be.benn0.pkmninstaller.apkm.ChannelZipSource
import be.benn0.pkmninstaller.shizuku.UserServiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the install flow.
 *
 * The install is deliberately kept out of the ViewModel: it can take minutes
 * for a large bundle, and the UI may be recreated (rotation, process
 * backgrounding) while it runs. [InstallService] keeps the process alive and
 * mirrors [state] into a notification, while the UI simply observes the same
 * flow.
 */
object InstallManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private val client = UserServiceClient()
    private val engine = InstallEngine(client)

    private var job: Job? = null

    val isRunning: Boolean get() = _state.value is InstallState.Running

    /**
     * Starts installing [bundle] from [uri], spoofing the installer package to
     * `com.android.vending`. No-op if an install is already running.
     *
     * @param downgrade pass `-d` to allow installing an older version code.
     */
    fun start(
        context: Context,
        uri: Uri,
        bundle: ApkmBundle,
        downgrade: Boolean = false,
    ) {
        if (isRunning) return
        _state.value = InstallState.Running(InstallProgress.Creating(bundle.totalSize))
        InstallService.start(context)

        val appContext = context.applicationContext
        val sessionStore = InstallSessionStore(appContext)

        job = scope.launch {
            val result = runCatching {
                bindAndAwait()
                openSource(appContext, uri).use { source ->
                    engine.install(
                        source = source,
                        bundle = bundle,
                        downgrade = downgrade,
                        onSessionCreated = { sessionStore.save(it) },
                        onProgress = { progress ->
                            _state.value = InstallState.Running(progress)
                        },
                    )
                }
            }
            // The session is finished (committed or abandoned) either way.
            sessionStore.clear()
            _state.value = result.fold(
                onSuccess = { r ->
                    when (r) {
                        is InstallResult.Success -> InstallState.Success(
                            packageName = r.packageName,
                            installerPackage = r.installerPackage,
                            verified = verifyInstaller(appContext, r.packageName),
                        )

                        is InstallResult.Failure -> InstallState.Error(InstallErrors.friendly(r.reason))
                    }
                },
                onFailure = { InstallState.Error(it.message ?: it.toString()) },
            )
        }
    }

    /**
     * Abandons a session left over from a previous process that died mid-install.
     *
     * Called once on app start. If a session id was persisted but never cleared,
     * the `pm` session is still open on the system; we bind the user service and
     * run `pm install-abandon` so it does not linger.
     */
    fun cleanupStaleSession(context: Context) {
        val sessionStore = InstallSessionStore(context.applicationContext)
        val staleId = sessionStore.load() ?: return
        sessionStore.clear()
        scope.launch {
            runCatching {
                bindAndAwait()
                client.abandonInstallSession(staleId)
            }
        }
    }

    /** Clears a finished result so the UI returns to the idle state. */
    fun reset() {
        if (isRunning) return
        _state.value = InstallState.Idle
    }

    /** Binds the user service and waits (briefly) for the connection. */
    private fun bindAndAwait() {
        if (!client.isBound) client.bind()
        var attempts = 0
        while (!client.isBound && attempts < 50) {
            Thread.sleep(100)
            attempts++
        }
        check(client.isBound) { "Could not connect to the Shizuku user service" }
    }

    /**
     * Reads the install source back from the system **in-app** via
     * [InstallerVerifier] (i.e. `PackageManager.getInstallSourceInfo`) to confirm
     * the spoof worked. Returns a short human-readable summary, or `null` if the
     * check could not be performed.
     */
    private fun verifyInstaller(context: Context, packageName: String): String? {
        val info = InstallerVerifier.read(context, packageName) ?: return null
        val installing = info.installingPackage ?: "unknown"
        val originating = info.originatingPackage
        return buildString {
            append("installing=$installing")
            if (originating != null) append(", originating=$originating")
            info.versionCode?.let { append(", versionCode=$it") }
        }
    }

    private fun openSource(context: Context, uri: Uri): ChannelZipSource {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open the selected file")
        // AutoCloseInputStream closes the descriptor when the channel closes.
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return ChannelZipSource(stream.channel)
    }
}
