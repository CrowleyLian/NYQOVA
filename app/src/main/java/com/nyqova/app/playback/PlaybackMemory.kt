package com.nyqova.app.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nyqova.app.settings.TvPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RememberedQueue(val source: String, val revision: String, val items: List<MediaItem>, val index: Int, val position: Long,
    val repeat: Int, val shuffle: Boolean)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackMemory {
    fun item(song: com.nyqova.app.storage.Song, source: String): MediaItem {
        val uri = if(song.path.startsWith("content:")) Uri.parse(song.path) else com.nyqova.app.storage.MusicSource.uri(song.path)
        return MediaItem.Builder().setMediaId("$uri#${song.startUs}").setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist.takeIf { it.isNotBlank() }).setAlbumTitle(song.album)
                .apply { song.artworkPath?.let { setArtworkUri(Uri.fromFile(File(it))) } }
                .setExtras(Bundle().apply {
                    putString("artwork_path", song.artworkPath); putString("lyrics_path", song.lyricsPath)
                    putLong("lyrics_offset", song.lyricsOffsetMs); putString("source_key", source)
                    putString("audio_path", song.path); putLong("track_start_us", song.startUs); putString("track_id", song.libraryId)
                }).build())
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(song.startUs / 1000)
                .apply { song.endUs?.let { setEndPositionMs(it / 1000) } }.build()).build()
    }
    private fun file(context: Context, name: String) = AtomicFile(File(context.filesDir, name))
    private fun write(file: AtomicFile, json: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch(e: Throwable) { file.failWrite(stream); throw e }
    }
    fun saveQueue(context: Context, revision: String, source: String, items: List<MediaItem>) {
        if(!TvPreferences(context).enabled("remember")) return
        val rows = JSONArray()
        items.forEach { item ->
            val m = item.mediaMetadata; val extras = m.extras
            rows.put(JSONObject().put("id", item.mediaId).put("uri", item.localConfiguration?.uri.toString())
                .put("title", m.title?.toString()).put("artist", m.artist?.toString()).put("album", m.albumTitle?.toString())
                .put("start", item.clippingConfiguration.startPositionMs).put("end", item.clippingConfiguration.endPositionMs)
                .put("art", extras?.getString("artwork_path")).put("lyrics", extras?.getString("lyrics_path"))
                .put("offset", extras?.getLong("lyrics_offset") ?: 0).put("path", extras?.getString("audio_path"))
                .put("us", extras?.getLong("track_start_us") ?: 0).put("track", extras?.getString("track_id")))
        }
        write(file(context, "playback-queue.json"), JSONObject().put("revision", revision).put("source", source).put("items", rows))
    }
    fun savePosition(context: Context, revision: String, index: Int, position: Long, repeat: Int, shuffle: Boolean) {
        if(!TvPreferences(context).enabled("remember")) return
        write(file(context, "playback-position.json"), JSONObject().put("revision", revision).put("index", index)
            .put("position", position.coerceAtLeast(0)).put("repeat", repeat).put("shuffle", shuffle))
    }
    fun clear(context: Context) { file(context, "playback-queue.json").delete(); file(context, "playback-position.json").delete() }
    fun load(context: Context): RememberedQueue? {
        if(!TvPreferences(context).enabled("remember")) return null
        val json = runCatching { file(context, "playback-queue.json").openRead().use { JSONObject(it.bufferedReader().readText()) } }.getOrNull() ?: return null
        val state = runCatching { file(context, "playback-position.json").openRead().use { JSONObject(it.bufferedReader().readText()) } }.getOrNull()
        val source = json.getString("source"); val revision = json.getString("revision")
        val rows = json.getJSONArray("items")
        val items = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun value(key: String) = if(row.isNull(key)) null else row.optString(key)
            MediaItem.Builder().setMediaId(row.getString("id")).setUri(row.getString("uri"))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(row.optLong("start"))
                    .setEndPositionMs(row.optLong("end", C.TIME_END_OF_SOURCE)).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(value("title")).setArtist(value("artist")).setAlbumTitle(value("album"))
                    .apply { value("art")?.let { setArtworkUri(Uri.fromFile(File(it))) } }
                    .setExtras(Bundle().apply {
                        putString("source_key", source); putString("audio_path", value("path")); putLong("track_start_us", row.optLong("us"))
                        putString("track_id", value("track")); putString("artwork_path", value("art")); putString("lyrics_path", value("lyrics")); putLong("lyrics_offset", row.optLong("offset"))
                    }).build()).build()
        }
        if(items.isEmpty()) return null
        val matches = state?.optString("revision") == revision
        return RememberedQueue(source, revision, items, if(matches) state!!.optInt("index").coerceIn(0, items.lastIndex) else 0,
            if(matches) state!!.optLong("position").coerceAtLeast(0) else 0, if(matches) state!!.optInt("repeat") else 0, matches && state!!.optBoolean("shuffle"))
    }
}
