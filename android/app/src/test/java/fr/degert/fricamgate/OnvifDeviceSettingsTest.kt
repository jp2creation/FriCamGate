package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OnvifDeviceSettingsTest {
    private val settingsXml = "<GetImagingSettingsResponse><ImagingSettings><Brightness>40</Brightness></ImagingSettings></GetImagingSettingsResponse>"
    private val optionsXml = "<GetOptionsResponse><ImagingOptions><Brightness><Min>0</Min><Max>100</Max></Brightness></ImagingOptions></GetOptionsResponse>"
    private fun camera(server: MockWebServer) = OnvifSavedCamera("id", "Test", server.url("/device").toString(), OnvifCredentials("a", "private-password"))
    private fun imageResponses(server: MockWebServer) {
        server.enqueue(MockResponse().setBody(settingsXml)); server.enqueue(MockResponse().setBody(optionsXml))
    }
    @Test fun loadingDeviceInformationAndImagingNeverWritesOrTriggersAnything() {
        MockWebServer().use { server ->
            val endpoint = server.url("/service").toString()
            server.enqueue(MockResponse().setBody("<GetSystemDateAndTimeResponse/>"))
            server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver10/media/wsdl</Namespace><XAddr>$endpoint</XAddr></Service></GetServicesResponse>"))
            server.enqueue(MockResponse().setBody("""<GetProfilesResponse><Profiles token="main"><Name>Main</Name><VideoEncoderConfiguration><Encoding>H265</Encoding><Resolution><Width>1920</Width><Height>1080</Height></Resolution></VideoEncoderConfiguration><VideoSourceConfiguration><SourceToken>source</SourceToken></VideoSourceConfiguration></Profiles></GetProfilesResponse>"""))
            server.enqueue(MockResponse().setBody("<GetDeviceInformationResponse><Manufacturer>Test</Manufacturer><SerialNumber>SN-123</SerialNumber></GetDeviceInformationResponse>"))
            server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver20/imaging/wsdl</Namespace><XAddr>$endpoint</XAddr></Service></GetServicesResponse>"))
            imageResponses(server)
            val client = OnvifDeviceSettingsClient()
            try {
                val state = client.load(camera(server))
                assertEquals("SN-123", state.information["SerialNumber"])
                assertEquals("1920 × 1080", state.profiles.single().resolution)
                assertEquals("H265", state.profiles.single().encoding)
                assertEquals("source", state.source)
                assertEquals(40.0, state.imaging.single().value, 0.0)
                assertEquals(7, server.requestCount)
                repeat(7) {
                    val xml = onvifXml(server.takeRequest().body.readUtf8())
                    assertNull(xml.descendant("SetImagingSettings")); assertNull(xml.descendant("SetRelayOutputState"))
                    assertNull(xml.descendant("RelativeMove")); assertNull(xml.descendant("SendAuxiliaryCommand"))
                }
            } finally { client.close() }
        }
    }
    @Test fun writesOneAllowedFieldOnlyAndDoesNotRetryRejectedCommand() {
        MockWebServer().use { server ->
            imageResponses(server); server.enqueue(MockResponse().setResponseCode(500))
            val client = OnvifDeviceSettingsClient()
            try {
                val state = OnvifDeviceSettings(emptyMap(), emptyList(), server.url("/image").toString(), "source", 0, emptyList(), false, emptyList())
                assertTrue(runCatching { client.setImage(camera(server), state, "Siren", 1.0) }.isFailure)
                assertEquals(0, server.requestCount)
                assertTrue(runCatching { client.setImage(camera(server), state, "Brightness", 55.0) }.isFailure)
                assertEquals(3, server.requestCount)
                server.takeRequest(); server.takeRequest()
                val request = server.takeRequest()
                assertEquals("/image", request.path)
                val body = request.body.readUtf8()
                val xml = onvifXml(body)
                assertEquals("source", xml.descendant("VideoSourceToken")?.text)
                assertEquals("55.0", xml.descendant("ImagingSettings")?.child("Brightness")?.text)
                assertEquals(1, xml.descendant("ImagingSettings")?.children?.size)
                assertFalse(body.contains("private-password"))
            } finally { client.close() }
        }
    }
    @Test fun outOfRangeChangesAreRejectedBeforeAnyWrite() {
        MockWebServer().use { server ->
            imageResponses(server)
            val client = OnvifDeviceSettingsClient()
            try {
                val state = OnvifDeviceSettings(emptyMap(), emptyList(), server.url("/image").toString(), "source", 0, emptyList(), false, emptyList())
                assertTrue(runCatching { client.setImage(camera(server), state, "Brightness", 101.0) }.isFailure)
                assertEquals(2, server.requestCount)
            } finally { client.close() }
        }
    }
    @Test fun exposesOnlyFiniteCurrentValuesWithDeclaredRanges() {
        val settings = onvifXml("<Settings><Brightness>40</Brightness><Contrast>NaN</Contrast><Sharpness>5</Sharpness></Settings>")
        val options = onvifXml("<Options><Brightness><Min>0</Min><Max>100</Max></Brightness><Contrast><Min>0</Min><Max>100</Max></Contrast></Options>")
        assertEquals(listOf(OnvifImageSetting("Brightness", 40.0, 0.0, 100.0)), parseOnvifImageSettings(settings, options))
    }
    @Test fun invalidAndOutOfRangeSettingsCannotBeEdited() {
        val settings = onvifXml("<Settings><Brightness>101</Brightness><Contrast>20</Contrast></Settings>")
        val options = onvifXml("<Options><Brightness><Min>0</Min><Max>100</Max></Brightness><Contrast><Min>50</Min><Max>10</Max></Contrast></Options>")
        assertTrue(parseOnvifImageSettings(settings, options).isEmpty())
    }
}
