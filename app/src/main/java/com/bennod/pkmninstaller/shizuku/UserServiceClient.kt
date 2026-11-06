package com.bennod.pkmninstaller.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.bennod.pkmninstaller.BuildConfig
import rikka.shizuku.Shizuku

/**
 * Binds to the Shizuku [UserService] and exposes blocking calls for running
 * shell commands and driving the `pm install-*` sequence.
 *
 * The user service runs in a separate process with shell/root identity, which is
 * the modern replacement for the deprecated `Shizuku.newProcess(...)`. Binding
 * is asynchronous, so callers should [bind] first and wait for [isBound] before
 * calling any of the operations.
 */
class UserServiceClient(
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
) {

    @Volatile
    private var service: IUserService? = null

    val isBound: Boolean get() = service != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUserService.Stub.asInterface(binder)
            onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            onDisconnected()
        }
    }

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    /** Starts binding; the result arrives via the [ServiceConnection]. */
    fun bind() {
        if (!Shizuku.pingBinder()) return
        Shizuku.bindUserService(args, connection)
    }

    /** Unbinds and asks Shizuku to remove the connection. */
    fun unbind() {
        if (!Shizuku.pingBinder()) return
        Shizuku.unbindUserService(args, connection, true)
        service = null
    }

    /**
     * Runs [command] through the user service.
     *
     * @throws IllegalStateException if the service is not bound yet.
     */
    fun exec(command: String): ShellResult = requireService().exec(command).toShellResult()

    /** Runs `pm install-create` and returns the raw result. */
    fun createInstallSession(
        installerPackage: String,
        totalSize: Long,
        replace: Boolean,
        downgrade: Boolean,
    ): ShellResult = requireService()
        .createInstallSession(installerPackage, totalSize, replace, downgrade)
        .toShellResult()

    /**
     * Runs `pm install-write`, streaming the APK bytes from [source] into the
     * command's stdin. The descriptor is consumed by the service.
     */
    fun writeApk(
        sessionId: Int,
        splitName: String,
        size: Long,
        source: ParcelFileDescriptor,
    ): ShellResult = requireService()
        .writeApk(sessionId, splitName, size, source)
        .toShellResult()

    /** Runs `pm install-commit`. */
    fun commitInstallSession(sessionId: Int): ShellResult =
        requireService().commitInstallSession(sessionId).toShellResult()

    /** Runs `pm install-abandon`. */
    fun abandonInstallSession(sessionId: Int): ShellResult =
        requireService().abandonInstallSession(sessionId).toShellResult()

    private fun requireService(): IUserService = service
        ?: error("User service is not bound (is Shizuku running and permission granted?)")

    private fun android.os.Bundle.toShellResult() = ShellResult(
        exitCode = getInt(UserService.KEY_EXIT_CODE, -1),
        stdout = getString(UserService.KEY_STDOUT).orEmpty(),
        stderr = getString(UserService.KEY_STDERR).orEmpty(),
    )
}
