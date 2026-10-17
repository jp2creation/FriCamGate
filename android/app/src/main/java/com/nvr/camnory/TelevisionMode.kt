package com.nvr.camnory

import android.content.Context
import android.content.res.Configuration

internal object TelevisionMode {
    fun enabled(context: Context) = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    fun adoptAppearance(context: Context) {
        if (!enabled(context)) return
        val shared = context.getSharedPreferences("camgate-settings", 0)
        if (shared.getBoolean("tabletTvReady", false)) return
        val previous = context.getSharedPreferences("camnory-tv-appearance", 0)
        val edit = shared.edit().putBoolean("tabletTvReady", true)
        if (!shared.contains("theme")) edit.putString("theme", "dark")
        if (!shared.contains("oledBlack")) edit.putBoolean("oledBlack", previous.getBoolean("black", true))
        if (!shared.contains("accent")) edit.putString("accent", previous.getString("accent", "pink"))
        if (!shared.contains("textSize")) edit.putString("textSize", if (previous.getBoolean("largeText", false)) "large" else "standard")
        edit.apply()
        val sound = context.getSharedPreferences("fricamgate-native", 0)
        if (!sound.contains("soundEnabled")) sound.edit().putBoolean("soundEnabled", !previous.getBoolean("muted", true)).apply()
    }
}

/** Share the TV's existing encrypted source, never copy it into the phone stores. */
internal object TvProviderStorage {
    fun read(context: Context): TvConfiguration? = if (TelevisionMode.enabled(context))
        TvConfigurationStore(context).load() ?: TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet()) else null
    fun update(context: Context, change: (TvConfiguration) -> TvConfiguration): Boolean {
        if (!TelevisionMode.enabled(context)) return false
        val store = TvConfigurationStore(context)
        store.update(change)
        return true
    }
}
