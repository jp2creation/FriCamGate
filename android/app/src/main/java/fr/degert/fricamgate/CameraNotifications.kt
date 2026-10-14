package fr.degert.fricamgate

import android.app.NotificationChannel
import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

internal data class CameraAlert(val id: String, val camera: String, val label: String, val startTime: Double)

internal fun newCameraAlerts(
    candidates: List<CameraAlert>, after: Double, hidden: Set<String>, posted: Set<String>
): List<CameraAlert> = candidates.filter {
    it.startTime >= after && it.camera !in hidden && it.id !in posted
}.distinctBy { it.id }.sortedBy { it.startTime }

internal object CameraNotifications {
    const val EVENT_ID = "cameraEventId"
    const val CHANNEL = "camera-alerts"
    private const val PERIODIC = "camera-alert-checks"
    private const val IMMEDIATE = "camera-alert-now"
    private val requestLock = Any()
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    fun enabled(context: Context): Boolean = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        .getBoolean("notificationsEnabled", false)

    fun permitted(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun configure(context: Context, enabled: Boolean) {
        val settings = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        val wasEnabled = settings.getBoolean("notificationsEnabled", false)
        settings.edit().putBoolean("notificationsEnabled", enabled).apply()
        if (enabled && !wasEnabled) {
            val native = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE)
            native.edit().putLong(cursorKey(native.getString("baseUrl", "").orEmpty(), native.getString("username", "").orEmpty()), System.currentTimeMillis()).apply()
        }
        schedule(context)
    }

    fun schedule(context: Context) {
        val worker = WorkManager.getInstance(context)
        val hasSession = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE).contains("token")
        if (!enabled(context) || !permitted(context) || !hasSession) {
            worker.cancelUniqueWork(PERIODIC)
            worker.cancelUniqueWork(IMMEDIATE)
            return
        }
        createChannel(context)
        worker.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CameraAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }

    fun checkNow(context: Context) {
        if (!enabled(context) || !permitted(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CameraAlertWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun test(context: Context) {
        val localized = localizedContext(context)
        publish(localized, "notification-test", localized.getString(R.string.test_title), localized.getString(R.string.test_body), null)
    }

    fun check(context: Context, stopped: () -> Boolean): Boolean = synchronized(requestLock) {
        if (!enabled(context) || !permitted(context) || stopped()) return@synchronized true
        val prefs = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", null) ?: return@synchronized true
        val token = prefs.getString("token", null) ?: return@synchronized true
        val username = prefs.getString("username", "").orEmpty()
        val key = cursorKey(baseUrl, username)
        val requestStarted = System.currentTimeMillis()
        val afterMillis = prefs.getLong(key, requestStarted)
        val request = Request.Builder().url("${normalizeBaseUrl(baseUrl)}/api/events?after=${(afterMillis / 1000 - 30).coerceAtLeast(0)}&limit=500&include_thumbnails=0")
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@synchronized response.code == 401 || response.code == 403
            val array = JSONArray(response.body?.string().orEmpty())
            val candidates = (0 until array.length()).mapNotNull { index ->
                val event = array.optJSONObject(index) ?: return@mapNotNull null
                val id = event.optString("id")
                val camera = event.optString("camera")
                val time = event.optDouble("start_time", Double.NaN)
                if (id.isBlank() || camera.isBlank() || !time.isFinite()) return@mapNotNull null
                val subLabel = event.optJSONArray("sub_label")?.optString(0)
                    ?: event.optString("sub_label").takeUnless { it == "null" }
                CameraAlert(id, camera, subLabel?.takeIf { it.isNotBlank() } ?: event.optString("label"), time)
            }
            // A late response must not publish for an account that has since signed out.
            if (stopped() || !enabled(context) || prefs.getString("token", null) != token || prefs.getString("baseUrl", null) != baseUrl) return@synchronized true
            val postedKey = "postedAlerts:$baseUrl:$username"
            val posted = prefs.getStringSet(postedKey, emptySet()).orEmpty().toMutableSet()
            val hidden = prefs.getStringSet("hiddenCameras:$baseUrl", emptySet()).orEmpty()
            val localized = localizedContext(context)
            newCameraAlerts(candidates, afterMillis / 1000.0, hidden, posted).takeLast(20).forEach { alert ->
                if (stopped() || !enabled(context)) return@synchronized true
                val title = alert.camera.replace('_', ' ')
                val text = localizedAlertLabel(localized, alert.label)
                publish(localized, alert.id, title, text, alert.id)
                posted.add(alert.id)
            }
            prefs.edit().putLong(key, requestStarted).putStringSet(postedKey, posted.toList().takeLast(1000).toSet()).apply()
            true
        }
    }

    private fun cursorKey(server: String, user: String) = "notificationCursor:$server:$user"

    private fun localizedContext(context: Context): Context {
        val locales = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
        } else {
            LocaleList.forLanguageTags(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
                .getString("language", "").orEmpty())
        }
        val config = Configuration(context.resources.configuration).apply {
            setLocales(if (locales.isEmpty) Resources.getSystem().configuration.locales else locales)
        }
        return context.createConfigurationContext(config)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.event_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun publish(context: Context, id: String, title: String, text: String, eventId: String?) {
        if (!permitted(context)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            eventId?.let { putExtra(EVENT_ID, it) }
        }
        val pending = PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val locked = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getBoolean("biometricLock", false)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(if (locked) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(id, 0, notification)
    }
}

class CameraAlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runCatching {
        if (CameraNotifications.check(applicationContext) { isStopped }) Result.success() else Result.retry()
    }.getOrElse { Result.retry() }
}

internal fun localizedAlertLabel(context: Context, value: String): String = when (value.lowercase()) {
    "person" -> context.getString(R.string.person)
    "car", "vehicle", "truck", "bus", "motorcycle", "bicycle" -> context.getString(R.string.vehicle)
    "dog", "cat", "bird", "animal" -> context.getString(R.string.animal)
    "" -> context.getString(R.string.new_event)
    else -> value
}
