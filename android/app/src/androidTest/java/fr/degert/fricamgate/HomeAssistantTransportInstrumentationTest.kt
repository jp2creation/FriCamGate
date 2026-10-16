package com.nvr.camnory

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HomeAssistantTransportInstrumentationTest {
    @Test fun closingAnIdleHttpsConnectionFromUiDoesNotPerformNetworkOnMainThread() {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("""[{"entity_id":"camera.front","state":"idle","attributes":{}}]"""))
            server.start()
            val client = HomeAssistantClient(server.url("/").toString(), "fixture-secret")
            // Trust this fixture certificate only; production TLS validation stays unchanged.
            HomeAssistantClient::class.java.getDeclaredField("http").apply { isAccessible = true }.set(client,
                OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build())
            try {
                assertEquals(1, client.cameras().size)
                assertTrue("HTTPS connection must be idle before closing it", client.http.connectionPool.idleConnectionCount() > 0)
                val error = AtomicReference<Throwable?>()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    try { client.close() } catch (failure: Throwable) { error.set(failure) }
                }
                assertNull("Closing HA from the UI must not crash: ${error.get()?.javaClass?.simpleName}", error.get())
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (client.http.connectionPool.connectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(25)
                assertEquals(0, client.http.connectionPool.connectionCount())
            } finally { client.close() }
        }
    }
}
