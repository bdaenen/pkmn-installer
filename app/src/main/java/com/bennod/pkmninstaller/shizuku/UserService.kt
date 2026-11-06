package com.bennod.pkmninstaller.shizuku

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * The Shizuku user service.
 *
 * This class is instantiated by Shizuku in a **separate process** running with
 * the identity of the Shizuku backend (uid 2000 / shell for ADB, uid 0 for
 * root). It is the modern replacement for the deprecated
 * `Shizuku.newProcess(...)`: instead of shelling out from the app process, the
 * app binds to this service and calls [exec] over Binder.
 *
 * Because it is not a normal Android app process, most `Context` APIs do not
 * work here — we only use it to run commands.
 */
class UserService : IUserService.Stub {

    /**
     * Shizuku v13+ prefers this constructor and passes a `Context` created with
     * `createPackageContextAsUser`. Older Shizuku versions use the default
     * constructor, so both must exist.
     */
    @Keep
    @Suppress("unused")
    constructor(context: Context) {
        Log.i(TAG, "UserService created with context: $context")
    }

    @Suppress("unused")
    constructor() {
        Log.i(TAG, "UserService created")
    }

    /** Reserved Shizuku method: called when the service is stopped. */
    override fun destroy() {
        Log.i(TAG, "destroy")
        System.exit(0)
    }

    override fun exec(command: String): Bundle =
        runCommand(listOf("sh", "-c", command), timeoutSeconds = EXEC_TIMEOUT_SECONDS)

    override fun createInstallSession(
        installerPackage: String,
        totalSize: Long,
        replace: Boolean,
        downgrade: Boolean,
    ): Bundle {
        val args = buildList {
            add("pm")
            add("install-create")
            add("-i")
            add(installerPackage)
            if (replace) add("-r")
            if (downgrade) add("-d")
            add("-S")
            add(totalSize.toString())
        }
        return runCommand(args, timeoutSeconds = EXEC_TIMEOUT_SECONDS)
    }

    override fun writeApk(
        sessionId: Int,
        splitName: String,
        size: Long,
        source: ParcelFileDescriptor,
    ): Bundle {
        val args = listOf(
            "pm", "install-write",
            "-S", size.toString(),
            sessionId.toString(),
            splitName,
            "-",
        )
        return runCommand(args, timeoutSeconds = INSTALL_TIMEOUT_SECONDS, stdin = source)
    }

    override fun commitInstallSession(sessionId: Int): Bundle =
        runCommand(
            listOf("pm", "install-commit", sessionId.toString()),
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
        )

    override fun abandonInstallSession(sessionId: Int): Bundle =
        runCommand(
            listOf("pm", "install-abandon", sessionId.toString()),
            timeoutSeconds = EXEC_TIMEOUT_SECONDS,
        )

    /**
     * Runs [args] as a subprocess, optionally streaming [stdin] into its input.
     *
     * stdout/stderr are drained on separate threads to avoid pipe-buffer
     * deadlocks while the (potentially large) stdin is being written.
     */
    private fun runCommand(
        args: List<String>,
        timeoutSeconds: Long,
        stdin: ParcelFileDescriptor? = null,
    ): Bundle {
        val result = Bundle()
        try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.copyTo(stdout) }
            val errThread = Thread { process.errorStream.copyTo(stderr) }
            outThread.start()
            errThread.start()

            if (stdin != null) {
                // Closing the stream signals EOF to the child process.
                ParcelFileDescriptor.AutoCloseInputStream(stdin).use { input ->
                    process.outputStream.use { out -> input.copyTo(out) }
                }
            } else {
                process.outputStream.close()
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                result.putInt(KEY_EXIT_CODE, -1)
                result.putString(KEY_STDERR, "Command timed out after ${timeoutSeconds}s")
                return result
            }
            outThread.join()
            errThread.join()

            result.putInt(KEY_EXIT_CODE, process.exitValue())
            result.putString(KEY_STDOUT, stdout.toString().trim())
            result.putString(KEY_STDERR, stderr.toString().trim())
        } catch (t: Throwable) {
            Log.e(TAG, "runCommand failed: $args", t)
            result.putInt(KEY_EXIT_CODE, -1)
            result.putString(KEY_STDERR, t.toString())
        }
        return result
    }

    private fun java.io.InputStream.copyTo(target: StringBuilder) {
        val buffer = ByteArray(4096)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            target.append(String(buffer, 0, read, Charsets.UTF_8))
        }
    }

    companion object {
        private const val TAG = "PkmnUserService"
        private const val EXEC_TIMEOUT_SECONDS = 30L
        private const val INSTALL_TIMEOUT_SECONDS = 600L

        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
    }
}
