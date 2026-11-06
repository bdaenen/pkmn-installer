package be.benn0.pkmninstaller.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import be.benn0.pkmninstaller.MainActivity
import be.benn0.pkmninstaller.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while an install runs and
 * mirrors [InstallManager.state] into an ongoing notification.
 *
 * Installs of a large `.apkm` can take minutes; without a foreground service the
 * system could kill the app once it is backgrounded, aborting the install
 * session. The service itself does no work — [InstallManager] owns the install —
 * it only holds the process up and shows progress.
 */
class InstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(InstallManager.state.value))
        observeState()
        return START_NOT_STICKY
    }

    private fun observeState() {
        collectJob?.cancel()
        collectJob = scope.launch {
            InstallManager.state.collectLatest { state ->
                when (state) {
                    is InstallState.Running -> {
                        NotificationManagerCompat.from(this@InstallService)
                            .notify(NOTIFICATION_ID, buildNotification(state))
                    }

                    is InstallState.Success, is InstallState.Error -> {
                        NotificationManagerCompat.from(this@InstallService)
                            .notify(NOTIFICATION_ID, buildNotification(state))
                        stopForegroundCompat()
                        stopSelf()
                    }

                    InstallState.Idle -> {
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun buildNotification(state: InstallState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(contentIntent)
            .setOngoing(state is InstallState.Running)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when (state) {
            InstallState.Idle -> builder.setContentText("Getting ready…")

            is InstallState.Running -> {
                val progress = state.progress
                builder.setContentText(progressText(progress))
                if (progress is InstallProgress.Writing && progress.totalBytes > 0) {
                    val percent = (progress.totalBytesWritten * 100 / progress.totalBytes).toInt()
                    builder.setProgress(100, percent, false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }

            is InstallState.Success -> {
                builder.setContentText("All done! ${state.packageName} is installed")
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            }

            is InstallState.Error -> {
                builder.setContentText("Install failed")
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(state.message),
                )
            }
        }
        return builder.build()
    }

    private fun progressText(progress: InstallProgress): String = when (progress) {
        is InstallProgress.Creating -> "Getting ready…"
        is InstallProgress.Writing ->
            "Installing… (${progress.apkIndex + 1}/${progress.apkCount})"
        is InstallProgress.Committing -> "Finishing up…"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.install_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.install_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "install"
        private const val NOTIFICATION_ID = 1

        /** Starts (or re-delivers to) the foreground service. */
        fun start(context: Context) {
            val intent = Intent(context, InstallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
