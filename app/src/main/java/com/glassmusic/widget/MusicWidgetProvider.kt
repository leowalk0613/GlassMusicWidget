package com.glassmusic.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log

class MusicWidgetProvider : BaseMusicWidgetProvider() {
    companion object {
        private const val TAG = "MusicWidgetProvider"

        private val PROVIDER_CLASSES = listOf(
            MusicWidgetProvider::class.java,
            MusicWidgetProvider4x1::class.java
        )

        fun updateAllWidgets(context: Context) {
            WidgetRefreshCoordinator.requestPush(context)
        }

        /** 不经广播，直接刷新已安装的小部件实例 */
        fun pushWidgetsDirect(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            for (providerClass in PROVIDER_CLASSES) {
                val componentName = ComponentName(context, providerClass)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                if (appWidgetIds.isEmpty()) {
                    continue
                }
                val provider = try {
                    providerClass.getDeclaredConstructor().newInstance()
                } catch (e: Exception) {
                    Log.e(TAG, "Create provider failed: ${providerClass.simpleName}", e)
                    continue
                }
                for (appWidgetId in appWidgetIds) {
                    provider.updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
        }

        fun setMediaController(controller: MediaController?) {
            BaseMusicWidgetProvider.setMediaController(controller)
        }

        fun setCurrentMetadata(metadata: MediaMetadata?) {
            BaseMusicWidgetProvider.setCurrentMetadata(metadata)
        }

        fun setCurrentPlaybackState(state: PlaybackState?) {
            BaseMusicWidgetProvider.setCurrentPlaybackState(state)
        }
    }

    override fun getLayoutResId(context: Context): Int {
        return R.layout.widget_music
    }

    override fun getProviderClass(): Class<out BaseMusicWidgetProvider> {
        return MusicWidgetProvider::class.java
    }
}
