package com.glassmusic.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.IBinder
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
        
        fun isRunning(): Boolean = isServiceRunning
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private var mediaControllerCallback: MediaController.Callback? = null
    
    private val mediaSessionListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
            Log.d(TAG, "Active sessions changed: ${controllers?.size ?: 0}")
            updateActiveSessions(controllers)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MusicMonitorService onCreate")
        isServiceRunning = true
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupMediaSessionListener()
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
            .setContentTitle("玻璃音乐小部件")
            .setContentText("正在监听音乐播放状态")
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
            // 没有活动的会话
            clearCurrentSession()
            return
        }
        
        // 找到正在播放的会话，或者取第一个
        val playingController = controllers.find { controller ->
            controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
        
        if (playingController != null && playingController.sessionToken != currentController?.sessionToken) {
            // 切换到新的会话
            switchToSession(playingController)
        }
    }

    private fun switchToSession(controller: MediaController) {
        Log.d(TAG, "Switching to session: ${controller.packageName}")
        
        // 移除旧的回调
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
                updateWidget()
            }

            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                super.onPlaybackStateChanged(state)
                Log.d(TAG, "Playback state changed: ${state?.state}")
                updateWidget()
            }
        }
        
        controller.registerCallback(mediaControllerCallback!!)
        
        // 立即更新widget
        updateWidget()
    }

    private fun clearCurrentSession() {
        if (mediaControllerCallback != null) {
            currentController?.unregisterCallback(mediaControllerCallback!!)
        }
        currentController = null
        mediaControllerCallback = null
        
        MusicWidgetProvider.setMediaController(null)
        MusicWidgetProvider.setCurrentMetadata(null)
        MusicWidgetProvider.setCurrentPlaybackState(null)
        
        updateWidget()
    }

    private fun updateProviderData() {
        val controller = currentController ?: return
        
        try {
            // 直接传递框架类对象，不需要转换
            MusicWidgetProvider.setMediaController(controller)
            
            // 传递metadata
            val metadata = controller.metadata
            MusicWidgetProvider.setCurrentMetadata(metadata)
            
            // 传递playback state
            val playbackState = controller.playbackState
            MusicWidgetProvider.setCurrentPlaybackState(playbackState)
        } catch (e: Exception) {
            Log.e(TAG, "Update provider data error", e)
        }
    }

    private fun updateWidget() {
        updateProviderData()
        MusicWidgetProvider.updateAllWidgets(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MusicMonitorService onDestroy")
        isServiceRunning = false
        
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
