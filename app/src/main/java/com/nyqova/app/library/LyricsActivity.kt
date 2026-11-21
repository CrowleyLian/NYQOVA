package com.nyqova.app.library

import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nyqova.app.playback.PlaybackService
import com.nyqova.app.storage.MusicSource
import com.nyqova.core.LrcParser
import com.nyqova.core.LyricsDocument
import com.nyqova.core.MusicText
import com.nyqova.app.online.*
import com.nyqova.core.TrackIdentity
import android.app.AlertDialog
import java.util.concurrent.Executors
import com.nyqova.app.ui.TvStyle
import com.nyqova.app.ui.Lucide
import com.nyqova.app.ui.Icon
import com.nyqova.app.settings.TvPreferences
import com.nyqova.core.ChineseKeys

class LyricsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var future: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private var mediaId: String? = null
    private var document: LyricsDocument? = null
    private var active = -1
    private var sequence = 0
    private var offset = 0L
    private var timelineOffset = 0L
    private var sourceLabel = "本地 LRC"
    private var requestScope = LookupScope()
    private var lookupTask: java.util.concurrent.Future<*>? = null
    private var query: TrackIdentity? = null
    private var originalQuery: TrackIdentity? = null
    private var candidates = emptyList<LyricCandidate>()
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var lines: ListView
    private val tick = object : Runnable {
        override fun run() { update(); main.postDelayed(this, 300) }
    }
    override fun onCreate(state: Bundle?) {
        TvStyle.applyTheme(this)
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(TvStyle.dp(this@LyricsActivity,32), TvStyle.dp(this@LyricsActivity,20), TvStyle.dp(this@LyricsActivity,32), TvStyle.dp(this@LyricsActivity,20)); setBackgroundColor(TvStyle.background) }
        setContentView(root)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading = TextView(this).apply { text = "同步歌词"; textSize = 22f; setTextColor(TvStyle.text) }
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        fun button(label: String, action: () -> Unit) = TvStyle.button(this,label,action)
        top.addView(Lucide.button(this,Icon.MORE,"更多",true) {
            TvStyle.dialog(this).setTitle("歌词设置").setItems(arrayOf("提前 0.5 秒", "延后 0.5 秒", "重试匹配", "选择版本", "手动搜索")) { _,i ->
                when(i) { 0 -> { offset+=500; active=-1; update() }; 1 -> { offset-=500; active=-1; update() }; 2 -> loadLyrics(true); 3 -> selectCandidate(); 4 -> searchDialog() }
            }.show()
        })
        top.addView(Lucide.button(this,Icon.DOWN,"返回") { finish() }); root.addView(top)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply { text = "正在查找歌词…"; textSize = 12f; setTextColor(TvStyle.muted) }
        actions.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        lines = ListView(this).apply { dividerHeight = 0; setPadding(0, 24, 0, 24) }
        root.addView(lines, LinearLayout.LayoutParams(-1, 0, 1f))
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({ if(!isDestroyed) runCatching { player = f.get(); update() } }, { main.post(it) })
    }
    private fun update() {
        val controller = player ?: return
        val item = controller.currentMediaItem ?: run { status.text = "请先选择一首音乐"; return }
        if(item.mediaId != mediaId) {
            mediaId = item.mediaId; heading.text = item.mediaMetadata.title ?: "同步歌词"; document = null; active = -1; offset = 0
            originalQuery = null
            lines.adapter = null
            loadLyrics(false)
        }
        val doc = document ?: return
        if(doc.lines.isEmpty()) return
        val position = controller.currentPosition + timelineOffset + offset
        val next = doc.indexAt(position)
        if(next != active) {
            active = next; (lines.adapter as? BaseAdapter)?.notifyDataSetChanged()
            if(active >= 0) lines.smoothScrollToPositionFromTop(active, lines.height / 3, 300)
        }
        val label = "$sourceLabel · 同步歌词 · 偏移 ${offset / 1000.0} 秒"
        if(status.text.toString() != label) status.text = label
        status.visibility=View.GONE
    }
    private fun loadLyrics(force: Boolean, custom: TrackIdentity? = null) {
        val item = player?.currentMediaItem ?: return
        val duration = player?.duration?.coerceAtLeast(0) ?: 0
        val id = ++sequence
        requestScope.cancel(); lookupTask?.cancel(true); requestScope = LookupScope()
        val scope = requestScope
        candidates = emptyList(); document = null; active = -1; lines.adapter = null
        status.text = "正在查找本地、缓存和在线歌词…"
        status.visibility=View.VISIBLE
        lookupTask = worker.submit {
            val result = runCatching {
                if(custom == null) {
                    val identity = OnlineMedia.identify(this, item, duration).identity
                    main.post { if(!isDestroyed && id == sequence) { query = identity; originalQuery = identity } }
                }
                if(custom == null) LyricsRepository.resolve(this, item, duration, scope, force)
                else {
                    check(TvPreferences(this).enabled("online_lyrics")) { "请先在设置中开启在线歌词" }
                    val response = OnlineMedia.lyrics(this, custom, scope, true)
                    response.candidate?.let { record -> originalQuery?.let { OnlineMedia.rememberLyrics(this, it, record,response.candidates,response.automatic) } }
                    ResolvedLyrics(response.candidate?.takeUnless { it.instrumental }?.document(),
                        if(response.candidate?.instrumental == true) "纯音乐，无歌词" else if(response.candidate != null) "${response.candidate.provider}${if(response.automatic) " · 自动选择，可更换版本" else " · 在线"}" else if(response.candidates.isNotEmpty()) "请选择匹配版本" else "未找到歌词", 0, custom, response)
                }
            }
            main.post {
                if(isDestroyed || id != sequence || scope.cancelled.get()) return@post
                result.onSuccess { resolved ->
                    query = resolved.identity; candidates = resolved.result?.candidates ?: emptyList()
                    if(custom == null) originalQuery = resolved.identity
                    sourceLabel = resolved.source; timelineOffset = resolved.timelineOffsetMs
                    val doc = resolved.document
                    if(doc != null) displayDocument(doc) else status.text = sourceLabel
                }.onFailure { status.text = "${it.message ?: "歌词联网失败"} · 点“重试”再次查找" }
            }
        }
    }
    private fun selectCandidate() {
        if(candidates.isEmpty()) { status.text = "暂无候选；可重试或手动搜索曲名、歌手。"; return }
        val rows = candidates.toList()
        val labels = rows.map { "${it.title} · ${it.artist} · ${it.durationMs / 60000}:${"%02d".format(it.durationMs / 1000 % 60)}${if(it.synced.isNotBlank()) " · 同步" else if(it.instrumental) " · 纯音乐" else " · 文本"}" }
        TvStyle.dialog(this).setTitle("选择对应录音版本").setItems(labels.toTypedArray()) { _, index ->
            val q = query ?: return@setItems
            val original = originalQuery ?: q
            val candidate = rows[index]
            val selectedMedia = mediaId
            worker.execute {
                val saved = runCatching {
                    OnlineMedia.rememberLyrics(this, q, candidate)
                    OnlineMedia.rememberLyrics(this, original, candidate)
                }.isSuccess
                main.post { if(!isDestroyed && mediaId == selectedMedia) {
                    sourceLabel = if(saved) "${candidate.provider} · 手动选择" else "${candidate.provider} · 本次匹配（缓存失败）"; timelineOffset = 0
                    if(candidate.instrumental) { document = null; lines.adapter = null; status.text = "纯音乐，无歌词" }
                    else displayDocument(candidate.document())
                } }
            }
        }.setNegativeButton("取消", null).show()
    }
    private fun searchDialog() {
        val current = query ?: TrackIdentity.from(player?.mediaMetadata?.title.toString(), player?.mediaMetadata?.artist?.toString().orEmpty(), "", player?.duration?.coerceAtLeast(0) ?: 0)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 0) }
        fun field(hint: String, value: String) = EditText(this).apply {
            this.hint = hint; setText(value); isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            form.addView(this)
        }
        val name = field("曲名", current.title)
        val artist = field("歌手", current.artist)
        TvStyle.dialog(this).setTitle("在线搜索歌词").setView(form).setPositiveButton("搜索") { _, _ ->
            val q = TrackIdentity.from(name.text.toString(), artist.text.toString(), "", current.durationMs)
            if(q.title.isNotBlank()) loadLyrics(true, q)
        }.setNegativeButton("取消", null).show()
    }
    private fun displayDocument(doc: LyricsDocument) {
        document = doc; active = -1
        if(doc.lines.isEmpty()) {
            val value=if(TvPreferences(this).enabled("simplified")) ChineseKeys.simplified(doc.plainText) else doc.plainText
            lines.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, value.lines())
            status.visibility=View.GONE
        } else { lines.adapter = LyricAdapter(doc); update() }
    }
    private inner class LyricAdapter(private val doc: LyricsDocument) : BaseAdapter() {
        override fun getCount() = doc.lines.size
        override fun getItem(position: Int) = doc.lines[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: android.view.View?, parent: ViewGroup) = (recycled as? TextView ?: TextView(this@LyricsActivity)).apply {
            val value=doc.lines[position].text.ifBlank { "♪" }
            text = if(TvPreferences(this@LyricsActivity).enabled("simplified")) ChineseKeys.simplified(value) else value
            textSize = (TvPreferences(this@LyricsActivity).number("lyric_size",22)+if(position==active) 5 else 0).toFloat()
            setTextColor(if(position == active) TvStyle.text else TvStyle.muted)
            gravity = Gravity.CENTER; setPadding(24, 28, 24, 28)
        }
    }
    override fun onStart() { super.onStart(); main.post(tick) }
    override fun onStop() { main.removeCallbacks(tick); super.onStop() }
    override fun onDestroy() { sequence++; requestScope.cancel(); lookupTask?.cancel(true); worker.shutdownNow(); future?.let { MediaController.releaseFuture(it) }; super.onDestroy() }
}
