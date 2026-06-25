package com.glassmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 玻璃质感音乐桌面小部件
 * iOS风格布局 + PNG毛玻璃背景 + 专辑封面主色调染色
 * 支持设置界面调节：透明度、颜色深度、自动取色、图标样式
 */
class MusicWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "MusicWidgetProvider"

        // 专辑封面圆角比例（按封面宽度计算）
        private const val COVER_CORNER_RATIO = 0.12f

        // SharedPreferences 键名（与 MainActivity 保持一致）
        const val PREFS_NAME = "GlassMusicWidgetPrefs"
        const val KEY_GLASS_ALPHA = "glass_alpha"
        const val KEY_TINT_ALPHA = "tint_alpha"
        const val KEY_AUTO_COLOR = "auto_color"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_ICON_STYLE = "icon_style"

        // 默认值
        const val DEFAULT_GLASS_ALPHA = 50
        const val DEFAULT_TINT_ALPHA = 50
        const val DEFAULT_AUTO_COLOR = true
        const val DEFAULT_TEXT_COLOR = true
        const val DEFAULT_ICON_STYLE = 1

        const val ACTION_PLAY_PAUSE = "com.glassmusic.widget.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.glassmusic.widget.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.glassmusic.widget.ACTION_PREVIOUS"
        const val ACTION_UPDATE_WIDGET = "com.glassmusic.widget.ACTION_UPDATE_WIDGET"

        private var currentMetadata: MediaMetadata? = null
        private var currentPlaybackState: PlaybackState? = null
        private var mediaController: MediaController? = null

        // 默认背景色（灰色）
        private val DEFAULT_COLOR = Color.parseColor("#888888")

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, MusicWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val intent = Intent(context, MusicWidgetProvider::class.java)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            context.sendBroadcast(intent)
        }

        fun setMediaController(controller: MediaController?) {
            mediaController = controller
        }

        fun setCurrentMetadata(metadata: MediaMetadata?) {
            currentMetadata = metadata
        }

        fun setCurrentPlaybackState(state: PlaybackState?) {
            currentPlaybackState = state
        }

        /**
         * 读取设置
         */
        private fun getSettings(context: Context): Settings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return Settings(
                glassAlpha = prefs.getInt(KEY_GLASS_ALPHA, DEFAULT_GLASS_ALPHA),
                tintAlpha = prefs.getInt(KEY_TINT_ALPHA, DEFAULT_TINT_ALPHA),
                autoColor = prefs.getBoolean(KEY_AUTO_COLOR, DEFAULT_AUTO_COLOR),
                textColor = prefs.getBoolean(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR),
                iconStyle = prefs.getInt(KEY_ICON_STYLE, DEFAULT_ICON_STYLE)
            )
        }

        /**
         * 设置数据类
         */
        data class Settings(
            val glassAlpha: Int,      // 玻璃透明度 0-100
            val tintAlpha: Int,       // 染色深度 0-255
            val autoColor: Boolean,   // 自动取色开关
            val textColor: Boolean,   // 文字随封面变色开关
            val iconStyle: Int        // 图标样式 1-4
        )
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate called")

        // 启动音乐监听服务
        try {
            val serviceIntent = Intent(context, MusicMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start service error", e)
        }

        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: ${intent.action}")

        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                handlePlayPause()
            }
            ACTION_NEXT -> {
                handleNext()
            }
            ACTION_PREVIOUS -> {
                handlePrevious()
            }
            ACTION_UPDATE_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, MusicWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                for (appWidgetId in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
        }
    }

    private fun handlePlayPause() {
        try {
            val controller = mediaController ?: return
            val state = currentPlaybackState ?: return

            if (state.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Play/Pause error", e)
        }
    }

    private fun handleNext() {
        try {
            mediaController?.transportControls?.skipToNext()
        } catch (e: Exception) {
            Log.e(TAG, "Next error", e)
        }
    }

    private fun handlePrevious() {
        try {
            mediaController?.transportControls?.skipToPrevious()
        } catch (e: Exception) {
            Log.e(TAG, "Previous error", e)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        Log.d(TAG, "updateAppWidget: $appWidgetId")

        try {
            val views = RemoteViews(context.packageName, R.layout.widget_music)

            // 读取设置
            val settings = getSettings(context)

            // 设置毛玻璃背景透明度（0-100 转换为 0f-1f）
            val glassAlphaFloat = settings.glassAlpha / 100f
            views.setFloat(R.id.glass_background, "setAlpha", glassAlphaFloat)

            // 设置图标样式
            applyIconStyle(views, settings.iconStyle)

            // 设置点击事件
            setupControlIntents(context, views)
            // 设置专辑封面点击事件 - 打开当前播放的音乐应用
            setupAlbumArtIntent(context, views)

            // 更新音乐信息和颜色
            updateMusicInfo(context, views, appWidgetManager, appWidgetId, settings)

            // 更新播放状态
            updatePlaybackState(context, views, settings.iconStyle)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Update widget error", e)
        }
    }

    /**
     * 应用图标样式
     * 现在使用PNG图标，样式固定为简洁风格
     */
    private fun applyIconStyle(views: RemoteViews, style: Int) {
        // 使用PNG图标，样式固定
        views.setImageViewResource(R.id.btn_previous, R.drawable.ic_skip_previous)
        views.setImageViewResource(R.id.btn_next, R.drawable.ic_skip_next)
    }

    private fun setupControlIntents(context: Context, views: RemoteViews) {
        // 播放/暂停
        val playPauseIntent = Intent(context, MusicWidgetProvider::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getBroadcast(
            context, 0, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_play_pause, playPausePendingIntent)

        // 上一首
        val prevIntent = Intent(context, MusicWidgetProvider::class.java).apply {
            action = ACTION_PREVIOUS
        }
        val prevPendingIntent = PendingIntent.getBroadcast(
            context, 1, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_previous, prevPendingIntent)

        // 下一首
        val nextIntent = Intent(context, MusicWidgetProvider::class.java).apply {
            action = ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context, 2, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_next, nextPendingIntent)
    }

    /**
     * 设置专辑封面点击事件
     * 有音乐播放时：打开当前播放的音乐应用
     * 无音乐播放时：打开设置界面
     */
    private fun setupAlbumArtIntent(context: Context, views: RemoteViews) {
        try {
            val controller = mediaController
            if (controller != null) {
                // 有当前播放的应用，尝试打开它
                val packageName = controller.packageName
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 3, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.iv_album_art, pendingIntent)
                    return
                }
            }
            // 没有当前播放的应用或获取失败，打开设置界面
            val settingsIntent = Intent(context, MainActivity::class.java)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context, 4, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.iv_album_art, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Setup album art intent error", e)
            // 出错时也打开设置界面作为兜底
            try {
                val settingsIntent = Intent(context, MainActivity::class.java)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    context, 4, settingsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.iv_album_art, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback settings intent error", e2)
            }
        }
    }

    private fun updateMusicInfo(
        context: Context,
        views: RemoteViews,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        settings: Settings
    ) {
        val metadata = currentMetadata

        if (metadata == null) {
            // 无音乐播放时显示默认状态
            views.setTextViewText(R.id.tv_play_status, "未播放")
            views.setTextViewText(R.id.tv_song_title, "未在播放")
            views.setTextViewText(R.id.tv_artist_name, "—")

            // 默认颜色
            setBackgroundColor(views, DEFAULT_COLOR, settings.tintAlpha)

            // 如果开启了文字变色，设置默认文字颜色（白色）
            if (settings.textColor) {
                setTextColors(views, Color.WHITE)
            } else {
                // 关闭文字变色，使用默认白色
                setTextColors(views, Color.WHITE)
            }
            return
        }

        // 更新歌曲名和歌手
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "未知歌曲"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知歌手"
        views.setTextViewText(R.id.tv_song_title, title)
        views.setTextViewText(R.id.tv_artist_name, artist)

        // 更新专辑封面（带圆角）
        val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (albumArt != null) {
            // 用正方形的边长计算圆角半径，确保非正方形封面圆角大小合适
            val coverSize = Math.min(albumArt.width, albumArt.height)
            val roundedCover = getRoundedBitmap(albumArt, coverSize * COVER_CORNER_RATIO)
            views.setImageViewBitmap(R.id.iv_album_art, roundedCover)

            // 如果开启了自动取色，异步提取主色调并更新背景和文字颜色
            if (settings.autoColor) {
                CoroutineScope(Dispatchers.Main).launch {
                    val dominantColor = extractDominantColor(albumArt, settings.tintAlpha)
                    setBackgroundColor(views, dominantColor)

                    // 如果开启了文字变色，根据背景颜色设置文字颜色
                    if (settings.textColor) {
                        val textColor = getTextColorForBackground(dominantColor)
                        setTextColors(views, textColor)
                    }

                    // 重新更新widget
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } else {
                // 关闭自动取色，使用默认颜色
                setBackgroundColor(views, DEFAULT_COLOR, settings.tintAlpha)

                // 如果开启了文字变色，设置默认文字颜色（白色）
                if (settings.textColor) {
                    setTextColors(views, Color.WHITE)
                }
            }
        } else {
            // 默认颜色
            setBackgroundColor(views, DEFAULT_COLOR, settings.tintAlpha)

            // 如果开启了文字变色，设置默认文字颜色（白色）
            if (settings.textColor) {
                setTextColors(views, Color.WHITE)
            }
        }
    }

    private fun setBackgroundColor(views: RemoteViews, color: Int) {
        // 给毛玻璃背景ImageView设置颜色过滤
        views.setInt(R.id.glass_background, "setColorFilter", color)
    }

    private fun setBackgroundColor(views: RemoteViews, color: Int, alpha: Int) {
        // 给颜色加上透明度
        val tintedColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        views.setInt(R.id.glass_background, "setColorFilter", tintedColor)
    }

    /**
     * 设置所有文字和图标的颜色
     */
    private fun setTextColors(views: RemoteViews, color: Int) {
        views.setTextColor(R.id.tv_play_status, color)
        views.setTextColor(R.id.tv_song_title, color)
        views.setTextColor(R.id.tv_artist_name, color)
        // 给播放器图标也设置相同的颜色，保持视觉统一
        views.setInt(R.id.btn_previous, "setColorFilter", color)
        views.setInt(R.id.btn_play_pause, "setColorFilter", color)
        views.setInt(R.id.btn_next, "setColorFilter", color)
    }

    /**
     * 计算颜色的相对亮度（0-1）
     * 使用 WCAG 相对亮度公式
     */
    private fun calculateLuminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0

        val rs = if (r <= 0.03928) r / 12.92 else Math.pow((r + 0.055) / 1.055, 2.4)
        val gs = if (g <= 0.03928) g / 12.92 else Math.pow((g + 0.055) / 1.055, 2.4)
        val bs = if (b <= 0.03928) b / 12.92 else Math.pow((b + 0.055) / 1.055, 2.4)

        return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
    }

    /**
     * 根据背景颜色选择合适的文字颜色（黑色或白色）
     * 确保文字与背景有足够的对比度
     */
    private fun getTextColorForBackground(backgroundColor: Int): Int {
        val luminance = calculateLuminance(backgroundColor)
        // 如果背景亮度大于0.5，使用黑色文字；否则使用白色文字
        return if (luminance > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    /**
     * 给Bitmap切统一圆角
     * 先裁剪成正方形，再加圆角，确保非正方形封面也能正确显示圆角
     */
    private fun getRoundedBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        try {
            // 第一步：先裁剪成正方形（centerCrop效果）
            val side = Math.min(bitmap.width, bitmap.height)
            val x = (bitmap.width - side) / 2
            val y = (bitmap.height - side) / 2
            val squareBitmap = Bitmap.createBitmap(bitmap, x, y, side, side)

            // 第二步：给正方形bitmap加圆角
            val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val paint = Paint().apply {
                isAntiAlias = true
                color = Color.BLACK
            }

            val rect = RectF(0f, 0f, side.toFloat(), side.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)

            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(squareBitmap, 0f, 0f, paint)

            // 回收临时bitmap
            if (squareBitmap != bitmap) {
                squareBitmap.recycle()
            }

            return output
        } catch (e: Exception) {
            Log.e(TAG, "Rounded bitmap error", e)
            return bitmap
        }
    }

    private suspend fun extractDominantColor(bitmap: Bitmap, tintAlpha: Int): Int = withContext(Dispatchers.Default) {
        try {
            val palette = Palette.from(bitmap).generate()
            // 优先获取有活力的颜色，其次是主色调，最后用默认色
            val color = palette.getVibrantColor(
                palette.getDominantColor(DEFAULT_COLOR)
            )
            // 给颜色加上透明度
            Color.argb(tintAlpha, Color.red(color), Color.green(color), Color.blue(color))
        } catch (e: Exception) {
            Log.e(TAG, "Palette extraction error", e)
            DEFAULT_COLOR
        }
    }

    private fun updatePlaybackState(context: Context, views: RemoteViews, iconStyle: Int) {
        val state = currentPlaybackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        // 获取当前播放的应用名
        val appName = getCurrentAppName(context)

        if (isPlaying) {
            views.setImageViewResource(R.id.btn_play_pause, R.drawable.ic_pause)
            views.setTextViewText(R.id.tv_play_status, "正在播放 · $appName")
        } else {
            views.setImageViewResource(R.id.btn_play_pause, R.drawable.ic_play)
            views.setTextViewText(R.id.tv_play_status, "已暂停 · $appName")
        }
    }

    /**
     * 获取当前播放音乐的应用名称
     */
    private fun getCurrentAppName(context: Context): String {
        return try {
            val controller = mediaController
            if (controller != null) {
                val packageName = controller.packageName
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } else {
                "未知应用"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get app name error", e)
            "音乐应用"
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "onEnabled")

        // 第一个widget被添加时启动服务
        try {
            val serviceIntent = Intent(context, MusicMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start service error", e)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "onDisabled")

        // 最后一个widget被移除时停止服务
        try {
            val serviceIntent = Intent(context, MusicMonitorService::class.java)
            context.stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Stop service error", e)
        }
    }
}
