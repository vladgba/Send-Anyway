package x.vladgba.sendanyway.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import x.vladgba.sendanyway.R
import x.vladgba.sendanyway.ui.MainActivity
import java.io.Closeable

/**
 * Keeps a running transfer alive when the screen is left or turned off.
 *
 * The transfer itself still runs on its worker thread; this service only makes the process a
 * foreground process (so Android doesn't kill or freeze it), holds a partial wake lock and a
 * high-performance Wi-Fi lock for the duration, and shows a progress notification with Cancel.
 *
 * One transfer at a time. Call [begin] from the UI thread when a transfer is claimed, [progress]
 * from the worker (rate-limited here), and [end] exactly once when it finishes or fails.
 */
class TransferService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout") // released in onDestroy; a transfer may legitimately take hours
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelHook?.close()
            return START_NOT_STICKY
        }
        val n = build(this, title, text, 0, 0, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SendAnyway:transfer")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            // HIGH_PERF is deprecated on 29+ but still honoured; LOW_LATENCY only works with the screen on.
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "SendAnyway:transfer")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY // a killed transfer can't be resumed, don't restart empty
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_ID = 2
        private const val ACTION_CANCEL = "x.vladgba.sendanyway.CANCEL"

        @Volatile private var title = ""
        @Volatile private var text = ""
        @Volatile private var cancelHook: Closeable? = null
        @Volatile private var active = false
        @Volatile private var lastUpdate = 0L

        val isActive: Boolean get() = active

        /** Start the foreground service. [cancel] is closed when the user taps Cancel. */
        fun begin(ctx: Context, title: String, text: String, cancel: Closeable) {
            val app = ctx.applicationContext
            ensureChannel(app)
            this.title = title
            this.text = text
            cancelHook = cancel
            active = true
            app.getSystemService(NotificationManager::class.java)?.cancel(RESULT_ID)
            val intent = Intent(app, TransferService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
                else app.startService(intent)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException: the transfer still runs, just unprotected
            }
        }

        /** Progress from any thread; rate-limited to ~2 updates/s (Android drops faster ones anyway). */
        fun progress(ctx: Context, done: Long, total: Long, line: String) {
            if (!active) return
            val now = SystemClock.uptimeMillis()
            if (now - lastUpdate < 500 && done < total) return
            lastUpdate = now
            text = line
            notify(ctx, NOTIFICATION_ID, build(ctx, title, line, done, total, ongoing = true))
        }

        /** Stop the service and leave a dismissible result notification. */
        @SuppressLint("ImplicitSamInstance") // stopService matches by component, a fresh Intent is correct
        fun end(ctx: Context, resultTitle: String, resultText: String) {
            if (!active) return
            active = false
            cancelHook = null
            val app = ctx.applicationContext
            app.stopService(Intent(app, TransferService::class.java))
            notify(app, RESULT_ID, build(app, resultTitle, resultText, 0, 0, ongoing = false))
        }

        @SuppressLint("MissingPermission") // POST_NOTIFICATIONS may be denied: then nothing is shown, which is fine
        private fun notify(ctx: Context, id: Int, n: Notification) {
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(id, n) } catch (_: Exception) {}
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }

        private fun build(ctx: Context, title: String, text: String, done: Long, total: Long, ongoing: Boolean): Notification {
            val app = ctx.applicationContext
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Brings the existing task to the front instead of starting a new screen.
            val open = Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(app, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(app)
            b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(app, 0, open, flags))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing)
            if (ongoing) {
                if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false) else b.setProgress(0, 0, true)
                val cancel = PendingIntent.getService(
                    app, 1, Intent(app, TransferService::class.java).setAction(ACTION_CANCEL), flags,
                )
                @Suppress("DEPRECATION")
                b.addAction(Notification.Action.Builder(null, app.getString(R.string.cancel), cancel).build())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            } else {
                b.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            }
            return b.build()
        }
    }
}
