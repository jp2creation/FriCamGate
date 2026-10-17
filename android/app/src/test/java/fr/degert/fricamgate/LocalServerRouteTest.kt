package com.nvr.camnory

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LocalServerRouteTest {
    @Test fun onlyMatchingPrivateNetworkCanUseLocalAddress() {
        val route = LocalServerRoute("http://192.168.0.144:5000/nvr", "192.168.0.0/24")
        assertTrue(route.matches(listOf("192.168.0.20")))
        assertFalse(route.matches(listOf("192.168.1.20")))
        assertFalse(route.matches(emptyList()))
    }
    @Test fun rejectsPublicAddressesCredentialsAndAmbiguousNetworks() {
        for (url in listOf("https://example.com", "http://8.8.8.8", "http://user:secret@192.168.0.2", "http://192.168.0.2?q=1")) {
            assertTrue(runCatching { LocalServerRoute(url, "192.168.0.0/24").validate() }.isFailure)
        }
        assertTrue(runCatching { LocalServerRoute("http://192.168.0.2", "0.0.0.0/0").validate() }.isFailure)
    }
    @Test fun rewritePreservesEncodedPathQueryAndRejectsForeignOrigin() {
        val route = LocalServerRoute("http://192.168.0.144:5000/nvr", "192.168.0.0/24")
        assertEquals("http://192.168.0.144:5000/nvr/api/front%20door?x=a%2Fb", route.rewrite(
            "https://remote.example/frigate".toHttpUrl(), "https://remote.example/frigate/api/front%20door?x=a%2Fb".toHttpUrl()).toString())
        assertTrue(runCatching { route.rewrite("https://remote.example/frigate".toHttpUrl(), "https://other.example/api".toHttpUrl()) }.isFailure)
    }
    @Test fun localPlaylistUrlsReturnToCanonicalIdentityWithoutLosingQuery() {
        val route = LocalServerRoute("http://192.168.0.144:5000/nvr", "192.168.0.0/24")
        assertEquals("https://external.example/frigate/api/hls/a?token=fixture",
            route.canonical("https://external.example/frigate".toHttpUrl(), "http://192.168.0.144:5000/nvr/api/hls/a?token=fixture".toHttpUrl()).toString())
        assertEquals("https://foreign.example/a", route.canonical("https://external.example".toHttpUrl(), "https://foreign.example/a".toHttpUrl()).toString())
    }
}
