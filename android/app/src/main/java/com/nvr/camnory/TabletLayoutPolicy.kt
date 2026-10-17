package com.nvr.camnory

internal data class TabletLayoutPolicy(val tablet: Boolean, val navigationRail: Boolean, val activityWidthDp: Int)

internal fun android.content.res.Configuration.tabletLayoutPolicy() = tabletLayoutPolicy(screenWidthDp, screenHeightDp, smallestScreenWidthDp,
    uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION)

internal fun tabletLayoutPolicy(widthDp: Int, heightDp: Int, smallestWidthDp: Int, television: Boolean = false): TabletLayoutPolicy {
    val tablet = widthDp >= 600 && (television || smallestWidthDp >= 600)
    val landscape = tablet && widthDp > heightDp
    return TabletLayoutPolicy(tablet, landscape, if (landscape && widthDp >= 1000) (widthDp / 4).coerceIn(260, 320) else 0)
}

internal fun effectiveCameraLayout(phone: String?, tablet: String?, largeScreen: Boolean): HomeCameraLayout =
    if (largeScreen) HomeCameraLayout.fromPreference(tablet ?: HomeCameraLayout.TwoColumns.preference) else HomeCameraLayout.fromPreference(phone)
