package com.nvr.camnory

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.WebSocket
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun frigateLiveMediaSource(api: FrigateNativeApi, token: String, stream: String, audioOnly: Boolean = false): ProgressiveMediaSource =
    ProgressiveMediaSource.Factory(DataSource.Factory {
        FrigateMseDataSource { onData, onError -> api.openMseStream(token, stream, onData, onError, audioOnly) }
    }).createMediaSource(MediaItem.Builder().setUri("camnory-live://frigate")
        .setMimeType(MimeTypes.VIDEO_MP4).build())

/** Feeds go2rtc's original fragmented MP4 bytes to Media3, without JPEG resizing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class FrigateMseDataSource(
    private val connect: ((ByteArray) -> Unit, () -> Unit) -> WebSocket
) : BaseDataSource(true) {
    private val chunks = ArrayBlockingQueue<ByteArray>(64)
    private val queuedBytes = AtomicInteger()
    private var socket: WebSocket? = null
    private var current = ByteArray(0)
    private var offset = 0
    @Volatile private var closed = true
    @Volatile private var failed = false
    private var opened = false
    private var source: Uri? = null

    override fun getUri() = source
    override fun open(dataSpec: DataSpec): Long {
        require(dataSpec.position == 0L) { "Live source is not seekable" }
        transferInitializing(dataSpec)
        source = dataSpec.uri
        chunks.clear(); queuedBytes.set(0); current = ByteArray(0); offset = 0
        closed = false; failed = false; opened = true
        socket = connect({ bytes ->
            if (!closed && !failed) {
                val total = queuedBytes.addAndGet(bytes.size)
                if (total > 8 * 1024 * 1024 || !chunks.offer(bytes)) fail()
            }
        }, ::fail)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }
    private fun fail() {
        if (closed) return
        failed = true
        chunks.clear(); chunks.offer(ByteArray(0))
        socket?.cancel()
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed) return C.RESULT_END_OF_INPUT
        if (failed) throw IOException("Live video transport unavailable")
        if (this.offset == current.size) {
            current = try { chunks.poll(15, TimeUnit.SECONDS) ?: throw IOException("Live video timed out") }
            catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Live video interrupted") }
            if (closed) return C.RESULT_END_OF_INPUT
            if (failed || current.isEmpty()) throw IOException("Live video transport ended")
            queuedBytes.addAndGet(-current.size)
            this.offset = 0
        }
        val count = minOf(length, current.size - this.offset)
        current.copyInto(buffer, offset, this.offset, this.offset + count)
        this.offset += count
        bytesTransferred(count)
        return count
    }
    override fun close() {
        closed = true
        socket?.cancel(); socket = null
        chunks.clear(); chunks.offer(ByteArray(0))
        current = ByteArray(0); source = null
        if (opened) { opened = false; transferEnded() }
    }
}
