package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun oledOnlyChangesTheDarkBackgroundAndLeavesAccentUntouched() {
        val style = Palette.backgroundStyle; val dark = Palette.dark
        val oled = Palette.oledBlack; val accent = Palette.accent
        try {
            Palette.backgroundStyle = BackgroundStyle.Forest
            Palette.oledBlack = true
            Palette.dark = false
            assertEquals(BackgroundStyle.Forest.lightBackground, Palette.background)
            Palette.dark = true
            assertEquals(0xff000000.toInt(), Palette.background)
            assertEquals(BackgroundStyle.Forest.card, Palette.card)
            Palette.oledBlack = false
            assertEquals(BackgroundStyle.Forest.background, Palette.background)
            assertEquals(accent, Palette.accent)
        } finally { Palette.backgroundStyle = style; Palette.dark = dark; Palette.oledBlack = oled }
    }
    @Test fun lightPalettesRemainDistinctAndSystemChangesKeepTheChosenPalette() {
        val choices = BackgroundStyle.values().filter { it != BackgroundStyle.Oled }
        assertEquals(choices.size, choices.map { it.lightBackground }.distinct().size)
        val style = Palette.backgroundStyle; val dark = Palette.dark; val oled = Palette.oledBlack
        try {
            Palette.backgroundStyle = BackgroundStyle.Midnight; Palette.oledBlack = false
            listOf(false, true, false).forEach { system ->
                Palette.dark = Appearance.isDark("system", system)
                assertEquals(system, Palette.dark)
                assertEquals(BackgroundStyle.Midnight, Palette.backgroundStyle)
                assertEquals(if (system) BackgroundStyle.Midnight.background else BackgroundStyle.Midnight.lightBackground, Palette.background)
            }
        } finally { Palette.backgroundStyle = style; Palette.dark = dark; Palette.oledBlack = oled }
    }
    @Test fun everyBackgroundRespectsLightModeAndHasReadableLightSurfaces() {
        val originalStyle = Palette.backgroundStyle
        val originalDark = Palette.dark
        try {
            BackgroundStyle.values().forEach { style ->
                Palette.backgroundStyle = style
                Palette.dark = false
                assertFalse("Background $style must not override light mode", Palette.dark)
                assertTrue(Palette.background ushr 16 and 255 >= 220)
                assertTrue(Palette.backgroundAlt ushr 16 and 255 >= 220)
                assertTrue(Palette.card ushr 16 and 255 >= 220)
                assertEquals(0xff202127.toInt(), Palette.text)
                Palette.dark = true
                assertTrue(Palette.dark)
                assertNotEquals(Palette.background, Palette.text)
            }
        } finally { Palette.backgroundStyle = originalStyle; Palette.dark = originalDark }
    }
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
        assertEquals(0xffa77b21.toInt(), Appearance.accent("ochre"))
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
