package com.nvr.camnory

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TvConfigurationStorageTest {
    @Test fun tvCredentialsUseTheirOwnEncryptedFileAndDoNotChangePhonePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "tv-test-config.enc"
        val file = File(context.noBackupFilesDir, filename)
        val before = context.getSharedPreferences("fricamgate-native", 0).all
        try {
            val store = TvConfigurationStore(context, filename)
            val config = TvConfiguration(emptyList(), emptyList(),
                HomeAssistantConnection("https://ha.example", "dummy-private-token", emptyList()), listOf("fixture"), emptySet())
            store.save(config)
            assertEquals("dummy-private-token", store.load()!!.ha!!.token)
            assertFalse(file.readText(Charsets.ISO_8859_1).contains("dummy-private-token"))
            assertEquals(before, context.getSharedPreferences("fricamgate-native", 0).all)
        } finally { file.delete(); File(file.path + ".bak").delete() }
    }
}
