package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class IpCameraTest {
    @Test fun directUrlsNeedNoOnvifEndpoint() {
        assertEquals("http://192.168.0.50:8080/video", ipCameraUrl("192.168.0.50:8080/video"))
        assertEquals("rtsp://192.168.0.50/live", ipCameraUrl("rtsp://192.168.0.50/live"))
        assertEquals("https://camera.example/video", ipCameraUrl("https://camera.example/video"))
    }
    @Test fun rejectsUnsafeSchemesAndCredentialsInTheAddress() {
        listOf("file:///etc/passwd", "javascript:alert(1)", "http://user:secret@camera/video", "http://camera/#secret", "http://camera/\nfoo", "").forEach {
            assertTrue(runCatching { ipCameraUrl(it) }.isFailure)
        }
    }
    @Test fun secretsAreNotExposedByModelOrUrlValidation() {
        val camera = IpCamera("id", "Webcam", "rtsp://camera/live", "user", "secret")
        assertFalse(camera.toString().contains("secret"))
        assertFalse(camera.toString().contains("camera/live"))
    }
    @Test fun transferRoundTripIncludesIpAndPreservesOldPayloads() {
        val camera = IpCamera("ip-one", "Webcam", "http://camera/video", "viewer", "secret")
        val config = TvConfiguration(emptyList(), emptyList(), null, listOf("ip-one"), emptySet(), listOf(camera))
        val copy = TvConfiguration.decode(config.encode())
        assertEquals("secret", copy.ip.single().password)
        assertEquals(camera.url, copy.ip.single().url)
        assertEquals(2, org.json.JSONObject(String(config.encode())).getInt("version"))
        val old = TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet())
        assertEquals(1, org.json.JSONObject(String(old.encode())).getInt("version"))
        assertTrue(TvConfiguration.decode(old.encode()).ip.isEmpty())
    }
    @Test fun jpegReaderBoundsFramesAndHandlesMultipartHeaders() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val stream = ("--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + jpeg + "\r\n--frame\r\n".toByteArray() + jpeg).inputStream()
        assertArrayEquals(jpeg, readStreamJpeg(stream))
        assertArrayEquals(jpeg, readStreamJpeg(stream))
        assertNull(readStreamJpeg(stream))
        assertTrue(runCatching { readStreamJpeg(jpeg.inputStream(), 4) }.isFailure)
        assertTrue(runCatching { readStreamJpeg(jpeg.copyOf(4).inputStream()) }.isFailure)
        assertTrue(runCatching { readStreamJpeg(ByteArray(65537).inputStream()) }.isFailure)
    }
}
