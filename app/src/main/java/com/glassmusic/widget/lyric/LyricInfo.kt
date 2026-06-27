package com.glassmusic.widget.lyric

data class LyricInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val lines: List<LyricLine> = emptyList(),
    val offset: Long = 0
) {
    companion object {
        val EMPTY = LyricInfo()
    }

    val isEmpty: Boolean
        get() = lines.isEmpty()

    fun getCurrentLineIndex(position: Long): Int {
        if (lines.isEmpty()) return -1
        val adjustedPosition = position + offset
        var index = -1
        for (i in lines.indices) {
            if (lines[i].time <= adjustedPosition) {
                index = i
            } else {
                break
            }
        }
        return index
    }

    fun getCurrentLine(position: Long): LyricLine? {
        val index = getCurrentLineIndex(position)
        return if (index >= 0 && index < lines.size) lines[index] else null
    }

    fun getNextLine(position: Long): LyricLine? {
        val index = getCurrentLineIndex(position) + 1
        return if (index >= 0 && index < lines.size) lines[index] else null
    }
}
