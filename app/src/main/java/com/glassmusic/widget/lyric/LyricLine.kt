package com.glassmusic.widget.lyric

data class LyricLine(
    val time: Long,
    val text: String,
    val translation: String? = null
) {
    companion object {
        val EMPTY = LyricLine(0, "")
    }
}
