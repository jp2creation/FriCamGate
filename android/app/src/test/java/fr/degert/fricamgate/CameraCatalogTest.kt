package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class CameraCatalogTest {
    @Test fun assignmentPreservesAliasesOtherGroupsAndOfflineCameras() {
        val catalog = CameraCatalog(listOf(CameraCategory("a", "A"), CameraCategory("b", "B")))
            .label("one", "Alias", setOf("a", "b")).label("offline", "Offline", setOf("a"))
            .assign("a", setOf("one", "two"), setOf("two"))
        assertEquals(setOf("b"), catalog.labels.getValue("one").categories)
        assertEquals("Alias", catalog.displayName("one", "Original"))
        assertEquals(setOf("a"), catalog.labels.getValue("two").categories)
        assertEquals(setOf("a"), catalog.labels.getValue("offline").categories)
    }
    @Test fun aliasesAndMultipleCategoriesDoNotChangeCameraIdentity() {
        val a = CameraCategory("outside", "Extérieur")
        val b = CameraCategory("garage", "Garage")
        val key = CameraArrangement.key("ip", "", "one")
        val other = CameraArrangement.key("onvif", "", "one")
        val catalog = CameraCatalog(listOf(a, b)).label(key, "Portail", setOf(a.id, b.id))
        assertEquals("Portail", catalog.displayName(key, "Camera 1"))
        assertEquals("Camera 1", catalog.displayName(other, "Camera 1"))
        assertTrue(catalog.select(a.id).includes(key))
        assertTrue(catalog.select(b.id).includes(key))
        assertFalse(catalog.select(a.id).includes(other))
        assertTrue(catalog.select(null).includes(other))
    }
    @Test fun deletionCleansMembershipAndActiveFilterWithoutDeletingTheCameraAlias() {
        val key = "camera"
        val catalog = CameraCatalog(listOf(CameraCategory("a", "Intérieur"), CameraCategory("b", "Étage")))
            .label(key, "Salon", setOf("a", "b")).select("a").delete("a")
        assertNull(catalog.selected)
        assertEquals(setOf("b"), catalog.labels.getValue(key).categories)
        assertEquals("Salon", catalog.displayName(key, "Original"))
        assertEquals("Original", catalog.label(key, "", setOf("b")).displayName(key, "Original"))
    }
    @Test fun roundTripAndRenamePreserveAssignments() {
        val original = CameraCatalog(listOf(CameraCategory("a", "Extérieur"))).label("key", "Entrée", setOf("a")).select("a")
        val copy = CameraCatalog.decode(original.encode()).rename("a", "Jardin")
        assertEquals("Jardin", copy.categories.single().name)
        assertEquals("Entrée", copy.displayName("key", "Original"))
        assertEquals("a", copy.selected)
        assertTrue(copy.includes("key"))
    }
    @Test fun rejectsDuplicateNamesMissingMembershipsAndInvalidText() {
        val catalog = CameraCatalog(listOf(CameraCategory("a", "Garage")))
        assertTrue(runCatching { catalog.add("b", " garage ") }.isFailure)
        assertTrue(runCatching { catalog.label("key", "Name", setOf("missing")) }.isFailure)
        assertTrue(runCatching { catalog.add("b", "\n") }.isFailure)
        assertTrue(runCatching { catalog.label("key", "x".repeat(121), emptySet()) }.isFailure)
    }
}
