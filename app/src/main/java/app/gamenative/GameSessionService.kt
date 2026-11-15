package app.gamenative

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
import timber.log.Timber

/**
 * Keeps the app in the foreground state while WoW runs, so it keeps running when the player switches apps (#88).
 * Without it, Android treats the backgrounded app as cached: it freezes the process within seconds, which drops the
 * connection to Blizzard's servers, and may kill it outright for memory. Players often need to switch apps mid-login to
 * read an authenticator code. The notification is low priority and silent; if notifications are turned off, Android
 * hides it but the service still works.
 */
class GameSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.w(e, "Couldn't keep the game session in the foreground")
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Game running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while World of Warcraft is running, so it keeps running when you switch apps."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("World of Warcraft is running")
            .setContentText("Tap to return to the game")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "game_session"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var running = false

        /** Starts the service if it isn't running. Only works while the app is in the foreground; retried on resume. */
        fun ensureStarted(context: Context) {
            if (running) return
            try {
                val intent = Intent(context, GameSessionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                running = true
            } catch (e: Exception) {
                // Android 12+ refuses to start a foreground service from the background; MainActivity.onResume retries.
                Timber.w(e, "Couldn't start the game session service")
            }
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, GameSessionService::class.java))
        }
    }
}
