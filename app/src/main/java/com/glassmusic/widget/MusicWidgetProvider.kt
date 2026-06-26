package com.glassmusic.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log

class MusicWidgetProvider : BaseMusicWidgetProvider() {
    companion object {
        private const val TAG = "MusicWidgetProvider"

        fun updateAllWidgets(context: Context) {
            updateWidgetByProvider(context, MusicWidgetProvider::class.java)
            updateWidgetByProvider(context, MusicWidgetProvider4x1::class.java)
        }

        private fun updateWidgetByProvider(context: Context, providerClass: Class<out BaseMusicWidgetProvider>) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, providerClass)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            val intent = Intent(context, providerClass)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            context.sendBroadcast(intent)
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

    override fun getLayoutResId(): Int {
        return R.layout.widget_music
    }

    override fun getProviderClass(): Class<out BaseMusicWidgetProvider> {
        return MusicWidgetProvider::class.java
    }
}
