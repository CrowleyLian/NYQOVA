package com.nyqova.core

data class CueTrack(val number: Int, val title: String, val artist: String,
                    val album: String, val file: String, val startUs: Long, val endUs: Long?)

/** INDEX 01 is the audible track boundary. INDEX 00 belongs to the preceding audio. */
object CueParser {
    private data class Pending(val number: Int, val file: String, var title: String = "",
                               var artist: String = "", var frames: Long? = null)

    fun parse(text: String): List<CueTrack> {
        var file = ""
        var album = ""
        var artist = ""
        var current: Pending? = null
        val tracks = mutableListOf<Pending>()
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            val fields = line.split(Regex("\\s+"), limit = 2)
            val command = fields[0].uppercase()
            val value = fields.getOrElse(1) { "" }.trim()
            fun unquote(v: String) = v.trim().removeSurrounding("\"")
            when (command) {
                "FILE" -> {
                    file = if (value.startsWith('"')) value.substringAfter('"').substringBefore('"')
                           else value.substringBeforeLast(' ')
                    current = null
                }
                "TRACK" -> {
                    val parts = value.split(Regex("\\s+"))
                    current = if (parts.getOrNull(1)?.equals("AUDIO", true) == true && file.isNotEmpty())
                        parts[0].toIntOrNull()?.let { Pending(it, file).also(tracks::add) } else null
                }
                "TITLE" -> current?.let { it.title = unquote(value) } ?: run { album = unquote(value) }
                "PERFORMER" -> current?.let { it.artist = unquote(value) } ?: run { artist = unquote(value) }
                "INDEX" -> if (value.split(Regex("\\s+"), limit = 2).firstOrNull() == "01") {
                    val t = value.split(Regex("\\s+"), limit = 2).getOrElse(1) { "" }.split(':').mapNotNull(String::toLongOrNull)
                    if (t.size == 3 && t[0] >= 0 && t[1] in 0..59 && t[2] in 0..74)
                        current?.frames = (t[0] * 60 + t[1]) * 75 + t[2]
                }
            }
        }
        val valid = tracks.filter { it.frames != null }
        return valid.mapIndexed { i, t ->
            val next = valid.getOrNull(i + 1)?.takeIf { it.file == t.file }
            require(next == null || next.frames!! > t.frames!!) { "CUE 曲目时间必须递增" }
            CueTrack(t.number, t.title.ifBlank { "曲目 ${t.number}" }, t.artist.ifBlank { artist }, album,
                t.file, t.frames!! * 1_000_000 / 75, next?.frames?.times(1_000_000)?.div(75))
        }
    }
}
