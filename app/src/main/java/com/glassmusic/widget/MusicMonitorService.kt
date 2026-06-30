package com.glassmusic.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 音乐监听服务
 * 继承自NotificationListenerService以获取MediaSession访问权限
 * 监听系统MediaSession变化，实时获取正在播放的音乐信息
 * 
 * 最低支持 API 31 (Android 12)
 */
class MusicMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "music_monitor_channel"
        private const val CHANNEL_NAME = "音乐监听服务"
        
        private var isServiceRunning = false

        @Volatile
        private var instance: MusicMonitorService? = null

        fun isRunning(): Boolean = isServiceRunning

        fun fetchNotificationAlbumArt(
            packageName: String?,
            metadata: MediaMetadata?,
            strictTitle: Boolean = false
        ): Bitmap? {
            packageName ?: return null
            metadata ?: return null
            return instance?.loadAlbumArtFromNotification(packageName, metadata, strictTitle)
        }

        fun fetchNotificationAlbumArt(
            packageName: String?,
            expectedTitle: String?,
            strictTitle: Boolean = false
        ): Bitmap? {
            packageName ?: return null
            return instance?.loadAlbumArtFromNotificationLegacy(packageName, expectedTitle, strictTitle)
        }

        private const val WIDGET_UPDATE_MS = 400L
        private const val ART_REFRESH_MS = 900L
        private const val PERIODIC_REFRESH_MS = 10000L
        private const val RETRY_REFRESH_MS = 3000L
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private var mediaControllerCallback: MediaController.Callback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingWidgetUpdate: Runnable? = null
    private var delayedArtRefresh: Runnable? = null
    private var periodicRefresh: Runnable? = null
    private var retryRefresh: Runnable? = null
    private var pendingNeedsArtReload = false
    private var lastPlaybackStateCode: Int = PlaybackState.STATE_NONE
    private var lastActivePackage: String? = null
    
    private val mediaSessionListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            Log.d(TAG, "Active sessions changed: ${controllers?.size ?: 0}")
            updateActiveSessions(controllers)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.e(AppBuildInfo.LOG_TAG, "MusicMonitorService onCreate ${AppBuildInfo.MARKER}")
        isServiceRunning = true
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupMediaSessionListener()
        schedulePeriodicRefresh()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        
        // 服务连接后刷新会话
        refreshSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // 网易云等会高频更新通知栏进度，不在此处刷新小部件（封面/metadata 由 MediaSession 回调处理）
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MusicMonitorService onStartCommand")
        
        // 重新检查活动的MediaSession
        refreshSessions()
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于监听系统音乐播放状态"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText("${getString(R.string.service_notification_text)} · ${AppBuildInfo.MARKER}")
            .setSmallIcon(R.drawable.ic_music_note)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun setupMediaSessionListener() {
        try {
            mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            
            // 使用当前服务的ComponentName
            val componentName = android.content.ComponentName(this, MusicMonitorService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                mediaSessionListener,
                componentName
            )
            
            Log.d(TAG, "Media session listener setup successful")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification access permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Setup media session listener error", e)
        }
    }

    private fun refreshSessions() {
        try {
            val componentName = android.content.ComponentName(this, MusicMonitorService::class.java)
            val sessions = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveSessions(sessions)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh sessions error", e)
        }
    }

    private fun updateActiveSessions(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            clearCurrentSession()
            return
        }
        
        val playingController = controllers.find { controller ->
            controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        }
        
        if (playingController != null) {
            if (playingController.sessionToken != currentController?.sessionToken) {
                switchToSession(playingController)
            }
        } else {
            val pausedController = controllers.find { controller ->
                controller.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED
            }
            
            if (pausedController != null) {
                if (pausedController.sessionToken != currentController?.sessionToken) {
                    switchToSession(pausedController)
                }
            } else {
                val firstController = controllers.firstOrNull()
                if (firstController != null) {
                    if (firstController.sessionToken != currentController?.sessionToken) {
                        switchToSession(firstController)
                    }
                }
            }
        }
    }

    private fun switchToSession(controller: MediaController) {
        Log.d(TAG, "Switching to session: ${controller.packageName}")
        
        lastActivePackage = controller.packageName
        
        if (mediaControllerCallback != null) {
            currentController?.unregisterCallback(mediaControllerCallback!!)
        }
        
        currentController = controller
        
        // 创建新的回调
        mediaControllerCallback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                super.onMetadataChanged(metadata)
                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                Log.d(TAG, "Metadata changed: $title")
                scheduleWidgetUpdate(reloadArt = true, delayMs = WIDGET_UPDATE_MS)
                scheduleDelayedArtRefresh()
            }

            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                super.onPlaybackStateChanged(state)
                val code = state?.state ?: return
                if (code == lastPlaybackStateCode) {
                    return
                }
                lastPlaybackStateCode = code
                Log.d(TAG, "Playback state changed: $code")
                scheduleWidgetUpdate(reloadArt = false, delayMs = WIDGET_UPDATE_MS)
            }
        }
        
        controller.registerCallback(mediaControllerCallback!!)
        lastPlaybackStateCode = controller.playbackState?.state ?: PlaybackState.STATE_NONE
        
        scheduleWidgetUpdate(reloadArt = true, delayMs = 0L)
    }

    private fun clearCurrentSession() {
        retryRefresh?.let { mainHandler.removeCallbacks(it) }
        
        retryRefresh = Runnable {
            retryRefresh = null
            try {
                val componentName = android.content.ComponentName(this, MusicMonitorService::class.java)
                val sessions = mediaSessionManager?.getActiveSessions(componentName)
                if (!sessions.isNullOrEmpty()) {
                    Log.d(TAG, "Retry found sessions: ${sessions.size}")
                    updateActiveSessions(sessions)
                    return@Runnable
                }
            } catch (e: Exception) {
                Log.e(TAG, "Retry refresh error", e)
            }
            
            reallyClearCurrentSession()
        }
        
        mainHandler.postDelayed(retryRefresh!!, RETRY_REFRESH_MS)
    }
    
    private fun reallyClearCurrentSession() {
        if (mediaControllerCallback != null) {
            currentController?.unregisterCallback(mediaControllerCallback!!)
        }
        currentController = null
        mediaControllerCallback = null
        lastActivePackage = null
        
        MusicWidgetProvider.setMediaController(null)
        MusicWidgetProvider.setCurrentMetadata(null)
        MusicWidgetProvider.setCurrentPlaybackState(null)
        WidgetArtCache.clear()
        lastPlaybackStateCode = PlaybackState.STATE_NONE

        scheduleWidgetUpdate(reloadArt = false, delayMs = 0L)
    }

    private fun schedulePeriodicRefresh() {
        periodicRefresh?.let { mainHandler.removeCallbacks(it) }
        periodicRefresh = Runnable {
            try {
                val componentName = android.content.ComponentName(this, MusicMonitorService::class.java)
                val sessions = mediaSessionManager?.getActiveSessions(componentName)
                
                if (!sessions.isNullOrEmpty()) {
                    val playingController = sessions.find { controller ->
                        controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                    }
                    
                    if (playingController != null && playingController.sessionToken != currentController?.sessionToken) {
                        Log.d(TAG, "Periodic refresh found new playing session: ${playingController.packageName}")
                        switchToSession(playingController)
                    } else if (currentController == null) {
                        val pausedController = sessions.find { controller ->
                            controller.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED
                        } ?: sessions.firstOrNull()
                        
                        if (pausedController != null) {
                            Log.d(TAG, "Periodic refresh found paused session: ${pausedController.packageName}")
                            switchToSession(pausedController)
                        }
                    } else {
                        val currentPkg = currentController?.packageName
                        val sessionExists = sessions.any { it.packageName == currentPkg }
                        if (!sessionExists) {
                            Log.d(TAG, "Periodic refresh: current session lost, rechecking")
                            val availableController = sessions.find { controller ->
                                controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                            } ?: sessions.firstOrNull()
                            if (availableController != null) {
                                switchToSession(availableController)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Periodic refresh error", e)
            }
            
            periodicRefresh?.let { mainHandler.postDelayed(it, PERIODIC_REFRESH_MS) }
        }
        
        mainHandler.postDelayed(periodicRefresh!!, PERIODIC_REFRESH_MS)
    }
    
    private fun updateProviderData(reloadArt: Boolean = true) {
        val controller = currentController
        if (controller == null) {
            Log.w(TAG, "updateProviderData: no active controller")
            return
        }

        try {
            MusicWidgetProvider.setMediaController(controller)

            val metadata = controller.metadata
            MusicWidgetProvider.setCurrentMetadata(metadata)

            val playbackState = controller.playbackState
            MusicWidgetProvider.setCurrentPlaybackState(playbackState)

            if (metadata == null) {
                Log.w(TAG, "updateProviderData: metadata null (${controller.packageName})")
                WidgetArtCache.clear()
                return
            }

            if (reloadArt) {
                val settings = BaseMusicWidgetProvider.readSettings(this)
                Log.e(AppBuildInfo.LOG_TAG, "updateProviderData: \"${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}\"")
                WidgetArtCache.prepareForUpdate(
                    this,
                    metadata,
                    settings.autoColor,
                    settings.tintAlpha,
                    controller.packageName,
                    allowBlockingLoad = true
                )
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                val artOk = WidgetArtCache.isCacheValidFor(metadata)
                Log.e(AppBuildInfo.LOG_TAG, "art ready=$artOk for \"$title\" (${controller.packageName})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update provider data error", e)
        }
    }

    private fun pushWidgets() {
        WidgetRefreshCoordinator.requestPush(this)
    }

    private fun scheduleWidgetUpdate(reloadArt: Boolean = true, delayMs: Long = WIDGET_UPDATE_MS) {
        if (reloadArt) {
            pendingNeedsArtReload = true
        }
        pendingWidgetUpdate?.let { mainHandler.removeCallbacks(it) }
        pendingWidgetUpdate = Runnable {
            pendingWidgetUpdate = null
            val metadata = currentController?.metadata
            val cacheInvalid = metadata != null && !WidgetArtCache.isCacheValidFor(metadata)
            val needsReload = pendingNeedsArtReload || cacheInvalid
            pendingNeedsArtReload = false

            updateProviderData(reloadArt = needsReload)

            if (metadata != null && !WidgetArtCache.isCacheValidFor(metadata)) {
                Log.w(TAG, "defer push: art not ready for \"${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}\"")
                scheduleDelayedArtRefresh()
                return@Runnable
            }
            pushWidgets()
        }
        if (delayMs <= 0L) {
            mainHandler.post(pendingWidgetUpdate!!)
        } else {
            mainHandler.postDelayed(pendingWidgetUpdate!!, delayMs)
        }
    }

    /** 延迟补刷：仅在封面尚未就绪时重新加载，避免误清已有封面 */
    private fun scheduleDelayedArtRefresh() {
        delayedArtRefresh?.let { mainHandler.removeCallbacks(it) }
        delayedArtRefresh = Runnable {
            val metadata = currentController?.metadata
            if (metadata != null && WidgetArtCache.isCacheValidFor(metadata)) {
                pushWidgets()
            } else {
                updateProviderData(reloadArt = true)
                pushWidgets()
            }
        }
        mainHandler.postDelayed(delayedArtRefresh!!, ART_REFRESH_MS)
    }

    private fun loadAlbumArtFromNotification(
        packageName: String,
        metadata: MediaMetadata,
        strictTitle: Boolean
    ): Bitmap? {
        val expectedTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val expectedArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return loadAlbumArtFromNotificationInternal(
            packageName = packageName,
            expectedTitle = expectedTitle,
            expectedArtist = expectedArtist,
            strictTitle = strictTitle
        )
    }

    private fun loadAlbumArtFromNotificationLegacy(
        packageName: String,
        expectedTitle: String?,
        strictTitle: Boolean
    ): Bitmap? {
        return loadAlbumArtFromNotificationInternal(
            packageName = packageName,
            expectedTitle = expectedTitle,
            expectedArtist = null,
            strictTitle = strictTitle
        )
    }

    private fun loadAlbumArtFromNotificationInternal(
        packageName: String,
        expectedTitle: String?,
        expectedArtist: String?,
        strictTitle: Boolean
    ): Bitmap? {
        val notifications = try {
            activeNotifications
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read active notifications", e)
            null
        } ?: return null

        val fromPackage = notifications
            .filter { it.packageName == packageName }
            .sortedByDescending { it.postTime }

        if (fromPackage.isEmpty()) {
            return null
        }

        if (!expectedTitle.isNullOrBlank()) {
            for (sbn in fromPackage) {
                if (notificationMatchesTitle(sbn.notification, expectedTitle, expectedArtist)) {
                    extractAlbumArtFromNotification(sbn.notification)?.let { return it }
                }
            }
        }

        if (strictTitle) {
            return null
        }

        for (sbn in fromPackage) {
            extractAlbumArtFromNotification(sbn.notification)?.let { return it }
        }
        return null
    }

    private fun notificationMatchesTitle(
        notification: Notification,
        expectedTitle: String,
        expectedArtist: String?
    ): Boolean {
        val extras = notification.extras ?: return false
        val title = expectedTitle.trim()
        if (title.isEmpty()) {
            return false
        }
        val artist = expectedArtist?.trim().orEmpty()
        val expectedTokens = buildList {
            add(title)
            if (artist.isNotEmpty()) {
                add("$artist - $title")
                add("$title - $artist")
                add("$title · $artist")
                add("$artist · $title")
            }
        }
        val candidates = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence("android.title")?.toString(),
            extras.getCharSequence("android.text")?.toString(),
            extras.getCharSequence(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
            extras.getCharSequence(MediaMetadata.METADATA_KEY_ARTIST)?.toString(),
            extras.getCharSequence(MediaMetadata.METADATA_KEY_ALBUM)?.toString()
        ).map { it.trim() }.filter { it.isNotEmpty() }

        return candidates.any { candidate ->
            expectedTokens.any { expected ->
                candidate.equals(expected, ignoreCase = true) ||
                    candidate.contains(expected, ignoreCase = true) ||
                    expected.contains(candidate, ignoreCase = true)
            }
        }
    }

    private fun extractAlbumArtFromNotification(notification: Notification): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification.getLargeIcon()?.let { icon ->
                icon.loadDrawable(this)?.let { drawable ->
                    AlbumArtLoader.drawableToBitmap(drawable)?.let { return it }
                }
            }
        }

        val extras = notification.extras ?: return null

        @Suppress("DEPRECATION")
        (extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON))?.let { bitmap ->
            AlbumArtLoader.toSoftwareBitmap(bitmap)?.let { return it }
        }

        @Suppress("DEPRECATION")
        (extras.getParcelable<Bitmap>("android.largeIcon"))?.let { bitmap ->
            AlbumArtLoader.toSoftwareBitmap(bitmap)?.let { return it }
        }

        return null
    }

    override fun onDestroy() {
        pendingWidgetUpdate?.let { mainHandler.removeCallbacks(it) }
        pendingWidgetUpdate = null
        delayedArtRefresh?.let { mainHandler.removeCallbacks(it) }
        delayedArtRefresh = null
        periodicRefresh?.let { mainHandler.removeCallbacks(it) }
        periodicRefresh = null
        retryRefresh?.let { mainHandler.removeCallbacks(it) }
        retryRefresh = null
        WidgetRefreshCoordinator.cancel()
        super.onDestroy()
        Log.d(TAG, "MusicMonitorService onDestroy")
        isServiceRunning = false
        instance = null
        
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(mediaSessionListener)
        } catch (e: Exception) {
            Log.e(TAG, "Remove listener error", e)
        }
        
        if (mediaControllerCallback != null) {
            currentController?.unregisterCallback(mediaControllerCallback!!)
        }
    }
}
