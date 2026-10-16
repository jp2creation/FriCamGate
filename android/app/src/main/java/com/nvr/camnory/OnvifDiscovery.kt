package com.nvr.camnory

import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringWriter
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal data class OnvifDiscoveredCamera(val name: String, val endpoint: String, val host: String)

internal fun onvifDiscoveryProbe(messageId: String): ByteArray {
    require(messageId.startsWith("urn:uuid:") && runCatching { UUID.fromString(messageId.removePrefix("urn:uuid:")) }.isSuccess)
    val out = StringWriter()
    val xml = XmlPullParserFactory.newInstance().newSerializer().apply { setOutput(out) }
    val soap = "http://www.w3.org/2003/05/soap-envelope"
    val addressing = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
    val discovery = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
    xml.setPrefix("s", soap); xml.setPrefix("a", addressing); xml.setPrefix("d", discovery)
    xml.setPrefix("dn", "http://www.onvif.org/ver10/network/wsdl")
    fun element(ns: String, name: String, value: String) { xml.startTag(ns, name); xml.text(value); xml.endTag(ns, name) }
    xml.startTag(soap, "Envelope"); xml.startTag(soap, "Header")
    element(addressing, "MessageID", messageId)
    xml.startTag(addressing, "ReplyTo")
    element(addressing, "Address", "$addressing/role/anonymous")
    xml.endTag(addressing, "ReplyTo")
    element(addressing, "To", "urn:schemas-xmlsoap-org:ws:2005:04:discovery")
    element(addressing, "Action", "$discovery/Probe")
    xml.endTag(soap, "Header"); xml.startTag(soap, "Body"); xml.startTag(discovery, "Probe")
    element(discovery, "Types", "dn:NetworkVideoTransmitter")
    xml.endTag(discovery, "Probe"); xml.endTag(soap, "Body"); xml.endTag(soap, "Envelope")
    xml.flush()
    return out.toString().toByteArray(Charsets.UTF_8)
}

internal fun onvifDiscoveryMatches(packet: ByteArray, sender: InetAddress, messageId: String): List<OnvifDiscoveredCamera> = runCatching {
    require(packet.size <= 32_768 && sender is Inet4Address &&
        (sender.isSiteLocalAddress || sender.isLinkLocalAddress) && !sender.isLoopbackAddress)
    val response = onvifXml(String(packet, Charsets.UTF_8))
    val envelope = response.child("Envelope") ?: return emptyList()
    val header = envelope.child("Header") ?: return emptyList()
    require(header.child("RelatesTo")?.text == messageId)
    require(header.child("Action")?.text == "http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches")
    val matches = envelope.child("Body")?.child("ProbeMatches")?.children.orEmpty().filter { it.name == "ProbeMatch" }
    matches.take(64).mapNotNull { match ->
        val types = match.child("Types")?.text.orEmpty().split(Regex("\\s+"))
        if (types.none { it.substringAfter(':') == "NetworkVideoTransmitter" }) return@mapNotNull null
        val endpoint = match.child("XAddrs")?.text.orEmpty().split(Regex("\\s+")).asSequence().take(16).mapNotNull { value ->
            runCatching { onvifEndpoint(value) }.getOrNull()?.takeIf { it.host == sender.hostAddress && it.port in -1..65535 && it.port != 0 }
        }.firstOrNull() ?: return@mapNotNull null
        val name = match.child("Scopes")?.text.orEmpty().split(Regex("\\s+")).asSequence().take(32).mapNotNull { scope ->
            runCatching { URI(scope) }.getOrNull()?.takeIf {
                it.scheme == "onvif" && it.host == "www.onvif.org" && it.path.startsWith("/name/")
            }?.path?.removePrefix("/name/")
        }.firstOrNull()?.filterNot { it.isISOControl() }?.take(80)?.trim().orEmpty().ifBlank { sender.hostAddress.orEmpty() }
        OnvifDiscoveredCamera(name, endpoint.normalize().toASCIIString(), sender.hostAddress.orEmpty())
    }.distinctBy { it.host }
}.getOrDefault(emptyList())

/** Probe only: this transport cannot authenticate or invoke camera control services. */
internal class OnvifDiscovery {
    private val generation = AtomicInteger()
    @Volatile var receivedPacketCount = 0
        private set
    @Volatile private var socket: MulticastSocket? = null

    fun cancel() { generation.incrementAndGet(); socket?.close() }

    fun search(localAddress: Inet4Address, bindNetwork: (MulticastSocket) -> Unit = {},
        target: InetSocketAddress = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 3702),
        durationMs: Long = 8000, onFound: (OnvifDiscoveredCamera) -> Unit = {}): List<OnvifDiscoveredCamera> {
        require(durationMs in 250..10_000)
        val request = generation.incrementAndGet()
        receivedPacketCount = 0
        val messageId = "urn:uuid:${UUID.randomUUID()}"
        val probe = onvifDiscoveryProbe(messageId)
        val found = linkedMapOf<String, OnvifDiscoveredCamera>()
        val opened = MulticastSocket(null)
        socket = opened
        try {
            bindNetwork(opened)
            opened.bind(InetSocketAddress(localAddress, 0))
            opened.networkInterface = NetworkInterface.getByInetAddress(localAddress)
            opened.timeToLive = 1; opened.soTimeout = 200
            val started = System.nanoTime()
            val sendAt = longArrayOf(0, 300, 900)
            var sent = 0
            var packets = 0
            while (request == generation.get() && !Thread.currentThread().isInterrupted && packets < 256 && found.size < 64) {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (elapsed >= durationMs) break
                if (sent < sendAt.size && elapsed >= sendAt[sent]) {
                    opened.send(DatagramPacket(probe, probe.size, target)); sent++
                }
                val buffer = ByteArray(32_769)
                val reply = DatagramPacket(buffer, buffer.size)
                try { opened.receive(reply) } catch (_: SocketTimeoutException) { continue }
                packets++
                receivedPacketCount = packets
                if (request != generation.get()) break
                if (reply.length > 32_768) continue
                onvifDiscoveryMatches(buffer.copyOf(reply.length), reply.address, messageId).forEach { camera ->
                    if (found.size < 64 && found.putIfAbsent(camera.host, camera) == null) onFound(camera)
                }
            }
        } catch (error: SocketException) {
            if (request == generation.get() && !Thread.currentThread().isInterrupted) throw error
        } finally {
            opened.close()
            if (socket === opened) socket = null
        }
        return found.values.toList()
    }
}
