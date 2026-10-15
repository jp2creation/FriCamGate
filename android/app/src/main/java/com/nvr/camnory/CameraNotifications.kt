package com.nvr.camnory

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
import java.util.Calendar
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal data class CameraAlert(val id: String, val camera: String, val label: String, val startTime: Double,
    val facts: EventFacts = EventFacts(label, null, null))

internal fun newCameraAlerts(
    candidates: List<CameraAlert>, after: Double, hidden: Set<String>, posted: Set<String>
): List<CameraAlert> = candidates.filter {
    it.startTime >= after && it.camera !in hidden && it.id !in posted
}.distinctBy { it.id }.sortedBy { it.startTime }

internal object CameraNotifications {
    const val EVENT_ID = "cameraEventId"
    private const val PERIODIC = "camera-alert-checks"
    private const val IMMEDIATE = "camera-alert-now"
    private val requestLock = Any()
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val imageClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()

    fun policy(context: Context): NotificationPolicy {
        val p = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        return NotificationPolicy(
            categories = setOf("person", "animal", "vehicle", "other").filter { p.getBoolean("alertType:$it", true) }.toSet(),
            cameras = if (p.getBoolean("alertAllCameras", true)) null else p.getStringSet("alertCameras", emptySet()).orEmpty(),
            activeHours = p.getBoolean("alertActiveHours", false), activeStart = p.getInt("alertActiveStart", 0), activeEnd = p.getInt("alertActiveEnd", 0),
            quietHours = p.getBoolean("alertQuietHours", false), quietStart = p.getInt("alertQuietStart", 1320), quietEnd = p.getInt("alertQuietEnd", 420),
            cooldownSeconds = p.getLong("alertCooldown", 0))
    }

    fun selectedChannel(context: Context, silent: Boolean = false): String {
        val p = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        val sound = !silent && p.getBoolean("alertSound", true)
        val vibration = !silent && p.getBoolean("alertVibration", true)
        return "camera-alerts-${if (sound) "sound" else "quiet"}-${if (vibration) "vibrate" else "still"}-v2"
    }

    fun enabled(context: Context): Boolean = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        .getBoolean("notificationsEnabled", false)

