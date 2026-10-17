package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class TvWorkspaceTest {
    @Test fun editingServerAddressPreservesCameraOrderAndVisibility() {
        val key = CameraArrangement.key("frigate", "https://old.example", "front")
        val config = TvConfiguration(emptyList(), emptyList(), null, listOf(key, "unrelated"), setOf(key))
        val next = TvWorkspace.remap(config, "frigate", "https://old.example", "https://new.example")
        val expected = CameraArrangement.key("frigate", "https://new.example", "front")
        assertEquals(listOf(expected, "unrelated"), next.order)
        assertEquals(setOf(expected), next.hidden)
    }
    @Test fun selectionKeepsPreferredCameraAndPagesOnlyAlternatives() {
        val keys = listOf("a", "b", "c", "d", "e")
        assertEquals("c", TvWorkspace.primary(keys, "c"))
        assertEquals(listOf("a", "b", "d"), TvWorkspace.alternatives(keys, "c", 0))
        assertEquals(listOf("e"), TvWorkspace.alternatives(keys, "c", 1))
        assertEquals("a", TvWorkspace.primary(keys, "missing"))
        assertNull(TvWorkspace.primary(emptyList(), "c"))
    }
    @Test fun mergingOneProviderDoesNotEraseOtherImportedSourcesOrArrangement() {
        val original = TvConfiguration(listOf(FrigateServer("one", "House", "https://nvr.example", "test", "fixture")),
            emptyList(), HomeAssistantConnection("https://ha.example", "fixture", emptyList()), listOf("a"), setOf("b"))
        val added = TvConfiguration(emptyList(), listOf(OnvifSavedCamera("two", "Door", "http://192.168.0.2/onvif/device_service", OnvifCredentials("test", "fixture"))),
            null, emptyList(), emptySet())
        val merged = TvWorkspace.merge(original, added, "onvif")
        assertEquals(original.frigate, merged.frigate)
        assertSame(original.ha, merged.ha)
        assertEquals(original.order, merged.order)
        assertEquals(original.hidden, merged.hidden)
        assertEquals("two", merged.onvif.single().id)
    }
}
