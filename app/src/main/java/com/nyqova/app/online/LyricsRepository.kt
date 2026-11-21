package com.nyqova.app.online

import android.content.Context
import androidx.media3.common.MediaItem
import com.nyqova.app.storage.MusicSource
import com.nyqova.core.*

data class ResolvedLyrics(val document: LyricsDocument?, val source: String, val timelineOffsetMs: Long,
    val identity: TrackIdentity, val result: LyricsResult?)

object LyricsRepository {
    fun resolve(context: Context, item: MediaItem, durationMs: Long, scope: LookupScope, forceOnline: Boolean = false): ResolvedLyrics {
        val identified = OnlineMedia.identify(context, item, durationMs)
        val q = identified.identity
        scope.check()
        if(!forceOnline && identified.localLyricsPath != null && item.mediaMetadata.extras?.getString("source_key") == MusicSource.key()) {
            val local = runCatching { LrcParser.parse(MusicText.decode(MusicSource.readSmall(identified.localLyricsPath))) }.getOrNull()
            if(local != null && (local.lines.isNotEmpty() || local.plainText.isNotBlank()))
                return ResolvedLyrics(local, "本地 LRC", identified.localLyricsOffsetMs, q, null)
        }
        scope.check()
        if(!com.nyqova.app.settings.TvPreferences(context).enabled("online_lyrics")) {
            val cached = OnlineMedia.cachedLyrics(context, q)
            return ResolvedLyrics(cached?.candidate?.takeUnless { it.instrumental }?.document(), "在线歌词已关闭", 0, q, cached)
        }
        val result = OnlineMedia.lyrics(context, q, scope, forceOnline)
        val record = result.candidate
        val label = when {
            record?.instrumental == true -> "纯音乐，无歌词"
            record != null && result.automatic -> "${record.provider} · 自动选择，可在歌词设置更换版本"
            record != null -> "${record.provider}${if(result.cached) " · 缓存" else " · 在线"}"
            result.candidates.isNotEmpty() -> "找到 ${result.candidates.size} 个候选，请选择录音版本"
            else -> "未找到匹配歌词，可重试或手动搜索"
        }
        return ResolvedLyrics(record?.takeUnless { it.instrumental }?.document(), label, 0, q, result)
    }
}
