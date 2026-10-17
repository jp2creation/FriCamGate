package com.nvr.camnory

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLSocket
import okio.ByteString.Companion.decodeBase64
import com.google.zxing.LuminanceSource
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.BarcodeFormat

class TvPairingTransportTest {
    private val payload get() = TvConfiguration(emptyList(), emptyList(), null, listOf("fixture"), emptySet()).encode()
    @Test fun qrOnlyAcceptsPrivateNumericIpv4AndRedactsInvitation() {
        val ticket = TvPairingTicket("192.168.1.20", 23000, "a".repeat(64), "b".repeat(43) + "=")
        assertEquals(ticket.host, TvPairingTicket.parse(ticket.qr()).host)
        assertEquals(6, ticket.code().length)
        assertFalse(ticket.toString().contains(ticket.secret))
        listOf("127.0.0.1", "8.8.8.8", "169.254.169.254", "0.0.0.0", "example.com", "192.168.001.1", "192.168.1.2/path").forEach {
            val json = JSONObject(ticket.qr()).put("host", it)
            assertTrue("Untrusted endpoint accepted", runCatching { TvPairingTicket.parse(json.toString()) }.isFailure)
        }
    }
    @Test fun realTlsTransferIsSingleUse() {
        val received = CountDownLatch(1)
        var value: ByteArray? = null
        TvPairingReceiver(InetAddress.getByName("127.0.0.1"), { value = it; received.countDown() }, {}).use { receiver ->
            receiver.start()
            TvPairingSender().use { it.send(receiver.ticket, payload) }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertArrayEquals(payload, value)
            assertTrue(runCatching { TvPairingSender().use { it.send(receiver.ticket, payload) } }.isFailure)
            value?.fill(0)
        }
    }
    @Test fun generatedQrCanBeDecodedWithoutEmbeddingConnectionCredentials() {
        val ticket = TvPairingTicket("192.168.1.20", 23000, "a".repeat(64), "b".repeat(43) + "=")
        val matrix = QRCodeWriter().encode(ticket.qr(), BarcodeFormat.QR_CODE, 512, 512)
        val luminance = object : LuminanceSource(512, 512) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = ByteArray(512) { x -> if (matrix[x, y]) 0 else 255.toByte() }
            override fun getMatrix(): ByteArray = ByteArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) 0 else 255.toByte() }
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance))).text
        assertEquals(ticket.qr(), decoded)
        assertEquals(ticket.host, TvPairingTicket.parse(decoded).host)
        assertFalse(decoded.contains("password"))
        assertFalse(decoded.contains("token"))
    }
    @Test fun malformedAndOversizedFramesAreRejectedBeforeValidSubmission() {
        val accepted = CountDownLatch(1)
        TvPairingReceiver(InetAddress.getByName("127.0.0.1"), { it.fill(0); accepted.countDown() }, {}).use { receiver ->
            receiver.start()
            // Trust bypass is confined to this adversarial fixture, not application transport.
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            listOf(TvConfiguration.MAX_BYTES + 1, -1, 2).forEach { size ->
                (tls.socketFactory.createSocket("127.0.0.1", receiver.ticket.port) as SSLSocket).use { socket ->
                    socket.soTimeout = 1500
                    DataOutputStream(socket.outputStream).apply {
                        writeInt(TvPairingReceiver.MAGIC); write(receiver.ticket.secret.decodeBase64()!!.toByteArray()); writeInt(size)
                        if (size == 2) write(byteArrayOf(0, 0))
                        flush()
                    }
                    assertTrue(runCatching { DataInputStream(socket.inputStream).readInt() }.isFailure)
                }
            }
            assertEquals(1L, accepted.count)
            TvPairingSender().use { it.send(receiver.ticket, payload) }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
        }
    }
    @Test fun wrongPinAndSecretCannotSubmitDataButValidPeerStillCan() {
        val received = CountDownLatch(1)
        TvPairingReceiver(InetAddress.getByName("127.0.0.1"), { it.fill(0); received.countDown() }, {}).use { receiver ->
            receiver.start()
            val t = receiver.ticket
            assertTrue(runCatching { TvPairingSender().use { it.send(TvPairingTicket(t.host, t.port, "0".repeat(64), t.secret), payload) } }.isFailure)
            assertTrue(runCatching { TvPairingSender().use { it.send(TvPairingTicket(t.host, t.port, t.pin, "A".repeat(43) + "="), payload) } }.isFailure)
            assertEquals(1L, received.count)
            TvPairingSender().use { it.send(t, payload) }
            assertTrue(received.await(3, TimeUnit.SECONDS))
        }
    }
    @Test fun closingAndExpiryInvalidateTheInvitation() {
        val expired = CountDownLatch(1)
        val receiver = TvPairingReceiver(InetAddress.getByName("127.0.0.1"), { fail("Expired transfer accepted") }, { expired.countDown() }, 150)
        receiver.start()
        assertTrue(expired.await(3, TimeUnit.SECONDS))
        assertTrue(runCatching { TvPairingSender().use { it.send(receiver.ticket, payload) } }.isFailure)
        receiver.close()
    }
}
