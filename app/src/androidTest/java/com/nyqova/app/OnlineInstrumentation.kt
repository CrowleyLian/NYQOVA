package com.nyqova.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.MediaController
import com.nyqova.app.library.LibraryDatabase
import com.nyqova.app.library.LyricsActivity
import com.nyqova.app.online.*
import com.nyqova.app.storage.MusicSource
import com.nyqova.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class OnlineInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); start() }
    private fun <T> ui(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun await(label: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 45000
        while(System.currentTimeMillis() < end) { if(check()) return; Thread.sleep(200) }
        error("Timed out: $label")
    }
    override fun onStart() {
        val report = mutableListOf<String>()
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            report += "runtime: ${if(android.os.Process.is64Bit()) "64" else "32"}-bit"
            val cacheOnly = args.getString("cacheOnly") == "true"
            val en = TrackIdentity("Back In Black", "AC/DC", "Back In Black", 255000)
            val cn = TrackIdentity("伤痕", "林忆莲", "Love, Sandy", 367000)
            val scope = LookupScope().also { if(cacheOnly) it.cancel() }
            val english = OnlineMedia.lyrics(targetContext, en, scope, force = !cacheOnly)
            check(english.candidate?.document()?.lines?.isNotEmpty() == true)
            report += "English synced lyrics: PASS (LRCLIB ${english.candidate!!.id}, cached=${english.cached})"
            val chinese = OnlineMedia.lyrics(targetContext, cn, scope, force = !cacheOnly)
            check(chinese.candidate?.document()?.lines?.isNotEmpty() == true)
            check(OnlineMatch.confident(cn, chinese.candidate!!.title, chinese.candidate!!.artist, chinese.candidate!!.durationMs))
            report += "Chinese simplified/traditional lyrics: PASS (LRCLIB ${chinese.candidate!!.id}, cached=${chinese.cached})"
            val offline = OnlineMedia.lyrics(targetContext, cn, LookupScope().also { it.cancel() })
            check(offline.cached && offline.candidate?.id == chinese.candidate!!.id)
            report += "Lyrics persistent cache without network: PASS"
            val artwork = if(cacheOnly) OnlineMedia.cachedArtwork(targetContext, "Love, Sandy", "林忆莲")
                else OnlineMedia.artwork(targetContext, "Love, Sandy", "林忆莲")
            check(artwork != null && File(artwork).length() > 0)
            report += "MusicBrainz/Cover Art Archive cover: PASS (cached=$cacheOnly)"
            val controllerField = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            await("controller") { ui { controllerField.get(activity) != null } }
            val controller = ui { controllerField.get(activity) as MediaController }
            val dao = LibraryDatabase.get(targetContext).library()
            val rows = dao.albums(MusicSource.key()).flatMap { dao.albumTracks(it.albumKey) }
            val track = rows.first { OnlineMatch.normalized(it.title) == OnlineMatch.normalized(cn.title) && OnlineMatch.normalized(it.artist) == OnlineMatch.normalized(cn.artist) }
            val album = dao.albumTracks(track.albumKey)
            val start = MainActivity::class.java.getDeclaredMethod("loadLibraryAlbum", String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            ui { start.invoke(activity, track.albumKey, album.indexOfFirst { it.id == track.id }) }
            await("Chinese track playback") { ui { controller.isPlaying } }
            val inline = MainActivity::class.java.getDeclaredField("inlineLyrics").apply { isAccessible = true }
            await("automatic lyrics") { ui { (inline.get(activity) as? LyricsDocument)?.lines?.isNotEmpty() == true } }
            report += "Automatic lyrics during actual NAS playback: PASS"
            val lyrics = startActivitySync(Intent(targetContext, LyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val docField = LyricsActivity::class.java.getDeclaredField("document").apply { isAccessible = true }
            await("lyrics page") { ui { (docField.get(lyrics) as? LyricsDocument)?.lines?.isNotEmpty() == true } }
            val doc = ui { docField.get(lyrics) as LyricsDocument }
            val target = doc.lines.first { it.text.isNotBlank() && it.timeMs > 1000 }.timeMs + 500
            ui { controller.seekTo(target) }
            val activeField = LyricsActivity::class.java.getDeclaredField("active").apply { isAccessible = true }
            await("online lyrics highlight") { ui { activeField.getInt(lyrics) == doc.indexAt(controller.currentPosition) && activeField.getInt(lyrics) >= 0 } }
            report += "Online LRC display and seek synchronization: PASS"
            ui { controller.pause(); lyrics.finish() }
            finish(0, Bundle().apply { putString("stream", report.joinToString("\n", postfix = "\n")); putString("result", "PASS") })
        } catch(error: Throwable) {
            val cause = error.cause ?: error
            finish(1, Bundle().apply { putString("stream", report.joinToString("\n") + "\nFAIL: ${cause.javaClass.simpleName}: ${cause.message}\n"); putString("result", "FAIL") })
        }
    }
}
