package com.nvr.camnory

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class IpCameraActivity : AppCompatActivity() {
    private lateinit var store: IpCameraStore
    private lateinit var lock: BiometricAppLock
    private var preview: IpCameraCard? = null
    private var editing = false
    private var confirmation: ConfirmationSheet? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Appearance.loadPalette(getSharedPreferences("camgate-settings", MODE_PRIVATE), resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        store = IpCameraStore(this)
        lock = BiometricAppLock(this) { list() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (editing) list() else finish() }
        })
        if (!lock.locked) {
            val camera = runCatching { store.load().firstOrNull { it.id == intent.getStringExtra("cameraId") } }.getOrNull()
            if (camera != null) edit(camera) else list()
        } else setContentView(LinearLayout(this))
    }
    private fun root(): LinearLayout {
        preview?.setActive(false); preview = null
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        val ui = ConnectionFormUi(this)
        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = getString(R.string.onvif_back)
                setOnClickListener { if (editing) list() else finish() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(ui.text(getString(R.string.ip_cameras), 20, Palette.text))
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.background)
            addView(FrameLayout(context).apply {
                addView(column, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(760)), -2, Gravity.CENTER_HORIZONTAL))
            })
        })
        return column
    }
    private fun list() {
        if (lock.locked) return
        editing = false
        val column = root()
        val ui = ConnectionFormUi(this)
        val cameras = try { store.load() } catch (_: Exception) {
            column.addView(ui.text(getString(R.string.onvif_storage_error))); return
        }
        cameras.forEach { camera ->
            column.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.action(camera.name, false) { edit(camera) }.apply { tag = "ip-camera:${camera.id}" }, LinearLayout.LayoutParams(0, dp(58), 1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_settings); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = getString(R.string.settings); setOnClickListener { edit(camera) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_delete); setColorFilter(Palette.muted); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = getString(R.string.onvif_delete)
                    setOnClickListener {
                        confirmation?.dismiss()
                        confirmation = ConfirmationSheet(this@IpCameraActivity, getString(R.string.ip_cameras), getString(R.string.onvif_delete_question, camera.name)) {
                            if (!lock.locked) runCatching { store.save(store.load().filterNot { it.id == camera.id }) }
                                .onSuccess { setResult(RESULT_OK); list() }.onFailure { toast(R.string.onvif_storage_error) }
                        }.also { it.show() }
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
        }
        column.addView(ui.action(getString(R.string.ip_add)) { edit(null) }.apply { tag = "ip-add" })
        TvRemoteSupport.install(column)
    }
    private fun edit(existing: IpCamera?) {
        if (lock.locked) return
        editing = true
        val column = root()
        val ui = ConnectionFormUi(this)
        val name = ui.field(column, getString(R.string.onvif_name), "Webcam", existing?.name.orEmpty(), key = "ip-name")
        val address = ui.field(column, getString(R.string.ip_address), "http://192.168.0.50:8080/video", existing?.url.orEmpty(), key = "ip-server").apply { isSaveEnabled = false }
        val auth = SwitchCompat(this).apply {
            applyAccentTint()
            text = getString(R.string.ip_auth); setTextColor(Palette.text)
            isChecked = !existing?.username.isNullOrBlank(); tag = "ip-auth"
        }
        column.addView(auth)
        val credentials = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val user = ui.field(credentials, getString(R.string.username), "", existing?.username.orEmpty(), key = "ip-user").apply { isSaveEnabled = false }
        val password = ui.field(credentials, getString(R.string.password), "", secret = true, key = "ip-password")
        column.addView(credentials)
        credentials.visibility = if (auth.isChecked) View.VISIBLE else View.GONE
        auth.setOnCheckedChangeListener { _, checked -> credentials.visibility = if (checked) View.VISIBLE else View.GONE }
        val status = ui.text("", 13, Palette.accent).apply { tag = "ip-status" }
        fun candidate(): IpCamera? = runCatching {
            IpCamera(existing?.id ?: java.util.UUID.randomUUID().toString(), name.text.toString().trim(), ipCameraUrl(address.text.toString()),
                if (auth.isChecked) user.text.toString() else "",
                if (auth.isChecked) password.text.toString().ifEmpty { existing?.password.orEmpty() } else "")
                .also { require(!auth.isChecked || it.username.isNotBlank()) }
        }.getOrElse { status.text = getString(R.string.ip_invalid); null }
        column.addView(ui.action(getString(R.string.ip_save)) {
            if (!lock.locked) candidate()?.let { camera ->
                runCatching { store.save(store.load().filterNot { it.id == camera.id } + camera) }
                    .onSuccess { password.text.clear(); setResult(RESULT_OK); toast(R.string.ip_saved); list() }
                    .onFailure { status.text = getString(R.string.onvif_storage_error) }
            }
        }.apply { tag = "ip-save" })
        val frame = FrameLayout(this)
        column.addView(ui.action(getString(R.string.ip_test), false) {
            if (!lock.locked) candidate()?.let { camera ->
                preview?.setActive(false); frame.removeAllViews()
                preview = IpCameraCard(this, camera, {}, {}, playerOnly = true).also {
                    frame.addView(it, FrameLayout.LayoutParams(-1, dp(230))); it.setActive(true)
                }
            }
        }.apply { tag = "ip-test" })
        column.addView(status); column.addView(frame)
        TvRemoteSupport.install(column)
    }
    override fun onResume() { super.onResume(); lock.onForeground() }
    override fun onPause() { preview?.setActive(false); confirmation?.dismiss(); lock.onBackground(); super.onPause() }
    private fun toast(id: Int) { Toast.makeText(this, id, Toast.LENGTH_SHORT).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
