package com.nyqova.app.online

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import com.nyqova.app.library.ArtworkCache
import com.nyqova.app.library.LibraryDatabase
import com.nyqova.app.storage.MusicSource
import com.nyqova.core.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LookupScope {
    val cancelled = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    fun attach(call: Call) { calls += call; if(cancelled.get()) call.cancel() }
    fun detach(call: Call) { calls -= call }
    fun check() { if(cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("查询已取消") }
    fun cancel() { cancelled.set(true); calls.forEach { it.cancel() } }
}
data class LyricCandidate(val id: Long, val title: String, val artist: String, val album: String, val durationMs: Long,
    val synced: String, val plain: String, val instrumental: Boolean, val provider: String="LRCLIB") {
    fun document() = LrcParser.parse(synced.ifBlank { plain })
    fun json() = JSONObject().put("id", id).put("trackName", title).put("artistName", artist).put("albumName", album)
        .put("duration", durationMs / 1000.0).put("syncedLyrics", synced).put("plainLyrics", plain).put("instrumental", instrumental).put("provider",provider)
    companion object {
        fun parse(json: JSONObject) = LyricCandidate(json.optLong("id"), json.optString("trackName"), json.optString("artistName"),
            json.optString("albumName"), (json.optDouble("duration", 0.0) * 1000).toLong(),
            if(json.isNull("syncedLyrics")) "" else json.optString("syncedLyrics"),
            if(json.isNull("plainLyrics")) "" else json.optString("plainLyrics"), json.optBoolean("instrumental"),json.optString("provider","LRCLIB"))
    }
}
data class LyricsResult(val candidate: LyricCandidate?, val candidates: List<LyricCandidate> = emptyList(), val cached: Boolean = false, val automatic: Boolean=false) {
    fun selectFirst(): LyricsResult=if(candidate!=null || candidates.isEmpty()) this else copy(candidate=candidates.first(),automatic=true)
}
data class IdentifiedTrack(val identity: TrackIdentity, val albumKey: String?, val localLyricsPath: String?, val localLyricsOffsetMs: Long)

/** Separate public client: NAS authorization headers, addresses, and files are never passed here. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object OnlineMedia {
    private val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).addNetworkInterceptor { chain ->
            val request = chain.request()
            check(request.header("Authorization") == null && request.header("Cookie") == null) { "在线匹配不能携带 NAS 凭据" }
            val host = request.url.host
            if(!request.url.isHttps || !(host in setOf("lrclib.net", "api.lrc.cx", "musicbrainz.org", "coverartarchive.org", "archive.org") || host.endsWith(".archive.org") || host.endsWith(".mzstatic.com"))) throw IOException("不受支持的在线服务地址：$host")
            chain.proceed(request)
        }.build()
    private val locks = ConcurrentHashMap<String, Any>()
    private var musicBrainzAt = 0L
    private var lyricsAt = 0L
    private fun folder(context: Context) = File(context.filesDir, "online-media").apply { mkdirs() }
    fun clearCache(context: Context) {
        val images=File(context.filesDir,"artwork").canonicalFile
        folder(context).listFiles()?.filter { it.isFile }?.forEach { file ->
            if(file.name.startsWith("art-")) {
                val path=runCatching { JSONObject(file.readText()).optString("path") }.getOrNull()
                if(!path.isNullOrBlank()) {
                    val image=File(path).canonicalFile
                    if(image.parentFile==images) { LibraryDatabase.get(context).library().clearArtworkPath(path); if(image.exists()) check(image.delete()) }
                }
            }
            check(file.delete())
        }
    }
    private fun lyricKey(q: TrackIdentity) = MusicSource.digest("${OnlineMatch.normalized(q.title)}|${OnlineMatch.normalized(q.artist)}|${OnlineMatch.normalized(q.album)}|${Math.round(q.durationMs / 1000.0)}")
    fun identify(context: Context, item: MediaItem, durationMs: Long): IdentifiedTrack {
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val path = extras?.getString("audio_path")
        val start = extras?.getLong("track_start_us") ?: item.clippingConfiguration.startPositionMs * 1000
        val stored = if(path != null && !path.startsWith("content:")) LibraryDatabase.get(context).library()
            .fileTracks(MusicSource.key(), path).minByOrNull { kotlin.math.abs(it.startUs - start) }?.takeIf { kotlin.math.abs(it.startUs - start) < 1000 } else null
        val identity = if(stored != null) TrackIdentity.from(stored.title, stored.artist, stored.album, stored.durationMs.takeIf { it > 0 } ?: durationMs)
            else TrackIdentity.from(metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(), metadata.albumTitle?.toString().orEmpty(), durationMs)
        return IdentifiedTrack(identity, stored?.albumKey, extras?.getString("lyrics_path") ?: stored?.lyricsPath,
            if(extras?.getString("lyrics_path") != null) extras.getLong("lyrics_offset") else stored?.lyricsOffsetMs ?: 0)
    }
    @Synchronized private fun pace(musicBrainz: Boolean, scope: LookupScope) {
        val last = if(musicBrainz) musicBrainzAt else lyricsAt
        val delay = (if(musicBrainz) 1100 else 400) - (SystemClock.elapsedRealtime() - last)
        if(delay > 0) Thread.sleep(delay)
        scope.check()
        if(musicBrainz) musicBrainzAt = SystemClock.elapsedRealtime() else lyricsAt = SystemClock.elapsedRealtime()
    }
    private fun request(url: HttpUrl, scope: LookupScope, limit: Int, missing: Boolean = true): ByteArray? {
        scope.check()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "NYQOVA/0.2.1 (Android TV; local development)").build())
        scope.attach(call)
        try {
            call.execute().use { response ->
                if(missing && response.code == 404) return null
                if(!response.isSuccessful) throw IOException(if(response.code in setOf(429, 503)) "在线服务暂时繁忙，请稍后重试" else "在线服务 HTTP ${response.code}")
                val body = response.body ?: throw IOException("在线服务没有返回内容")
                if(body.contentLength() > limit) throw IOException("在线返回内容过大")
                return body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while(true) {
                        scope.check(); val n = input.read(buffer); if(n < 0) break
                        if(out.size() + n > limit) throw IOException("在线返回内容过大")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
        } finally { scope.detach(call) }
    }
    private fun write(file: File, json: JSONObject) {
        val temp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        check(temp.renameTo(file)) { "在线内容缓存写入失败" }
    }
    fun cachedLyrics(context: Context, q: TrackIdentity): LyricsResult? {
        val file = File(folder(context), "lyrics-${lyricKey(q)}.json")
        val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        if(json.optBoolean("missing")) return if(System.currentTimeMillis() - json.optLong("at") < 3600000) LyricsResult(null, cached = true) else null
        return json.optJSONObject("record")?.let {
            val record=LyricCandidate.parse(it); val rows=json.optJSONArray("candidates")
            LyricsResult(record,if(rows==null) listOf(record) else (0 until rows.length()).map { index -> LyricCandidate.parse(rows.getJSONObject(index)) },true,json.optBoolean("automatic"))
        }
    }
    fun rememberLyrics(context: Context, q: TrackIdentity, candidate: LyricCandidate, candidates: List<LyricCandidate> = emptyList(), automatic: Boolean=false) {
        val choices=(listOf(candidate)+(if(candidates.isNotEmpty()) candidates else cachedLyrics(context,q)?.candidates.orEmpty()))
            .distinctBy { "${it.provider}:${it.id}" }.take(20)
        write(File(folder(context), "lyrics-${lyricKey(q)}.json"), JSONObject().put("at", System.currentTimeMillis()).put("record", candidate.json())
            .put("automatic",automatic).put("candidates",JSONArray().apply { choices.forEach { put(it.json()) } }))
    }
    fun chooseLyrics(context: Context,q: TrackIdentity,result: LyricsResult): LyricsResult {
        val selected=result.selectFirst()
        selected.candidate?.let { record -> runCatching { rememberLyrics(context,q,record,selected.candidates,selected.automatic) } }
        return selected
    }
    fun lyrics(context: Context,q: TrackIdentity,scope: LookupScope=LookupScope(),force: Boolean=false): LyricsResult {
        val mode=com.nyqova.app.settings.TvPreferences(context).text("lyrics_source","auto")
        if(!force) cachedLyrics(context,q)?.takeIf { result -> result.candidate?.let { mode=="auto" || (mode=="lrclib" && it.provider=="LRCLIB") || (mode=="lrcapi" && it.provider=="LrcAPI") }==true }?.let { return it }
        var primary=LyricsResult(null); var failure: Exception?=null
        if(mode!="lrcapi") try { primary=lrclibLyrics(context,q,scope,true); if(primary.candidate!=null) return chooseLyrics(context,q,primary) }
        catch(error: Exception) { scope.check(); failure=error }
        if(mode!="lrclib") try {
            val backup=lrcApiLyrics(q,scope)
            if(backup.candidate!=null) return chooseLyrics(context,q,backup.copy(candidates=(primary.candidates+backup.candidates).distinctBy { "${it.provider}:${it.id}" }))
            primary=primary.copy(candidates=(primary.candidates+backup.candidates).distinctBy { "${it.provider}:${it.id}" })
        } catch(error: Exception) { scope.check(); failure=error }
        if(primary.candidates.isEmpty() && failure!=null) throw failure
        scope.check()
        return chooseLyrics(context,q,primary)
    }
    private fun lrcApiLyrics(q: TrackIdentity,scope: LookupScope): LyricsResult {
        if(q.title.isBlank() || q.artist.isBlank()) return LyricsResult(null)
        pace(false,scope)
        val url="https://api.lrc.cx/jsonapi".toHttpUrl().newBuilder().addQueryParameter("title",q.title).addQueryParameter("artist",q.artist).addQueryParameter("album",q.album).build()
        val bytes=request(url,scope,2*1024*1024) ?: return LyricsResult(null)
        val json=JSONArray(String(bytes,Charsets.UTF_8)); val rows=mutableListOf<LyricCandidate>()
        for(i in 0 until minOf(json.length(),30)) {
            val row=json.getJSONObject(i); val lrc=row.optString("lrc",row.optString("lyrics"))
            if(lrc.isBlank() || lrc.trimStart().startsWith("<")) continue
            val duration=(row.optDouble("duration",0.0)*1000).toLong()
            val doc=LrcParser.parse(lrc)
            rows+=LyricCandidate(row.optString("id").hashCode().toLong(),row.optString("title"),row.optString("artist"),row.optString("album"),duration,
                if(doc.lines.isNotEmpty()) lrc else "",if(doc.lines.isEmpty()) lrc else "",false,"LrcAPI")
        }
        val best=rows.filter { OnlineMatch.confident(q,it.title,it.artist,it.durationMs) }.sortedBy { it.synced.isBlank() }.firstOrNull()
        return LyricsResult(best,rows.take(20))
    }
    private fun lrclibLyrics(context: Context, q: TrackIdentity, scope: LookupScope, force: Boolean): LyricsResult {
        val key = lyricKey(q)
        synchronized(locks.getOrPut("lyrics:$key") { Any() }) {
            if(!force) cachedLyrics(context, q)?.let { return it }
            scope.check()
            if(q.title.isBlank()) return LyricsResult(null)
            val found = linkedMapOf<Long, LyricCandidate>()
            if(q.artist.isNotBlank()) {
                val signatures = listOf(Triple(q.title, q.artist, q.album), Triple(q.title, q.artist, ""),
                    Triple(ChineseKeys.simplified(q.title), ChineseKeys.simplified(q.artist), ""),
                    Triple(ChineseKeys.traditional(q.title), ChineseKeys.traditional(q.artist), "")).distinct()
                for((title, artist, album) in signatures) {
                    val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder().addQueryParameter("track_name", title).addQueryParameter("artist_name", artist)
                        .apply { if(album.isNotBlank()) addQueryParameter("album_name", album); if(q.durationMs in 1000..3600000) addQueryParameter("duration", (q.durationMs / 1000.0).toString()) }.build()
                    pace(false, scope)
                    request(url, scope, 2 * 1024 * 1024)?.let { bytes ->
                        val record = LyricCandidate.parse(JSONObject(String(bytes, Charsets.UTF_8)))
                        if(record.instrumental || record.synced.isNotBlank() || record.plain.isNotBlank()) {
                            found[record.id] = record
                            if(OnlineMatch.confident(q, record.title, record.artist, record.durationMs)) {
                                return LyricsResult(record,found.values.toList())
                            }
                        }
                    }
                }
            }
            val searches = listOf(true, false).filter { !it || q.artist.isNotBlank() }
            for(withArtist in searches) {
                scope.check(); pace(false, scope)
                val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("track_name", q.title)
                    .apply { if(withArtist) addQueryParameter("artist_name", q.artist) }.build()
                val bytes = request(url, scope, 2 * 1024 * 1024) ?: continue
                val rows = JSONArray(String(bytes, Charsets.UTF_8))
                for(i in 0 until minOf(rows.length(), 100)) {
                    val record = LyricCandidate.parse(rows.getJSONObject(i))
                    if(record.instrumental || record.synced.isNotBlank() || record.plain.isNotBlank()) found[record.id] = record
                }
                val best = found.values.filter { OnlineMatch.confident(q, it.title, it.artist, it.durationMs) }
                    .sortedWith(compareBy<LyricCandidate> { it.synced.isBlank() }.thenBy { kotlin.math.abs(it.durationMs - q.durationMs) }).firstOrNull()
                if(best != null) return LyricsResult(best,found.values.toList())
            }
            val candidates = found.values.sortedWith(compareBy<LyricCandidate> { OnlineMatch.normalized(it.title) != OnlineMatch.normalized(q.title) }
                .thenBy { kotlin.math.abs(it.durationMs - q.durationMs) }).take(20)
            if(candidates.isEmpty()) write(File(folder(context), "lyrics-$key.json"), JSONObject().put("at", System.currentTimeMillis()).put("missing", true))
            return LyricsResult(null, candidates)
        }
    }
    private fun artKey(album: String, artist: String) = MusicSource.digest("${OnlineMatch.normalized(album)}|${OnlineMatch.normalized(artist)}")
    fun cachedArtwork(context: Context, album: String, artist: String): String? {
        val json = runCatching { JSONObject(File(folder(context), "art-${artKey(album, artist)}.json").readText()) }.getOrNull()
        val mode=com.nyqova.app.settings.TvPreferences(context).text("art_source","auto")
        val backup=json?.optString("provider")=="LrcAPI"
        if((mode=="lrcapi" && !backup) || (mode=="musicbrainz" && backup)) return null
        return json?.optString("path")?.takeIf { it.isNotBlank() && File(it).exists() }
    }
    fun artwork(context: Context,album: String,artist: String,scope: LookupScope=LookupScope()): String? {
        val mode=com.nyqova.app.settings.TvPreferences(context).text("art_source","auto")
        cachedArtwork(context,album,artist)?.let { return it }
        if(!com.nyqova.app.settings.TvPreferences(context).enabled("online_art") || album.isBlank() || artist.isBlank()) return null
        if(mode!="lrcapi") try { primaryArtwork(context,album,artist,scope)?.let { return it } } catch(error: Exception) { scope.check(); if(mode=="musicbrainz") throw error }
        if(mode=="musicbrainz") return null
        val query="https://api.lrc.cx/jsonapi".toHttpUrl().newBuilder().addQueryParameter("album",album).addQueryParameter("artist",artist).build()
        pace(false,scope)
        val records=request(query,scope,2*1024*1024)?.let { JSONArray(String(it,Charsets.UTF_8)) } ?: return null
        val matched=(0 until minOf(records.length(),30)).map { records.getJSONObject(it) }.firstOrNull {
            OnlineMatch.normalized(it.optString("album"))==OnlineMatch.normalized(album) && OnlineMatch.normalized(it.optString("artist"))==OnlineMatch.normalized(artist)
        } ?: return null
        val url="https://api.lrc.cx/cover".toHttpUrl().newBuilder().addQueryParameter("album",album).addQueryParameter("artist",artist).addQueryParameter("title",matched.optString("title")).build()
        pace(false,scope)
        val image=request(url,scope,5*1024*1024) ?: return null
        val key=artKey(album,artist)
        val path=ArtworkCache.store(context,"online-backup:$key",image) ?: return null
        write(File(folder(context),"art-$key.json"),JSONObject().put("at",System.currentTimeMillis()).put("path",path).put("provider","LrcAPI"))
        return path
    }
    private fun primaryArtwork(context: Context, album: String, artist: String, scope: LookupScope): String? {
        if(!com.nyqova.app.settings.TvPreferences(context).enabled("online_art")) return cachedArtwork(context,album,artist)
        if(album.isBlank() || album in setOf("散曲", "未分类音乐") || artist.isBlank()) return null
        val key = artKey(album, artist)
        synchronized(locks.getOrPut("art:$key") { Any() }) {
            cachedArtwork(context, album, artist)?.let { return it }
            val file = File(folder(context), "art-$key.json")
            val miss = runCatching { JSONObject(file.readText()) }.getOrNull()
            if(miss?.optBoolean("missing") == true && System.currentTimeMillis() - miss.optLong("at") < 86400000) return null
            fun escaped(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
            pace(true, scope)
            val url = "https://musicbrainz.org/ws/2/release/".toHttpUrl().newBuilder()
                .addQueryParameter("query", "release:\"${escaped(album)}\" AND artist:\"${escaped(ChineseKeys.traditional(artist))}\"")
                .addQueryParameter("fmt", "json").addQueryParameter("limit", "5").build()
            val bytes = request(url, scope, 1024 * 1024) ?: return null
            val releases = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("releases") ?: JSONArray()
            val attempted = mutableSetOf<String>()
            for(i in 0 until minOf(5, releases.length())) {
                scope.check()
                val release = releases.getJSONObject(i)
                if(release.optInt("score") < 95 || OnlineMatch.normalized(release.optString("title")) != OnlineMatch.normalized(album)) continue
                val id = release.optString("id")
                if(!Regex("[0-9a-f-]{36}").matches(id)) continue
                val group = release.optJSONObject("release-group")?.optString("id").orEmpty()
                val endpoints = buildList {
                    if(Regex("[0-9a-f-]{36}").matches(group)) add("release-group/$group")
                    add("release/$id")
                }
                for(endpoint in endpoints) {
                    if(!attempted.add(endpoint)) continue
                    val image = request("https://coverartarchive.org/$endpoint/front-500".toHttpUrl(), scope, 5 * 1024 * 1024) ?: continue
                    val path = ArtworkCache.store(context, "online:$key", image) ?: continue
                    write(file, JSONObject().put("at", System.currentTimeMillis()).put("path", path).put("release", id)); return path
                }
            }
            write(file, JSONObject().put("at", System.currentTimeMillis()).put("missing", true)); return null
        }
    }
}
