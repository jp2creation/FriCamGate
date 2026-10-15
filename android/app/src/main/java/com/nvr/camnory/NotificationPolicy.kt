package com.nvr.camnory

internal fun notificationCategory(label: String): String = when (label.lowercase()) {
    "person" -> "person"
    "car", "vehicle", "truck", "bus", "motorcycle", "bicycle" -> "vehicle"
    "dog", "cat", "bird", "animal", "horse", "sheep", "cow", "bear", "deer" -> "animal"
    else -> "other"
}

internal fun minuteInWindow(minute: Int, start: Int, end: Int): Boolean = when {
    start == end -> true
    start < end -> minute in start until end
    else -> minute >= start || minute < end
}

internal data class NotificationPolicy(
    val categories: Set<String> = setOf("person", "animal", "vehicle", "other"),
    val cameras: Set<String>? = null,
    val activeHours: Boolean = false,
    val activeStart: Int = 0,
    val activeEnd: Int = 0,
    val quietHours: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 7 * 60,
    val cooldownSeconds: Long = 0
) {
    fun accepts(camera: String, objectLabel: String, localMinute: Int): Boolean =
        notificationCategory(objectLabel) in categories && (cameras == null || camera in cameras) &&
            (!activeHours || minuteInWindow(localMinute, activeStart, activeEnd))

    fun silent(localMinute: Int): Boolean = quietHours && minuteInWindow(localMinute, quietStart, quietEnd)

    fun cooldownAllows(nowMillis: Long, lastPostedMillis: Long): Boolean =
        lastPostedMillis <= 0 || nowMillis - lastPostedMillis >= cooldownSeconds * 1000
}
