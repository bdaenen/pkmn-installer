package com.bennod.pkmninstaller.install

import android.os.ParcelFileDescriptor
import com.bennod.pkmninstaller.apkm.ApkmBundle
import com.bennod.pkmninstaller.apkm.ApkEntry
import com.bennod.pkmninstaller.apkm.ZipEntryInfo
import com.bennod.pkmninstaller.apkm.ZipSource
import com.bennod.pkmninstaller.shizuku.ShellResult
import com.bennod.pkmninstaller.shizuku.UserServiceClient

/** Progress emitted while an install is running. */
sealed interface InstallProgress {
    data class Creating(val totalBytes: Long) : InstallProgress

    data class Writing(
        val apkIndex: Int,
        val apkCount: Int,
        val splitName: String,
        val apkBytesWritten: Long,
        val apkSize: Long,
        val totalBytesWritten: Long,
        val totalBytes: Long,
    ) : InstallProgress

    data class Committing(val totalBytes: Long) : InstallProgress
}

/** Outcome of an install attempt. */
sealed interface InstallResult {
    data class Success(val packageName: String, val installerPackage: String) : InstallResult
    data class Failure(val reason: String) : InstallResult
}

/**
 * Drives the `pm install-create` / `pm install-write` / `pm install-commit`
 * sequence through Shizuku, emulating
 * `adb install-multiple -i com.android.vending`.
 *
 * APK bytes are streamed from the `.apkm` [ZipSource] into each
 * `pm install-write` process's stdin via a pipe, so the bundle is never loaded
 * into memory and the shell user never needs to read the app's private storage.
 */
class InstallEngine(private val client: UserServiceClient) {

    /**
     * Installs [bundle] from [source], setting the installer package to
     * [installerPackage].
     *
     * @param replace pass `-r` to `pm install-create` (update an existing app).
     * @param downgrade pass `-d` to allow installing an older version code.
     * @param onSessionCreated called with the session id as soon as it is
     *   created, so the caller can persist it and clean it up if the process
     *   dies mid-install.
     * @param onProgress called from the calling thread as the install advances.
     */
    fun install(
        source: ZipSource,
        bundle: ApkmBundle,
        installerPackage: String = DEFAULT_INSTALLER,
        replace: Boolean = true,
        downgrade: Boolean = false,
        onSessionCreated: (Int) -> Unit = {},
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallResult {
        val totalBytes = bundle.totalSize
        onProgress(InstallProgress.Creating(totalBytes))

        val create = client.createInstallSession(installerPackage, totalBytes, replace, downgrade)
        val sessionId = PmOutput.parseSessionId(create.stdout)
            ?: return InstallResult.Failure(PmOutput.failureReason(create.stdout, create.stderr))
        onSessionCreated(sessionId)

        var totalWritten = 0L
        bundle.apks.forEachIndexed { index, apk ->
            val entry = source.entries.firstOrNull { it.name == apk.entryName }
                ?: return abandon(sessionId, "Bundle is missing entry ${apk.entryName}")

            val write = writeApk(source, entry, sessionId, apk) { bytes ->
                totalWritten += bytes
                onProgress(
                    InstallProgress.Writing(
                        apkIndex = index,
                        apkCount = bundle.apks.size,
                        splitName = apk.splitName,
                        apkBytesWritten = totalWritten - writtenBefore(bundle, index),
                        apkSize = apk.size,
                        totalBytesWritten = totalWritten,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!write.isSuccess) {
                return abandon(sessionId, PmOutput.failureReason(write.stdout, write.stderr))
            }
        }

        onProgress(InstallProgress.Committing(totalBytes))
        val commit = client.commitInstallSession(sessionId)
        return if (commit.isSuccess) {
            InstallResult.Success(bundle.info.packageName, installerPackage)
        } else {
            InstallResult.Failure(PmOutput.failureReason(commit.stdout, commit.stderr))
        }
    }

    /** Abandons [sessionId] and returns a failure carrying [reason]. */
    private fun abandon(sessionId: Int, reason: String): InstallResult {
        runCatching { client.abandonInstallSession(sessionId) }
        return InstallResult.Failure(reason)
    }

    /**
     * Streams a single APK into `pm install-write`'s stdin.
     *
     * A pipe connects the app process (writer) to the user service (reader). The
     * writer runs on its own thread because the binder call blocks until the
     * service has consumed the whole stream.
     */
    private fun writeApk(
        source: ZipSource,
        entry: ZipEntryInfo,
        sessionId: Int,
        apk: ApkEntry,
        onBytes: (Long) -> Unit,
    ): ShellResult {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val writer = Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    source.open(entry).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            onBytes(read.toLong())
                        }
                    }
                }
            } catch (t: Throwable) {
                // Close the write end so the reader sees EOF instead of hanging.
                runCatching { writeEnd.close() }
            }
        }
        writer.start()

        return try {
            client.writeApk(sessionId, apk.splitName, apk.size, readEnd)
        } finally {
            runCatching { readEnd.close() }
            writer.join()
        }
    }

    private fun writtenBefore(bundle: ApkmBundle, index: Int): Long =
        bundle.apks.take(index).sumOf { it.size }

    companion object {
        const val DEFAULT_INSTALLER = "com.android.vending"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
