package com.nyqova.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class TrackIdentity(val title: String, val artist: String, val album: String, val durationMs: Long) {
    companion object {
        fun from(title: String, artist: String, album: String, durationMs: Long): TrackIdentity {
            val missingArtist=artist.isBlank() || artist in setOf("未知艺术家","Unknown Artist","unknown")
            // Tagged titles such as '18 And Life' and '21 Guns' contain meaningful numbers.
            val prefix=if(missingArtist) "^\\s*\\d{1,3}[.、 _-]+" else "^\\s*\\d{1,3}\\s*[.、_-]+\\s*"
            var name = title.replace(Regex(prefix), "").trim()
            name = name.replace(Regex("[（(]\\s*cover\\s*[:：]?[^）)]*[）)]", RegexOption.IGNORE_CASE), "").trim()
            var performer = artist.takeUnless { it in setOf("未知艺术家", "Unknown Artist", "unknown") }.orEmpty().trim()
            val parts = name.split(Regex("\\s+[-–—]\\s+"), limit = 2)
            if(parts.size == 2) {
                if(performer.isBlank()) { performer = parts[0].trim(); name = parts[1].trim() }
                else if(OnlineMatch.normalized(parts[0]) == OnlineMatch.normalized(performer)) name = parts[1].trim()
                else if(OnlineMatch.normalized(parts[1]) == OnlineMatch.normalized(performer)) name = parts[0].trim()
            }
            return TrackIdentity(name, performer, album.takeUnless { it in setOf("散曲", "未分类音乐") }.orEmpty(), durationMs)
        }
    }
}
object OnlineMatch {
    fun normalized(value: String): String = ChineseKeys.simplified(Normalizer.normalize(value, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }
    private fun version(value: String): Set<String> = listOf("live", "remix", "acoustic", "instrumental", "现场", "伴奏", "演唱会")
        .filter { value.lowercase(Locale.ROOT).contains(it) }.toSet()
    fun confident(query: TrackIdentity, title: String, artist: String, durationMs: Long): Boolean {
        if(query.artist.isBlank() || normalized(query.title) != normalized(title)) return false
        if(normalized(query.artist) != normalized(artist) || version(query.title) != version(title)) return false
        return query.durationMs <= 0 || (durationMs > 0 && abs(query.durationMs - durationMs) <= 3000)
    }
}
