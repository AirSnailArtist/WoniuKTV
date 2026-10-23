package com.homektv.tv.player

/**
 * 轻量级 LRC 时间轴解析器，支持一行多个时间戳。
 *
 * Lightweight LRC timeline parser that supports multiple timestamps per line.
 */
data class LyricWord(val startMs: Long, val text: String)
data class LyricLine(val startMs: Long, val text: String, val words: List<LyricWord> = emptyList())

/** LRC lyric parser and timestamp conversion utilities. / LRC 歌词解析与时间戳转换工具。 */
object LrcParser {
    private val lineTag = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val wordTag = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")

    fun parse(raw: String): List<LyricLine> = raw.lineSequence().flatMap { line ->
        val trimmed = line.trim()
        val lineMatches = lineTag.findAll(trimmed).toList()
        if (lineMatches.isEmpty()) return@flatMap emptySequence()
        val content = trimmed.substring(lineMatches.last().range.last + 1).trim()
        val matches = wordTag.findAll(content).toList()
        val words = matches.mapIndexed { index, tagMatch ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: content.length
            LyricWord(toMillis(tagMatch), content.substring(tagMatch.range.last + 1, end))
        }.filter { it.text.isNotBlank() }
        val text = content.replace(wordTag, "").trim()
        lineMatches.asSequence().map { LyricLine(toMillis(it), text, words) }
    }.filter { it.text.isNotBlank() }.sortedBy { it.startMs }.toList()

    private fun toMillis(match: MatchResult): Long {
        val fraction = match.groupValues[3]
            .let { if (it.isEmpty()) 0 else it.padEnd(3, '0').take(3).toLong() }
        return (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 + fraction
    }
}
