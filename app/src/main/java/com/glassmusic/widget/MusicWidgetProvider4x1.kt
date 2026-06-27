package com.glassmusic.widget

import android.content.Context

class MusicWidgetProvider4x1 : BaseMusicWidgetProvider() {
    override fun getLayoutResId(context: Context): Int {
        return if (context.resources.configuration.smallestScreenWidthDp >= 600) {
            R.layout.widget_music_4x1_tablet
        } else {
            R.layout.widget_music_4x1
        }
    }

    override fun getProviderClass(): Class<out BaseMusicWidgetProvider> {
        return MusicWidgetProvider4x1::class.java
    }

    override fun getCornerRadiusDp(): Float = 24f
}
