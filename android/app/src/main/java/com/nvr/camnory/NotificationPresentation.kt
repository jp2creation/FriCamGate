package com.nvr.camnory

import java.util.Locale

internal fun notificationMatchesKind(event: NativeEvent, kind: EventKind): Boolean =
    kind.labels?.split(',')?.contains(event.objectLabel.lowercase(Locale.ROOT)) ?: true

internal fun recentNotifications(events: List<NativeEvent>, visibleCameras: Set<String>, now: Double): List<NativeEvent> =
    events.filter { it.camera in visibleCameras && (it.startTime == null || it.startTime >= now - 86400) }
        .sortedByDescending { it.startTime ?: 0.0 }.take(100)
