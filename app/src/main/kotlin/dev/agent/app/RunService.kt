package dev.agent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps the process alive while tasks run, so a long agent turn
 * survives the app going to the background. Started by the first run, stopped by the last.
 */
class RunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Running tasks", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent is working")
            .setContentText("A task is running. Open the app to see progress or answer approvals.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "runs"
        private const val NOTIFICATION_ID = 1
        private val active = AtomicInteger(0)

        /** Call when a task starts. Starts the service for the first active run. */
        fun begin(context: Context) {
            if (active.incrementAndGet() == 1) {
                context.startForegroundService(Intent(context, RunService::class.java))
            }
        }

        /** Call when a task ends, whatever the outcome. Stops the service after the last one. */
        fun end(context: Context) {
            if (active.decrementAndGet() == 0) {
                context.stopService(Intent(context, RunService::class.java))
            }
        }
    }
}
