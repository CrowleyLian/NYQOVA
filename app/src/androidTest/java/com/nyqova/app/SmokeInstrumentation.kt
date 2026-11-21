package com.nyqova.app

import android.app.Instrumentation
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.MediaController
import com.nyqova.app.storage.MusicSource
import com.nyqova.app.storage.SmbSource
import com.nyqova.app.storage.Song
import com.nyqova.app.storage.ConnectionStore
import com.nyqova.app.storage.SourceDiscovery
import com.nyqova.app.library.LibraryScanner
import com.nyqova.app.library.LibraryDatabase
import com.nyqova.app.library.LibraryActivity
import com.nyqova.app.library.ArtworkCache
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Run explicitly with NAS parameters. No credentials or music samples are packaged with the test. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmokeInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); start() }
    private fun <T> ui(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        runOnMainSync { value.set(runCatching(action)) }
        return value.get().getOrThrow()
    }
    private fun await(label: String, timeoutMs: Long = 30000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while(System.currentTimeMillis() < deadline) {
            if(check()) return
            Thread.sleep(200)
        }
        error("Timed out: $label")
    }
    override fun onStart() {
        val report = mutableListOf<String>()
        try {
            report += "runtime: ${if(android.os.Process.is64Bit()) "64" else "32"}-bit ARM"
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val field = MainActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }
            await("controller") { ui { field.get(activity) != null } }
            val controller = ui { field.get(activity) as MediaController }
            if(args.getString("restoreOnly") != "true") ui {
                val method = MainActivity::class.java.getDeclaredMethod("connectionDialog").apply { isAccessible = true }
                val dialog = method.invoke(activity) as AlertDialog
                val inputs = mutableListOf<EditText>()
                fun collect(view: View) {
                    if(view is EditText) inputs += view
                    if(view is ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
                }
                collect(dialog.window!!.decorView)
                check(inputs.size == 4)
                inputs[0].setText("invalid-address")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(dialog.isShowing && inputs[0].error != null)
                val sourceUrl = args.getString("url")?.takeIf { it.isNotBlank() }
                    ?: error("Pass an explicit NAS address with -e url; no default server is configured")
                inputs[0].setText(sourceUrl)
                inputs[1].setText(args.getString("username", ""))
                inputs[2].setText(args.getString("password", ""))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                check(!dialog.isShowing)
            }
            report += "connection-form-and-validation: PASS"
            val saved = ConnectionStore.load(targetContext) ?: error("Missing saved connection")
            check(saved.url.trimEnd('/') == MusicSource.label().trimEnd('/'))
            check(saved.password == MusicSource.password)
            check(saved.username == MusicSource.username)
            val encryptedPrefs = targetContext.getSharedPreferences("connection_secure", android.content.Context.MODE_PRIVATE)
            if(saved.password.isNotEmpty()) check(!encryptedPrefs.all.toString().contains(saved.password))
            report += "encrypted-connection-restore: PASS"
            if(args.getString("discover") == "true") {
                val found = java.util.Collections.synchronizedList(mutableListOf<String>())
                val discovery = ui { SourceDiscovery(targetContext, { found += it.url }, {}).also { it.start() } }
                try {
                    await("NAS discovery", 25000) { synchronized(found) {
                        found.any { it.trimEnd('/') == saved.url.trimEnd('/') }
                    } }
                    report += "local-network-discovery: PASS"
                } finally { ui { discovery.close() } }
            }
            val root = MusicSource.list("")
            check(root.isNotEmpty()); report += "directory-list: PASS (${root.size} items)"
            val load = MainActivity::class.java.getDeclaredMethod("loadFolder", String::class.java).apply { isAccessible = true }
            ui { load.invoke(activity, "") }
            val single = root.firstOrNull { SmbSource.isAudio(it.path) && it.path.endsWith(".flac", true) }
                ?: root.first { SmbSource.isAudio(it.path) }
            val startQueue = MainActivity::class.java.getDeclaredMethod("startQueue", List::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            ui { startQueue.invoke(activity, listOf(Song(single.name, single.path)), 0) }
            await("single audio playback") { ui { controller.isPlaying && controller.currentPosition > 1500 } }
            report += "single-audio-playback: PASS"
            val cue = root.filter { it.directory }.asSequence().flatMap { MusicSource.list(it.path).asSequence() }
                .first { SmbSource.isCue(it.path) }
            val songs = MusicSource.cue(cue.path)
            check(songs.size >= 3 && songs[1].startUs > 0)
            report += "cue-parse: PASS (${songs.size} tracks)"
            ui { startQueue.invoke(activity, songs, 1) }
            await("cue second track") { ui { controller.currentMediaItemIndex == 1 && controller.isPlaying && controller.currentPosition > 1500 } }
            report += "cue-offset-playback: PASS"
            ui { controller.seekTo(60000) }
            await("seek") { ui { controller.isPlaying && controller.currentPosition >= 60000 } }
            report += "random-seek: PASS"
            ui { controller.seekTo(controller.duration - 1800) }
            await("automatic cue transition") { ui { controller.currentMediaItemIndex == 2 && controller.isPlaying && controller.currentPosition > 1000 } }
            report += "automatic-next-cue-track: PASS"
            ui { controller.seekToPreviousMediaItem() }
            await("previous") { ui { controller.currentMediaItemIndex == 1 && controller.isPlaying } }
            ui { controller.pause() }
            await("pause") { ui { !controller.isPlaying } }
            report += "previous-and-pause: PASS"
            if(args.getString("library") == "true") {
                val scanner = LibraryScanner(targetContext)
                val first = scanner.scan(force = true)
                check(first.tracks >= songs.size && first.albums > 0)
                report += "library-full-scan: PASS (${first.albums} albums, ${first.tracks} tracks, ${first.warnings} metadata warnings)"
                val second = LibraryScanner(targetContext).scan()
                check(second.changedDirectories == 0 && second.reusedDirectories > 0 && second.tracks == first.tracks)
                report += "library-incremental-reuse: PASS (${second.reusedDirectories} directories)"
                val dao = LibraryDatabase.get(targetContext).library()
                val before = dao.albums(MusicSource.key()).sumOf { it.trackCount }
                val indexed = dao.albums(MusicSource.key()).flatMap { dao.albumTracks(it.albumKey) }
                report += "local-artwork: ${indexed.count { it.artworkPath != null }} tracks; local-lrc: ${indexed.count { it.lyricsPath != null }} tracks"
                val interrupted = LibraryScanner(targetContext, java.util.concurrent.atomic.AtomicBoolean(true))
                check(runCatching { interrupted.scan() }.exceptionOrNull() is java.util.concurrent.CancellationException)
                check(dao.albums(MusicSource.key()).sumOf { it.trackCount } == before)
                report += "cancel-preserves-library: PASS"
                val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                check(File(ArtworkCache.store(targetContext, "test-fixture", bytes)!!).exists())
                report += "artwork-cache-fixture: PASS"
                ui {
                    val pane=MainActivity::class.java.getDeclaredField("library").apply { isAccessible=true }.get(activity)
                    pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,"albums","专辑")
                    MainActivity::class.java.getDeclaredMethod("showPage",String::class.java).apply { isAccessible=true }.invoke(activity,"library")
                }
                Thread.sleep(1500)
                val wall = File(targetContext.getExternalFilesDir(null), "library-wall.png")
                uiAutomation.takeScreenshot()?.let { picture -> wall.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; picture.recycle() }
                val chosen = dao.albums(MusicSource.key()).first()
                val expected = dao.albumTracks(chosen.albumKey).first()
                ui { activity.startActivity(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("library_album", chosen.albumKey).putExtra("track_index", 0)) }
                await("album navigation playback") { ui { controller.isPlaying && controller.currentMediaItem?.mediaMetadata?.title.toString() == expected.title } }
                ui { controller.pause() }
                report += "album-navigation-playback: PASS"
            }
            if(args.getString("libraryPersisted") == "true") {
                val dao = LibraryDatabase.get(targetContext).library()
                val albums = dao.albums(MusicSource.key())
                check(albums.isNotEmpty())
                val indexed = albums.flatMap { dao.albumTracks(it.albumKey) }
                val withLyrics = indexed.firstOrNull { it.lyricsPath != null }
                report += "library-cold-restore: PASS (${albums.size} albums, ${indexed.size} tracks)"
                if(withLyrics != null) {
                    val doc = com.nyqova.core.LrcParser.parse(com.nyqova.core.MusicText.decode(MusicSource.readSmall(withLyrics.lyricsPath!!)))
                    check(doc.lines.isNotEmpty() || doc.plainText.isNotBlank())
                    ui { startQueue.invoke(activity, listOf(withLyrics.song()), 0) }
                    await("library lyric track") { ui { controller.isPlaying } }
                    if(doc.lines.size > 2) ui { controller.seekTo((doc.lines[1].timeMs - withLyrics.lyricsOffsetMs + 1000).coerceAtLeast(0)) }
                    val lyrics = startActivitySync(Intent(targetContext, com.nyqova.app.library.LyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Thread.sleep(2000)
                    val screen = File(targetContext.getExternalFilesDir(null), "lyrics-local.png")
                    uiAutomation.takeScreenshot()?.let { picture -> screen.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; picture.recycle() }
                    ui { lyrics.finish(); controller.pause() }
                    report += "real-local-lyrics: PASS (${doc.lines.size} timed lines)"
                } else {
                    report += "real-local-lyrics: NOT AVAILABLE in this source"
                    val lyrics = startActivitySync(Intent(targetContext, com.nyqova.app.library.LyricsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val playerField = lyrics.javaClass.getDeclaredField("player").apply { isAccessible = true }
                    await("lyric controller") { ui { playerField.get(lyrics) != null } }
                    ui { controller.seekTo(6000) }
                    val fixture = com.nyqova.core.LrcParser.parse("[00:00.000]测试歌词 · 第一行\n[00:05.000]测试歌词 · 第二行\n[00:10.000]测试歌词 · 第三行")
                    val display = lyrics.javaClass.getDeclaredMethod("displayDocument", com.nyqova.core.LyricsDocument::class.java).apply { isAccessible = true }
                    ui { display.invoke(lyrics, fixture) }
                    val activeField = lyrics.javaClass.getDeclaredField("active").apply { isAccessible = true }
                    await("lyric highlight") { ui { activeField.getInt(lyrics) == 1 } }
                    waitForIdleSync()
                    Thread.sleep(600)
                    val linesField = lyrics.javaClass.getDeclaredField("lines").apply { isAccessible = true }
                    check(ui { (linesField.get(lyrics) as android.widget.ListView).childCount > 0 })
                    val screen = File(targetContext.getExternalFilesDir(null), "lyrics-fixture.png")
                    uiAutomation.takeScreenshot()?.let { picture -> screen.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; picture.recycle() }
                    ui { lyrics.finish() }
                    report += "lyrics-ui-sync-fixture: PASS"
                }
            }
            val cueLoad = MainActivity::class.java.getDeclaredMethod("loadCue", String::class.java).apply { isAccessible = true }
            ui { cueLoad.invoke(activity, cue.path) }
            Thread.sleep(1500)
            val file = File(targetContext.getExternalFilesDir(null), "smoke-${if(MusicSource.webDavBase != null) "webdav" else "smb"}.png")
            uiAutomation.takeScreenshot()?.let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            report += "screenshot: ${file.name}"
            finish(0, Bundle().apply { putString("stream", report.joinToString("\n", postfix = "\n")); putString("result", "PASS") })
        } catch(error: Throwable) {
            val cause = error.cause ?: error
            finish(1, Bundle().apply { putString("stream", report.joinToString("\n") + "\nFAIL: ${cause.javaClass.simpleName}: ${cause.message}"); putString("result", "FAIL") })
        }
    }
}
