package com.glassmusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object WidgetArtCache {

    private const val TAG = "WidgetArtCache"
    private val logTag get() = AppBuildInfo.LOG_TAG

    private val lock = Any()

    private var boundTrackSignature: String? = null
    private var boundDisplayKey: String? = null
    private var boundAlbum: String? = null
    private var boundArtist: String? = null
    private var boundDuration: Long = 0L
    private var loadedArtUri: String? = null
    private var lastArtFingerprint: Int? = null
    private var albumArt: Bitmap? = null
    private var widgetCover: Bitmap? = null
    private var loadGeneration = 0

    private var staleGuardFingerprint: Int? = null
    private var staleGuardAlbum: String? = null
    private var staleGuardTitle: String? = null
    private var boundTitle: String? = null

    fun clear() {
        synchronized(lock) {
            boundTrackSignature = null
            boundDisplayKey = null
            boundAlbum = null
            boundArtist = null
            boundDuration = 0L
            loadedArtUri = null
            lastArtFingerprint = null
            staleGuardFingerprint = null
            staleGuardAlbum = null
            staleGuardTitle = null
            boundTitle = null
            loadGeneration++
            recycleAlbumArtLocked()
            recycleWidgetCoverLocked()
        }
        Log.i(TAG, "clear")
    }

    fun onTrackChanged(metadata: MediaMetadata) {
        synchronized(lock) {
            if (hasCoverLocked() &&
                AlbumArtLoader.isSameTrack(metadata, boundTitle, boundArtist, boundDuration)
            ) {
                syncBoundMetadataLocked(metadata)
                return
            }
            val signature = AlbumArtLoader.cacheSignature(metadata)
            if (boundTrackSignature == signature && hasCoverLocked()) {
                return
            }
            Log.i(TAG, "track changed -> \"${metadata.titleLabel()}\"")
            if (hasCoverLocked()) {
                staleGuardFingerprint = lastArtFingerprint
                staleGuardAlbum = boundAlbum
                staleGuardTitle = boundTitle
            }
            boundTrackSignature = null
            boundDisplayKey = null
            boundAlbum = null
            boundArtist = null
            boundDuration = 0L
            boundTitle = null
            loadedArtUri = null
            lastArtFingerprint = null
            loadGeneration++
            recycleAlbumArtLocked()
            recycleWidgetCoverLocked()
        }
    }

    private fun hasCoverLocked(): Boolean {
        return (widgetCover != null && !widgetCover!!.isRecycled) ||
            (albumArt != null && !albumArt!!.isRecycled)
    }

    private fun syncBoundMetadataLocked(metadata: MediaMetadata) {
        boundTrackSignature = AlbumArtLoader.cacheSignature(metadata)
        boundDisplayKey = AlbumArtLoader.displayKey(metadata)
        metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            boundTitle = it
        }
        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            boundArtist = it
        }
        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            boundAlbum = it
        }
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration > 0L) {
            boundDuration = duration
        }
    }

    fun getAlbumArt(): Bitmap? = synchronized(lock) {
        albumArt?.takeIf { !it.isRecycled }
    }

    fun getWidgetCover(metadata: MediaMetadata?): Bitmap? {
        synchronized(lock) {
            if (metadata != null &&
                !AlbumArtLoader.isSameTrack(metadata, boundTitle, boundArtist, boundDuration)
            ) {
                return null
            }
            widgetCover?.takeIf { !it.isRecycled }?.let { return it }
            val art = albumArt?.takeIf { !it.isRecycled }
            if (art != null) {
                val scaled = AlbumArtLoader.scaleForRemoteViews(art)
                widgetCover = scaled.copy(Bitmap.Config.ARGB_8888, false)
                return widgetCover
            }
            return null
        }
    }

    fun getAlbumArtFor(metadata: MediaMetadata): Bitmap? {
        return getDisplayArt(metadata, null)
    }

    fun isCacheValidFor(metadata: MediaMetadata): Boolean {
        return getWidgetCover(metadata) != null
    }

    fun getDisplayArt(metadata: MediaMetadata, @Suppress("UNUSED_PARAMETER") sourcePackage: String?): Bitmap? {
        getWidgetCover(metadata)?.let { return it }
        synchronized(lock) {
            val art = albumArt?.takeIf { !it.isRecycled } ?: return null
            if (AlbumArtLoader.isSameTrack(metadata, boundTitle, boundArtist, boundDuration)) {
                syncBoundMetadataLocked(metadata)
                return art
            }
            return null
        }
    }

    fun prepareForUpdate(
        context: Context,
        metadata: MediaMetadata?,
        autoColor: Boolean,
        tintAlpha: Int,
        sourcePackage: String? = null,
        allowBlockingLoad: Boolean = false
    ) {
        Log.e(logTag, "prepareForUpdate title=${metadata?.titleLabel()} blocking=$allowBlockingLoad pkg=$sourcePackage")

        if (metadata == null) {
            if (!MusicMonitorService.isRunning()) {
                clear()
            }
            return
        }

        onTrackChanged(metadata)

        val cacheHit = synchronized(lock) {
            hasCoverLocked() &&
                AlbumArtLoader.isSameTrack(metadata, boundTitle, boundArtist, boundDuration)
        }
        if (cacheHit) {
            synchronized(lock) {
                syncBoundMetadataLocked(metadata)
            }
            return
        }

        val artUri = AlbumArtLoader.artUriSignature(metadata)
        val generation = synchronized(lock) { ++loadGeneration }

        val fastBitmap = loadFast(context, metadata, sourcePackage, allowNetwork = false)
        if (fastBitmap != null) {
            synchronized(lock) {
                if (generation == loadGeneration) {
                    bindArtLocked(AlbumArtLoader.cacheSignature(metadata), fastBitmap, artUri, metadata)
                } else {
                    recycleIfOwned(fastBitmap)
                }
            }
            return
        }

        Log.i(TAG, "fast load miss for \"${metadata.titleLabel()}\" blocking=$allowBlockingLoad")

        if (!allowBlockingLoad) {
            return
        }

        val loadTask = LoadTask(
            generation = generation,
            signature = AlbumArtLoader.cacheSignature(metadata),
            artUri = artUri,
            metadata = metadata,
            sourcePackage = sourcePackage,
            appContext = context.applicationContext
        )

        try {
            val loaded = runBlocking {
                withContext(Dispatchers.IO) {
                    loadTask.loadBitmap()
                }
            }
            synchronized(lock) {
                if (loadTask.generation != loadGeneration) {
                    loaded?.let { recycleIfOwned(it) }
                    return
                }
                if (loaded != null) {
                    bindArtLocked(loadTask.signature, loaded, loadTask.artUri, loadTask.metadata)
                } else {
                    Log.w(TAG, "blocking load failed for \"${loadTask.metadata.titleLabel()}\"")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "prepareForUpdate failed", e)
        }
    }

    private fun loadFast(
        context: Context,
        metadata: MediaMetadata,
        sourcePackage: String?,
        allowNetwork: Boolean
    ): Bitmap? {
        val sourceNames = listOf("embedded", "uri-local", "notification", "uri-network")
        val sources = listOf(
            { AlbumArtLoader.loadEmbedded(metadata) },
            { AlbumArtLoader.loadPreferUri(context.applicationContext, metadata, allowNetwork = false) },
            {
                MusicMonitorService.fetchNotificationAlbumArt(
                    sourcePackage,
                    metadata,
                    strictTitle = true
                )
            },
            { AlbumArtLoader.loadPreferUri(context.applicationContext, metadata, allowNetwork) }
        )
        for ((index, source) in sources.withIndex()) {
            val name = sourceNames[index]
            val bitmap = try {
                source()
            } catch (e: Exception) {
                Log.w(TAG, "source $name error: ${e.message}")
                null
            } ?: continue
            if (isStaleDuringTransition(bitmap, metadata)) {
                recycleIfOwned(bitmap)
                continue
            }
            Log.i(TAG, "loaded art from $name for \"${metadata.titleLabel()}\" (${bitmap.width}x${bitmap.height})")
            return bitmap
        }
        return null
    }

    private fun MediaMetadata.titleLabel(): String {
        return getString(MediaMetadata.METADATA_KEY_TITLE) ?: "?"
    }

    private data class LoadTask(
        val generation: Int,
        val signature: String,
        val artUri: String,
        val metadata: MediaMetadata,
        val sourcePackage: String?,
        val appContext: Context
    ) {
        fun loadBitmap(): Bitmap? {
            return loadFast(appContext, metadata, sourcePackage, allowNetwork = true)
        }
    }

    private fun isStaleDuringTransition(bitmap: Bitmap, metadata: MediaMetadata): Boolean {
        synchronized(lock) {
            if (AlbumArtLoader.isSameTrack(metadata, boundTitle, boundArtist, boundDuration)) {
                return false
            }
            val guardFingerprint = staleGuardFingerprint ?: return false
            if (AlbumArtLoader.bitmapFingerprint(bitmap) != guardFingerprint) {
                return false
            }
            val newAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
            val guardAlbum = staleGuardAlbum?.trim().orEmpty()
            if (guardAlbum.isNotEmpty() && newAlbum.isNotEmpty()) {
                return guardAlbum != newAlbum
            }
            val newTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            val guardTitle = staleGuardTitle?.trim().orEmpty()
            if (guardTitle.isNotEmpty() && newTitle.isNotEmpty() && guardTitle != newTitle) {
                return true
            }
            return false
        }
    }

    private fun bindArtLocked(signature: String, bitmap: Bitmap, artUri: String, metadata: MediaMetadata) {
        val fingerprint = AlbumArtLoader.bitmapFingerprint(bitmap)
        val sameTrack = AlbumArtLoader.isSameTrack(metadata, boundTitle, boundArtist, boundDuration)
        if (sameTrack && widgetCover != null && !widgetCover!!.isRecycled) {
            albumArt = bitmap
            syncBoundMetadataLocked(metadata)
            loadedArtUri = artUri.takeIf { it.isNotEmpty() }
            if (fingerprint != lastArtFingerprint) {
                recycleWidgetCoverLocked()
                val scaled = AlbumArtLoader.scaleForRemoteViews(bitmap)
                widgetCover = scaled.copy(Bitmap.Config.ARGB_8888, false)
            }
            lastArtFingerprint = fingerprint
            staleGuardFingerprint = null
            staleGuardAlbum = null
            staleGuardTitle = null
            return
        }

        recycleAlbumArtLocked()
        recycleWidgetCoverLocked()
        albumArt = bitmap
        syncBoundMetadataLocked(metadata)
        loadedArtUri = artUri.takeIf { it.isNotEmpty() }
        lastArtFingerprint = fingerprint
        staleGuardFingerprint = null
        staleGuardAlbum = null
        staleGuardTitle = null
        val scaled = AlbumArtLoader.scaleForRemoteViews(bitmap)
        widgetCover = scaled.copy(Bitmap.Config.ARGB_8888, false)
        Log.e(logTag, "bound art for \"${metadata.titleLabel()}\" widgetCover=${widgetCover?.width}x${widgetCover?.height}")
    }

    private fun recycleAlbumArtLocked() {
        albumArt?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        albumArt = null
    }

    private fun recycleWidgetCoverLocked() {
        widgetCover?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        widgetCover = null
    }

    private fun recycleIfOwned(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
