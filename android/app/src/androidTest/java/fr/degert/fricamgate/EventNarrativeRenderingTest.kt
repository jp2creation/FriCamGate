package fr.degert.fricamgate

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class EventNarrativeRenderingTest {
    @Test fun formatsRealResourcesInAllSupportedLanguages() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (code in listOf("fr", "en", "de", "es", "it", "pt")) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(code)))
            })
            val sentence = eventNarrative(context, EventFacts("person", "Céline", null), "Entrée suivi", 1792041090.0)
            assertTrue(sentence.contains("Céline"))
            assertTrue(sentence.contains("Entrée suivi"))
            assertFalse(sentence.contains("%"))
            assertFalse(sentence.contains("null"))
            assertTrue(context.resources.getQuantityString(R.plurals.activity_detections, 3, 3).contains("3"))
        }
    }
}
