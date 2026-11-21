package com.nyqova.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nyqova.app.library.*
import com.nyqova.app.online.*
import com.nyqova.app.playback.PlaybackMemory
import com.nyqova.app.settings.TvPreferences
import com.nyqova.app.storage.*
import com.nyqova.core.*
import com.nyqova.app.ui.Icon
import com.nyqova.app.ui.Lucide
import com.nyqova.app.ui.TvStyle
import android.app.AlertDialog
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Run on the connected device; fixtures use an isolated source and never write NAS files. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    private val report=mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { args=arguments ?: Bundle(); start() }
    private fun <T> ui(action: () -> T): T {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) return action()
        val result=AtomicReference<Result<T>>(); runOnMainSync { result.set(runCatching(action)) }; return result.get().getOrThrow()
    }
    private fun field(obj: Any,name: String): Any? = obj.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(obj)
    private fun call(obj: Any,name: String,vararg params: Pair<Class<*>,Any?>) = obj.javaClass.getDeclaredMethod(name,*params.map { it.first }.toTypedArray()).apply { isAccessible=true }.invoke(obj,*params.map { it.second }.toTypedArray())
    private fun await(label: String,timeout: Long=30000,check: () -> Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) { if(check()) return; Thread.sleep(150) }; error("Timed out: $label")
    }
    private fun views(root: View): List<View> = listOf(root)+(if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList())
    private fun button(activity: MainActivity,text: String): Button = ui { views(activity.window.decorView).filterIsInstance<Button>().first { (it.text.toString()==text || it.contentDescription?.toString()==text) && it.isShown } }
    private fun click(view: View) { ui { check(view.requestFocusFromTouch()) }; sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); waitForIdleSync() }
    private fun screenshot(name: String) {
        waitForIdleSync(); Thread.sleep(450)
        val bitmap=uiAutomation.takeScreenshot() ?: error("No screenshot")
        if(args.getString("require1080")=="true") check(bitmap.width==1920 && bitmap.height==1080) { "Screenshot ${bitmap.width}x${bitmap.height}" }
        File(targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    override fun onStart() {
        try {
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            report+="Runtime: ${if(android.os.Process.is64Bit()) "64" else "32"}-bit, API ${android.os.Build.VERSION.SDK_INT}"
            report+="Activity canvas: ${activity.resources.configuration.screenWidthDp} x ${activity.resources.configuration.screenHeightDp} dp"
            await("controller") { ui { field(activity,"controller")!=null } }
            val player=ui { field(activity,"controller") as MediaController }
            val db=LibraryDatabase.get(targetContext); val dao=db.collections(); val key=MusicSource.key()
            val stored=ConnectionStore.load(targetContext) ?: error("Saved NAS connection missing")
            check(stored.url.trimEnd('/')==MusicSource.label().trimEnd('/'))
            val tracks=dao.tracks(TrackScope(key).query()); check(tracks.isNotEmpty())
            if(args.getString("mode")!="resume") {
                ui { com.nyqova.app.library.LibraryIndexing.cancel() }
                await("automatic metadata work paused",30000) { !com.nyqova.app.library.LibraryIndexing.state.active }
            }
            report+="Upgrade retained encrypted NAS connection and library: ${tracks.size} tracks, database v${db.openHelper.readableDatabase.version}"
            if(args.getString("mode")=="scan") {
                ui { player.pause(); com.nyqova.app.library.LibraryIndexing.cancel() }
                await("background indexing paused",30000) { !com.nyqova.app.library.LibraryIndexing.state.active }
                val started=System.currentTimeMillis()
                var progressAt=0L
                var batches=0
                val result=LibraryScanner(targetContext).scan { message ->
                    batches++
                    check(db.library().albums(key).isNotEmpty())
                    val now=System.currentTimeMillis()
                    if(now-progressAt>=15000) { progressAt=now; sendStatus(0,Bundle().apply { putString("stream",message+"\n") }) }
                }
                check(result.tracks>0)
                val indexed=dao.tracks(TrackScope(key).query())
                val folderSizes=indexed.groupBy { it.folder }.mapValues { it.value.map { row -> row.path }.distinct().size }
                val jobs=db.audioIndex().files(key).associateBy { it.path }
                var mixedCoverChecks=0
                indexed.filter { it.cuePath==null && (folderSizes[it.folder] ?: 0)>1 }.forEach { row ->
                    val job=jobs[row.path] ?: return@forEach
                    val cover=job.coverPath ?: return@forEach
                    fun normalized(value: String)=value.lowercase().filter { it.isLetterOrDigit() }
                    if(normalized(row.album)==normalized(row.folder.substringAfterLast('/')) || normalized(row.album)==normalized(cover.substringAfterLast('/').substringBeforeLast('.'))) return@forEach
                    val cached=com.nyqova.app.library.ArtworkCache.cached(targetContext,"$key:$cover:${job.coverModified}:${job.coverSize}") ?: return@forEach
                    check(row.artworkPath!=cached) { "Unrelated folder cover assigned to ${row.title}" }; mixedCoverChecks++
                }
                report+="Mixed-folder artwork isolation: $mixedCoverChecks raw tracks do not inherit an arbitrary shared cover: PASS"
                report+="Fast NAS index: ${result.albums} albums, ${result.tracks} tracks in ${(System.currentTimeMillis()-started)/1000}s; $batches committed folder batches visible before completion; ${result.pendingMetadata} files await metadata: PASS"
                val enrichment=LibraryScanner(targetContext)
                val before=db.audioIndex().pendingCount(key)
                var processed=0
                runCatching { enrichment.enrich { count,_,_ -> processed=count; if(count>=3) enrichment.cancelled.set(true) } }
                check(processed>=3 || before==0)
                report+="Separate enrichment processed $processed files; cancellation retained playable index and ${db.audioIndex().pendingCount(key)} durable pending files: PASS"
                val pending=db.audioIndex().pendingCount(key)
                if(pending>0) {
                    ui { com.nyqova.app.library.LibraryIndexing.resume(targetContext) }
                    await("background enrichment resumes") { db.audioIndex().pendingCount(key)<pending }
                    screenshot("tv-scan-progress-1080.png")
                    ui { com.nyqova.app.library.LibraryIndexing.cancel() }
                    await("background enrichment stops") { !com.nyqova.app.library.LibraryIndexing.state.active }
                    check(dao.tracks(TrackScope(key).query()).size==result.tracks)
                    report+="Background resume/cancel leaves the song index playable while independent UI queries remain available: PASS"
                }
                ui { (field(activity,"library") as com.nyqova.app.ui.TvLibraryPane).refresh() }
            } else if(args.getString("mode")=="reliability") {
                discoveryChecks(activity); lyricFallbackChecks(activity)
            } else if(args.getString("mode")=="themes") {
                themeChecks(activity,player)
            } else if(args.getString("mode")=="current-track") {
                currentTrackChecks(activity,player)
            } else if(args.getString("mode")=="appearance") {
                appearanceChecks(activity)
            } else if(args.getString("mode")=="initials") {
                initialChecks(activity)
            } else if(args.getString("mode")=="remote") {
                remoteChecks(activity,player)
            } else if(args.getString("mode")=="search") {
                ui { player.pause() }; searchChecks(activity)
            } else if(args.getString("mode")=="counts") {
                countChecks(activity)
            } else if(args.getString("mode")=="codecs") {
                codecChecks(activity,player)
            } else if(args.getString("mode")=="codec-resume") {
                val expected=org.json.JSONObject(File(targetContext.getExternalFilesDir(null),"codec-resume.json").readText())
                await("APE CUE cold restore",60000) { ui { player.isPlaying && player.currentMediaItemIndex==expected.getInt("index") && player.currentMediaItem?.mediaId==expected.getString("media") && player.currentPosition>=expected.getLong("position")-1500 } }
                check(ui { player.currentMediaItem!!.clippingConfiguration.startPositionMs>0 })
                report+="Native APE+CUE queue and relative seek position survive force-stop and cold restart: PASS"
            } else if(args.getString("mode")=="fixes") {
                revisionChecks(activity,player)
            } else if(args.getString("mode")=="menus") {
                menuChecks(activity,player)
            } else if(args.getString("mode")=="interaction") {
                interactionChecks(activity,player)
            } else if(args.getString("mode")=="resume") {
                val expected=org.json.JSONObject(File(targetContext.getExternalFilesDir(null),"tv-resume-expected.json").readText())
                await("cold automatic resume") { ui { player.mediaItemCount==expected.getInt("count") && player.currentMediaItemIndex==expected.getInt("index") && player.currentMediaItem?.mediaId==expected.getString("media") && player.currentPosition>=expected.getLong("position")-1500 && player.isPlaying } }
                check(ui { player.currentMediaItem!!.clippingConfiguration.startPositionMs>0 && player.repeatMode==Player.REPEAT_MODE_ALL })
                report+="Cold process restart: queue, CUE index, relative seek position, repeat mode and automatic playback restored: PASS"
                val pending=expected.optInt("pending",0)
                if(pending>0) {
                    await("cold metadata queue resume") { db.audioIndex().pendingCount(key)<pending }
                    report+="Cold restart automatically resumes the persisted metadata queue: PASS"
                }
                ui { call(activity,"showPage",String::class.java to "player") }; screenshot("tv-resumed-1080.png")
            } else {
                ui { player.pause() }; collectionsContract(db)
                val doc=LrcParser.parse("[00:01.000]愛與夢\n[00:08.500]還有風聲")
                check(ChineseKeys.simplified(doc.lines[0].text)=="爱与梦" && doc.lines[1].timeMs==8500L && doc.indexAt(9000)==1)
                report+="Simplified Chinese display preserves lyric timestamps: PASS"
                if(args.getString("scan")=="true") {
                    val scan=LibraryScanner(targetContext).scan()
                    check(scan.tracks>=tracks.size)
                    report+="NAS metadata upgrade: ${scan.albums} albums, ${scan.tracks} tracks; genre/file size populated without changing song IDs"
                }
                val pane=ui { field(activity,"library")!! }; val recycler=ui { field(pane,"list") as RecyclerView }
                click(button(activity,"专辑"))
                await("album wall") { ui { recycler.adapter!!.itemCount>0 && recycler.layoutManager is GridLayoutManager } }
                check(ui { (recycler.layoutManager as GridLayoutManager).spanCount==5 })
                screenshot("tv-albums-1080.png")
                val firstCard=ui { recycler.findViewHolderForAdapterPosition(0)!!.itemView }
                click(firstCard)
                await("direct album track list") { ui { recycler.layoutManager !is GridLayoutManager && recycler.adapter!!.itemCount>0 } }
                val cueAlbum=tracks.filter { it.cuePath!=null }.groupBy { it.albumKey }.values.first()
                check(dao.tracks(TrackScope(key,"album",cueAlbum[0].albumKey).query()).all { it.cuePath!=null })
                screenshot("tv-album-tracks-1080.png")
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("back to album wall") { ui { recycler.layoutManager is GridLayoutManager && recycler.findViewHolderForAdapterPosition(0)?.itemView?.hasFocus()==true } }
                report+="1080p five-column album wall, D-pad album open, CUE logical tracks and Back focus restore: PASS"
                click(button(activity,"歌手")); await("artists") { ui { recycler.adapter!!.itemCount>0 } }; screenshot("tv-artists-1080.png")
                val artistIds=ui { val location=field(pane,"location")!!; field(location,"target").toString() }.let { target -> dao.tracks(TrackScope(key,"artist",target).query()).map { it.id }.toSet() }
                click(button(activity,"播放全部"))
                await("artist queue context") { ui { player.mediaItemCount==artistIds.size && player.mediaItemCount>0 && (0 until player.mediaItemCount).all { player.getMediaItemAt(it).mediaMetadata.extras?.getString("track_id") in artistIds } } }
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                report+="Artist Play All uses the selected artist scope: PASS"
                click(button(activity,"风格")); await("genres") { ui { recycler.adapter!!.itemCount>0 } }
                click(button(activity,"歌单")); await("playlists") { ui { recycler.adapter!!.itemCount>=1 } }; screenshot("tv-playlists-1080.png")
                val lyricTrack=dao.tracks(TrackScope(key).query()).firstOrNull { OnlineMatch.normalized(it.title).contains("伤痕") } ?: cueAlbum[1]
                val album=dao.tracks(TrackScope(key,"album",lyricTrack.albumKey).query())
                val index=album.indexOfFirst { it.id==lyricTrack.id }
                ui { call(activity,"startQueue",List::class.java to album.map { it.song() },Int::class.javaPrimitiveType!! to index) }
                await("NAS CUE playback") { ui { player.isPlaying && player.currentMediaItemIndex==index && player.currentPosition>1000 && player.mediaItemCount==album.size && player.currentMediaItem?.mediaMetadata?.extras?.getString("track_id")==lyricTrack.id } }
                val before=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }
                val liked=lyricTrack.id in dao.favorites(key,"track")
                dao.favorite(Favorite(key,"track",lyricTrack.id)); dao.unfavorite(key,"track",lyricTrack.id)
                if(liked) dao.favorite(Favorite(key,"track",lyricTrack.id))
                check(ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }==before)
                report+="Favorite changes do not mutate the active queue snapshot: PASS"
                await("online/cached lyrics",45000) { ui { field(activity,"inlineLyrics")!=null } }
                ui { player.seekTo(90000) }; await("relative seek") { ui { player.currentPosition>=90000 } }
                val seek=ui { field(activity,"progress") as View }; ui { seek.requestFocus() }
                val old=ui { player.currentPosition }; sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("D-pad jump") { ui { player.currentPosition>=old+9000 } }
                screenshot("tv-player-1080.png")
                ui { call(activity,"showQueue") }; screenshot("tv-queue-1080.png"); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click(button(activity,"设置")); screenshot("tv-settings-1080.png")
                sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                click(button(activity,"首页")); await("home cards") { ui { recycler.adapter!!.itemCount>=4 } }; screenshot("tv-home-1080.png")
                report+="NAS playback, simplified online lyrics, remote progress jump, queue drawer, settings and home: PASS"
                val seeded=cueAlbum.sortedBy { it.number }.take(4)
                ui { call(activity,"startQueue",List::class.java to seeded.map { it.song() },Int::class.javaPrimitiveType!! to 1) }
                await("resume seed") { ui { player.isPlaying && player.currentMediaItemIndex==1 && player.currentPosition>500 } }
                ui { player.seekTo(63000); player.repeatMode=Player.REPEAT_MODE_ALL; player.shuffleModeEnabled=false }
                await("resume checkpoint") { val memory=PlaybackMemory.load(targetContext); memory!=null && memory.index==1 && memory.position>=63000 && memory.repeat==Player.REPEAT_MODE_ALL }
                val expected=ui { org.json.JSONObject().put("count",player.mediaItemCount).put("index",player.currentMediaItemIndex).put("media",player.currentMediaItem!!.mediaId).put("position",player.currentPosition) }
                expected.put("pending",db.audioIndex().pendingCount(key))
                File(targetContext.getExternalFilesDir(null),"tv-resume-expected.json").writeText(expected.toString())
                report+="Durable queue checkpoint ready for external force-stop/relaunch test: PASS"
            }
            finish(0,Bundle().apply { putString("stream",report.joinToString("\n",postfix="\n")); putString("result","PASS") })
        } catch(error: Throwable) {
            val cause=error.cause ?: error
            finish(1,Bundle().apply { putString("stream",report.joinToString("\n")+"\nFAIL: ${cause.javaClass.simpleName}: ${cause.message}\n${cause.stackTrace.take(5).joinToString("\n")}"); putString("result","FAIL") })
        }
    }
    private fun discoveryChecks(activity: MainActivity) {
        val release=java.util.concurrent.CountDownLatch(1); val entered=java.util.concurrent.CountDownLatch(1)
        val attempts=java.util.concurrent.atomic.AtomicInteger(); val completions=java.util.concurrent.atomic.AtomicInteger(); val discovered=java.util.concurrent.atomic.AtomicInteger()
        val sessions=mutableListOf<SourceDiscovery>()
        try {
            repeat(3) {
                val createdAt=android.os.SystemClock.elapsedRealtime()
                val discovery=ui { SourceDiscovery(activity,{
                    check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()); discovered.incrementAndGet()
                },{ check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()); completions.incrementAndGet() },
                    durationMs=350,nsdProvider={
                        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()); attempts.incrementAndGet(); entered.countDown(); release.await(); null
                    },addressProvider={ "192.168.7.33" },probeOverride={ host,port ->
                        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
                        if(host=="192.168.7.42" && port==8888) DiscoveredSource("http://$host:$port/","测试 WebDAV") else null
                    }).also { sessions+=it; it.start(); it.start() } }
                check(android.os.SystemClock.elapsedRealtime()-createdAt<1000)
                check(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
                val heartbeat=java.util.concurrent.CountDownLatch(1); ui { android.os.Handler(android.os.Looper.getMainLooper()).post { heartbeat.countDown() } }
                check(heartbeat.await(500,java.util.concurrent.TimeUnit.MILLISECONDS))
                await("discovery bounded finish",3000) { completions.get()==it+1 }
                discovery.close()
            }
            check(attempts.get()==1 && discovered.get()==3)
            val cancelledDone=java.util.concurrent.atomic.AtomicInteger()
            val cancelled=ui { SourceDiscovery(activity,{}, { cancelledDone.incrementAndGet() },durationMs=150,addressProvider={null}).also { it.start(); it.close() } }
            sessions+=cancelled
            Thread.sleep(250); check(cancelledDone.get()==0)
            report+="Simulated indefinitely blocked NSD lookup: main heartbeat responsive, independent fallback finds sources, 3 searches finish once each, only 1 NSD initialization, cancellation has no late callbacks: PASS"
        } finally { sessions.forEach { it.close() }; release.countDown() }
        val dialogs=com.nyqova.app.ui.ConnectionDialogs(activity) {}
        try {
            val parent=ui { dialogs.show() }
            ui { views(parent.window!!.decorView).filterIsInstance<Button>().first { it.text.toString()=="发现局域网设备" }.performClick() }
            check(ui { (field(dialogs,"discoveryDialog") as AlertDialog).isShowing })
            screenshot("tv-discovery-responsive-1080.png")
            ui { dialogs.close() }
            check(ui { !parent.isShowing && field(dialogs,"discoveryDialog")==null })
            report+="Real connection/discovery dialog opens and dismisses with background cleanup; no system-property or daemon changes: PASS"
        } finally { ui { dialogs.close() } }
    }
    private fun lyricFallbackChecks(activity: MainActivity) {
        val title="候选测试${java.util.UUID.randomUUID()}"; val q=TrackIdentity.from(title,"测试歌手","测试专辑",123000)
        val first=LyricCandidate(901,"不同录音","第一歌手","",100000,"[00:01.00]第一个候选\n[00:02.00]继续显示","",false)
        val second=LyricCandidate(902,"另一版本","第二歌手","",200000,"[00:01.00]手动更换版本","",false,"LrcAPI")
        val prefs=TvPreferences(targetContext); val oldMode=prefs.text("lyrics_source","auto"); val oldEnabled=prefs.enabled("online_lyrics")
        val key=call(OnlineMedia,"lyricKey",TrackIdentity::class.java to q).toString(); val cache=File(targetContext.filesDir,"online-media/lyrics-$key.json")
        try {
            prefs.set("lyrics_source","auto"); prefs.set("online_lyrics",true)
            check(!OnlineMatch.confident(q,first.title,first.artist,first.durationMs))
            val selected=OnlineMedia.chooseLyrics(targetContext,q,LyricsResult(null,listOf(first,second)))
            check(selected.candidate==first && selected.automatic && selected.candidates.size==2)
            val item=androidx.media3.common.MediaItem.Builder().setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(q.artist).setAlbumTitle(q.album).build()).build()
            val resolved=LyricsRepository.resolve(targetContext,item,q.durationMs,LookupScope())
            check(resolved.document!!.lines.first().text=="第一个候选" && resolved.result!!.cached && resolved.result!!.candidates.size==2)
            OnlineMedia.rememberLyrics(targetContext,q,second)
            val manual=LyricsRepository.resolve(targetContext,item,q.durationMs,LookupScope())
            check(manual.document!!.lines.first().text=="手动更换版本" && manual.result!!.candidate==second && !manual.result!!.automatic && manual.result!!.candidates.size==2)
            check(LyricsResult(second,listOf(first,second)).selectFirst().candidate==second)
            check(LyricsResult(null).selectFirst().candidate==null)
            val plain=OnlineMedia.chooseLyrics(targetContext,q,LyricsResult(null,listOf(first.copy(synced="",plain="普通文本歌词"))))
            check(plain.candidate!!.document().plainText.contains("普通文本歌词"))
            OnlineMedia.rememberLyrics(targetContext,q,second,(1L..25L).map { first.copy(id=it) })
            check(OnlineMedia.cachedLyrics(targetContext,q)!!.candidates.any { it.id==second.id && it.provider==second.provider })
            report+="No exact match + multiple candidates auto-selects first and yields timed lyrics; candidate list survives cache; manual second choice overrides and persists; exact selection, no results and plain text remain valid: PASS"
        } finally { cache.delete(); prefs.set("lyrics_source",oldMode); prefs.set("online_lyrics",oldEnabled) }
    }
    private fun themeChecks(started: MainActivity,player: MediaController) {
        val app=targetContext.applicationContext as android.app.Application; val current=AtomicReference(started); val prefs=TvPreferences(targetContext)
        val raw=targetContext.getSharedPreferences("tv_preferences",android.content.Context.MODE_PRIVATE)
        val keys=listOf("appearance_theme","accent")+TvStyle.palettes.map { "theme_accent_${it.id}" }
        val saved=keys.associateWith { raw.getString(it,null) }; val oldDelay=prefs.number("auto_player_delay",30)
        val originalItems=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }; val originalIndex=ui { player.currentMediaItemIndex }; val originalPosition=ui { player.currentPosition }; val originalPlay=ui { player.playWhenReady }
        val originalRepeat=ui { player.repeatMode }; val originalShuffle=ui { player.shuffleModeEnabled }
        val callback=object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity,b: Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) { if(a is MainActivity) current.set(a) }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity,b: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callback)
        fun controller()=ui { field(current.get(),"controller") as MediaController }
        fun settings() { val a=current.get(); ui { call(a,"showPage",String::class.java to "settings") }; click(button(a,"外观设置")) }
        fun pick(tag: String) {
            val previous=current.get(); click(ui { previous.window.decorView.findViewWithTag<View>(tag) })
            await("theme recreation") { current.get()!==previous && !current.get().isDestroyed }
            await("theme controller and focus") { ui { field(current.get(),"controller")!=null && current.get().currentFocus?.tag==tag && field(current.get(),"page")=="settings" } }
        }
        try {
            ui { prefs.set("auto_player_delay",0) }
            val seed=LibraryDatabase.get(targetContext).collections().tracks(TrackScope(MusicSource.key()).query()).first { it.cuePath==null && it.durationMs>60000 }
            ui { call(started,"startQueue",List::class.java to listOf(seed.song()),Int::class.javaPrimitiveType!! to 0) }
            await("theme playback seed") { ui { player.isPlaying && player.currentMediaItem?.mediaMetadata?.extras?.getString("track_id")==seed.id } }
            ui { player.seekTo(20000); player.repeatMode=Player.REPEAT_MODE_ALL }
            await("theme seek") { ui { player.currentPosition>=20000 } }
            val media=ui { player.currentMediaItem!!.mediaId }; val before=ui { player.currentPosition }
            for(palette in TvStyle.palettes) {
                settings(); pick("settings_theme_${palette.id}")
                val a=current.get(); val c=controller()
                check(TvStyle.palette.id==palette.id && prefs.text("appearance_theme")==palette.id)
                check(ui { c.isPlaying && c.mediaItemCount==1 && c.currentMediaItem?.mediaId==media && c.currentPosition>=before && c.repeatMode==Player.REPEAT_MODE_ALL })
                check(ui { val value=android.util.TypedValue(); a.theme.resolveAttribute(android.R.attr.colorBackgroundFloating,value,true); value.data==palette.panel })
                check(ui { val value=android.util.TypedValue(); a.theme.resolveAttribute(android.R.attr.textColorPrimary,value,true); (if(value.resourceId==0) value.data else a.getColor(value.resourceId))==palette.text })
                screenshot("tv-theme-${palette.id}-settings-1080.png")
                val pane=ui { field(a,"library")!! }
                ui { call(a,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,"home","首页") }
                await("themed home") { ui { field(pane,"loading")==false && (field(pane,"rows") as List<*>).size>4 } }
                screenshot("tv-theme-${palette.id}-home-1080.png")
                click(ui { field(pane,"searchButton") as View }); val dialog=ui { field(pane,"activeMenu") as AlertDialog }
                check(ui { val value=android.util.TypedValue(); dialog.context.theme.resolveAttribute(android.R.attr.colorBackgroundFloating,value,true); value.data==palette.panel })
                if(palette.id in listOf("warm","contrast")) screenshot("tv-theme-${palette.id}-search-1080.png")
                ui { dialog.dismiss() }
                if(palette.id=="contrast") { ui { call(a,"showPage",String::class.java to "player") }; screenshot("tv-theme-contrast-player-1080.png"); ui { call(a,"showQueue") }; screenshot("tv-theme-contrast-queue-1080.png"); ui { (field(a,"queueDialog") as android.app.Dialog).dismiss() } }
            }
            settings(); pick("settings_theme_warm")
            val warmBackground=TvStyle.background
            pick("settings_accent_#4078ED")
            check(TvStyle.palette.id=="warm" && TvStyle.background==warmBackground && TvStyle.accent==android.graphics.Color.parseColor("#4078ED"))
            pick("settings_theme_midnight"); pick("settings_theme_warm")
            check(TvStyle.accent==android.graphics.Color.parseColor("#4078ED"))
            report+="All four palettes apply to native dialogs/text, home/settings/search/player/queue; playing media, queue, repeat mode and >=20s progress survive each theme recreation: PASS"
            report+="Independent accent selection leaves palette intact; each palette remembers its own accent; settings and D-pad focus retained: PASS"
        } finally {
            ui { val edit=raw.edit(); saved.forEach { (key,value) -> if(value==null) edit.remove(key) else edit.putString(key,value) }; edit.apply(); prefs.set("auto_player_delay",oldDelay) }
            val previous=current.get(); ui { previous.recreate() }
            await("restore original theme") { current.get()!==previous && !current.get().isDestroyed }
            await("restored controller") { ui { field(current.get(),"controller")!=null } }
            ui { val c=controller(); c.setMediaItems(originalItems,originalIndex,originalPosition); c.repeatMode=originalRepeat; c.shuffleModeEnabled=originalShuffle; c.prepare(); c.playWhenReady=originalPlay; call(current.get(),"showPage",String::class.java to "library") }
            app.unregisterActivityLifecycleCallbacks(callback)
        }
    }
    private fun appearanceChecks(started: MainActivity) {
        val app=targetContext.applicationContext as android.app.Application
        val current=AtomicReference(started); val prefs=TvPreferences(targetContext)
        val oldColor=prefs.text("accent","#E6435A"); val oldCollapsed=prefs.enabled("nav_collapsed",false); val oldDelay=prefs.number("auto_player_delay",30)
        val callback=object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: android.app.Activity,b: Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityResumed(a: android.app.Activity) { if(a is MainActivity) current.set(a) }
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity,b: Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callback)
        fun select(color: String) {
            val old=current.get()
            click(ui { old.window.decorView.findViewWithTag<View>("settings_accent_$color") })
            await("theme recreation $color") { current.get()!==old && !current.get().isDestroyed }
            await("theme setting restores focus") { ui { current.get().currentFocus?.tag=="settings_accent_$color" } }
            val a=current.get(); val expected=android.graphics.Color.parseColor(color)
            check(prefs.text("accent")==color && TvStyle.accent==expected)
            check(ui { val value=android.util.TypedValue(); a.theme.resolveAttribute(android.R.attr.colorAccent,value,true); value.data==expected })
            check(ui { (field(a,"progress") as android.widget.SeekBar).progressTintList!!.defaultColor==expected && (field(a,"miniProgress") as android.widget.ProgressBar).progressTintList!!.defaultColor==expected })
            check(ui { val swatch=a.window.decorView.findViewWithTag<Button>("settings_accent_$color"); swatch.isSelected && swatch.text.toString()=="已选" })
        }
        try {
            ui { prefs.set("auto_player_delay",0); call(started,"showPage",String::class.java to "settings") }
            click(button(started,"外观设置"))
            select("#4078ED"); screenshot("tv-theme-blue-1080.png")
            select("#7DA743"); screenshot("tv-theme-green-1080.png")
            select(oldColor)
            val a=current.get()
            ui { call(a,"showPage",String::class.java to "library"); call(a,"setNavigationCollapsed",Boolean::class.javaPrimitiveType!! to true) }
            val nav=ui { @Suppress("UNCHECKED_CAST") (field(a,"navigation") as Map<String,Button>) }
            for((key,label) in listOf("home" to "首页","albums" to "专辑","artists" to "歌手","settings" to "设置")) {
                ui { nav.getValue(key).requestFocus() }
                await("collapsed hint $label") { ui { val hint=field(a,"navigationHint") as TextView; hint.isShown && hint.text.toString()==label && !hint.isFocusable && nav.getValue(key).hasFocus() } }
            }
            ui { nav.getValue("albums").requestFocus() }; await("album hint") { ui { (field(a,"navigationHint") as TextView).text.toString()=="专辑" } }
            screenshot("tv-collapsed-label-1080.png")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("hint hides outside navigation") { ui { !(field(a,"navigationHint") as View).isShown } }
            ui { call(a,"setNavigationCollapsed",Boolean::class.javaPrimitiveType!! to false) }
            check(ui { !(field(a,"navigationHint") as View).isShown })
            val pane=ui { field(a,"library")!! }
            ui { pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,"playlists","播放列表") }
            await("custom playlists only") { ui { field(pane,"loading")==false && (field(pane,"quantity") as TextView).text.isNotEmpty() } }
            val rows=ui { (field(pane,"rows") as List<*>).filterNotNull() }
            check(rows.count { field(it,"builtin")=="create" }==1 && rows.all { field(it,"playlist")!=null || field(it,"builtin")=="create" })
            val count=LibraryDatabase.get(targetContext).collections().playlists(MusicSource.key()).size
            check(rows.size==count+1 && ui { (field(pane,"quantity") as TextView).text.toString()=="$count 个播放列表" })
            screenshot("tv-custom-playlists-1080.png")
            report+="Blue/green theme applies to native accent, progress and selected swatch; recreation retains settings focus and saved color: PASS"
            report+="Collapsed navigation exposes non-focusable names and hides on exit; playlist overview contains only Create and saved playlists with exact totals: PASS"
        } finally {
            app.unregisterActivityLifecycleCallbacks(callback)
            ui { prefs.set("auto_player_delay",oldDelay); call(current.get(),"setNavigationCollapsed",Boolean::class.javaPrimitiveType!! to oldCollapsed) }
        }
    }
    private fun initialChecks(activity: MainActivity) {
        val db=LibraryDatabase.get(targetContext); val source=MusicSource.key(); val dao=db.collections()
        val pane=ui { field(activity,"library")!! }
        for((name,key) in listOf("周杰伦" to "ZJL","周杰倫" to "ZJL","张学友" to "ZXY","林憶蓮" to "LYL","陈奕迅" to "CYX","Green Day" to "GD","Don't Stop Believin'" to "DSB","《爱你》" to "AN")) check(SearchInitials.key(name)==key) { "Initials $name -> ${SearchInitials.key(name)} expected $key" }
        fun ready()=ui { field(pane,"loading")==false && field(pane,"groupLoading")==false && (field(pane,"quantity") as TextView).text.isNotEmpty() }
        fun section(kind: String,label: String) { ui { call(activity,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,kind,label) }; await("$kind ready") { ready() } }
        fun dialog()=ui { field(pane,"activeMenu") as AlertDialog }
        fun tagged(tag: String)=ui { dialog().window!!.decorView.findViewWithTag<View>(tag) ?: error("No $tag") }
        fun input()=ui { dialog().window!!.decorView.findViewWithTag<android.widget.EditText>("search_input") }
        fun search(term: String) {
            click(ui { field(pane,"searchButton") as View }); ui { input().setText(term) }; click(tagged("search_submit")); await("search results") { ready() }
        }
        section("all","音乐库")
        click(ui { field(pane,"searchButton") as View })
        check(ui { input().isShown && !input().showSoftInputOnFocus })
        click(tagged("search_key_Z")); click(tagged("search_key_J")); click(tagged("search_key_X")); click(tagged("search_backspace")); click(tagged("search_key_L"))
        check(ui { input().text.toString()=="ZJL" && dialog().isShowing })
        check(ui { listOf("I","R","0","9").all { letter -> val view=tagged("search_key_$letter"); val rect=android.graphics.Rect(); view.getGlobalVisibleRect(rect) && rect.width()==view.width && rect.height()==view.height } })
        screenshot("tv-search-keyboard-1080.png")
        click(tagged("search_submit")); await("ZJL results") { ready() }
        val expected=dao.count(TrackScope(source,search="ZJL").countQuery())
        check(expected>0 && ui { (field(pane,"quantity") as TextView).text.toString()=="${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(expected)} 首歌曲" })
        val result=ui { (field(pane,"rows") as List<*>).map { field(it!!,"track") as LibraryTrack } }
        check(result.isNotEmpty() && result.all { listOf(it.title,it.artist,it.album).any { name -> SearchInitials.matches(name,"ZJL") } })
        screenshot("tv-search-zjl-1080.png")
        click(ui { field(pane,"searchButton") as View }); click(tagged("search_clear")); check(ui { input().text.isEmpty() }); click(tagged("search_submit")); await("clear results") { ready() && ui { field(pane,"search")=="" } }
        section("albums","专辑"); search("GD")
        check(ui { (field(pane,"rows") as List<*>).isNotEmpty() && (field(pane,"rows") as List<*>).all { field(it!!,"album")!=null && field(it,"track")==null } })
        section("artists","歌手"); search("zjl")
        check(ui { (field(pane,"groupRows") as List<*>).isNotEmpty() && (field(pane,"groupRows") as List<*>).all { SearchInitials.matches(field(it!!,"group").toString(),"zjl") } })
        check(db.searchInitials().missing().isEmpty())
        report+="Unified text field + 36-key TV keyboard; insert/backspace/clear/submit, no automatic IME, full grid visible: PASS"
        report+="Chinese/traditional per-character initials ZJL/ZXY/LYL/CYX and English word initials GD/DSB; live scoped ZJL songs/artist and GD albums, exact counts, lowercase and clearing: PASS"
        section("home","首页")
    }
    private fun currentTrackChecks(activity: MainActivity,player: MediaController) {
        val dao=LibraryDatabase.get(targetContext).collections(); val source=MusicSource.key(); val prefs=TvPreferences(targetContext)
        val pane=ui { field(activity,"library")!! }; val list=ui { field(pane,"list") as RecyclerView }
        val oldDelay=prefs.number("auto_player_delay",30)
        val originalItems=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }; val originalIndex=ui { player.currentMediaItemIndex }; val originalPosition=ui { player.currentPosition }; val originalPlay=ui { player.playWhenReady }
        val repeat=ui { player.repeatMode }; val shuffle=ui { player.shuffleModeEnabled }
        val tracks=dao.tracks(TrackScope(source).query())
        fun openTrack(track: LibraryTrack) {
            ui { call(activity,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,"all","音乐库"); call(pane,"applySearch",String::class.java to track.title) }
            await("track visible") { ui { field(pane,"loading")==false && (field(pane,"rows") as List<*>).any { (field(it!!,"track") as? LibraryTrack)?.id==track.id } } }
            val position=ui { (field(pane,"rows") as List<*>).indexOfFirst { (field(it!!,"track") as? LibraryTrack)?.id==track.id } }
            ui { call(pane,"focusRow",Int::class.javaPrimitiveType!! to position) }
            await("track focused") { ui { list.findViewHolderForAdapterPosition(position)?.itemView?.hasFocus()==true } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        fun verify(queue: List<LibraryTrack>,index: Int) {
            val target=queue[index]
            ui { call(activity,"startQueue",List::class.java to queue.map { it.song() },Int::class.javaPrimitiveType!! to index) }
            await("seed current song") { ui { player.isPlaying && player.currentMediaItem?.mediaMetadata?.extras?.getString("track_id")==target.id } }
            ui { player.seekTo(20000) }; await("seek before click") { ui { player.currentPosition>=20000 } }
            val ids=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }
            openTrack(target)
            await("playing click preserves progress") { ui { field(activity,"page")=="player" && player.isPlaying && player.currentPosition>=20000 } }
            check(ui { player.currentMediaItemIndex==index && (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }==ids && player.currentMediaItem!!.clippingConfiguration.startPositionMs==target.startUs/1000 })
            ui { player.pause() }; val paused=ui { player.currentPosition }
            openTrack(target)
            await("paused click resumes") { ui { field(activity,"page")=="player" && player.isPlaying && player.currentPosition>=paused && player.currentPosition<paused+8000 } }
            check(ui { player.currentMediaItemIndex==index && (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }==ids })
        }
        try {
            ui { prefs.set("auto_player_delay",0) }
            val singles=tracks.filter { it.cuePath==null && it.durationMs>60000 }.take(3); check(singles.size==3); verify(singles,1)
            val cue=tracks.filter { it.cuePath!=null && it.durationMs>60000 }.groupBy { it.albumKey }.values.first { it.size>=3 }.sortedBy { it.number }.take(3)
            verify(cue,1)
            openTrack(cue[2])
            await("different cue segment starts normally") { ui { player.isPlaying && player.currentMediaItem?.mediaMetadata?.extras?.getString("track_id")==cue[2].id && player.currentPosition<8000 && player.currentMediaItem!!.clippingConfiguration.startPositionMs==cue[2].startUs/1000 } }
            report+="Library click on playing/paused single and CUE preserves queue/index/relative progress and resumes; selecting another CUE segment starts that track normally: PASS"
        } finally {
            ui { prefs.set("auto_player_delay",oldDelay); player.setMediaItems(originalItems,originalIndex,originalPosition); player.repeatMode=repeat; player.shuffleModeEnabled=shuffle; player.prepare(); player.playWhenReady=originalPlay; call(activity,"showPage",String::class.java to "library") }
        }
    }
    private fun remoteChecks(activity: MainActivity,player: MediaController) {
        val db=LibraryDatabase.get(targetContext); val dao=db.collections(); val source=MusicSource.key(); val prefs=TvPreferences(targetContext)
        val pane=ui { field(activity,"library")!! }; val list=ui { field(pane,"list") as RecyclerView }; val groups=ui { field(pane,"groupList") as RecyclerView }
        val oldDelay=prefs.number("auto_player_delay",30)
        val playlist=SavedPlaylist(source=source,name="遥控与专辑视图测试")
        val folderPlaylist=SavedPlaylist(source=source,name="文件夹专辑视图测试",kind="folder",folder="")
        val folderViewKey="playlist_view:$source:folder:"; val oldFolderView=prefs.text(folderViewKey,"songs")
        val albums=db.library().albums(source).filter { it.trackCount>=3 }.take(2)
        check(albums.size==2)
        val members=albums.flatMap { dao.tracks(TrackScope(source,"album",it.albumKey).query()).take(2) }
        val favorites=dao.favorites(source,"track").toSet(); val albumLikes=dao.favorites(source,"album").toSet()
        val originals=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }; val oldIndex=ui { player.currentMediaItemIndex }; val oldPosition=ui { player.currentPosition }; val oldPlay=ui { player.playWhenReady }
        fun rows()=ui { (field(pane,"rows") as List<*>).filterNotNull() }
        fun ready()=ui { field(pane,"loading")==false && (field(pane,"quantity") as TextView).text.isNotEmpty() }
        fun section(kind: String,label: String) { ui { call(activity,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,kind,label) }; await("section $kind") { ready() && rows().isNotEmpty() } }
        fun focus(position: Int) { ui { call(pane,"focusRow",Int::class.javaPrimitiveType!! to position) }; await("focus row $position") { ui { list.findViewHolderForAdapterPosition(position)?.itemView?.hasFocus()==true } } }
        fun menu()=ui { field(pane,"activeMenu") as AlertDialog }
        fun choose(label: String) { ui { val dialog=menu(); val index=(0 until dialog.listView.adapter.count).firstOrNull { dialog.listView.adapter.getItem(it).toString()==label } ?: error("Missing $label; menu=${(0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it) }}"); dialog.listView.performItemClick(null,index,index.toLong()) }; waitForIdleSync() }
        fun toggle(label: String) { ui { list.findViewHolderForAdapterPosition(field(pane,"selected") as Int)!!.itemView.performLongClick() }; choose(label) }
        fun openPlaylist(id: String) { section("playlists","播放列表"); val position=rows().indexOfFirst { (field(it,"playlist") as? SavedPlaylist)?.id==id }; focus(position); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); await("playlist opens") { ready() && ui { field(field(pane,"location")!!,"target")==id || field(field(pane,"location")!!,"kind")=="folder" } } }
        try {
            ui { prefs.set("auto_player_delay",0); prefs.set(folderViewKey,"songs"); player.pause() }
            db.runInTransaction { dao.savePlaylist(playlist); dao.savePlaylist(folderPlaylist); members.forEachIndexed { i,t -> dao.add(PlaylistMember(playlist.id,t.id,i)) } }
            section("all","音乐库"); focus(12)
            val song=field(rows()[12],"track") as LibraryTrack
            toggle(if(song.id in favorites) "取消收藏歌曲" else "收藏歌曲")
            await("song favorite keeps focus") { ui { list.findViewHolderForAdapterPosition(12)?.itemView?.hasFocus()==true } && (song.id in dao.favorites(source,"track"))!=(song.id in favorites) }
            focus(79); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("pagination stays in content") { ui { field(pane,"selected")==80 && list.findViewHolderForAdapterPosition(80)?.itemView?.hasFocus()==true } }
            dao.favorite(Favorite(source,"track",song.id)); section("favorites","我喜欢")
            val favoritePosition=rows().indexOfFirst { (field(it,"track") as? LibraryTrack)?.id==song.id }; check(favoritePosition>=0)
            focus(favoritePosition); toggle("取消收藏歌曲")
            await("removed favorite restores neighbor or empty search") { ready() && rows().none { (field(it,"track") as? LibraryTrack)?.id==song.id } && ui { if(rows().isEmpty()) (field(pane,"searchButton") as View).hasFocus() else list.hasFocus() } }
            section("albums","专辑"); focus(8); val album=field(rows()[8],"album") as AlbumSummary
            toggle(if(album.albumKey in albumLikes) "取消收藏专辑" else "收藏专辑")
            await("album favorite keeps focus") { ui { list.findViewHolderForAdapterPosition(8)?.itemView?.hasFocus()==true } && (album.albumKey in dao.favorites(source,"album"))!=(album.albumKey in albumLikes) }
            for((kind,label) in listOf("artists" to "歌手","genres" to "风格")) {
                section(kind,label); await("groups ready") { ui { groups.adapter!!.itemCount>1 && groups.findViewHolderForAdapterPosition(1)!=null } }
                val card=ui { groups.findViewHolderForAdapterPosition(1)!!.itemView }; click(card)
                await("group retains focus") { ready() && ui { card.hasFocus() } }
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); await("right enters tracks") { ui { list.hasFocus() } }
                sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); await("left restores group") { ui { card.hasFocus() } }
            }
            report+="Long-press song/album favorite, filtered removal and paginated browsing preserve D-pad focus; artist/genre group Right/Left reaches and restores content: PASS"
            openPlaylist(playlist.id)
            click(ui { field(pane,"viewButton") as View }); choose("切换为专辑视图")
            await("playlist album groups") { ready() && rows().size==2 && rows().all { field(it,"album")!=null } }
            check(rows().all { (field(it,"album") as AlbumSummary).trackCount==2 })
            screenshot("tv-playlist-albums-1080.png")
            focus(0); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("scoped album tracks") { ready() && rows().size==2 && rows().all { (field(it,"track") as LibraryTrack).id in members.map { it.id } } }
            click(ui { field(pane,"playAll") as View })
            await("scoped playlist album playback") { ui { player.mediaItemCount==2 && field(activity,"page")=="player" } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); await("playlist detail return") { ui { field(activity,"page")=="library" } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); await("playlist albums return") { ready() && rows().size==2 && rows().all { field(it,"album")!=null } }
            openPlaylist(playlist.id); check(rows().all { field(it,"album")!=null })
            click(ui { field(pane,"viewButton") as View }); choose("切换为歌曲视图"); await("playlist songs") { ready() && rows().size==4 && rows().all { field(it,"track")!=null } }
            focus(3); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("last row to mini") { ui { (field(activity,"miniPlay") as View).hasFocus() } }
            screenshot("tv-mini-focus-1080.png")
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); await("mini restores row") { ui { list.findViewHolderForAdapterPosition(3)?.itemView?.hasFocus()==true } }
            val nav=ui { field(activity,"navigationToggle") as View }; ui { nav.requestFocus() }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); await("rail to mini") { ui { (field(activity,"miniPlay") as View).hasFocus() } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP); check(ui { nav.hasFocus() })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); await("rail down to mini") { ui { (field(activity,"miniPlay") as View).hasFocus() } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            check(ui { (field(activity,"miniTitle") as View).hasFocus() })
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); await("mini title enters player") { ui { field(activity,"page")=="player" } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            openPlaylist(folderPlaylist.id); click(ui { field(pane,"viewButton") as View }); choose("切换为专辑视图")
            await("folder playlist albums") { ready() && rows().isNotEmpty() && rows().all { field(it,"album")!=null } }
            check(dao.count(TrackScope(source,"folder",folderPlaylist.folder).albumCountQuery())==db.library().albums(source).size)
            ui { player.clearMediaItems() }; await("empty queue hides mini") { ui { !(field(activity,"mini") as View).isShown } }
            report+="Manual/folder playlist album views retain scoped tracks/counts and remembered view; Play All uses the subset; mini-player boundary/rail navigation, ordered controls, return focus and empty queue hiding: PASS"
        } finally {
            db.runInTransaction {
                dao.clearMembers(playlist.id); dao.deletePlaylist(playlist.id); dao.deletePlaylist(folderPlaylist.id)
                dao.favorites(source,"track").filter { it !in favorites }.forEach { dao.unfavorite(source,"track",it) }; favorites.forEach { dao.favorite(Favorite(source,"track",it)) }
                dao.favorites(source,"album").filter { it !in albumLikes }.forEach { dao.unfavorite(source,"album",it) }; albumLikes.forEach { dao.favorite(Favorite(source,"album",it)) }
            }
            ui { prefs.set("auto_player_delay",oldDelay); prefs.set(folderViewKey,oldFolderView); player.setMediaItems(originals,oldIndex,oldPosition); player.prepare(); player.playWhenReady=oldPlay; pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,"home","首页"); call(activity,"showPage",String::class.java to "library") }
        }
    }
    private fun searchChecks(activity: MainActivity) {
        val dao=LibraryDatabase.get(targetContext).collections(); val source=MusicSource.key()
        val pane=ui { field(activity,"library")!! }; val list=ui { field(pane,"list") as RecyclerView }
        fun section(kind: String,label: String) { ui { call(activity,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,kind,label) }; await("section $kind") { ui { field(pane,"loading")==false && field(pane,"groupLoading")==false && (field(pane,"quantity") as TextView).text.isNotEmpty() } } }
        fun rows()=ui { (field(pane,"rows") as List<*>).filterNotNull() }
        fun kind()=ui { field(field(pane,"location")!!,"kind").toString() }
        fun search(term: String) {
            click(ui { field(pane,"searchButton") as View })
            ui { val dialog=field(pane,"activeMenu") as AlertDialog
                views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText(term)
                dialog.window!!.decorView.findViewWithTag<View>("search_submit").performClick()
            }
            await("search complete") { ui { field(pane,"loading")==false && field(pane,"groupLoading")==false && (field(pane,"quantity") as TextView).text.isNotEmpty() } }
        }
        section("albums","专辑")
        val album=field(rows().first(),"album") as AlbumSummary
        search(album.title.take(5))
        check(kind()=="albums" && rows().isNotEmpty() && rows().all { field(it,"album")!=null && field(it,"track")==null })
        screenshot("tv-search-albums-1080.png")
        click(ui { list.findViewHolderForAdapterPosition(0)!!.itemView })
        await("searched album opens") { kind()=="album" && rows().isNotEmpty() }
        val target=ui { field(field(pane,"location")!!,"target").toString() }
        search(dao.tracks(TrackScope(source,"album",target).query()).first().title.take(4))
        check(kind()=="album" && rows().all { (field(it,"track") as LibraryTrack).albumKey==target })
        check(ui { (pane as com.nyqova.app.ui.TvLibraryPane).back() })
        await("back to album search") { kind()=="albums" && rows().isNotEmpty() }
        check(ui { field(pane,"search")==album.title.take(5).trim() })
        search("NYQOVA-no-match-123456")
        check(rows().isEmpty() && ui { (field(pane,"quantity") as TextView).text.toString()=="0 张专辑" })
        search(""); check(rows().isNotEmpty() && kind()=="albums")
        section("artists","歌手")
        val artist=dao.tracks(TrackScope(source).query()).first { it.artist.isNotBlank() }.artist
        search(artist)
        check(ui { (field(pane,"groupRows") as List<*>).all { field(it!!,"group").toString().contains(artist.trim(),true) } }) { "Artist filter expected=[$artist], groupSearch=[${ui { field(pane,"groupSearch") }}], kind=${kind()}, groups=${ui { (field(pane,"groupRows") as List<*>).take(5).map { field(it!!,"group") } }}" }
        check(kind()=="artist" && rows().all { (field(it,"track") as LibraryTrack).artist.contains(artist.trim(),true) })
        search("NYQOVA-no-artist-123456"); check(rows().isEmpty())
        search(""); check(rows().isNotEmpty())
        val temporary=SavedPlaylist(source=source,name="搜索验证 ${java.util.UUID.randomUUID()}")
        dao.savePlaylist(temporary)
        try {
            section("playlists","歌单"); search(temporary.name)
            check(rows().count { (field(it,"playlist") as? SavedPlaylist)?.id==temporary.id }==1 && rows().none { field(it,"builtin") in listOf("all","favorites","recent","added") })
            check(ui { (field(pane,"quantity") as TextView).text.toString()=="1 个播放列表" })
        } finally { dao.deletePlaylist(temporary.id) }
        check(targetContext.applicationInfo.loadLabel(targetContext.packageManager).toString()=="聆序")
        ui { call(activity,"showPage",String::class.java to "settings") }
        click(button(activity,"关于"))
        check(ui { val texts=views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }; "作者：L.VSN" in texts && texts.any { it.contains("NYQOVA") } && texts.any { it.contains("${targetContext.packageManager.getPackageInfo(targetContext.packageName,0).versionName} (") } })
        screenshot("tv-about-1080.png")
        report+="Scoped album search, album detail filtering, Back restores search, empty/clear results, artist group filtering, playlist filtering and counts: PASS"
        report+="Launcher name 聆序 and About author/product/runtime version: PASS"
        section("home","首页")
    }
    private fun countChecks(activity: MainActivity) {
        val db=LibraryDatabase.get(targetContext); val dao=db.collections(); val source=MusicSource.key()
        SearchInitials.ensure(db)
        val pane=ui { field(activity,"library")!! }; val list=ui { field(pane,"list") as RecyclerView }
        fun count()=ui { (field(pane,"quantity") as TextView).text.toString() }
        fun formatted(n: Int)=java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)
        fun section(kind: String,label: String) { ui { call(activity,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,kind,label) } }
        val total=dao.count(TrackScope(source).countQuery())
        section("all","音乐库"); await("all total") { count()=="${formatted(total)} 首歌曲" }
        check(total>80 && ui { list.adapter!!.itemCount==80 })
        screenshot("tv-counts-library-1080.png")
        val term=dao.tracks(TrackScope(source).query(1))[0].title.take(5)
        ui { pane.javaClass.getDeclaredField("search").apply { isAccessible=true }.set(pane,term); call(pane,"reload") }
        val filtered=dao.count(TrackScope(source,search=term).countQuery())
        await("filtered total") { count()=="${formatted(filtered)} 首歌曲" }
        ui { pane.javaClass.getDeclaredField("search").apply { isAccessible=true }.set(pane,"NYQOVA-no-match-${java.util.UUID.randomUUID()}"); call(pane,"reload") }
        await("empty total") { count()=="0 首歌曲" && ui { (field(pane,"status") as TextView).text.toString()=="没有匹配结果" } }
        section("albums","专辑"); val albums=db.library().albums(source).size
        await("album total") { count()=="${formatted(albums)} 张专辑" }
        screenshot("tv-counts-albums-1080.png")
        val first=ui { (field(pane,"rows") as List<*>).first()!! }; val album=field(first,"album") as AlbumSummary
        click(ui { list.findViewHolderForAdapterPosition(0)!!.itemView })
        await("album detail total") { count()=="${formatted(album.trackCount)} 首歌曲" }
        section("artists","歌手")
        val artists=dao.count(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT artist FROM tracks WHERE source=? GROUP BY artist)",arrayOf(source)))
        await("artist total") { count()=="${formatted(artists)} 位歌手" }
        screenshot("tv-counts-artists-1080.png")
        report+="Exact totals: $total songs / $albums albums / $artists artist groups; library stays paged at 80, search and empty results count their actual scope, album detail shows tracks: PASS"
        section("home","首页")
    }
    private fun codecChecks(activity: MainActivity,player: MediaController) {
        check(io.github.eugenedibtsev.media3.ape.ApeLibrary.isAvailable())
        check(androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat("audio/alac"))
        check(!android.os.Process.is64Bit())
        report+="Both native decoders load in a 32-bit process: MACLib APE and FFmpeg ALAC: PASS"
        val saved=ConnectionStore.load(targetContext)!!; val prefs=TvPreferences(targetContext); val oldDelay=prefs.number("auto_player_delay",30)
        val oldItems=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }; val oldIndex=ui { player.currentMediaItemIndex }; val oldPosition=ui { player.currentPosition }
        fun play(songs: List<com.nyqova.app.storage.Song>,index: Int=0) {
            ui { call(activity,"startQueue",List::class.java to songs,Int::class.javaPrimitiveType!! to index) }
            await("codec playback ${songs[index].path}",60000) { ui { player.isPlaying && player.currentMediaItemIndex==index && player.currentPosition>300 && player.currentMediaItem?.mediaMetadata?.title.toString()==songs[index].title } }
        }
        fun seek() {
            val target=ui { minOf(15000L,(player.duration/2).coerceAtLeast(1000)) }
            ui { player.seekTo(target) }; await("codec seek",60000) { ui { player.isPlaying && player.currentPosition>=target && player.currentPosition<target+6000 } }
        }
        val fixture=com.nyqova.app.storage.ConnectionProfile("http://127.0.0.1:8765/")
        try {
            ui { prefs.set("auto_player_delay",0); player.pause() }; fixture.activate()
            val result=LibraryScanner(targetContext).scan(); check(result.tracks==7)
            val db=LibraryDatabase.get(targetContext); val source=MusicSource.key(); val entries=MusicSource.listAll("")
            for(name in listOf("normal.ape","alac16.m4a","alac24.m4a","mislabel.ape")) {
                val meta=com.nyqova.app.library.AudioMetadataReader.read(entries.first { it.name==name },true)
                check(meta.durationMs>24000 && meta.artist=="NYQOVA" && meta.album=="Codec Fixtures") { "Metadata $name: $meta" }
                if(name=="normal.ape") check(meta.picture!=null)
                play(listOf(com.nyqova.app.storage.Song(meta.title,name,meta.artist,meta.album,durationMs=meta.durationMs))); seek()
                report+="Fixture $name: tags, duration, playback and HTTP Range seek: PASS"
            }
            val rows=db.collections().tracks(TrackScope(source).query()); val cues=rows.filter { it.cuePath!=null }.sortedBy { it.number }
            check(cues.size==3 && rows.none { it.path=="album.ape" && it.cuePath==null })
            play(cues.map { it.song() },1); check(ui { player.duration in 39000..41000 }); seek()
            ui { player.seekToNextMediaItem() }; await("APE CUE next") { ui { player.isPlaying && player.currentMediaItemIndex==2 && player.currentPosition>300 } }
            report+="APE+CUE: source hidden, second track starts at 40s, relative seek and next-track clipping: PASS"
        } finally {
            val fixtureKey=MusicSource.key(); saved.activate()
            ui { player.setMediaItems(oldItems,oldIndex.coerceAtLeast(0),oldPosition); player.prepare(); player.pause(); prefs.set("auto_player_delay",oldDelay) }
            if(fixtureKey!=MusicSource.key()) {
                val db=LibraryDatabase.get(targetContext); db.runInTransaction { db.library().deleteTracks(fixtureKey); db.library().deleteDirectories(fixtureKey); db.audioIndex().files(fixtureKey).forEach { db.audioIndex().delete(fixtureKey,it.path) } }
            }
        }
        val result=LibraryScanner(targetContext).scan()
        val real=LibraryDatabase.get(targetContext).collections().tracks(TrackScope(MusicSource.key()).query()).filter { it.path.endsWith(".ape",true) }
        check(real.isNotEmpty()) { "No NAS APE samples found" }
        fun genuine(row: LibraryTrack): Boolean {
            val data=com.nyqova.app.storage.MusicDataSource(com.nyqova.app.storage.DavRequest.metadataClient)
            return try {
                data.open(androidx.media3.datasource.DataSpec.Builder().setUri(MusicSource.uri(row.path)).setLength(minOf(row.fileSize,65536L)).build())
                io.github.eugenedibtsev.media3.ape.ApeExtractor().sniff(androidx.media3.extractor.DefaultExtractorInput(data,0,row.fileSize))
            } finally { data.close() }
        }
        val single=real.firstOrNull { it.cuePath==null && genuine(it) }; check(single!=null) { "No genuine standalone NAS APE" }
        play(listOf(single.song())); seek()
        val cue=real.filter { it.cuePath!=null }.groupBy { it.cuePath }.values.firstOrNull { it.size>1 && genuine(it.first()) } ?: error("No genuine NAS APE CUE album")
        for(row in listOf(single,cue.first())) {
            val meta=com.nyqova.app.library.AudioMetadataReader.read(com.nyqova.app.storage.RemoteEntry(row.path.substringAfterLast('/'),row.path,false,row.fileSize),true)
            check(meta.durationMs>0)
        }
        play(cue.sortedBy { it.number }.map { it.song() },1); seek()
        report+="Real NAS: ${result.tracks} total tracks, ${real.size} APE logical tracks; standalone APE and APE+CUE playback/seek: PASS"
        screenshot("tv-ape-cue-1080.png")
        await("APE persisted queue checkpoint") { PlaybackMemory.load(targetContext)?.let { it.index==1 && it.position>5000 }==true }
        val expected=ui { org.json.JSONObject().put("index",player.currentMediaItemIndex).put("media",player.currentMediaItem!!.mediaId).put("position",player.currentPosition) }
        File(targetContext.getExternalFilesDir(null),"codec-resume.json").writeText(expected.toString())
    }
    private fun revisionChecks(activity: MainActivity,player: MediaController) {
        val prefs=TvPreferences(targetContext); val oldLyrics=prefs.text("lyrics_source","auto"); val oldArt=prefs.text("art_source","auto"); val oldDelay=prefs.number("auto_player_delay",30)
        try {
            ui { prefs.set("auto_player_delay",0); call(activity,"showPage",String::class.java to "player") }
            val pane=ui { field(activity,"playback") as View }
            await("player fills display") { ui { val xy=IntArray(2); pane.getLocationOnScreen(xy); xy[0]==0 && xy[1]==0 && pane.width==1920 && pane.height==1080 } }
            screenshot("tv-full-player-1080.png")
            report+="Player occupies the entire 1920x1080 canvas, with navigation/mini bar hidden and no outer library padding: PASS"
            ui { call(activity,"showQueue") }; val queue=ui { field(activity,"queueDialog") as android.app.Dialog }
            check(ui { views(queue.window!!.decorView).filterIsInstance<TextView>().none { it.text.toString()=="曲目操作" } })
            ui { val list=field(queue,"list") as android.widget.ListView; val index=player.currentMediaItemIndex.coerceAtLeast(0); list.onItemLongClickListener!!.onItemLongClick(list,list.selectedView,index,index.toLong()) }
            val menu=ui { field(queue,"submenu") as AlertDialog }
            check(ui { menu.listView.adapter.count==4 }); ui { menu.dismiss(); queue.dismiss() }
            report+="Queue footer has no Track Actions button; song long-press retains remove/reorder/play-next menu: PASS"
            val listener=ui { val library=field(activity,"library")!!; field(library,"indexListener") as (com.nyqova.app.library.LibraryIndexing.State)->Unit }
            ui { call(activity,"showPage",String::class.java to "library"); listener(com.nyqova.app.library.LibraryIndexing.State(MusicSource.key(),"2 个文件信息未补全，可重试；歌曲仍可播放",false,999999)) }
            await("transient index message hides",11000) { ui { !(field(field(activity,"library")!!,"scanBar") as View).isShown } }
            ui { call(activity,"showPage",String::class.java to "settings"); activity.javaClass.getDeclaredField("settingsCategory").apply { isAccessible=true }.set(activity,"online"); call(activity,"replaceSettings") }
            check(ui { activity.window.decorView.findViewWithTag<View>("settings_lyrics_source").isShown && activity.window.decorView.findViewWithTag<View>("settings_art_source").isShown })
            screenshot("tv-online-sources-1080.png")
            report+="Completed/failed indexing hint disappears after eight seconds; retry/details and online source controls live in Settings: PASS"
            prefs.set("lyrics_source","lrcapi"); prefs.set("art_source","lrcapi")
            val q=com.nyqova.core.TrackIdentity.from("伤痕","林忆莲","",365813)
            val response=com.nyqova.app.online.OnlineMedia.lyrics(targetContext,q,com.nyqova.app.online.LookupScope(),true)
            check(response.candidate?.provider=="LrcAPI" && response.candidate!!.document().lines.size>10)
            val cached=com.nyqova.app.online.OnlineMedia.lyrics(targetContext,q)
            check(cached.cached && cached.candidate?.provider=="LrcAPI")
            val art=com.nyqova.app.online.OnlineMedia.artwork(targetContext,"Love, Sandy","林忆莲")
            check(art!=null && File(art).isFile)
            report+="Live backup lyrics and album artwork retrieved; synchronized lyric matching and provider-aware lyric cache: PASS"
            val problems=LibraryDatabase.get(targetContext).audioIndex().files(MusicSource.key()).filter { !it.ready && it.attempts>=2 }
            report+="Metadata failures retained for Settings diagnosis: ${problems.size} files (${problems.joinToString { it.path.substringAfterLast('/') }})"
        } finally { ui { prefs.set("lyrics_source",oldLyrics); prefs.set("art_source",oldArt); prefs.set("auto_player_delay",oldDelay); call(activity,"showPage",String::class.java to "library") } }
    }
    private fun menuChecks(activity: MainActivity,player: MediaController) {
        val prefs=TvPreferences(targetContext); val db=LibraryDatabase.get(targetContext); val dao=db.collections(); val source=MusicSource.key()
        val oldDelay=prefs.number("auto_player_delay",30); val oldSort=prefs.text("sort","title"); val oldLayout=prefs.text("album_layout","grid"); val oldAlbumSort=prefs.text("album_sort","title")
        val oldSize=prefs.number("lyric_size",22); val oldStep=prefs.number("seek_step")
        val originalItems=ui { (0 until player.mediaItemCount).map { player.getMediaItemAt(it) } }
        val originalIndex=ui { player.currentMediaItemIndex }; val originalPosition=ui { player.currentPosition }; val originalPlay=ui { player.playWhenReady }
        val originalRepeat=ui { player.repeatMode }; val originalShuffle=ui { player.shuffleModeEnabled }
        val tracks=dao.tracks(TrackScope(source).query()).take(60); check(tracks.size>40)
        val playlist=SavedPlaylist(source=source,name="TV 菜单验证")
        val tempAlbum=db.library().albums(source).first().albumKey; val wasLiked=tempAlbum in dao.favorites(source,"album")
        val pane=ui { field(activity,"library")!! }; val recycler=ui { field(pane,"list") as RecyclerView }
        fun rows(): List<*> = ui { field(pane,"rows") as List<*> }
        fun kind()=ui { field(field(pane,"location")!!,"kind").toString() }
        fun selectedTrack(): String? = ui { (field(pane,"rows") as List<*>).getOrNull(field(pane,"selected") as Int)?.let { (field(it!!,"track") as? LibraryTrack)?.id } }
        fun tagged(tag: String)=ui { activity.window.decorView.findViewWithTag<View>(tag) ?: error("Missing $tag") }
        fun menu()=ui { field(pane,"activeMenu") as AlertDialog }
        fun labels(dialog: AlertDialog)=ui { (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() } }
        fun choose(dialog: AlertDialog,label: String) { ui { val index=labels(dialog).indexOfFirst { it==label }; check(index>=0) { "Missing menu entry: $label" }; dialog.listView.performItemClick(null,index,index.toLong()) }; waitForIdleSync() }
        fun section(k: String,title: String) { ui { call(activity,"showPage",String::class.java to "library"); pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,k,title) }; await("$k rows") { rows().isNotEmpty() } }
        fun focus(position: Int) { ui { call(pane,"focusRow",Int::class.javaPrimitiveType!! to position) }; await("row focus $position") { ui { recycler.findViewHolderForAdapterPosition(position)?.itemView?.hasFocus()==true } } }
        try {
            ui { prefs.set("auto_player_delay",0); call(activity,"resetIdleNavigation") }
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            section("all","音乐库"); focus(12)
            val target=selectedTrack()!!; val targetTitle=ui { (field(rows()[12]!!,"track") as LibraryTrack).title }
            sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
            check(labels(menu()).none { it in listOf("播放此曲","播放此列表","刷新") })
            check(ui { views(menu().window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()==targetTitle } })
            ui { menu().dismiss() }
            await("item menu restores focus") { ui { recycler.findViewHolderForAdapterPosition(12)?.itemView?.hasFocus()==true } }
            check(ui { activity.window.decorView.findViewWithTag<View>("library_item_actions")==null && activity.window.decorView.findViewWithTag<View>("library_page_actions")==null })
            report+="D2/T1: explicit item target, persistent selection, remote Menu and no duplicate corner actions; no duplicate play entries: PASS"

            click(tagged("library_view")); check(labels(menu()).none { it.contains("封面") })
            choose(menu(),labels(menu()).first { it.contains("歌名") }); await("song sort stays in scope") { kind()=="all" && rows().isNotEmpty() }
            section("albums","专辑"); click(tagged("library_view")); choose(menu(),"切换为列表")
            await("same-page album list") { kind()=="albums" && ui { recycler.layoutManager !is GridLayoutManager } && rows().isNotEmpty() }
            click(tagged("library_view")); choose(menu(),"切换为封面网格"); await("album grid") { ui { recycler.layoutManager is GridLayoutManager } && rows().isNotEmpty() }
            click(tagged("library_view")); choose(menu(),labels(menu()).first { it.startsWith("排序：") }); choose(menu(),"歌手")
            await("album artist order") { rows().isNotEmpty() && prefs.text("album_sort")=="artist" }
            val artists=ui { rows().map { (field(it!!,"album") as AlbumSummary).artist.lowercase(java.util.Locale.ROOT) } }; check(artists==artists.sorted())
            dao.favorite(Favorite(source,"album",tempAlbum)); section("albums","专辑")
            click(tagged("library_view")); choose(menu(),"只看收藏专辑")
            val liked=dao.favorites(source,"album").toSet()
            await("filtered albums") { rows().isNotEmpty() && ui { rows().all { (field(it!!,"album") as AlbumSummary).albumKey in liked } } }
            check(!tagged("library_play_all").isShown)
            section("playlists","播放列表"); check(!tagged("library_play_all").isShown && !tagged("library_view").isShown)

            section("genres","风格"); await("genre detail") { kind()=="genre" && rows().isNotEmpty() }
            check(ui { !(field(pane,"albumsTab") as View).isShown && (field(pane,"songsTab") as View).isSelected })
            section("artists","歌手"); await("artist detail") { kind()=="artist" && rows().isNotEmpty() }
            click(ui { field(pane,"albumsTab") as View }); await("artist albums tab") { kind()=="artistAlbums" && rows().isNotEmpty() }
            click(tagged("library_view")); check(labels(menu()).none { it=="歌曲" || it=="专辑" }); ui { menu().dismiss() }
            report+="D4/D5/T2/T3: contextual views, real album ordering, filtered overview cannot play whole library, visible tabs only and no duplicate New Playlist: PASS"

            db.runInTransaction { dao.savePlaylist(playlist); tracks.forEachIndexed { i,t -> dao.add(PlaylistMember(playlist.id,t.id,i)) } }
            section("playlists","播放列表")
            val playlistIndex=ui { rows().indexOfFirst { (field(it!!,"playlist") as? SavedPlaylist)?.id==playlist.id } }; check(playlistIndex>=0)
            focus(playlistIndex); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); await("manual playlist") { kind()=="playlist" && rows().size==tracks.size }
            focus(35); val movedId=selectedTrack()!!
            sendKeyDownUpSync(KeyEvent.KEYCODE_MENU); choose(menu(),"下移")
            await("reorder focus") { ui { field(pane,"selected")==36 && recycler.findViewHolderForAdapterPosition(36)?.itemView?.hasFocus()==true } && selectedTrack()==movedId }
            sendKeyDownUpSync(KeyEvent.KEYCODE_MENU); choose(menu(),"从歌单移除")
            await("neighbor after removal") { rows().size==tracks.size-1 && ui { field(pane,"selected")==36 && recycler.findViewHolderForAdapterPosition(36)?.itemView?.hasFocus()==true } }
            check(selectedTrack()==tracks[37].id)
            check(ui { (recycler.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()>0 })
            val playingId=selectedTrack(); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("play selected item") { ui { field(activity,"page")=="player" && player.currentMediaItem?.mediaMetadata?.extras?.getString("track_id")==playingId && player.isPlaying } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
            val playerMenu=ui { field(activity,"playerMenuDialog") as AlertDialog }; check(labels(playerMenu).size==2 && labels(playerMenu).none { it.contains("收藏") || it.contains("全屏") || it.contains("跳转") }); ui { playerMenu.dismiss() }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("return focus to playing row") { selectedTrack()==playingId && ui { recycler.findViewHolderForAdapterPosition(36)?.itemView?.hasFocus()==true } }
            report+="D1/T4: trimmed player menu; playlist reorder/remove retain scroll and neighbor focus; Back from player returns to the originating song: PASS"

            ui { call(activity,"showPage",String::class.java to "player"); (field(activity,"progress") as View).requestFocusFromTouch() }
            check(tagged("seek_hint").isShown)
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); await("progress time dialog") { ui { field(activity,"idleFocused")==false } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); await("progress focus after dialog") { ui { field(activity,"idleFocused")==true && (field(activity,"progress") as View).hasFocus() } }
            screenshot("tv-menus-player-1080.png")
            report+="D6: focused progress explains remote controls, OK opens time input, dismissal restores progress focus: PASS"

            ui { player.seekTo(45000) }; await("queue seek") { ui { player.currentPosition>=45000 && player.isPlaying } }
            ui { call(activity,"showQueue") }; val queue=ui { field(activity,"queueDialog") as android.app.Dialog }
            val before=ui { player.currentPosition }; val count=ui { player.mediaItemCount }
            ui { val list=field(queue,"list") as android.widget.ListView; list.performItemClick(null,player.currentMediaItemIndex,player.currentMediaItemIndex.toLong()) }
            Thread.sleep(350); check(ui { player.currentPosition>=before && player.currentPosition<before+2500 && player.isPlaying })
            ui { call(queue,"actions") }; val actions=ui { field(queue,"submenu") as AlertDialog }; check("播放" !in labels(actions)); ui { actions.dismiss() }
            click(ui { queue.window!!.decorView.findViewWithTag<View>("queue_clear") })
            val confirmation=ui { field(queue,"submenu") as AlertDialog }
            check(ui { confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).hasFocus() && player.mediaItemCount==count })
            ui { confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }; check(ui { player.mediaItemCount==count && player.isPlaying })
            click(ui { queue.window!!.decorView.findViewWithTag<View>("queue_clear") }); screenshot("tv-queue-clear-confirm-1080.png")
            ui { (field(queue,"submenu") as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("confirmed clear") { ui { player.mediaItemCount==0 && !player.isPlaying && !queue.isShowing } }
            report+="D3/T5: current queued track resumes without seeking; Clear defaults to Cancel and only stops/clears after confirmation: PASS"

            section("home","首页"); click(button(activity,"设置"))
            check(ui { activity.window.decorView.findViewWithTag<View>("settings_nav_collapsed")==null })
            click(tagged("settings_lyric_size")); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("lyric setting focus") { ui { activity.currentFocus?.tag=="settings_lyric_size" } && prefs.number("lyric_size",22)==30 }
            click(tagged("settings_category_playback")); click(tagged("settings_seek_step"))
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("seek setting focus") { ui { activity.currentFocus?.tag=="settings_seek_step" } && prefs.number("seek_step")==30 }
            report+="D5/T4: navigation has one collapse control; edited settings keep their focus after labels rebuild: PASS"
        } finally {
            db.runInTransaction { dao.clearMembers(playlist.id); dao.deletePlaylist(playlist.id); if(!wasLiked) dao.unfavorite(source,"album",tempAlbum) }
            ui { prefs.set("auto_player_delay",oldDelay); prefs.set("sort",oldSort); prefs.set("album_sort",oldAlbumSort); prefs.set("album_layout",oldLayout); prefs.set("lyric_size",oldSize); prefs.set("seek_step",oldStep)
                pane.javaClass.getDeclaredField("sort").apply { isAccessible=true }.set(pane,oldSort)
                pane.javaClass.getDeclaredField("albumSort").apply { isAccessible=true }.set(pane,oldAlbumSort)
                pane.javaClass.getMethod("selectSection",String::class.java,String::class.java).invoke(pane,"home","首页")
                player.setMediaItems(originalItems,originalIndex,originalPosition); player.repeatMode=originalRepeat; player.shuffleModeEnabled=originalShuffle; player.prepare(); player.playWhenReady=originalPlay
                call(activity,"showPage",String::class.java to "library")
            }
        }
    }
    private fun interactionChecks(activity: MainActivity,player: MediaController) {
        val prefs=TvPreferences(targetContext)
        val delay=prefs.number("auto_player_delay",30); val collapsed=prefs.enabled("nav_collapsed",false)
        report+="Saved idle preference retained ($delay seconds); testing the 30-second setting and playback guards"
        await("restored queue") { ui { player.mediaItemCount>0 } }
        val original=ui { listOf(player.currentMediaItemIndex.toLong(),player.currentPosition,if(player.playWhenReady) 1L else 0L) }
        fun setDelay(seconds: Int) { ui { prefs.set("auto_player_delay",seconds); call(activity,"resetIdleNavigation") } }
        fun library() { ui { call(activity,"showPage",String::class.java to "library") } }
        fun page()=ui { field(activity,"page").toString() }
        try {
            setDelay(0); library()
            ui { Icon.values().forEach { check(Lucide.drawable(activity,it) is android.graphics.drawable.VectorDrawable) } }
            check(targetContext.assets.open("licenses/lucide.txt").bufferedReader().use { it.readText() }.contains("ISC License"))
            report+="All ${Icon.values().size} used Lucide vector assets inflate natively; bundled upstream license: PASS"
            ui { call(activity,"setNavigationCollapsed",Boolean::class.javaPrimitiveType!! to false) }
            click(button(activity,"收起导航"))
            check(ui { field(activity,"navigationCollapsed")==true && (field(activity,"railHost") as View).layoutParams.width==TvStyle.dp(activity,60) })
            @Suppress("UNCHECKED_CAST") val navigation=ui { field(activity,"navigation") as Map<String,Button> }
            check(ui { navigation.values.all { it.text.isBlank() && it.foreground!=null && !it.contentDescription.isNullOrBlank() } })
            check(TvPreferences(targetContext).enabled("nav_collapsed",false))
            click(button(activity,"专辑"))
            await("collapsed navigation album open") { ui { field(field(activity,"library")!!,"location").toString().contains("kind=albums") } }
            click(button(activity,"首页")); screenshot("tv-lucide-collapsed-1080.png")
            click(button(activity,"展开导航")); check(ui { field(activity,"navigationCollapsed")==false })
            click(button(activity,"首页")); screenshot("tv-lucide-expanded-1080.png")
            report+="D-pad collapse/expand, icon-only labels, live content resize, persistent preference and collapsed navigation: PASS"

            ui { player.pause() }; setDelay(2); library(); Thread.sleep(2600)
            check(page()=="library" && ui { !player.isPlaying })
            report+="Paused playback never enters player despite elapsed inactivity: PASS"
            ui { player.play() }; await("playing") { ui { player.isPlaying } }; library()
            Thread.sleep(1100); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(1200)
            check(page()=="library") { "Remote key did not reset idle countdown" }
            await("active idle entry",4000) { page()=="player" }; screenshot("tv-lucide-player-1080.png")
            report+="Playing-only idle entry and remote input restart the deadline: PASS"
            setDelay(0); library(); Thread.sleep(2300); check(page()=="library")
            report+="Disabled automatic entry remains disabled during playback: PASS"

            setDelay(2); library()
            val dialog=ui { AlertDialog.Builder(activity).setTitle("输入测试").setView(android.widget.EditText(activity)).setNegativeButton("关闭",null).show() }
            await("modal focus") { ui { field(activity,"idleFocused")==false } }
            Thread.sleep(2500); check(page()=="library")
            ui { dialog.dismiss() }; await("modal dismissed") { ui { field(activity,"idleFocused")==true } }
            Thread.sleep(1000); check(page()=="library"); await("fresh deadline after modal",3000) { page()=="player" }
            report+="Open input dialog suspends navigation; dismissal begins a fresh countdown: PASS"

            library(); ui { activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
            await("background") { ui { field(activity,"idleResumed")==false } }; Thread.sleep(2500); check(page()=="library")
            ui { activity.startActivity(Intent(activity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("foreground") { ui { field(activity,"idleResumed")==true && field(activity,"idleFocused")==true } }
            setDelay(0)
            report+="Background activity cannot navigate; foreground re-arms inactivity: PASS"

            ui { call(activity,"showPage",String::class.java to "settings"); activity.javaClass.getDeclaredField("settingsCategory").apply { isAccessible=true }.set(activity,"playback"); call(activity,"replaceSettings") }
            check(button(activity,"自动进入播放页：关闭").isShown)
            screenshot("tv-idle-settings-1080.png")
            ui { player.seekTo(0); player.play() }; await("real default playback") { ui { player.isPlaying } }
            setDelay(30); library(); val start=android.os.SystemClock.uptimeMillis()
            Thread.sleep(25000); check(page()=="library")
            try { await("real 30-second idle deadline",15000) { page()=="player" } }
            catch(error: Exception) { error("Idle state: ${ui { listOf(page(),player.isPlaying,player.playbackState,player.playerError?.errorCodeName,field(activity,"idleResumed"),field(activity,"idleFocused"),field(activity,"idleScheduled"),field(field(activity,"idlePolicy")!!,"lastInteractionMs"),android.os.SystemClock.uptimeMillis()) }}") }
            check(android.os.SystemClock.uptimeMillis()-start>=29500 && ui { player.isPlaying })
            report+="Actual 30-second foreground inactivity enters the player without pausing or restarting audio: PASS"
            ui { player.pause() }; library(); Thread.sleep(2300); check(page()=="library")
        } finally {
            ui { prefs.set("auto_player_delay",delay); call(activity,"setNavigationCollapsed",Boolean::class.javaPrimitiveType!! to collapsed)
                if(original[0].toInt() in 0 until player.mediaItemCount) player.seekTo(original[0].toInt(),original[1]); player.playWhenReady=original[2]==1L
                call(activity,"showPage",String::class.java to "library")
            }
        }
    }
    private fun collectionsContract(db: LibraryDatabase) {
        val source="tv-fixture-${java.util.UUID.randomUUID()}"; val dao=db.collections()
        val fixtures=(0 until 205).map { i -> LibraryTrack("$source:$i",source,when(i%3) { 0 -> "music"; 1 -> "music/disc"; else -> "music2" },"$source:album","${if(i==204) "100%_song" else "%03d".format(i)}","Artist","Album","$i.flac",null,i,0,null,120000,null,null,0,genre=if(i%2==0) "Rock" else "Pop") }
        val playlist=SavedPlaylist(source=source,name="Fixture")
        try {
            db.library().insertTracks(fixtures)
            check(dao.count(TrackScope(source).countQuery())==205 && dao.tracks(TrackScope(source).query(80)).size==80)
            check(dao.count(TrackScope(source,search="100%_").countQuery())==1)
            check(dao.count(TrackScope(source,"genre","Rock").countQuery())==103)
            val pages=listOf(0,80,160).flatMap { dao.tracks(TrackScope(source).query(80,it)) }
            check(pages.size==205 && pages.map { it.id }.distinct().size==205)
            check(dao.tracks(TrackScope(source,search="%_").query()).size==1)
            check(dao.tracks(TrackScope(source,search="' OR 1=1 --").query()).isEmpty())
            check(dao.tracks(TrackScope(source,"folder","music").query()).size==fixtures.count { it.folder=="music" || it.folder.startsWith("music/") })
            check(dao.tracks(TrackScope(source,"folder","",recursive=false).query()).isEmpty())
            check(dao.tracks(TrackScope(source,"genre","Rock").query()).size==103)
            check(dao.tracks(TrackScope(source,"artist","Artist").query()).size==205)
            report+="205-track paging, escaped search, genre/artist filters and recursive folder boundaries: PASS"
            dao.favorite(Favorite(source,"album",fixtures[0].albumKey))
            check(dao.tracks(TrackScope(source,"favorites").query()).isEmpty())
            dao.favorite(Favorite(source,"track",fixtures[2].id))
            check(dao.tracks(TrackScope(source,"favorites").query()).single().id==fixtures[2].id)
            db.runInTransaction { dao.savePlaylist(playlist); listOf(2,1,2).forEachIndexed { n,i -> dao.add(PlaylistMember(playlist.id,fixtures[i].id,n)) } }
            check(dao.tracks(TrackScope(source,"playlist",playlist.id).query()).map { it.id }==listOf(fixtures[2].id,fixtures[1].id))
            dao.updateMembers(dao.members(playlist.id).reversed().mapIndexed { i,m -> m.copy(position=i) })
            check(dao.tracks(TrackScope(source,"playlist",playlist.id).query()).first().id==fixtures[1].id)
            db.runInTransaction { db.library().deleteTracks(source); db.library().insertTracks(fixtures) }
            check(dao.tracks(TrackScope(source,"favorites").query()).size==1 && dao.members(playlist.id).size==2)
            dao.history(PlayHistory(source,fixtures[2].id,1)); dao.history(PlayHistory(source,fixtures[1].id,2))
            check(dao.tracks(TrackScope(source,"recent").query()).first().id==fixtures[1].id)
            dao.remove(playlist.id,fixtures[1].id); check(dao.members(playlist.id).size==1)
            dao.clearMembers(playlist.id); dao.deletePlaylist(playlist.id); check(db.library().folderTracks(source,"music").isNotEmpty())
            report+="Independent album/song favorites, persistent playlist dedup/order/remove, scan retention and history: PASS"
        } finally {
            db.runInTransaction { db.library().deleteTracks(source); dao.clearMembers(playlist.id); dao.deletePlaylist(playlist.id)
                db.openHelper.writableDatabase.execSQL("DELETE FROM favorites WHERE source=?",arrayOf(source)); db.openHelper.writableDatabase.execSQL("DELETE FROM play_history WHERE source=?",arrayOf(source)) }
        }
    }
}
