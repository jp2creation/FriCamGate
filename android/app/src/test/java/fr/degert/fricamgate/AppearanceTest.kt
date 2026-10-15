package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun backgroundChoicesHaveDistinctTonesAndSafeFallback() {
        assertEquals(BackgroundStyle.Automatic, BackgroundStyle.fromPreference("unknown"))
        assertEquals(0xff000000.toInt(), BackgroundStyle.Oled.background)
        assertEquals(5, BackgroundStyle.values().filter { it != BackgroundStyle.Automatic }.map { it.background }.distinct().size)
    }
    @Test fun themeFollowsSystemUnlessOverridden() {
        assertTrue(Appearance.isDark("system", true))
        assertFalse(Appearance.isDark("system", false))
        assertTrue(Appearance.isDark("dark", false))
        assertFalse(Appearance.isDark("light", true))
        assertFalse(Appearance.isDark("unknown", false))
    }

    @Test fun textSizeIsBoundedAndDefaultsAreStable() {
        assertEquals(1f, Appearance.textScale(null), 0f)
        assertEquals(0.9f, Appearance.textScale("small"), 0f)
        assertEquals(1.1f, Appearance.textScale("large"), 0f)
        assertEquals(1f, Appearance.textScale("unknown"), 0f)
        assertEquals(0xffff2d63.toInt(), Appearance.accent(null))
        assertEquals(Appearance.accent(null), Appearance.accent("unknown"))
    }

    @Test fun lightThemeChangesAllInterfaceSurfacesAndText() {
        val wasDark = Palette.dark
        try {
            Palette.dark = true
            val darkColors = listOf(Palette.background, Palette.backgroundAlt, Palette.card, Palette.surface,
                Palette.overlay, Palette.text, Palette.muted, Palette.border, Palette.tick, Palette.tickMinor)
            Palette.dark = false
            val lightColors = listOf(Palette.background, Palette.backgroundAlt, Palette.card, Palette.surface,
                Palette.overlay, Palette.text, Palette.muted, Palette.border, Palette.tick, Palette.tickMinor)
            assertTrue(darkColors.zip(lightColors).all { (dark, light) -> dark != light })
            assertEquals(0xffffffff.toInt(), Palette.card)
            assertEquals(0xff202127.toInt(), Palette.text)
        } finally {
            Palette.dark = wasDark
        }
    }
}
