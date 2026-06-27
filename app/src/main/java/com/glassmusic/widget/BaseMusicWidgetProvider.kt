package com.glassmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

abstract class BaseMusicWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "BaseMusicWidgetProvider"
        private const val WIDGET_UPDATE_THROTTLE_MS = 500L
        private val lastWidgetUpdateMs = mutableMapOf<Int, Long>()

        private const val COVER_CORNER_RATIO = 0.12f

        const val PREFS_NAME = "GlassMusicWidgetPrefs"
        const val KEY_GLASS_ALPHA = "glass_alpha"
        const val KEY_TINT_ALPHA = "tint_alpha"
        const val KEY_AUTO_COLOR = "auto_color"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_ICON_STYLE = "icon_style"

        const val DEFAULT_GLASS_ALPHA = 55
        const val DEFAULT_TINT_ALPHA = 90
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

        private val appNameCache = mutableMapOf<String, String>()

        fun setMediaController(controller: MediaController?) {
            mediaController = controller
        }

        fun setCurrentMetadata(metadata: MediaMetadata?) {
            currentMetadata = metadata
        }

        fun setCurrentPlaybackState(state: PlaybackState?) {
            currentPlaybackState = state
        }

        fun syncFromMediaController() {
            val controller = mediaController ?: return
            try {
                currentMetadata = controller.metadata
                currentPlaybackState = controller.playbackState
            } catch (e: Exception) {
                Log.e(TAG, "Sync media controller error", e)
            }
        }

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

        fun readSettings(context: Context): Settings = getSettings(context)

        data class Settings(
            val glassAlpha: Int,
            val tintAlpha: Int,
            val autoColor: Boolean,
            val textColor: Boolean,
            val iconStyle: Int
        )
    }

    protected abstract fun getLayoutResId(context: Context): Int

    protected abstract fun getProviderClass(): Class<out BaseMusicWidgetProvider>

    protected open fun getCornerRadiusDp(): Float = 20f

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate called")

        try {
            if (!MusicMonitorService.isRunning()) {
                val serviceIntent = Intent(context, MusicMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
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
                WidgetRefreshCoordinator.requestPush(context, immediate = true)
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

    internal fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = lastWidgetUpdateMs[appWidgetId] ?: 0L
        if (now - last < WIDGET_UPDATE_THROTTLE_MS) {
            return
        }
        lastWidgetUpdateMs[appWidgetId] = now

        Log.w(AppBuildInfo.LOG_TAG, "updateAppWidget id=$appWidgetId ${AppBuildInfo.MARKER}")

        try {
            val views = RemoteViews(context.packageName, getLayoutResId(context))

            val settings = getSettings(context)

            syncFromMediaController()

            // 服务已在后台加载封面；小部件只读取缓存，避免 prepareForUpdate 清空已绑定的图
            if (!MusicMonitorService.isRunning()) {
                WidgetArtCache.prepareForUpdate(
                    context,
                    currentMetadata,
                    settings.autoColor,
                    settings.tintAlpha,
                    mediaController?.packageName,
                    allowBlockingLoad = currentMetadata != null
                )
            }

            val tintColor = computeTintColor(context, currentMetadata, settings)

            applyIconStyle(views, settings.iconStyle)

            setupControlIntents(context, views)
            setupAlbumArtIntent(context, views)

            updateMusicInfo(context, views, settings, tintColor)

            applyTransparentStyleLayers(
                views,
                tintColor,
                settings.glassAlpha
            )

            updatePlaybackState(context, views, settings, tintColor)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Update widget error", e)
            showFallbackWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun showFallbackWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        try {
            val views = RemoteViews(context.packageName, getLayoutResId(context))
            applyTransparentStyleLayers(
                views,
                WidgetColors.fallbackTint(DEFAULT_TINT_ALPHA),
                DEFAULT_GLASS_ALPHA
            )
            views.setTextViewText(R.id.tv_song_title, "未在播放")
            views.setTextViewText(R.id.tv_artist_name, "—")
            views.setTextViewText(R.id.tv_play_status, "未播放")
            views.setImageViewResource(R.id.iv_album_art, R.drawable.default_album)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback widget error", e)
        }
    }

    private fun applyIconStyle(views: RemoteViews, style: Int) {
        views.setImageViewResource(R.id.btn_previous, R.drawable.ic_skip_previous)
        views.setImageViewResource(R.id.btn_next, R.drawable.ic_skip_next)
    }

    private fun setupControlIntents(context: Context, views: RemoteViews) {
        val playPauseIntent = Intent(context, getProviderClass()).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getBroadcast(
            context, 0, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_play_pause, playPausePendingIntent)

        val prevIntent = Intent(context, getProviderClass()).apply {
            action = ACTION_PREVIOUS
        }
        val prevPendingIntent = PendingIntent.getBroadcast(
            context, 1, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_previous, prevPendingIntent)

        val nextIntent = Intent(context, getProviderClass()).apply {
            action = ACTION_NEXT
        }
        val nextPendingIntent = PendingIntent.getBroadcast(
            context, 2, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_next, nextPendingIntent)
    }

    private fun setupAlbumArtIntent(context: Context, views: RemoteViews) {
        val clickTargetId = R.id.iv_album_art

        try {
            val controller = mediaController
            if (controller != null) {
                val packageName = controller.packageName
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pendingIntent = PendingIntent.getActivity(
                        context, 3, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(clickTargetId, pendingIntent)
                    return
                }
            }
            val settingsIntent = Intent(context, MainActivity::class.java)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context, 4, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(clickTargetId, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Setup album art intent error", e)
            try {
                val settingsIntent = Intent(context, MainActivity::class.java)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntent.getActivity(
                    context, 4, settingsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(clickTargetId, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback settings intent error", e2)
            }
        }
    }

    private fun updateMusicInfo(
        context: Context,
        views: RemoteViews,
        settings: Settings,
        tintColor: Int
    ) {
        val metadata = currentMetadata

        if (metadata == null) {
            views.setTextViewText(R.id.tv_play_status, "未播放")
            views.setTextViewText(R.id.tv_song_title, "未在播放")
            views.setTextViewText(R.id.tv_artist_name, "—")
            setTextColors(views, Color.WHITE)
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "未知歌曲"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知歌手"
        views.setTextViewText(R.id.tv_song_title, title)
        views.setTextViewText(R.id.tv_artist_name, artist)

        val albumArt = resolveDisplayAlbumArt(metadata)
        views.setImageViewResource(R.id.iv_album_art, R.drawable.default_album)
        if (albumArt != null) {
            val roundedCover = getRoundedBitmap(albumArt, minOf(albumArt.width, albumArt.height) * COVER_CORNER_RATIO)
            views.setImageViewBitmap(R.id.iv_album_art, roundedCover)
            Log.w(AppBuildInfo.LOG_TAG, "cover set ${albumArt.width}x${albumArt.height} for \"$title\"")
        } else {
            Log.w(AppBuildInfo.LOG_TAG, "cover missing for \"$title\" cached=${WidgetArtCache.getWidgetCover(metadata) != null}")
        }

        if (settings.textColor) {
            setTextColors(views, getTextColorForBackground(tintColor))
        } else {
            setTextColors(views, Color.WHITE)
        }
    }

    private fun computeTintColor(
        context: Context,
        metadata: MediaMetadata?,
        settings: Settings
    ): Int {
        if (!settings.autoColor) {
            return WidgetColors.fallbackTint(settings.tintAlpha)
        }

        val art = metadata?.let { WidgetArtCache.getDisplayArt(it, null) }
            ?: WidgetArtCache.getAlbumArt()

        if (art != null && !art.isRecycled) {
            return try {
                val rgb = WidgetColors.extractDominantRgbBlocking(art)
                WidgetColors.buildTintColor(rgb, settings.tintAlpha)
            } catch (e: Exception) {
                Log.w(TAG, "Tint extraction failed", e)
                WidgetColors.fallbackTint(settings.tintAlpha)
            }
        }
        return WidgetColors.fallbackTint(settings.tintAlpha)
    }

    /** 小部件展示封面 */
    private fun resolveDisplayAlbumArt(metadata: MediaMetadata?): Bitmap? {
        return WidgetArtCache.getWidgetCover(metadata)
            ?: metadata?.let { WidgetArtCache.getDisplayArt(it, mediaController?.packageName) }
            ?: WidgetArtCache.getAlbumArt()
    }

    private fun applyTransparentStyleLayers(
        views: RemoteViews,
        tintColor: Int,
        glassAlpha: Int
    ) {
        val transparency = (glassAlpha / 100f).coerceIn(0f, 1f)
        val opaque = 1f - transparency
        val frostStrength = opaque * opaque * 0.4f

        views.setImageViewResource(R.id.tint_background, getTintFillDrawableRes())
        views.setInt(R.id.tint_background, "setColorFilter", tintColor)

        views.setImageViewResource(R.id.glass_background, getGlassBackgroundDrawableRes())
        views.setViewVisibility(R.id.glass_background, View.VISIBLE)
        views.setFloat(R.id.glass_background, "setAlpha", frostStrength)

        val tintViewAlpha = if (transparency >= 1f) 0f else 0.03f + opaque * 0.97f
        views.setFloat(R.id.tint_background, "setAlpha", tintViewAlpha)

        val borderAlpha = 0.15f + opaque * 0.85f
        views.setFloat(R.id.glass_border, "setAlpha", borderAlpha)
    }

    private fun getTintFillDrawableRes(): Int {
        return if (getCornerRadiusDp() >= 24f) {
            R.drawable.widget_tint_fill_4x1
        } else {
            R.drawable.widget_tint_fill
        }
    }

    private fun getGlassBackgroundDrawableRes(): Int {
        return if (getCornerRadiusDp() >= 24f) {
            R.drawable.widget_glass_background_4x1
        } else {
            R.drawable.widget_glass_background
        }
    }

    private fun setTextColors(views: RemoteViews, color: Int) {
        views.setTextColor(R.id.tv_play_status, color)
        views.setTextColor(R.id.tv_song_title, color)
        views.setTextColor(R.id.tv_artist_name, color)
        views.setInt(R.id.btn_previous, "setColorFilter", color)
        views.setInt(R.id.btn_next, "setColorFilter", color)
    }

    private fun applyPlayButton(views: RemoteViews, isPlaying: Boolean, color: Int) {
        views.setImageViewResource(
            R.id.btn_play_pause,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setInt(R.id.btn_play_pause, "setColorFilter", color)
    }

    private fun calculateLuminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0

        val rs = if (r <= 0.03928) r / 12.92 else Math.pow((r + 0.055) / 1.055, 2.4)
        val gs = if (g <= 0.03928) g / 12.92 else Math.pow((g + 0.055) / 1.055, 2.4)
        val bs = if (b <= 0.03928) b / 12.92 else Math.pow((b + 0.055) / 1.055, 2.4)

        return 0.2126 * rs + 0.7152 * gs + 0.0722 * bs
    }

    private fun getTextColorForBackground(backgroundColor: Int): Int {
        val opaque = Color.argb(
            255,
            Color.red(backgroundColor),
            Color.green(backgroundColor),
            Color.blue(backgroundColor)
        )
        val luminance = calculateLuminance(opaque)
        return if (luminance > 0.5) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    private fun getRoundedBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        try {
            val side = Math.min(bitmap.width, bitmap.height)
            val x = (bitmap.width - side) / 2
            val y = (bitmap.height - side) / 2
            val squareBitmap = Bitmap.createBitmap(bitmap, x, y, side, side)

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

            if (squareBitmap != bitmap) {
                squareBitmap.recycle()
            }

            return output
        } catch (e: Exception) {
            Log.e(TAG, "Rounded bitmap error", e)
            return bitmap
        }
    }

    private fun updatePlaybackState(
        context: Context,
        views: RemoteViews,
        settings: Settings,
        tintColor: Int
    ) {
        val playbackState = mediaController?.playbackState ?: currentPlaybackState
        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val appName = try {
            getCurrentAppName()
        } catch (e: Exception) {
            Log.w(TAG, "App name fallback: ${e.message}")
            formatFallbackAppName(mediaController?.packageName.orEmpty())
        }

        val buttonColor = if (settings.textColor) {
            getTextColorForBackground(tintColor)
        } else {
            Color.WHITE
        }
        applyPlayButton(views, isPlaying, buttonColor)

        val statusText = when {
            currentMetadata == null -> "未播放"
            isPlaying -> "正在播放 · $appName"
            else -> "已暂停 · $appName"
        }
        views.setTextViewText(R.id.tv_play_status, statusText)
    }

    private fun getCurrentAppName(): String {
        val packageName = mediaController?.packageName?.takeIf { it.isNotBlank() } ?: return "未知应用"
        return appNameCache.getOrPut(packageName) {
            formatFallbackAppName(packageName)
        }
    }

    private fun formatFallbackAppName(packageName: String): String {
        val segment = packageName.substringAfterLast('.')
        if (segment.isBlank()) {
            return "音乐应用"
        }
        return segment.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "onEnabled")

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

        try {
            val serviceIntent = Intent(context, MusicMonitorService::class.java)
            context.stopService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Stop service error", e)
        }
    }
}
