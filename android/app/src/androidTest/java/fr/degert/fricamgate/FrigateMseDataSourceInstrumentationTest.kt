package com.nvr.camnory

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FrigateMseDataSourceInstrumentationTest {
    private class Socket : WebSocket {
        var cancelled = false
        override fun request() = Request.Builder().url("https://example.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
    private fun spec() = DataSpec(android.net.Uri.parse("camnory-live://test"))
    @Test fun preservesFragmentsAcrossPartialReadsAndCancelsOnRelease() {
        val socket = Socket()
        val source = FrigateMseDataSource { data, _ -> data(byteArrayOf(1, 2, 3)); data(byteArrayOf(4, 5)); socket }
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec()))
        val buffer = ByteArray(8)
        assertEquals(2, source.read(buffer, 0, 2))
        assertEquals(1, source.read(buffer, 2, 4))
        assertEquals(2, source.read(buffer, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.copyOf(5))
        source.close()
        assertTrue(socket.cancelled)
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
    }
    @Test fun transportFailureAndBackpressureAreReportedInsteadOfGrowingMemory() {
        val socket = Socket()
        val source = FrigateMseDataSource { data, _ -> data(ByteArray(8 * 1024 * 1024 + 1)); socket }
        source.open(spec())
        try { source.read(ByteArray(1), 0, 1); fail("Overflow must fail") } catch (_: IOException) { }
        finally { source.close() }
        assertTrue(socket.cancelled)
        val broken = FrigateMseDataSource { _, error -> error(); socket }
        broken.open(spec())
        try { broken.read(ByteArray(1), 0, 1); fail("Transport error must fail") } catch (_: IOException) { }
        finally { broken.close() }
    }
}
