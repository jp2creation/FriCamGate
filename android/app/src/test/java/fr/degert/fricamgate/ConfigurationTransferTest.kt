package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class ConfigurationTransferTest {
    private val a = CameraArrangement.key("frigate", "https://nvr.example", "a")
    private val b = CameraArrangement.key("frigate", "https://nvr.example", "b")
    private fun config() = TvConfiguration(listOf(FrigateServer("one", "NVR", "https://nvr.example", "user", "dummy")), emptyList(), null, listOf(a, b), emptySet())
    @Test fun selectedCamerasCarryOnlyNeededConnectionsAndMetadata() {
        val catalog = CameraCatalog(listOf(CameraCategory("garden", "Garden"))).label(a, "Gate", setOf("garden"))
        val value = ConfigurationTransfer.select(config(), catalog, setOf(a), mapOf("theme" to "dark"), true)
        val copy = ConfigurationTransfer.decode(value.encode())
        assertEquals(setOf("a"), copy.config!!.selection.getValue("https://nvr.example"))
        assertEquals(listOf(a), copy.config.order)
        assertEquals("Gate", copy.catalog!!.displayName(a, "Original"))
        assertEquals("dark", copy.appearance!!.getValue("theme"))
        assertFalse(String(copy.encode()).contains("dummy-password"))
    }
    @Test fun appearanceOnlyContainsNoCredentialsOrCameras() {
        val value = ConfigurationTransfer.select(config(), CameraCatalog(), emptySet(), mapOf("accent" to "blue"), false)
        assertNull(value.config)
        assertNull(value.catalog)
        assertFalse(String(value.encode()).contains("dummy"))
        assertEquals("blue", ConfigurationTransfer.decode(value.encode()).appearance!!["accent"])
    }
    @Test fun mergeKeepsExistingCamerasAndUnionsRestrictedSelections() {
        val old = config().copy(selection = mapOf("https://nvr.example" to setOf("b")))
        val incoming = config().copy(selection = mapOf("https://nvr.example" to setOf("a")))
        assertEquals(setOf("a", "b"), ConfigurationTransfer.mergeConfig(old, incoming).selection["https://nvr.example"])
        assertFalse(ConfigurationTransfer.mergeConfig(config(), incoming).selection.containsKey("https://nvr.example"))
    }
    @Test fun categoryNameCollisionsPreserveBothMemberships() {
        val old = CameraCatalog(listOf(CameraCategory("local", "Garden"))).label(b, "Old", setOf("local"))
        val incoming = CameraCatalog(listOf(CameraCategory("remote", "Garden"))).label(a, "New", setOf("remote"))
        val merged = ConfigurationTransfer.mergeCatalog(old, incoming)
        assertEquals(1, merged.categories.size)
        assertEquals(setOf("local"), merged.labels.getValue(a).categories)
        assertEquals("Old", merged.displayName(b, "Original"))
    }
    @Test fun rejectsDangerousPreferencesAndMalformedEnvelope() {
        assertTrue(runCatching { TransferAppearance.validate(mapOf("alertEnabled" to true)) }.isFailure)
        assertTrue(runCatching { TransferAppearance.validate(mapOf("theme" to "invalid")) }.isFailure)
        assertTrue(runCatching { ConfigurationTransfer.decode("{\"transfer\":1,\"command\":\"siren\"}".toByteArray()) }.isFailure)
        assertTrue(runCatching { ConfigurationTransfer.decode(ByteArray(TvConfiguration.MAX_BYTES + 1)) }.isFailure)
    }
    @Test fun scopedConfigurationSurvivesAnotherTransferAndLegacyPayloadsStillDecode() {
        val selected = ConfigurationTransfer.select(config(), CameraCatalog(), setOf(a), null, true).config!!
        val decoded = TvConfiguration.decode(selected.copy(order = listOf(a)).encode())
        assertEquals(setOf("a"), decoded.selection.getValue("https://nvr.example"))
        assertEquals(config().frigate, ConfigurationTransfer.decode(config().encode()).config!!.frigate)
    }
    @Test fun incompatibleHomeAssistantSourcesAreRejectedBeforeMerge() {
        val old = config().copy(ha = HomeAssistantConnection("https://one.example", "dummy", emptyList()))
        val incoming = config().copy(ha = HomeAssistantConnection("https://two.example", "dummy", emptyList()))
        assertTrue(runCatching { ConfigurationTransfer.mergeConfig(old, incoming) }.isFailure)
        assertEquals("https://one.example", old.ha!!.baseUrl)
    }
}
