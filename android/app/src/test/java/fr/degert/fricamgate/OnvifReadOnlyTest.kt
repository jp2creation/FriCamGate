package com.nvr.camnory

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OnvifReadOnlyTest {
    @Test fun onlyReadOperationsAreSentAndPasswordIsDigested() {
        MockWebServer().use { server ->
            val endpoint = server.url("/onvif/device_service").toString()
            server.enqueue(MockResponse().setBody("<GetSystemDateAndTimeResponse/>"))
            server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver10/media/wsdl</Namespace><XAddr>$endpoint</XAddr></Service></GetServicesResponse>"))
            server.enqueue(MockResponse().setBody("<GetProfilesResponse><Profiles token=\"main\"><Name>Front &amp; garden</Name><VideoEncoderConfiguration><Encoding>H264</Encoding></VideoEncoderConfiguration></Profiles></GetProfilesResponse>"))
            server.enqueue(MockResponse().setBody("<GetStreamUriResponse><MediaUri><Uri>rtsp://localhost:554/video</Uri></MediaUri></GetStreamUriResponse>"))
            val client = OnvifReadOnlyClient()
            val credentials = OnvifCredentials("test&amp", "private-password")
            val connection = client.connect(endpoint, credentials)
            assertEquals("Front & garden", connection.profiles.single().name)
            assertEquals("rtsp", client.stream(connection, connection.profiles.single(), credentials).scheme)
            val expected = listOf("GetSystemDateAndTime", "GetServices", "GetProfiles", "GetStreamUri")
            expected.forEach { method ->
                val request = server.takeRequest()
                assertTrue(request.getHeader("SOAPAction")!!.endsWith("/$method\""))
                val xml = request.body.readUtf8()
                assertFalse(xml.contains("private-password"))
                assertFalse(xml.contains("SetRelay") || xml.contains("SetDigital") || xml.contains("Auxiliary"))
                if (method != "GetSystemDateAndTime") assertTrue(xml.contains("PasswordDigest"))
                onvifXml(xml)
            }
        }
    }
    @Test fun rejectsCameraServiceOnAnotherHost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<GetSystemDateAndTimeResponse/>"))
            server.enqueue(MockResponse().setBody("<GetServicesResponse><Service><Namespace>http://www.onvif.org/ver10/media/wsdl</Namespace><XAddr>http://other.invalid/onvif</XAddr></Service></GetServicesResponse>"))
            assertTrue(runCatching { OnvifReadOnlyClient().connect(server.url("/").toString(), OnvifCredentials("a", "b")) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun rejectsEntitiesAndUnsafeEndpoints() {
        assertTrue(runCatching { onvifXml("<!DOCTYPE test [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><test>&x;</test>") }.isFailure)
        listOf("file:///etc/passwd", "http://user:secret@camera/", "http://camera/?token=secret").forEach {
            assertTrue(runCatching { onvifEndpoint(it) }.isFailure)
        }
        assertEquals("http://camera:8000/onvif/device_service", onvifEndpoint("camera:8000").toString())
    }
    @Test fun rtspCredentialsAreEscapedAndNeverChangeTheHost() {
        val credentials = OnvifCredentials("user", "p@ss:#/%")
        val uri = onvifStreamUri("rtsp://camera:554/main", "http://camera:8000/onvif/device_service", credentials)
        assertEquals("camera", uri.host)
        assertEquals("user:p@ss:#/%", uri.userInfo)
        assertFalse(credentials.toString().contains("p@ss"))
        assertTrue(runCatching { onvifStreamUri("rtsp://other/main", "http://camera/", credentials) }.isFailure)
    }
}
