package com.nvr.camnory

internal enum class ActivityBriefingStyle(val preference: String, val titleRes: Int) {
    Icon("icon", R.string.activity_style_icon),
    Bell("bell", R.string.activity_style_bell),
    Banner("banner", R.string.activity_style_banner),
    Floating("floating", R.string.activity_style_floating);

    companion object {
        fun fromPreference(value: String?): ActivityBriefingStyle =
            values().firstOrNull { it.preference == value } ?: Floating
    }
}
