package com.nvr.camnory

internal object Appearance {
    fun loadPalette(prefs: android.content.SharedPreferences, systemDark: Boolean) {
        // Old OLED backgrounds become an independent option without overriding the display mode.
        if (prefs.getString("backgroundStyle", null) == "oled") {
            prefs.edit().apply {
                putString("backgroundStyle", "automatic")
                if (!prefs.contains("oledBlack")) putBoolean("oledBlack", true)
            }.apply()
        }
        Palette.backgroundStyle = BackgroundStyle.fromPreference(prefs.getString("backgroundStyle", null))
        Palette.oledBlack = prefs.getBoolean("oledBlack", false)
        Palette.dark = isDark(prefs.getString("theme", "system"), systemDark)
        Palette.accent = accent(prefs.getString("accent", null))
    }
    fun isDark(theme: String?, systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    fun textScale(value: String?): Float = when (value) {
        "small" -> 0.9f
        "large" -> 1.1f
        else -> 1f
    }

    fun accent(value: String?): Int = when (value) {
        "blue" -> 0xff1976d2.toInt()
        "green" -> 0xff00856a.toInt()
        "violet" -> 0xff9162d4.toInt()
        "ochre" -> 0xffa77b21.toInt()
        else -> 0xffff2d63.toInt()
    }
}
