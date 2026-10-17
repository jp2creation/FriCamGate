package com.nvr.camnory

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

internal class TvPairingTicket internal constructor(val host: String, val port: Int, val pin: String, val secret: String) {
    override fun toString() = "TvPairingTicket([redacted])"
    fun qr(): String = JSONObject().put("type", "camnory-tv-1").put("host", host).put("port", port)
        .put("pin", pin).put("secret", secret).toString()
    fun code(): String = (MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).take(3)
        .fold(0) { total, byte -> (total shl 8) or (byte.toInt() and 255) } % 1_000_000).toString().padStart(6, '0')
    fun manualCode(): String {
        val bytes = host.split('.').map { it.toInt().toByte() }.toByteArray() +
            byteArrayOf((port shr 8).toByte(), port.toByte()) + pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
            secret.toByteArray(Charsets.US_ASCII)
        return "CTV1-" + bytes.toByteString().base64Url().trimEnd('=')
    }
    companion object {
        fun parse(value: String): TvPairingTicket {
            require(value.length in 1..2048)
            if (value.startsWith("CTV1-")) {
                val bytes = requireNotNull(value.removePrefix("CTV1-").decodeBase64()).toByteArray()
                require(bytes.size == 82)
                val host = bytes.take(4).joinToString(".") { (it.toInt() and 255).toString() }
                val port = ((bytes[4].toInt() and 255) shl 8) or (bytes[5].toInt() and 255)
                val pin = bytes.copyOfRange(6, 38).joinToString("") { "%02x".format(it.toInt() and 255) }
                val secret = String(bytes.copyOfRange(38, 82), Charsets.US_ASCII)
                return parse(TvPairingTicket(host, port, pin, secret).qr())
            }
            val o = JSONObject(value)
            require(o.keys().asSequence().toSet() == setOf("type", "host", "port", "pin", "secret"))
            require(o.getString("type") == "camnory-tv-1")
            val host = o.getString("host")
            require(privateIpv4(host))
            val port = o.getInt("port").also { require(it in 1024..65535) }
            val pin = o.getString("pin").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            val secret = o.getString("secret").also { require(it.length == 44 && it.decodeBase64()?.size == 32) }
            return TvPairingTicket(host, port, pin, secret)
        }
        fun privateIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() !in 0..255 }) return false
            val p = parts.map { it.toInt() }
            return p[0] == 10 || p[0] == 172 && p[1] in 16..31 || p[0] == 192 && p[1] == 168
        }
    }
}

/** Foreground, single-use TLS receiver. No HTTP parser, plaintext secret exchange or open command endpoint. */
internal class TvPairingReceiver(address: InetAddress, private val onReceived: (ByteArray) -> Unit,
    private val onExpired: () -> Unit, lifetimeMs: Long = 120_000) : AutoCloseable {
    private val certificate = HeldCertificate.Builder().ecdsa256().commonName("Camnory TV")
        .addSubjectAlternativeName(address.hostAddress!!).build()
    private val credentials = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val server = credentials.sslContext().serverSocketFactory.createServerSocket(0, 1, address) as SSLServerSocket
    private val executor = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false
    @Volatile private var activeSocket: Socket? = null
    private val secret = ByteArray(32).apply { SecureRandom().nextBytes(this) }
    val ticket = TvPairingTicket(address.hostAddress!!, server.localPort,
        certificate.certificate.encoded.toByteString().sha256().hex(), secret.toByteString().base64Url())
    init {
        require(address.isSiteLocalAddress || address.isLoopbackAddress)
        require(lifetimeMs in 100..120_000)
        server.enabledProtocols = server.supportedProtocols.filter { it in setOf("TLSv1.2", "TLSv1.3") }.toTypedArray()
    }
    fun start() {
        timer.schedule({ if (!closed) { close(); onExpired() } }, lifetime, TimeUnit.MILLISECONDS)
        executor.execute {
            repeat(8) {
                if (closed) return@execute
                val result = runCatching {
                    val socket = server.accept() as SSLSocket
                    activeSocket = socket
                    socket.use {
                        socket.soTimeout = 8_000
                        socket.startHandshake()
                        val input = DataInputStream(socket.inputStream)
                        require(input.readInt() == MAGIC)
                        val supplied = ByteArray(32).apply { input.readFully(this) }
                        require(MessageDigest.isEqual(secret, supplied))
                        supplied.fill(0)
                        val size = input.readInt(); require(size in 2..TvConfiguration.MAX_BYTES)
                        val bytes = ByteArray(size).apply { input.readFully(this) }
                        try { ConfigurationTransfer.decode(bytes) } catch (e: Exception) { bytes.fill(0); throw e }
                        try { DataOutputStream(socket.outputStream).apply { writeInt(MAGIC); flush() } }
                        catch (e: Exception) { bytes.fill(0); throw e }
                        bytes
                    }
                }
                activeSocket = null
                if (result.isSuccess && !closed) {
                    val bytes = result.getOrThrow()
                    close()
                    onReceived(bytes)
                    return@execute
                }
                result.getOrNull()?.fill(0)
            }
            if (!closed) { close(); onExpired() }
        }
    }
    private val lifetime = lifetimeMs
    override fun close() {
        closed = true
        runCatching { activeSocket?.close() }; runCatching { server.close() }
        secret.fill(0); timer.shutdownNow(); executor.shutdownNow()
    }
    companion object { const val MAGIC = 0x434d5401 }
}

internal class TvPairingSender : AutoCloseable {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    fun send(ticket: TvPairingTicket, payload: ByteArray) {
        check(!closed)
        ConfigurationTransfer.decode(payload)
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.size != 1 || chain[0].encoded.toByteString().sha256().hex() != ticket.pin)
                    throw java.security.cert.CertificateException("Pairing certificate mismatch")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val connection = tls.socketFactory.createSocket() as SSLSocket
        socket = connection
        try {
            check(!closed)
            connection.enabledProtocols = connection.supportedProtocols.filter { it in setOf("TLSv1.2", "TLSv1.3") }.toTypedArray()
            connection.soTimeout = 10_000
            connection.connect(InetSocketAddress(ticket.host, ticket.port), 5_000)
            connection.startHandshake()
            check(!closed)
            val secret = ticket.secret.decodeBase64()?.toByteArray() ?: throw IOException("Invalid pairing invitation")
            try {
                DataOutputStream(connection.outputStream).apply {
                    writeInt(TvPairingReceiver.MAGIC); write(secret); writeInt(payload.size); write(payload); flush()
                }
            } finally { secret.fill(0) }
            require(DataInputStream(connection.inputStream).readInt() == TvPairingReceiver.MAGIC)
        } finally { connection.close(); socket = null }
    }
    override fun close() { closed = true; runCatching { socket?.close() } }
}
