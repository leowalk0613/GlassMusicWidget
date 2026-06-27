package com.glassmusic.widget

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton

/**
 * 设置界面 - Material Design 3 风格
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // SharedPreferences 键名（与 MusicWidgetProvider 保持一致）
        const val PREFS_NAME = "GlassMusicWidgetPrefs"
        const val KEY_GLASS_ALPHA = "glass_alpha"
        const val KEY_TINT_ALPHA = "tint_alpha"
        const val KEY_AUTO_COLOR = "auto_color"
        const val KEY_ICON_STYLE = "icon_style"

        // 默认值
        const val DEFAULT_GLASS_ALPHA = 55
        const val DEFAULT_TINT_ALPHA = 90
        const val DEFAULT_AUTO_COLOR = true
        const val DEFAULT_ICON_STYLE = 1
    }

    // 控件
    private lateinit var sliderGlassAlpha: Slider
    private lateinit var tvGlassAlphaValue: TextView
    private lateinit var sliderTintAlpha: Slider
    private lateinit var tvTintAlphaValue: TextView
    private lateinit var switchAutoColor: MaterialSwitch
    private lateinit var btnApply: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var layoutNotificationPermission: android.widget.LinearLayout
    private lateinit var layoutMediaPermission: android.widget.LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        title = getString(R.string.app_name)
        findViewById<TextView>(R.id.tv_app_version).text =
            getString(R.string.main_subtitle) + " · " + AppBuildInfo.MARKER
        Log.e(AppBuildInfo.LOG_TAG, "MainActivity ${AppBuildInfo.MARKER}")
        loadSettings()
        setupListeners()
        checkPermissions()
    }

    private fun initViews() {
        sliderGlassAlpha = findViewById(R.id.slider_glass_alpha)
        tvGlassAlphaValue = findViewById(R.id.tv_glass_alpha_value)
        sliderTintAlpha = findViewById(R.id.slider_tint_alpha)
        tvTintAlphaValue = findViewById(R.id.tv_tint_alpha_value)
        switchAutoColor = findViewById(R.id.switch_auto_color)
        btnApply = findViewById(R.id.btn_apply)
        btnReset = findViewById(R.id.btn_reset)
        layoutNotificationPermission = findViewById(R.id.layout_notification_permission)
        layoutMediaPermission = findViewById(R.id.layout_media_permission)
    }

    private fun setupListeners() {
        // 通透度滑块
        sliderGlassAlpha.addOnChangeListener { _, value, _ ->
            tvGlassAlphaValue.text = "${value.toInt()}%"
        }

        // 染色深度滑块
        sliderTintAlpha.addOnChangeListener { _, value, _ ->
            tvTintAlphaValue.text = value.toInt().toString()
        }

        // 应用按钮
        btnApply.setOnClickListener {
            saveSettings()
            updateWidget()
            finish()
        }

        // 恢复默认按钮
        btnReset.setOnClickListener {
            resetSettings()
        }

        // 通知权限点击
        layoutNotificationPermission.setOnClickListener {
            openNotificationAccessSettings()
        }

        // 媒体权限点击
        layoutMediaPermission.setOnClickListener {
            openMediaControlSettings()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val glassAlpha = prefs.getInt(KEY_GLASS_ALPHA, DEFAULT_GLASS_ALPHA)
        val tintAlpha = prefs.getInt(KEY_TINT_ALPHA, DEFAULT_TINT_ALPHA)
        val autoColor = prefs.getBoolean(KEY_AUTO_COLOR, DEFAULT_AUTO_COLOR)

        sliderGlassAlpha.value = glassAlpha.toFloat()
        tvGlassAlphaValue.text = "$glassAlpha%"
        sliderTintAlpha.value = tintAlpha.toFloat()
        tvTintAlphaValue.text = tintAlpha.toString()
        switchAutoColor.isChecked = autoColor
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putInt(KEY_GLASS_ALPHA, sliderGlassAlpha.value.toInt())
        editor.putInt(KEY_TINT_ALPHA, sliderTintAlpha.value.toInt())
        editor.putBoolean(KEY_AUTO_COLOR, switchAutoColor.isChecked)

        editor.apply()
    }

    private fun resetSettings() {
        sliderGlassAlpha.value = DEFAULT_GLASS_ALPHA.toFloat()
        tvGlassAlphaValue.text = "$DEFAULT_GLASS_ALPHA%"
        sliderTintAlpha.value = DEFAULT_TINT_ALPHA.toFloat()
        tvTintAlphaValue.text = DEFAULT_TINT_ALPHA.toString()
        switchAutoColor.isChecked = DEFAULT_AUTO_COLOR
    }

    private fun updateWidget() {
        WidgetRefreshCoordinator.requestPush(this, immediate = true)
    }

    private fun checkPermissions() {
        // 权限状态通过系统设置查看，点击条目跳转授权
    }

    private fun isNotificationServiceEnabled(): Boolean {
        return try {
            val componentName = ComponentName(this, MusicMonitorService::class.java)
            val flat = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
            if (flat != null && flat.isNotEmpty()) {
                val names = flat.split(":").toTypedArray()
                for (name in names) {
                    val cn = ComponentName.unflattenFromString(name)
                    if (cn != null && cn.packageName == packageName) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun hasMediaControlPermission(): Boolean {
        return try {
            val mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controllers: List<MediaController> = mediaSessionManager.getActiveSessions(
                ComponentName(this, MusicMonitorService::class.java)
            )
            controllers.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun openMediaControlSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }
}
