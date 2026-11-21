package com.nyqova.core

data class LyricLine(val timeMs: Long, val text: String)
data class LyricsDocument(val lines: List<LyricLine>, val plainText: String) {
    fun indexAt(positionMs: Long): Int {
        var low = 0; var high = lines.lastIndex; var result = -1
        while(low <= high) {
            val middle = (low + high) ushr 1
            if(lines[middle].timeMs <= positionMs) { result = middle; low = middle + 1 } else high = middle - 1
        }
        return result
    }
}
object LrcParser {
    private val stamp = Regex("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    fun parse(text: String): LyricsDocument {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val lines = mutableListOf<LyricLine>()
        text.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val stamps = stamp.findAll(line).toList()
            val words = line.substring(stamps.lastOrNull()?.range?.last?.plus(1) ?: 0).trim()
            stamps.forEach { match ->
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0
                if(seconds in 0..59) lines += LyricLine(match.groupValues[1].toLong() * 60000 + seconds * 1000 + fraction - offset, words)
            }
        }
        return LyricsDocument(lines.groupBy { it.timeMs }.toSortedMap().map { (time, rows) ->
            LyricLine(time, rows.map { it.text }.distinct().joinToString("\n"))
        }, text.lineSequence().filterNot { it.trim().startsWith('[') && !stamp.containsMatchIn(it) }.joinToString("\n"))
    }
}
