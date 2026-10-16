package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OnvifDiscoveryTest {
    private val id = "urn:uuid:5d743b1e-4458-4f66-b629-a6bfa72e6fee"
    private val sender = InetAddress.getByName("192.168.1.25")

    @Test fun hardwareDevantUnicast() {
        org.junit.Assume.assumeTrue(System.getenv("CAMNORY_TEST_DISCOVERY_DEVANT") == "true")
        val target = InetSocketAddress("192.168.0.109", 3702)
        val local = DatagramSocket().use { it.connect(target); it.localAddress as Inet4Address }
        val discovery = OnvifDiscovery()
        val found = discovery.search(local, target = target)
        assertTrue("Devant discovery packets=${discovery.receivedPacketCount}", found.any { it.host == "192.168.0.109" })
        assertTrue(found.all { it.host == "192.168.0.109" })
    }

    private fun response(messageId: String = id, host: String = sender.hostAddress!!, path: String = "/onvif/device_service",
        type: String = "dn:NetworkVideoTransmitter", scopes: String = "onvif://www.onvif.org/name/Front%20%2B%20garden") =
        """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
        <s:Header><a:RelatesTo>$messageId</a:RelatesTo><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</a:Action></s:Header>
        <s:Body><d:ProbeMatches><d:ProbeMatch><d:Types>$type</d:Types><d:Scopes>$scopes</d:Scopes><d:XAddrs>http://$host:8000$path</d:XAddrs></d:ProbeMatch></d:ProbeMatches></s:Body></s:Envelope>""".toByteArray()

    @Test fun probeContainsOnlyDiscoveryAndNoCredentialsOrControlOperation() {
        val probe = onvifXml(String(onvifDiscoveryProbe(id)))
        val envelope = probe.child("Envelope")!!
        assertEquals(id, envelope.child("Header")!!.child("MessageID")!!.text)
        assertEquals(listOf("Probe"), envelope.child("Body")!!.children.map { it.name })
        assertEquals("dn:NetworkVideoTransmitter", envelope.child("Body")!!.child("Probe")!!.child("Types")!!.text)
        assertTrue(probe.descendants("Security").isEmpty())
        assertFalse(String(onvifDiscoveryProbe(id)).contains("SetRelay"))
    }

    @Test fun acceptsCorrelatedCameraAndDecodesItsName() {
        val camera = onvifDiscoveryMatches(response(), sender, id).single()
        assertEquals("Front + garden", camera.name)
        assertEquals("http://192.168.1.25:8000/onvif/device_service", camera.endpoint)
        assertEquals(sender.hostAddress, camera.host)
    }

    @Test fun rejectsUnrelatedMessagesOtherDevicesAndForeignEndpoints() {
        assertTrue(onvifDiscoveryMatches(response("another-id"), sender, id).isEmpty())
        assertTrue(onvifDiscoveryMatches(response(host = "192.168.1.99"), sender, id).isEmpty())
        assertTrue(onvifDiscoveryMatches(response(host = "camera.invalid"), sender, id).isEmpty())
        assertTrue(onvifDiscoveryMatches(response(type = "dn:Printer"), sender, id).isEmpty())
        assertTrue(onvifDiscoveryMatches(response(path = "/onvif?token=bad"), sender, id).isEmpty())
        assertTrue(onvifDiscoveryMatches(response(host = "user:secret@192.168.1.25"), sender, id).isEmpty())
        assertTrue(onvifDiscoveryMatches(response(), InetAddress.getByName("127.0.0.1"), id).isEmpty())
    }

    @Test fun malformedOversizedAndEntityPayloadsAreIgnored() {
        listOf("not XML", "<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>", "x".repeat(32769)).forEach {
            assertTrue(onvifDiscoveryMatches(it.toByteArray(), sender, id).isEmpty())
        }
    }

    @Test fun udpSearchDeduplicatesAndExpiresWithoutAuthenticating() {
        val local = localAddress()
        DatagramSocket(InetSocketAddress(local, 0)).use { responder ->
            responder.soTimeout = 2000
            val failure = AtomicReference<Throwable?>()
            val done = CountDownLatch(1)
            val server = thread(isDaemon = true) {
                try {
                    val packet = DatagramPacket(ByteArray(32768), 32768)
                    responder.receive(packet)
                    val request = onvifXml(String(packet.data, 0, packet.length)).child("Envelope")!!
                    val messageId = request.child("Header")!!.child("MessageID")!!.text
                    assertEquals(listOf("Probe"), request.child("Body")!!.children.map { it.name })
                    val reply = response(messageId, local.hostAddress!!)
                    repeat(2) { responder.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
                } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }
            val found = OnvifDiscovery().search(local, target = InetSocketAddress(local, responder.localPort), durationMs = 450)
            assertTrue(done.await(2, TimeUnit.SECONDS)); server.join(1000)
            failure.get()?.let { throw AssertionError("Local UDP fixture failed", it) }
            assertEquals(1, found.size)
        }
    }

    @Test fun cancellationClosesTheReceiveSocketPromptly() {
        val local = localAddress()
        DatagramSocket(InetSocketAddress(local, 0)).use { responder ->
            responder.soTimeout = 2000
            val discovery = OnvifDiscovery()
            val failure = AtomicReference<Throwable?>()
            val done = CountDownLatch(1)
            val task = thread(isDaemon = true) {
                try { discovery.search(local, target = InetSocketAddress(local, responder.localPort)) }
                catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
            }
            try {
                responder.receive(DatagramPacket(ByteArray(32768), 32768))
                discovery.cancel()
                assertTrue("Cancellation left UDP receive running", done.await(1, TimeUnit.SECONDS))
                task.join(1000)
                assertNull(failure.get())
            } finally { discovery.cancel() }
        }
    }

    private fun localAddress(): Inet4Address = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().first { it.isSiteLocalAddress }
}
