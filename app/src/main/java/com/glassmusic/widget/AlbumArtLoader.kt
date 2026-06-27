package com.glassmusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

object AlbumArtLoader {

    private const val TAG = "AlbumArtLoader"

    fun load(context: Context, metadata: MediaMetadata): Bitmap? {
        return loadPreferUri(context, metadata) ?: loadEmbedded(metadata)
    }

    /** 优先从 URI 拉取，避免 metadata 内嵌 bitmap 滞后于曲目信息 */
    fun loadPreferUri(
        context: Context,
        metadata: MediaMetadata,
        allowNetwork: Boolean = true
    ): Bitmap? {
        metadata.description?.iconUri?.let { uri ->
            if (allowNetwork || !uri.isNetworkScheme()) {
                loadFromUri(context, uri)?.let { return it }
            }
        }

        listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        ).forEach { key ->
            metadata.getString(key)?.let { uriString ->
                val uri = Uri.parse(uriString)
                if (allowNetwork || !uri.isNetworkScheme()) {
                    loadFromUri(context, uri)?.let { return it }
                }
            }
        }

        return null
    }

    private fun Uri.isNetworkScheme(): Boolean {
        val scheme = scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    fun artUriSignature(metadata: MediaMetadata): String {
        return buildString {
            append(metadata.description?.iconUri?.toString().orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty())
        }
    }

    fun bitmapFingerprint(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return 0
        }
        return try {
            val size = 8
            val sample = Bitmap.createScaledBitmap(bitmap, size, size, false)
            var hash = 1
            for (x in 0 until size) {
                for (y in 0 until size) {
                    hash = 31 * hash + sample.getPixel(x, y)
                }
            }
            if (sample !== bitmap) {
                sample.recycle()
            }
            hash
        } catch (e: Exception) {
            Log.w(TAG, "bitmapFingerprint failed", e)
            0
        }
    }

    fun loadEmbedded(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return toSoftwareBitmap(it) }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return toSoftwareBitmap(it) }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return toSoftwareBitmap(it) }
        metadata.description?.iconBitmap?.let { return toSoftwareBitmap(it) }
        return null
    }

    fun toSoftwareBitmap(source: Bitmap): Bitmap? {
        if (source.isRecycled) {
            return null
        }
        if (source.config == Bitmap.Config.ARGB_8888) {
            return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        val output = Bitmap.createBitmap(
            source.width.coerceAtLeast(1),
            source.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Canvas(output).drawBitmap(source, 0f, 0f, null)
        return output
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap ?: return null
            return toSoftwareBitmap(bitmap)
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return try {
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            output
        } catch (e: Exception) {
            Log.w(TAG, "Drawable to bitmap failed", e)
            null
        }
    }

    fun trackKey(metadata: MediaMetadata): String = cacheSignature(metadata)

    /** 判断是否为同一首歌（容忍网易云后续补全/变更标题后缀） */
    fun isSameTrack(
        metadata: MediaMetadata,
        boundTitle: String?,
        boundArtist: String?,
        boundDuration: Long
    ): Boolean {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (boundTitle.isNullOrEmpty() && boundDuration <= 0L) {
            return false
        }

        if (boundDuration > 0L && duration > 0L && boundDuration == duration) {
            return true
        }

        if (titlesCompatible(title, boundTitle)) {
            return true
        }

        return false
    }

    private fun titlesCompatible(title: String, boundTitle: String?): Boolean {
        if (boundTitle.isNullOrEmpty()) {
            return title.isEmpty()
        }
        if (title == boundTitle) {
            return true
        }
        if (title.length >= 4 && boundTitle.length >= 4) {
            if (title.startsWith(boundTitle) || boundTitle.startsWith(title)) {
                return true
            }
        }
        return false
    }

    /** 展示匹配用：优先 mediaId，否则用标题（避免歌手/专辑字段分批到达导致签名不一致） */
    fun displayKey(metadata: MediaMetadata): String {
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (mediaId.isNotEmpty()) {
            return "$mediaId|$title"
        }
        return title
    }

    /** 缩小到 RemoteViews 可传输的尺寸 */
    fun scaleForRemoteViews(source: Bitmap, maxSide: Int = 512): Bitmap {
        if (source.isRecycled) {
            return source
        }
        val software = toSoftwareBitmap(source) ?: source
        val maxDim = maxOf(software.width, software.height)
        if (maxDim <= maxSide) {
            return software
        }
        val scale = maxSide.toFloat() / maxDim
        val width = (software.width * scale).toInt().coerceAtLeast(1)
        val height = (software.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(software, width, height, true)
    }

    /** 曲目 + 封面 URI + 时长，用于缓存失效判断 */
    fun cacheSignature(metadata: MediaMetadata): String {
        return buildString {
            append(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            append('|')
            append(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
            append('|')
            append(metadata.description?.iconUri?.toString().orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty())
            append('|')
            append(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty())
        }
    }

    fun loadFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            when (uri.scheme?.lowercase()) {
                "content", "file", "android.resource" -> {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }?.let { toSoftwareBitmap(it) }
                }
                "http", "https" -> loadFromNetwork(uri)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Load album art failed: $uri (${e.message})")
            null
        }
    }

    private fun loadFromNetwork(uri: Uri): Bitmap? {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            instanceFollowRedirects = true
            useCaches = false
            defaultUseCaches = false
            setRequestProperty("User-Agent", "GlassMusicWidget/1.0")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            connection.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { toSoftwareBitmap(it) }
        } finally {
            connection.disconnect()
        }
    }
}
