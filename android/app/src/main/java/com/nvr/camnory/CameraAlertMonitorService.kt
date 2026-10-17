package com.nvr.camnory

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CameraAlertMonitorService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var polling: ScheduledFuture<*>? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !CameraNotifications.enabled(this) || !CameraNotifications.permitted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.alert_monitor), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraAlertMonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(getString(R.string.alert_monitor)).setContentText(getString(R.string.alert_monitor_running))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, getString(R.string.alert_monitor_stop), stop).build()
        ServiceCompat.startForeground(this, 4201, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        running = true
        if (polling == null) {
            polling = executor.scheduleWithFixedDelay({
                if (!CameraNotifications.enabled(this) || !CameraNotifications.permitted(this) ||
                    runCatching { FrigateServerStore(this@CameraAlertMonitorService).load().isEmpty() }.getOrDefault(true)) {
                    stopSelf()
                } else runCatching { CameraNotifications.check(this) { !running } }
                    .onFailure { if (running) CameraNotifications.diagnostic(this, "NETWORK") }
            }, 0, 20, TimeUnit.SECONDS)
            // End before Android 15's six-hour dataSync limit, leaving periodic work active.
            executor.schedule({ stopSelf() }, 345, TimeUnit.MINUTES)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        running = false
        polling?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "camera-monitor"
        private const val STOP = "com.nvr.camnory.STOP_MONITOR"
        @Volatile var running = false
            private set

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, CameraAlertMonitorService::class.java))
            true
        }.getOrDefault(false)
    }
}
