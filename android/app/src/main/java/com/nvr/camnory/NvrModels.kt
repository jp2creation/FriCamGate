package com.nvr.camnory

import android.graphics.Bitmap

internal data class NativeLogin(val token: String, val username: String)

internal data class DashboardData(val cameras: List<NativeCamera>, val events: List<NativeEvent>)

internal data class NativeCamera(
    val name: String,
    val friendlyName: String,
    val enabled: Boolean,
    val streamName: String,
    val detectEnabled: Boolean?,
    val motionEnabled: Boolean?,
    val recordingsEnabled: Boolean?,
    val snapshotsEnabled: Boolean?,
    val audioEnabled: Boolean?,
    val notificationsEnabled: Boolean?,
    val audioTranscriptionEnabled: Boolean?,
    val tvStreamName: String = streamName
)

internal data class NativeEvent(
    val id: String,
    val camera: String,
    val label: String,
    val objectLabel: String,
    val subLabel: String?,
    val time: String,
    val startTime: Double?,
    val endTime: Double?,
    val thumbnail: Bitmap?,
    val description: String? = null
)
