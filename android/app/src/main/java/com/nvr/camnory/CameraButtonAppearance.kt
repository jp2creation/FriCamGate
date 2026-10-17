package com.nvr.camnory

import android.content.Context
import android.view.View

internal enum class CameraButton(val preference: String, val titleRes: Int) {
    Sound("sound", R.string.playback_sound),
    Fullscreen("fullscreen", R.string.camera_fullscreen),
    Settings("settings", R.string.settings),
    Events("events", R.string.events_tab),
    More("more", R.string.camera_button_more),
    Microphone("microphone", R.string.microphone),
    Light("light", R.string.accessory_light),
    Siren("siren", R.string.accessory_siren),
    Ptz("ptz", R.string.ptz_title);

    fun visible(context: Context) = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        .getBoolean("cameraButton:$preference", true)

    companion object {
        fun forIcon(icon: Int): CameraButton? = when (icon) {
            R.drawable.ic_volume, R.drawable.ic_volume_off -> Sound
            R.drawable.ic_fullscreen -> Fullscreen
            R.drawable.ic_settings, R.drawable.ic_info -> Settings
            R.drawable.ic_list -> Events
            R.drawable.ic_more_vertical -> More
            R.drawable.ic_mic -> Microphone
            else -> null
        }
        fun iconVisible(context: Context, icon: Int) = forIcon(icon)?.visible(context) ?: true
    }
}

internal fun <T : View> T.cameraButton(kind: CameraButton): T = apply { setTag(R.id.camera_button_kind, kind) }
