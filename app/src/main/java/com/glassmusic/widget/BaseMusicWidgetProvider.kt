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

abstract class BaseMusicWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "BaseMusicWidgetProvider"

        private const val COVER_CORNER_RATIO = 0.12f

        const val PREFS_NAME = "GlassMusicWidgetPrefs"
        const val KEY_GLASS_ALPHA = "glass_alpha"
        const val KEY_TINT_ALPHA = "tint_alpha"
        const val KEY_AUTO_COLOR = "auto_color"
        const val KEY_TEXT_COLOR = "text_color"
        const val KEY_ICON_STYLE = "icon_style"

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

        private val DEFAULT_COLOR = Color.parseColor("#888888")

        fun setMediaController(controller: MediaController?) {
            mediaController = controller
        }

        fun setCurrentMetadata(metadata: MediaMetadata?) {
            currentMetadata = metadata
        }

        fun setCurrentPlaybackState(state: PlaybackState?) {
            currentPlaybackState = state
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

        data class Settings(
            val glassAlpha: Int,
            val tintAlpha: Int,
            val autoColor: Boolean,
            val textColor: Boolean,
            val iconStyle: Int
        )
    }

    protected abstract fun getLayoutResId(): Int

    protected abstract fun getProviderClass(): Class<out BaseMusicWidgetProvider>

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Log.d(TAG, "onUpdate called")

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
                val componentName = ComponentName(context, getProviderClass())
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
            val views = RemoteViews(context.packageName, getLayoutResId())

            val settings = getSettings(context)

            val glassAlphaFloat = settings.glassAlpha / 100f
            views.setFloat(R.id.glass_background, "setAlpha", glassAlphaFloat)

            applyIconStyle(views, settings.iconStyle)

            setupControlIntents(context, views)
            setupAlbumArtIntent(context, views)

            updateMusicInfo(context, views, appWidgetManager, appWidgetId, settings)

            updatePlaybackState(context, views, settings.iconStyle)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Widget updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Update widget error", e)
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
                    views.setOnClickPendingIntent(R.id.iv_album_art, pendingIntent)
                    return
                }
            }
            val settingsIntent = Intent(context, MainActivity::class.java)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context, 4, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.iv_album_art, pendingIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Setup album art intent error", e)
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
            views.setTextViewText(R.id.tv_play_status, "未播放")
            views.setTextViewText(R.id.tv_song_title, "未在播放")
            views.setTextViewText(R.id.tv_artist_name, "—")

            setBackgroundColor(views, DEFAULT_COLOR, settings.tintAlpha)

            if (settings.textColor) {
                setTextColors(views, Color.WHITE)
            } else {
                setTextColors(views, Color.WHITE)
            }
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "未知歌曲"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "未知歌手"
        views.setTextViewText(R.id.tv_song_title, title)
        views.setTextViewText(R.id.tv_artist_name, artist)

        val albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (albumArt != null) {
            val coverSize = Math.min(albumArt.width, albumArt.height)
            val roundedCover = getRoundedBitmap(albumArt, coverSize * COVER_CORNER_RATIO)
            views.setImageViewBitmap(R.id.iv_album_art, roundedCover)

            if (settings.autoColor) {
                CoroutineScope(Dispatchers.Main).launch {
                    val dominantColor = extractDominantColor(albumArt, settings.tintAlpha)
                    setBackgroundColor(views, dominantColor)

                    if (settings.textColor) {
                        val textColor = getTextColorForBackground(dominantColor)
                        setTextColors(views, textColor)
                    }

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } else {
                setBackgroundColor(views, DEFAULT_COLOR, settings.tintAlpha)

                if (settings.textColor) {
                    setTextColors(views, Color.WHITE)
                }
            }
        } else {
            setBackgroundColor(views, DEFAULT_COLOR, settings.tintAlpha)

            if (settings.textColor) {
                setTextColors(views, Color.WHITE)
            }
        }
    }

    private fun setBackgroundColor(views: RemoteViews, color: Int) {
        views.setInt(R.id.glass_background, "setColorFilter", color)
    }

    private fun setBackgroundColor(views: RemoteViews, color: Int, alpha: Int) {
        val tintedColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        views.setInt(R.id.glass_background, "setColorFilter", tintedColor)
    }

    private fun setTextColors(views: RemoteViews, color: Int) {
        views.setTextColor(R.id.tv_play_status, color)
        views.setTextColor(R.id.tv_song_title, color)
        views.setTextColor(R.id.tv_artist_name, color)
        views.setInt(R.id.btn_previous, "setColorFilter", color)
        views.setInt(R.id.btn_play_pause, "setColorFilter", color)
        views.setInt(R.id.btn_next, "setColorFilter", color)
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
        val luminance = calculateLuminance(backgroundColor)
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

    private suspend fun extractDominantColor(bitmap: Bitmap, tintAlpha: Int): Int = withContext(Dispatchers.Default) {
        try {
            val palette = Palette.from(bitmap).generate()
            val color = palette.getVibrantColor(
                palette.getDominantColor(DEFAULT_COLOR)
            )
            Color.argb(tintAlpha, Color.red(color), Color.green(color), Color.blue(color))
        } catch (e: Exception) {
            Log.e(TAG, "Palette extraction error", e)
            DEFAULT_COLOR
        }
    }

    private fun updatePlaybackState(context: Context, views: RemoteViews, iconStyle: Int) {
        val state = currentPlaybackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        val appName = getCurrentAppName(context)

        if (isPlaying) {
            views.setImageViewResource(R.id.btn_play_pause, R.drawable.ic_pause)
            views.setTextViewText(R.id.tv_play_status, "正在播放 · $appName")
        } else {
            views.setImageViewResource(R.id.btn_play_pause, R.drawable.ic_play)
            views.setTextViewText(R.id.tv_play_status, "已暂停 · $appName")
        }
    }

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
