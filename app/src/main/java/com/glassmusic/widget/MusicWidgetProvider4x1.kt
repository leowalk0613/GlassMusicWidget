package com.glassmusic.widget

class MusicWidgetProvider4x1 : BaseMusicWidgetProvider() {
    override fun getLayoutResId(): Int {
        return R.layout.widget_music_4x1
    }

    override fun getProviderClass(): Class<out BaseMusicWidgetProvider> {
        return MusicWidgetProvider4x1::class.java
    }
}
