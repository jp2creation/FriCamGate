package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OnvifPtzTest {
    private fun discovery(server: MockWebServer, endpoint: String = server.url("/ptz").toString()) {
        val media = server.url("/media").toString()
        server.enqueue(MockResponse().setBody("<GetSystemDateAndTimeResponse/>"))
        server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver10/media/wsdl</Namespace><XAddr>$media</XAddr></Service></GetServicesResponse>"))
        server.enqueue(MockResponse().setBody("""<GetProfilesResponse><Profiles token="selected"><VideoEncoderConfiguration><Encoding>H264</Encoding></VideoEncoderConfiguration><PTZConfiguration token="config"><NodeToken>node</NodeToken></PTZConfiguration></Profiles></GetProfilesResponse>"""))
        server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver20/ptz/wsdl</Namespace><XAddr>$endpoint</XAddr></Service></GetServicesResponse>"))
    }
    private fun camera(server: MockWebServer, profile: String? = "selected") = OnvifSavedCamera("id", "Test",
        server.url("/device").toString(), OnvifCredentials("user", "private-password"), profile)

    @Test fun discoveryIsReadOnlyAndMovesUseSelectedProfileAndBoundedTranslation() {
        MockWebServer().use { server ->
            discovery(server)
            server.enqueue(MockResponse().setBody("""<GetConfigurationOptionsResponse><RelativeZoomTranslationSpace><URI>$ONVIF_RELATIVE_ZOOM</URI><XRange><Min>-1</Min><Max>1</Max></XRange></RelativeZoomTranslationSpace></GetConfigurationOptionsResponse>"""))
            server.enqueue(MockResponse().setBody("<GetPresetsResponse/>"))
            val client = OnvifPtzClient()
            try {
                val camera = camera(server)
                val catalog = requireNotNull(client.discover(camera))
                assertEquals(6, server.requestCount)
                repeat(6) {
                    val body = server.takeRequest().body.readUtf8()
                    assertFalse(body.contains("RelativeMove") || body.contains("GotoPreset") || body.contains("SetRelay") || body.contains("ContinuousMove"))
                }
                assertTrue(runCatching { client.move(camera, catalog, "left") }.isFailure)
                assertEquals(6, server.requestCount)
                server.enqueue(MockResponse().setBody("<RelativeMoveResponse/>"))
                client.move(camera, catalog, "in")
                val move = server.takeRequest()
                assertEquals("/ptz", move.path)
                val body = move.body.readUtf8()
                val xml = onvifXml(body)
                assertEquals("selected", xml.descendant("ProfileToken")?.text)
                assertEquals("0.1", xml.descendant("Zoom")?.attributes?.get("x"))
                assertNull(xml.descendant("PanTilt"))
                assertFalse(body.contains("private-password"))
                assertTrue(runCatching { client.move(camera, catalog, "siren") }.isFailure)
                assertEquals(7, server.requestCount)
            } finally { client.close() }
        }
    }
    @Test fun foreignPtzHostIsRejectedBeforeCredentialsAreSent() {
        MockWebServer().use { server ->
            discovery(server, "http://other.invalid/ptz")
            val client = OnvifPtzClient()
            try {
                assertTrue(runCatching { client.discover(camera(server)) }.isFailure)
                assertEquals(4, server.requestCount)
            } finally { client.close() }
        }
    }
    @Test fun savedProfileIsNeverReplacedByAnotherProfile() {
        MockWebServer().use { server ->
            discovery(server)
            val client = OnvifPtzClient()
            try {
                assertNull(client.discover(camera(server, "deleted-profile")))
                assertEquals(3, server.requestCount)
            } finally { client.close() }
        }
    }
    @Test fun removedPresetIsRejectedWithoutMoving() {
        MockWebServer().use { server ->
            val client = OnvifPtzClient()
            try {
                server.enqueue(MockResponse().setBody("<GetPresetsResponse/>"))
                val catalog = OnvifPtzCatalog(server.url("/ptz").toString(), "selected", 0,
                    OnvifPtzSpaces(null, null), listOf(OnvifPtzPreset("door", "Door")))
                assertTrue(runCatching { client.preset(camera(server), catalog, "door") }.isFailure)
                assertEquals(1, server.requestCount)
                assertFalse(server.takeRequest().body.readUtf8().contains("GotoPreset"))
            } finally { client.close() }
        }
    }
    @Test fun stopAndPresetUseOnlyTheirTypedOperations() {
        MockWebServer().use { server ->
            val client = OnvifPtzClient()
            try {
                val camera = camera(server)
                val catalog = OnvifPtzCatalog(server.url("/ptz").toString(), "selected", 0,
                    OnvifPtzSpaces(null, null), listOf(OnvifPtzPreset("door", "Door")))
                server.enqueue(MockResponse().setBody("<StopResponse/>"))
                client.stop(camera, catalog)
                val stop = onvifXml(server.takeRequest().body.readUtf8())
                assertEquals("true", stop.descendant("PanTilt")?.text)
                assertEquals("true", stop.descendant("Zoom")?.text)
                assertNull(stop.descendant("RelativeMove"))
                server.enqueue(MockResponse().setBody("<GetPresetsResponse><Preset token=\"door\"/></GetPresetsResponse>"))
                server.enqueue(MockResponse().setBody("<GotoPresetResponse/>"))
                client.preset(camera, catalog, "door")
                server.takeRequest()
                val preset = onvifXml(server.takeRequest().body.readUtf8())
                assertEquals("selected", preset.descendant("ProfileToken")?.text)
                assertEquals("door", preset.descendant("PresetToken")?.text)
                assertNull(preset.descendant("SetRelayOutputState"))
            } finally { client.close() }
        }
    }
    @Test fun onlyDeclaredGenericRelativeSpacesAreAccepted() {
        val options = onvifXml("""<GetConfigurationOptionsResponse><PTZConfigurationOptions><Spaces>
            <RelativePanTiltTranslationSpace><URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationGenericSpace</URI><XRange><Min>-1</Min><Max>1</Max></XRange><YRange><Min>-1</Min><Max>1</Max></YRange></RelativePanTiltTranslationSpace>
            <RelativeZoomTranslationSpace><URI>vendor:unknown</URI><XRange><Min>-1</Min><Max>1</Max></XRange></RelativeZoomTranslationSpace>
            </Spaces></PTZConfigurationOptions></GetConfigurationOptionsResponse>""")
        val spaces = parseOnvifPtzSpaces(options)
        assertNotNull(spaces.panTilt)
        assertNull(spaces.zoom)
    }
    @Test fun malformedOrOneWayRangesDoNotEnableBidirectionalControls() {
        val options = onvifXml("""<Options><RelativeZoomTranslationSpace><URI>http://www.onvif.org/ver10/tptz/ZoomSpaces/TranslationGenericSpace</URI><XRange><Min>0</Min><Max>NaN</Max></XRange></RelativeZoomTranslationSpace></Options>""")
        assertNull(parseOnvifPtzSpaces(options).zoom)
    }
    @Test fun presetsRequireSafeUniqueTokens() {
        val root = onvifXml("""<GetPresetsResponse><Preset token="a"><Name>Door</Name></Preset><Preset token="a"/><Preset token=""/></GetPresetsResponse>""")
        assertEquals(listOf(OnvifPtzPreset("a", "Door")), parseOnvifPtzPresets(root))
    }
}
