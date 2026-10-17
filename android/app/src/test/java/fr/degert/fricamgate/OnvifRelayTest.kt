package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OnvifRelayTest {
    private fun prepareDiscovery(server: MockWebServer) {
        val endpoint = server.url("/onvif").toString()
        server.enqueue(MockResponse().setBody("<GetSystemDateAndTimeResponse/>"))
        server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver10/media/wsdl</Namespace><XAddr>$endpoint</XAddr></Service></GetServicesResponse>"))
        server.enqueue(MockResponse().setBody("<GetProfilesResponse><Profiles token=\"main\"><VideoEncoderConfiguration><Encoding>H264</Encoding></VideoEncoderConfiguration></Profiles></GetProfilesResponse>"))
        server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver10/deviceIO/wsdl</Namespace><XAddr>$endpoint</XAddr></Service></GetServicesResponse>"))
        server.enqueue(MockResponse().setBody("<GetRelayOutputsResponse><RelayOutputs token=\"r1\"/></GetRelayOutputsResponse>"))
    }
    @Test fun discoveryNeverWritesAndExplicitCommandTargetsOnlyAdvertisedRelay() {
        MockWebServer().use { server ->
            prepareDiscovery(server)
            val camera = OnvifSavedCamera("test", "Test", server.url("/onvif").toString(), OnvifCredentials("user", "secret"))
            val client = OnvifRelayClient()
            try {
                val catalog = client.discover(camera)
                assertEquals(5, server.requestCount)
                repeat(5) { assertFalse(server.takeRequest().body.readUtf8().contains("SetRelay")) }
                assertTrue(runCatching { client.set(camera, catalog, "unknown", true) }.isFailure)
                assertEquals(5, server.requestCount)
                server.enqueue(MockResponse().setBody("<GetRelayOutputsResponse><RelayOutputs token=\"r1\"/></GetRelayOutputsResponse>"))
                server.enqueue(MockResponse().setBody("<SetRelayOutputStateResponse/>"))
                client.set(camera, catalog, "r1", false)
                assertTrue(server.takeRequest().getHeader("SOAPAction")!!.contains("GetRelayOutputs"))
                val command = server.takeRequest().body.readUtf8()
                assertTrue(command.contains("SetRelayOutputState"))
                assertTrue(command.contains(">r1<"))
                assertTrue(command.contains(">inactive<"))
                assertFalse(command.contains("secret"))
            } finally { client.close() }
        }
    }
    @Test fun removedRelayIsRejectedWithoutSendingACommand() {
        MockWebServer().use { server ->
            val camera = OnvifSavedCamera("test", "Test", server.url("/").toString(), OnvifCredentials("a", "b"))
            val client = OnvifRelayClient()
            try {
                server.enqueue(MockResponse().setBody("<GetRelayOutputsResponse/>"))
                val catalog = OnvifRelayCatalog(camera.endpoint, 0, listOf(OnvifRelay("r1", "")))
                assertTrue(runCatching { client.set(camera, catalog, "r1", true) }.isFailure)
                assertEquals(1, server.requestCount)
                assertFalse(server.takeRequest().body.readUtf8().contains("SetRelay"))
            } finally { client.close() }
        }
    }
    @Test fun relayParsingKeepsTokensButNeverGuessesTheirPurposeOrState() {
        val root = onvifXml("""<GetRelayOutputsResponse><RelayOutputs token="relay1"><Properties><Mode>Bistable</Mode><IdleState>open</IdleState></Properties></RelayOutputs></GetRelayOutputsResponse>""")
        val relay = parseOnvifRelays(root).single()
        assertEquals("relay1", relay.token)
        assertEquals("Bistable", relay.mode)
    }
    @Test fun malformedAndDuplicateRelayTokensAreNotOffered() {
        val root = onvifXml("""<GetRelayOutputsResponse><RelayOutputs token=""/><RelayOutputs token="r1"/><RelayOutputs token="r1"/></GetRelayOutputsResponse>""")
        assertEquals(listOf("r1"), parseOnvifRelays(root).map { it.token })
    }
}
