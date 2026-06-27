package com.glassmusic.widget.lyric

object LrcParser {
    private val TIME_LINE_PATTERN = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]")
    private val META_PATTERN = Regex("\\[([a-z]+):(.+?)\\]", RegexOption.IGNORE_CASE)

    fun parse(lrcText: String): LyricInfo {
        if (lrcText.isBlank()) return LyricInfo.EMPTY

        val lines = lrcText.lines()
        val lyricLines = mutableListOf<LyricLine>()
        var title = ""
        var artist = ""
        var album = ""
        var offset = 0L

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val metaMatch = META_PATTERN.find(trimmedLine)
            if (metaMatch != null && !TIME_LINE_PATTERN.containsMatchIn(trimmedLine)) {
                val key = metaMatch.groupValues[1].lowercase()
                val value = metaMatch.groupValues[2].trim()
                when (key) {
                    "ti" -> title = value
                    "ar" -> artist = value
                    "al" -> album = value
                    "offset" -> offset = value.toLongOrNull() ?: 0
                }
                continue
            }

            val timeMatches = TIME_LINE_PATTERN.findAll(trimmedLine)
            val text = TIME_LINE_PATTERN.replace(trimmedLine, "").trim()

            if (text.isNotEmpty()) {
                for (match in timeMatches) {
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val millisStr = match.groupValues[3]
                    val millis = if (millisStr.length == 2) {
                        millisStr.toLong() * 10
                    } else {
                        millisStr.toLong()
                    }
                    val time = minutes * 60 * 1000 + seconds * 1000 + millis
                    lyricLines.add(LyricLine(time, text))
                }
            }
        }

        lyricLines.sortBy { it.time }

        return LyricInfo(
            title = title,
            artist = artist,
            album = album,
            lines = lyricLines,
            offset = offset
        )
    }
}