    fun permitted(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(selectedChannel(context))
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
            context.stopService(Intent(context, CameraAlertMonitorService::class.java))
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

    fun test(context: Context): Boolean {
        val localized = localizedContext(context)
        return publish(localized, "notification-test-${System.currentTimeMillis()}", localized.getString(R.string.test_title), localized.getString(R.string.test_body), null)
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
            if (!response.isSuccessful) {
                diagnostic(context, "HTTP ${response.code}")
                return@synchronized false
            }
            val array = JSONArray(response.body?.string().orEmpty())
            val candidates = (0 until array.length()).mapNotNull { index ->
                val event = array.optJSONObject(index) ?: return@mapNotNull null
                val id = event.optString("id")
                val camera = event.optString("camera")
                val time = event.optDouble("start_time", Double.NaN)
                if (id.isBlank() || camera.isBlank() || !time.isFinite()) return@mapNotNull null
                val facts = EventFacts.fromJson(event)
                CameraAlert(id, camera, facts.name ?: facts.label, time, facts)
            }
            // A late response must not publish for an account that has since signed out.
            if (stopped() || !enabled(context) || prefs.getString("token", null) != token || prefs.getString("baseUrl", null) != baseUrl) return@synchronized true
            val postedKey = "postedAlerts:$baseUrl:$username"
            val posted = prefs.getStringSet(postedKey, emptySet()).orEmpty().toMutableSet()
            val hidden = prefs.getStringSet("hiddenCameras:$baseUrl", emptySet()).orEmpty()
            val localized = localizedContext(context)
            val policy = policy(context)
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            newCameraAlerts(candidates, afterMillis / 1000.0 - 30.0, hidden, posted)
                .filter { policy.accepts(it.camera, it.facts.label, minute) }.takeLast(20).forEach { alert ->
                if (stopped() || !enabled(context)) return@synchronized true
                val cooldownKey = "alertLast:$baseUrl:$username:${alert.camera}:${notificationCategory(alert.facts.label)}"
                val last = prefs.getLong(cooldownKey, 0)
                if (!policy.accepts(alert.camera, alert.facts.label, minute) || !policy.cooldownAllows(requestStarted, last)) {
                    posted.add(alert.id)
                    return@forEach
                }
                val title = alert.camera.replace('_', ' ')
                val narrative = eventNarrative(localized, alert.facts, title, alert.startTime)
                val text = alert.facts.description?.let {
                    "$narrative\n${localized.getString(R.string.frigate_description_inline, it)}"
                } ?: narrative
                val bitmap = if (context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getBoolean("biometricLock", false)) null
                    else snapshot(baseUrl, token, alert.id)
                if (stopped() || !enabled(context) || prefs.getString("token", null) != token) return@synchronized true
                if (publish(localized, alert.id, title, text, alert.id, policy.silent(minute), bitmap)) {
                    posted.add(alert.id)
                    prefs.edit().putLong(cooldownKey, requestStarted).apply()
                }
            }
            prefs.edit().putLong(key, requestStarted).putStringSet(postedKey, posted.toList().takeLast(1000).toSet()).apply()
            diagnostic(context, "OK")
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

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        for (sound in listOf(false, true)) for (vibration in listOf(false, true)) {
            val id = "camera-alerts-${if (sound) "sound" else "quiet"}-${if (vibration) "vibrate" else "still"}-v2"
            val mode = context.getString(when {
                sound && vibration -> R.string.alert_sound_vibration
                sound -> R.string.alert_sound
                vibration -> R.string.alert_vibration
                else -> R.string.alert_silent
            })
            manager.createNotificationChannel(NotificationChannel(id, "${context.getString(R.string.event_channel)} · $mode",
                if (sound || vibration) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW).apply {
                enableVibration(vibration)
                if (vibration) vibrationPattern = longArrayOf(0, 250, 120, 250)
                setSound(if (sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            })
        }
    }

    private fun publish(context: Context, id: String, title: String, text: String, eventId: String?, silent: Boolean = false, bitmap: Bitmap? = null): Boolean {
        if (!permitted(context)) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        createChannel(context)
        if (Build.VERSION.SDK_INT >= 26 && context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(selectedChannel(context, silent))?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            eventId?.let { putExtra(EVENT_ID, it) }
        }
        val pending = PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val locked = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getBoolean("biometricLock", false)
        val settings = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        val notification = NotificationCompat.Builder(context, selectedChannel(context, silent))
            .setSmallIcon(R.drawable.ic_bell).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(if (locked) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSound(if (!silent && settings.getBoolean("alertSound", true)) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null)
            .setVibrate(if (!silent && settings.getBoolean("alertVibration", true)) longArrayOf(0, 250, 120, 250) else longArrayOf(0))
            .apply {
                if (bitmap != null) setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(text))
            }
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, 0, notification)
            context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).edit()
                .putLong("alertLastDelivery", System.currentTimeMillis()).apply()
            true
        }.getOrDefault(false)
    }

    fun diagnostic(context: Context, status: String) {
        context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).edit()
            .putString("alertLastCheckStatus", status).putLong("alertLastCheck", System.currentTimeMillis()).apply()
    }

    private fun snapshot(server: String, token: String, id: String): Bitmap? = runCatching {
        val request = Request.Builder().url("${normalizeBaseUrl(server)}/api/events/${android.net.Uri.encode(id)}/snapshot.jpg?h=180")
            .header("Authorization", "Bearer $token").build()
        imageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val source = response.body?.source() ?: return@use null
            source.request(2 * 1024 * 1024L + 1)
            if (source.buffer.size > 2 * 1024 * 1024) return@use null
            val bytes = source.readByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()
}

class CameraAlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runCatching {
        if (CameraNotifications.check(applicationContext) { isStopped }) Result.success() else Result.retry()
    }.getOrElse {
        CameraNotifications.diagnostic(applicationContext, "NETWORK")
        Result.retry()
    }
}

internal fun localizedAlertLabel(context: Context, value: String): String = when (value.lowercase()) {
    "person" -> context.getString(R.string.person)
    "car", "vehicle", "truck", "bus", "motorcycle", "bicycle" -> context.getString(R.string.vehicle)
    "dog", "cat", "bird", "animal" -> context.getString(R.string.animal)
    "" -> context.getString(R.string.new_event)
    else -> value
}
