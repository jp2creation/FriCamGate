package com.nvr.camnory

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import okhttp3.WebSocket

/** Recorded media is separate from live transport: a provider need not use go2rtc. */
internal interface NvrRecordedMedia {
    fun eventVodUrl(eventId: String): String
    fun recordingVodUrl(cameraName: String, startSec: Long, endSec: Long, stream: String? = null): String
    fun recordingClipUrl(cameraName: String, startSec: Long, endSec: Long): String
}

internal interface NvrImages {
    fun latestFrameUrl(cameraName: String): String
    fun eventSnapshotUrl(eventId: String): String
    fun eventPreviewUrl(eventId: String): String
    fun recordingSnapshotUrl(cameraName: String, frameTime: Double): String
    fun loadBitmap(token: String, url: String, callback: (Bitmap?) -> Unit)
    fun loadGif(token: String, url: String, callback: (Drawable?) -> Unit)
}

internal interface NvrPlaybackMedia : NvrRecordedMedia, NvrImages

/** Optional transport contract; does not imply support for live audio or talkback. */
internal interface NvrLiveFrames {
    fun openMjpegStream(token: String, streamName: String, onFrame: (Bitmap) -> Unit, onError: () -> Unit): WebSocket
}

internal interface NvrEventHistory {
    fun loadEvents(token: String, cameraName: String, afterSec: Double, beforeSec: Double,
        callback: (Result<List<NativeEvent>>) -> Unit)
    fun searchEvents(token: String, search: EventSearch, now: Double, before: Double?,
        callback: (Result<List<NativeEvent>>) -> Unit)
}
