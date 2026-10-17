package com.nvr.camnory

internal enum class BackgroundStyle(val preference: String, val titleRes: Int, val background: Int, val card: Int, val surface: Int) {
    Automatic("automatic", R.string.background_auto, 0xff09090a.toInt(), 0xff17171c.toInt(), 0xff22222a.toInt()),
    Oled("oled", R.string.background_oled, 0xff000000.toInt(), 0xff101012.toInt(), 0xff202024.toInt()),
    Anthracite("anthracite", R.string.background_anthracite, 0xff121214.toInt(), 0xff202023.toInt(), 0xff2c2c31.toInt()),
    Graphite("graphite", R.string.background_graphite, 0xff24262a.toInt(), 0xff303338.toInt(), 0xff3b3f45.toInt()),
    Midnight("midnight", R.string.background_midnight, 0xff0d1725.toInt(), 0xff182638.toInt(), 0xff26364b.toInt()),
    Forest("forest", R.string.background_forest, 0xff101d18.toInt(), 0xff1c2c24.toInt(), 0xff2b3c32.toInt());

    val lightBackground: Int get() = when (this) {
        Anthracite -> 0xfff0f1f3.toInt()
        Graphite -> 0xffe9ecef.toInt()
        Midnight -> 0xffedf3fb.toInt()
        Forest -> 0xffedf5ef.toInt()
        else -> 0xfff5f6f8.toInt()
    }
    val lightCard: Int get() = when (this) {
        Graphite -> 0xfff8f9fa.toInt()
        Midnight -> 0xfff8fbff.toInt()
        Forest -> 0xfff9fcfa.toInt()
        else -> 0xffffffff.toInt()
    }
    val lightSurface: Int get() = when (this) {
        Anthracite -> 0xffe1e3e6.toInt()
        Graphite -> 0xffdce1e5.toInt()
        Midnight -> 0xffdce7f5.toInt()
        Forest -> 0xffdcebe1.toInt()
        else -> 0xffe5e7ec.toInt()
    }

    companion object {
        fun fromPreference(value: String?): BackgroundStyle = values().firstOrNull { it.preference == value } ?: Automatic
    }
}
