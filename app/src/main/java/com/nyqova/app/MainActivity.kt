package com.nyqova.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nyqova.app.library.*
import com.nyqova.app.online.*
import com.nyqova.app.playback.*
import com.nyqova.app.settings.TvPreferences
import com.nyqova.app.storage.*
import com.nyqova.app.ui.*
import com.nyqova.core.ChineseKeys
import com.nyqova.core.LyricsDocument
import com.nyqova.core.IdlePlaybackPolicy
import java.io.File
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val onlineWorker = Executors.newFixedThreadPool(2)
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val prefs by lazy { TvPreferences(this) }
    private lateinit var library: TvLibraryPane
    private lateinit var body: FrameLayout
    private lateinit var playback: LinearLayout
    private lateinit var settings: ScrollView
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: Button
    private val miniControls=mutableListOf<View>()
    private var miniOrigin: View?=null
    private var miniFromLibrary=false
    private lateinit var miniPlay: Button
    private lateinit var miniFavorite: Button
    private lateinit var miniArt: ImageView
    private lateinit var rail: LinearLayout
    private lateinit var railHost: ScrollView
    private lateinit var shell: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var navigationHint: TextView
    private lateinit var miniProgress: ProgressBar
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var time: TextView
    private lateinit var seekHint: TextView
    private lateinit var play: Button
    private lateinit var mode: Button
    private lateinit var progress: SeekBar
    private lateinit var artwork: ImageView
    private lateinit var lyricsColumn: LinearLayout
    private val lyricRows = mutableListOf<TextView>()
    private val navigation = mutableMapOf<String, Button>()
    private val navigationLabels=mutableMapOf<String,String>()
    private val navigationIcons=mutableMapOf<String,Icon>()
    private lateinit var navigationToggle: Button
    private var navigationCollapsed=false
    private val idlePolicy=IdlePlaybackPolicy()
    private var idleResumed=false
    private var idleFocused=false
    private var idleScheduled=false
    private var idleWasPlaying=false
    private val idleNavigation=Runnable {
        idleScheduled=false
        val remaining=idlePolicy.remainingMs(SystemClock.uptimeMillis(),prefs.number("auto_player_delay",30))
        if(idleResumed && idleFocused && page!="player" && remaining==0L && controller?.isPlaying==true) showPage("player") else scheduleIdleNavigation()
    }
    private var page = "library"
    private var selectedSection="home"
    private var settingsCategory="appearance"
    private lateinit var playerFavorite: Button
    private var settingsFocus: View?=null
    private var connectionDialogs: ConnectionDialogs?=null
    private var queueDialog: TvQueueDialog?=null
    private var playerMenuDialog: AlertDialog?=null
    private var browserReturnFocus: View?=null
    private var playerReturnPage="library"
    private lateinit var playerBack: Button
    private var pendingSettingFocus: String?=null
    private var pendingSettingScroll=0
    private var displayedMedia: String? = null
    private var onlineMediaId: String? = null
    private var onlineScope = LookupScope()
    private var onlineTask: java.util.concurrent.Future<*>? = null
    private var inlineLyrics: LyricsDocument? = null
    private var inlineOffset = 0L
    private var inlineStatus = ""
    private var lyricRetries=0
    private var seeking = false
    private var queueRequest = 0
    private var favoriteId: String? = null
    private var favorited = false
    private var pendingAlbum: Pair<String, Int>? = null
    private val tick = object : Runnable { override fun run() { updatePlayback(); main.postDelayed(this,400) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        TvStyle.applyTheme(this)
        super.onCreate(savedInstanceState)
        immersive()
        if(!MusicSource.initialized) runCatching { ConnectionStore.load(this)?.activate() }
        if(!MusicSource.initialized) MusicSource.initialized = true
        settingsCategory=savedInstanceState?.getString("settings_category") ?: "appearance"
        pendingSettingFocus=savedInstanceState?.getString("settings_focus")
        pendingSettingScroll=savedInstanceState?.getInt("settings_scroll") ?: 0
        buildUi()
        savedInstanceState?.getString("page")?.let { showPage(it) }
        pendingAlbum = intent.getStringExtra("library_album")?.let { it to intent.getIntExtra("track_index",0) }
        val future = MediaController.Builder(this,SessionToken(this,ComponentName(this,PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({ if(!isDestroyed) runCatching {
            controller = future.get()
            controller!!.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { updatePlayback() }
                override fun onPlayerError(error: PlaybackException) {
                    inlineStatus = "连接中断，可在设置中检查网络后重新播放"
                    toast(inlineStatus)
                }
            })
            updatePlayback()
            pendingAlbum?.let { pendingAlbum=null; loadLibraryAlbum(it.first,it.second) }
        }.onFailure { toast("播放服务暂时不可用，请重新打开") } }, { main.post(it) })
    }
    private fun buildUi() {
        window.statusBarColor=TvStyle.background; window.navigationBarColor=TvStyle.background
        val root=LinearLayout(this).apply {
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(TvStyle.top,TvStyle.background))
            setPadding(dp(18),dp(16),dp(22),dp(16))
        }
        shell=root
        overlay=FrameLayout(this)
        overlay.addView(root,FrameLayout.LayoutParams(-1,-1))
        navigationHint=TvStyle.label(this,"",14,bold=true).apply {
            tag="navigation_hint"; visibility=View.GONE; isFocusable=false; isClickable=false
            background=TvStyle.shape(this@MainActivity,TvStyle.panel,8,true)
            setPadding(dp(14),dp(9),dp(14),dp(9)); elevation=dp(8).toFloat()
        }
        overlay.addView(navigationHint,FrameLayout.LayoutParams(-2,-2))
        setContentView(overlay)
        rail=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=TvStyle.shape(this@MainActivity,TvStyle.panel); setPadding(dp(6),dp(8),dp(6),dp(8)) }
        railHost=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; addView(rail,FrameLayout.LayoutParams(-1,-2)) }
        root.addView(railHost,LinearLayout.LayoutParams(dp(136),-1).apply { rightMargin=dp(20) })
        fun nav(label: String,key: String,icon: Icon,kind: String? = null,title: String=label) {
            val b=Lucide.button(this,icon,label) { if(kind!=null) { selectedSection=key; library.selectSection(kind,title); showPage("library") } else showPage(key) }.apply { gravity=Gravity.CENTER_VERTICAL; textSize=14f; background=TvStyle.selector(this@MainActivity,true); tag="nav_$key"; maxLines=1 }
            b.onFocusChangeListener=View.OnFocusChangeListener { _,_ -> updateNavigationHint() }
            navigationLabels[key]=label
            navigationIcons[key]=icon
            navigation[key]=b; rail.addView(b,LinearLayout.LayoutParams(-1,dp(40)).apply { bottomMargin=dp(2) })
        }
        nav("首页","home",Icon.HOME,"home","首页")
        nav("收藏","favorites",Icon.HEART,"favorites","我喜欢")
        nav("最近","recent",Icon.CLOCK,"recent","最近播放")
        rail.addView(View(this),LinearLayout.LayoutParams(1,dp(10)))
        nav("专辑","albums",Icon.DISC,"albums","专辑")
        nav("歌手","artists",Icon.ARTIST,"artists","歌手")
        nav("风格","genres",Icon.GENRE,"genres","风格")
        nav("音乐库","all",Icon.LIBRARY,"all","音乐库")
        nav("歌单","playlists",Icon.QUEUE,"playlists","播放列表")
        rail.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        nav("文件夹","folders",Icon.FOLDER,"folders","NAS 文件夹")
        nav("设置","settings",Icon.SETTINGS)
        navigationToggle=Lucide.button(this,Icon.COLLAPSE,"收起导航") { setNavigationCollapsed(!navigationCollapsed) }.apply { tag="nav_toggle"; textSize=12f; maxLines=1 }
        navigationToggle.onFocusChangeListener=View.OnFocusChangeListener { _,_ -> updateNavigationHint() }
        rail.addView(navigationToggle,LinearLayout.LayoutParams(-1,dp(44)))
        setNavigationCollapsed(prefs.enabled("nav_collapsed",false))
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(content,LinearLayout.LayoutParams(0,-1,1f))
        body=FrameLayout(this); content.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        library=TvLibraryPane(this,::startQueue,::enqueue,::refreshFavorite,::resumePlaying)
        body.addView(library,FrameLayout.LayoutParams(-1,-1))
        playback=buildPlayback(); body.addView(playback,FrameLayout.LayoutParams(-1,-1))
        settings=buildSettings(); body.addView(settings,FrameLayout.LayoutParams(-1,-1))
        mini=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; background=TvStyle.shape(this@MainActivity,TvStyle.raised,28); setPadding(dp(10),dp(6),dp(10),dp(6)) }
        content.addView(mini,LinearLayout.LayoutParams(-1,dp(56)).apply { topMargin=dp(10); leftMargin=dp(18); rightMargin=dp(18) })
        mini.addView(Lucide.button(this,Icon.PREVIOUS,"上一曲",true) { controller?.seekToPreviousMediaItem() })
        miniPlay=Lucide.button(this,Icon.PLAY,"播放",true,::togglePlay); mini.addView(miniPlay)
        mini.addView(Lucide.button(this,Icon.NEXT,"下一曲",true) { controller?.seekToNextMediaItem() })
        miniArt=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setImageDrawable(Lucide.drawable(this@MainActivity,Icon.MUSIC,color=TvStyle.muted)) }
        mini.addView(miniArt,LinearLayout.LayoutParams(dp(44),dp(44)))
        val miniTrack=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        miniTitle=button("选择一首音乐") { showPage("player") }.apply { gravity=Gravity.CENTER_VERTICAL; maxLines=2; textSize=12f; minHeight=0; minimumHeight=0; background=TvStyle.selector(this@MainActivity,true); ellipsize=android.text.TextUtils.TruncateAt.END }
        miniTrack.addView(miniTitle,LinearLayout.LayoutParams(-1,0,1f))
        miniProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=ColorStateList.valueOf(TvStyle.accent); isFocusable=false }
        miniTrack.addView(miniProgress,LinearLayout.LayoutParams(-1,dp(3)))
        mini.addView(miniTrack,LinearLayout.LayoutParams(0,-1,1f).apply { rightMargin=dp(10) })
        miniFavorite=Lucide.button(this,Icon.HEART,"收藏歌曲",true,::toggleFavorite); mini.addView(miniFavorite)
        mini.addView(Lucide.button(this,Icon.QUEUE,"播放队列",true,::showQueue))
        for(i in 0 until mini.childCount) if(mini.getChildAt(i) is Button) (mini.getChildAt(i) as Button).background=TvStyle.selector(this,true)
        miniControls.addAll(listOf(mini.getChildAt(0),miniPlay,mini.getChildAt(2),miniTitle,miniFavorite,mini.getChildAt(mini.childCount-1)))
        miniControls.forEachIndexed { index,view -> view.tag="mini_control_$index" }
        mini.tag="mini_player"
        showPage("library")
        navigation["home"]?.isSelected=true; navigation["home"]?.requestFocus()
    }
    private fun buildPlayback(): LinearLayout {
        val pane=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(4),dp(20),dp(8)) }
        val top=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        playerBack=Lucide.button(this,Icon.DOWN,"返回音乐库") { showPage(playerReturnPage) }; top.addView(playerBack)
        top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        playerFavorite=Lucide.button(this,Icon.HEART,"收藏歌曲",true,::toggleFavorite); top.addView(playerFavorite)
        top.addView(Lucide.button(this,Icon.MORE,"更多",true,::playingMenu)); top.addView(Lucide.button(this,Icon.QUEUE,"播放队列",true,::showQueue))
        top.addView(Lucide.button(this,Icon.FULLSCREEN,"全屏歌词",true) { startActivity(Intent(this,LyricsActivity::class.java)) }); pane.addView(top)
        val visual=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(40),dp(12),dp(32),0) }
        pane.addView(visual,LinearLayout.LayoutParams(-1,0,1f))
        val left=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        visual.addView(left,LinearLayout.LayoutParams(0,-1,1f).apply { rightMargin=dp(38) })
        val cover=FrameLayout(this).apply { background=TvStyle.shape(this@MainActivity,TvStyle.panel,16); clipToOutline=true }
        cover.addView(ImageView(this).apply { setImageDrawable(Lucide.drawable(this@MainActivity,Icon.MUSIC,color=TvStyle.accent)); scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(68),dp(48),dp(68),dp(48)) },FrameLayout.LayoutParams(-1,-1))
        artwork=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
        cover.addView(artwork,FrameLayout.LayoutParams(-1,-1))
        left.addView(cover,LinearLayout.LayoutParams(-1,0,1f).apply { bottomMargin=dp(12) })
        lyricsColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(8),0) }
        repeat(5) { index -> lyricRows += TvStyle.label(this,"",if(index==2) 22 else 17,if(index==2) TvStyle.text else TvStyle.muted,index==2).apply {
            gravity=Gravity.CENTER_VERTICAL; maxLines=2; minHeight=dp(48); lyricsColumn.addView(this,LinearLayout.LayoutParams(-1,-2))
        } }
        visual.addView(lyricsColumn,LinearLayout.LayoutParams(0,-1,1.15f))
        title=TvStyle.label(this,"选择一首音乐",24,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
        artist=TvStyle.label(this,"",12,TvStyle.muted).apply { maxLines=1 }
        left.addView(title); left.addView(artist)
        progress=SeekBar(this).apply {
            max=1000; contentDescription="播放进度，左右快进快退，确认输入时间"
            progressTintList=ColorStateList.valueOf(TvStyle.accent); thumbTintList=ColorStateList.valueOf(TvStyle.accent)
            background=TvStyle.selector(this@MainActivity,true)
            setOnKeyListener { _,key,event ->
                when(key) {
                    KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if(event.action==KeyEvent.ACTION_DOWN) controller?.let { p -> if(p.duration>0) p.seekTo((p.currentPosition+(if(key==KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)*prefs.number("seek_step").toLong()*1000).coerceIn(0,p.duration)) }; true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> { if(event.action==KeyEvent.ACTION_UP) jumpDialog(); true }
                    else -> false
                }
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?,value: Int,user: Boolean) { if(user && !seeking) controller?.let { if(it.duration>0) it.seekTo(it.duration*value/1000) } }
                override fun onStartTrackingTouch(bar: SeekBar?) { seeking=true }
                override fun onStopTrackingTouch(bar: SeekBar?) { seeking=false; controller?.let { if(it.duration>0) it.seekTo(it.duration*progress/1000) } }
            })
        }
        left.addView(progress,LinearLayout.LayoutParams(-1,dp(28)))
        time=TvStyle.label(this,"00:00 / --:--",11,TvStyle.muted); left.addView(time)
        seekHint=TvStyle.label(this,"",11,TvStyle.muted).apply { visibility=View.GONE; tag="seek_hint" }
        left.addView(seekHint)
        progress.onFocusChangeListener=View.OnFocusChangeListener { _,focused ->
            seekHint.text="左右：±${prefs.number("seek_step")} 秒 · 确认：定位时间"
            seekHint.visibility=if(focused) View.VISIBLE else View.GONE
        }
        val controls=LinearLayout(this)
        controls.addView(Lucide.button(this,Icon.PREVIOUS,"上一曲",true) { controller?.seekToPreviousMediaItem() },LinearLayout.LayoutParams(0,dp(44),1f))
        play=Lucide.button(this,Icon.PLAY,"播放",true,::togglePlay); controls.addView(play,LinearLayout.LayoutParams(0,dp(48),1f))
        controls.addView(Lucide.button(this,Icon.NEXT,"下一曲",true) { controller?.seekToNextMediaItem() },LinearLayout.LayoutParams(0,dp(44),1f))
        mode=button("顺序") { controller?.let { p -> when {
            p.shuffleModeEnabled -> { p.shuffleModeEnabled=false; p.repeatMode=Player.REPEAT_MODE_OFF }
            p.repeatMode==Player.REPEAT_MODE_OFF -> p.repeatMode=Player.REPEAT_MODE_ALL
            p.repeatMode==Player.REPEAT_MODE_ALL -> p.repeatMode=Player.REPEAT_MODE_ONE
            else -> { p.repeatMode=Player.REPEAT_MODE_ALL; p.shuffleModeEnabled=true }
        } } }; Lucide.decorate(mode,Icon.REPEAT); controls.addView(mode)
        for(i in 0 until controls.childCount) controls.getChildAt(i).background=TvStyle.selector(this,true)
        left.addView(controls)
        lyricsColumn.visibility=if(prefs.text("player_view","lyrics")=="cover") View.GONE else View.VISIBLE
        return pane
    }
    private fun buildSettings(): ScrollView {
        val layout=LinearLayout(this).apply { setPadding(dp(50),dp(34),dp(50),dp(18)) }
        val categories=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=TvStyle.shape(this@MainActivity,TvStyle.panel); setPadding(dp(6),dp(8),dp(6),dp(8)) }
        layout.addView(categories,LinearLayout.LayoutParams(dp(128),-2).apply { rightMargin=dp(22) })
        listOf("外观设置" to "appearance","音乐库管理" to "library","播放与歌词" to "playback","在线服务" to "online","服务器设置" to "source","关于" to "about").forEach { (label,key) ->
            categories.addView(button(label) { settingsCategory=key; replaceSettings("settings_category_$key") }.apply { tag="settings_category_$key"; isSelected=key==settingsCategory; if(isSelected) settingsFocus=this; textSize=13f; background=TvStyle.selector(this@MainActivity,true) },LinearLayout.LayoutParams(-1,dp(44)))
        }
        categories.addView(Lucide.button(this,Icon.DOWN,"返回音乐库") { showPage("library") }.apply { textSize=12f })
        val right=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(right,LinearLayout.LayoutParams(0,-2,1f))
        right.addView(TvStyle.label(this,when(settingsCategory) { "appearance" -> "外观设置"; "library" -> "音乐库管理"; "playback" -> "播放与歌词"; "online" -> "在线服务"; "about" -> "关于"; else -> "服务器设置" },24,bold=true),LinearLayout.LayoutParams(-1,dp(52)))
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=TvStyle.shape(this@MainActivity,TvStyle.panel); setPadding(dp(14),dp(12),dp(14),dp(12)) }
        right.addView(rows)
        if(settingsCategory=="source") {
        rows.addView(button("音乐源与自动发现") { connectionDialog() })
        rows.addView(TvStyle.label(this,MusicSource.label(),12,TvStyle.muted).apply { maxLines=2 })
        rows.addView(TvStyle.label(this,"连接信息会加密保存，重新打开自动连接",12,TvStyle.muted).apply { setPadding(0,dp(14),0,dp(4)) })
        }
        if(settingsCategory=="library") {
        rows.addView(button("扫描音乐库") { showPage("library"); library.scan() })
        rows.addView(button("完整重新扫描") { showPage("library"); library.scan(true) })
        rows.addView(button("取消正在进行的扫描") { library.cancelScan() })
        rows.addView(button("查看未补全文件 / 重试") { showIndexProblems() }.apply { tag="settings_index_problems" })
        }
        fun toggle(label: String,key: String,change: () -> Unit = {}) {
            val control=Switch(this).apply {
                text=label; textSize=16f; setTextColor(TvStyle.text); isChecked=prefs.enabled(key)
                tag="settings_$key"; minHeight=dp(48); background=TvStyle.selector(this@MainActivity,true); setPadding(dp(12),0,dp(12),0)
                thumbTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(TvStyle.accent,TvStyle.muted))
                trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf((TvStyle.accent and 0xffffff) or 0x66000000,TvStyle.raised))
                setOnCheckedChangeListener { _, checked -> prefs.set(key,checked); change() }
            }; rows.addView(control,LinearLayout.LayoutParams(-1,dp(48)))
        }
        if(settingsCategory=="appearance") {
            rows.addView(TvStyle.label(this,"外观主题",15,bold=true))
            val themes=LinearLayout(this)
            TvStyle.palettes.forEach { candidate ->
                val card=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL; isFocusable=true; isClickable=true; id=View.generateViewId()
                    tag="settings_theme_${candidate.id}"; isSelected=TvStyle.palette.id==candidate.id
                    background=TvStyle.selector(this@MainActivity,true); setPadding(dp(8),dp(8),dp(8),dp(8))
                    contentDescription="${candidate.title}${if(isSelected) "，已选中" else ""}"
                    addView(TvStyle.label(this@MainActivity,candidate.title,13,bold=true))
                    addView(TvStyle.label(this@MainActivity,candidate.description,10,TvStyle.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                    val preview=LinearLayout(this@MainActivity).apply { setPadding(0,dp(6),0,dp(4)) }
                    listOf(candidate.background,candidate.panel,android.graphics.Color.parseColor(candidate.defaultAccent)).forEach { c -> preview.addView(View(this@MainActivity).apply { setBackgroundColor(c) },LinearLayout.LayoutParams(0,dp(12),1f)) }
                    addView(preview)
                    addView(TvStyle.label(this@MainActivity,if(isSelected) "当前使用" else "",10,TvStyle.muted))
                    setOnClickListener { chooseTheme(candidate.id) }
                }
                themes.addView(card,LinearLayout.LayoutParams(0,dp(96),1f))
            }; rows.addView(themes)
            rows.addView(TvStyle.label(this,"强调色",15,bold=true).apply { setPadding(0,dp(10),0,0) })
            val colorRows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            TvStyle.accents.keys.toList().chunked(4).forEach { choices ->
                val colors=LinearLayout(this)
                choices.forEach { color -> colors.addView(button(if(TvStyle.accent==android.graphics.Color.parseColor(color)) "已选" else "") {
                    prefs.set("accent",color); prefs.set("theme_accent_${TvStyle.palette.id}",color); recreate()
                }.apply { tag="settings_accent_$color"; isSelected=TvStyle.accent==android.graphics.Color.parseColor(color); textSize=12f; Lucide.decorate(this,Icon.COLOR,26,android.graphics.Color.parseColor(color)); contentDescription="强调色 $color${if(isSelected) "，已选中" else ""}" },LinearLayout.LayoutParams(0,dp(44),1f)) }
                colorRows.addView(colors)
            }; rows.addView(colorRows)
            toggle("启用界面动画","animations")
            rows.addView(button("歌词字号：${prefs.number("lyric_size",22)}") {
                TvStyle.dialog(this).setTitle("歌词字号").setItems(arrayOf("标准","大","特大")) { _,i -> prefs.set("lyric_size",listOf(22,26,30)[i]); replaceSettings("settings_lyric_size") }.show()
            }.apply { tag="settings_lyric_size" })
        }
        if(settingsCategory=="about") {
            val info=packageManager.getPackageInfo(packageName,0)
            rows.addView(TvStyle.label(this,"聆序",26,bold=true))
            rows.addView(TvStyle.label(this,"NYQOVA · NAS 音乐播放器",16).apply { setPadding(0,dp(12),0,dp(16)) })
            rows.addView(TvStyle.label(this,"作者：L.VSN",16))
            rows.addView(TvStyle.label(this,"版本：${info.versionName} (${info.versionCode})",16).apply { tag="about_version"; setPadding(0,dp(12),0,dp(12)) })
            rows.addView(TvStyle.label(this,"适用于 Android TV · armeabi-v7a · Android 6.0 及以上",12,TvStyle.muted).apply { setPadding(0,0,0,dp(16)) })
            rows.addView(button("开源许可") {
                val files=assets.list("licenses").orEmpty().sorted()
                TvStyle.dialog(this).setTitle("开源许可").setItems(files.toTypedArray()) { _,index ->
                    val text=TvStyle.label(this,assets.open("licenses/${files[index]}").bufferedReader().use { it.readText() },12).apply { setPadding(dp(16),dp(12),dp(16),dp(12)) }
                    TvStyle.dialog(this).setTitle(files[index]).setView(ScrollView(this).apply { addView(text); isFocusable=true }).setNegativeButton("关闭",null).show()
                }.setNegativeButton("关闭",null).show()
            })
        }
        if(settingsCategory=="playback") {
        toggle("记忆播放队列和进度","remember")
        toggle("进入 App 自动继续上次播放","auto_resume")
        rows.addView(Lucide.button(this,Icon.CLOCK,"自动进入播放页：${idleDelayLabel()}") { idleDelayDialog() }.apply { tag="idle_delay_setting" })
        rows.addView(TvStyle.label(this,"仅正在播放时生效；暂停、弹窗、输入和后台时不跳转",12,TvStyle.muted))
        toggle("歌词优先显示简体中文","simplified") { updatePlayback() }
        rows.addView(button("快进 / 快退步长：${prefs.number("seek_step")} 秒") {
            TvStyle.dialog(this).setTitle("跳转步长").setItems(arrayOf("5 秒","10 秒","30 秒")) { _,i -> prefs.set("seek_step",listOf(5,10,30)[i]); replaceSettings("settings_seek_step") }.show()
        }.apply { tag="settings_seek_step" })
        }
        if(settingsCategory=="online") {
            toggle("在线匹配歌词","online_lyrics") { onlineMediaId=null; updatePlayback() }
            toggle("在线匹配封面","online_art") { onlineMediaId=null; updatePlayback() }
            fun sourceChoice(key: String,label: String,values: Array<String>,names: Array<String>) {
                val selected=values.indexOf(prefs.text(key,"auto")).coerceAtLeast(0)
                rows.addView(button("$label：${names[selected]}") {
                    TvStyle.dialog(this).setTitle(label).setSingleChoiceItems(names,selected) { dialog,index ->
                        prefs.set(key,values[index]); dialog.dismiss(); onlineMediaId=null; updatePlayback(); replaceSettings("settings_$key")
                    }.setNegativeButton("取消",null).show()
                }.apply { tag="settings_$key" })
            }
            sourceChoice("lyrics_source","歌词来源",arrayOf("auto","lrclib","lrcapi"),arrayOf("自动切换","LRCLIB","LrcAPI"))
            sourceChoice("art_source","封面来源",arrayOf("auto","musicbrainz","lrcapi"),arrayOf("自动切换","MusicBrainz / CAA","LrcAPI"))
            rows.addView(TvStyle.label(this,"本地文件和缓存优先；自动模式下主源失败或未匹配时尝试备用源",12,TvStyle.muted))
            rows.addView(button("重试当前歌曲的在线歌词") {
                val item=controller?.currentMediaItem
                if(item==null) toast("请先选择歌曲") else { lyricRetries=0; enrichPlaying(item,controller?.duration?.coerceAtLeast(0) ?: 0,true); toast("正在重新匹配歌词") }
            }.apply { tag="settings_retry_lyrics" })
        }
        if(settingsCategory=="library") {
        toggle("隐藏 CUE 原始音频文件","hide_cue") { library.refresh() }
        rows.addView(button("清理在线封面与歌词缓存") {
            onlineScope.cancel(); onlineTask?.cancel(true)
            worker.execute { val result=runCatching { OnlineMedia.clearCache(this) }; main.post { onlineMediaId=null; toast(if(result.isSuccess) "在线缓存已清理" else "部分缓存未清理，请重试") } }
        })
        }
        return ScrollView(this).apply { addView(layout); isFillViewport=true }
    }
    private fun chooseTheme(id: String) {
        val candidate=TvStyle.palettes.firstOrNull { it.id==id } ?: return
        prefs.set("theme_accent_${TvStyle.palette.id}",prefs.text("accent",TvStyle.palette.defaultAccent))
        prefs.set("appearance_theme",candidate.id)
        prefs.set("accent",prefs.text("theme_accent_${candidate.id}",candidate.defaultAccent))
        recreate()
    }
    private fun replaceSettings() { rebuildSettings(currentFocus?.tag as? String) }
    private fun replaceSettings(focusTag: String) { rebuildSettings(focusTag) }
    private fun rebuildSettings(focusTag: String?) {
        pendingSettingFocus=focusTag; pendingSettingScroll=settings.scrollY
        body.removeView(settings); settings=buildSettings(); body.addView(settings,FrameLayout.LayoutParams(-1,-1)); showPage("settings")
    }
    private fun showPage(key: String) {
        val previous=page
        if(previous=="library" && key!="library") { browserReturnFocus=currentFocus; library.captureReturnFocus() }
        if(previous=="settings" && key!="settings") { pendingSettingScroll=settings.scrollY; pendingSettingFocus=currentFocus?.tag as? String }
        if(key=="player" && previous!="player") {
            playerReturnPage=if(previous=="settings") "settings" else "library"
            playerBack.text=if(playerReturnPage=="settings") "返回设置" else "返回音乐库"
            playerBack.contentDescription=playerBack.text
        }
        page=key; library.visibility=if(key=="library") View.VISIBLE else View.GONE
        if(key=="player") shell.setPadding(0,0,0,0) else shell.setPadding(dp(18),dp(16),dp(22),dp(16))
        playback.visibility=if(key=="player") View.VISIBLE else View.GONE; settings.visibility=if(key=="settings") View.VISIBLE else View.GONE
        mini.visibility=if(key=="library" && (controller?.mediaItemCount ?: 0)>0) View.VISIBLE else View.GONE
        railHost.visibility=if(key=="library") View.VISIBLE else View.GONE
        navigation.forEach { (name,b) -> b.isSelected=name==(if(key=="library") selectedSection else key) }
        updatePlayback()
        if(key=="settings") settings.post {
            settings.scrollTo(0,pendingSettingScroll)
            val target=pendingSettingFocus?.let { settings.findViewWithTag<View>(it) }
            if(target!=null) { target.requestFocus(); pendingSettingFocus=null } else if(!settings.hasFocus()) settingsFocus?.requestFocus()
        }
        if(key=="library" && previous!="library") library.post {
            val previousFocus=browserReturnFocus
            if(library.restoreReturnFocus()) Unit
            else if(previousFocus?.isAttachedToWindow==true && previousFocus.isShown && previousFocus.requestFocus()) Unit
            else navigation[selectedSection]?.requestFocus()
        }
        if(key=="player" && previous!=key) play.requestFocus()
        updateNavigationHint()
        resetIdleNavigation()
    }
    private fun togglePlay() { controller?.let { if(it.isPlaying) it.pause() else { if(it.playbackState==Player.STATE_ENDED) it.seekTo(0); it.prepare(); it.play() } } }
    private fun resumePlaying(song: Song): Boolean {
        val player=controller ?: return false
        val current=player.currentMediaItem ?: return false
        if(player.playbackState==Player.STATE_ENDED) return false
        val source=MusicSource.key(); val candidate=PlaybackMemory.item(song,source)
        if(current.mediaMetadata.extras?.getString("source_key")!=source || current.mediaId!=candidate.mediaId ||
            current.clippingConfiguration.startPositionMs!=candidate.clippingConfiguration.startPositionMs ||
            current.clippingConfiguration.endPositionMs!=candidate.clippingConfiguration.endPositionMs) return false
        queueRequest++
        if(player.playbackState==Player.STATE_IDLE) player.prepare()
        player.play(); showPage("player"); return true
    }
    private fun startQueue(songs: List<Song>, index: Int) {
        if(songs.isEmpty() || controller==null) return
        val request=++queueRequest; val source=MusicSource.key()
        worker.execute {
            val result=runCatching { songs.map { PlaybackMemory.item(it,source) } }
            main.post { if(!isDestroyed && request==queueRequest && source==MusicSource.key()) result.onSuccess {
                controller?.let { player -> player.setMediaItems(it,index.coerceIn(0,it.lastIndex),0); player.prepare(); player.play() }
                showPage("player")
            }.onFailure { toast("播放列表读取失败") } }
        }
    }
    private fun enqueue(song: Song,next: Boolean) {
        controller?.let { p ->
            val item=PlaybackMemory.item(song,MusicSource.key())
            if(next && p.mediaItemCount>0) p.addMediaItem(p.currentMediaItemIndex+1,item) else p.addMediaItem(item)
            toast(if(next) "将作为下一首播放" else "已加入队列")
        }
    }
    private fun showQueue() {
        val p=controller ?: return
        if(p.mediaItemCount==0) { toast("播放队列为空"); return }
        queueDialog?.dismiss(); queueDialog=TvQueueDialog(this,p).also { it.show() }
    }
    private fun toggleFavorite() { favoriteId?.let { library.favoriteTrack(it) } ?: toast("扫描到音乐库后即可收藏此曲") }
    private fun refreshFavorite() {
        val id=favoriteId; val source=MusicSource.key()
        worker.execute { val liked=runCatching { id!=null && id in LibraryDatabase.get(this).collections().favorites(source,"track") }.getOrDefault(false)
            main.post { if(!isDestroyed && favoriteId==id) { favorited=liked; updateFavoriteIcons() } }
        }
    }
    private fun playingMenu() {
        val options=arrayOf("添加到歌单",if(lyricsColumn.visibility==View.VISIBLE) "只显示封面" else "封面与歌词")
        playerMenuDialog=TvStyle.dialog(this).setTitle(title.text).setItems(options) { _,i -> when(i) {
            0 -> favoriteId?.let { library.addToPlaylist(listOf(it)) } ?: toast("请先扫描音乐库")
            1 -> { val hide=lyricsColumn.visibility==View.VISIBLE; lyricsColumn.visibility=if(hide) View.GONE else View.VISIBLE; prefs.set("player_view",if(hide) "cover" else "lyrics") }
        } }.show()
    }
    private fun jumpDialog() {
        val p=controller ?: return
        if(p.duration<=0) return
        val field=EditText(this).apply { isSingleLine=true; inputType=InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME; setText(clock(p.currentPosition)); selectAll(); hint="分:秒，例如 02:30" }
        val dialog=TvStyle.dialog(this).setTitle("跳转到（${clock(p.duration)}）").setView(field).setNegativeButton("取消",null).setPositiveButton("跳转",null).create()
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pieces=field.text.toString().split(':'); val mins=pieces.getOrNull(0)?.toLongOrNull(); val secs=pieces.getOrNull(1)?.toLongOrNull()
            if(pieces.size!=2 || mins==null || secs==null || mins<0 || secs !in 0..59 || mins>p.duration/60000) field.error="请输入有效的 分:秒"
            else { p.seekTo((mins*60000+secs*1000).coerceAtMost(p.duration)); dialog.dismiss() }
        }
        dialog.setOnDismissListener { if(!isDestroyed) progress.requestFocus() }
    }
    private fun updatePlayback() {
        val p=controller ?: return
        val showMini=page=="library" && p.mediaItemCount>0
        if(!showMini && mini.hasFocus()) library.focusEntry()
        mini.visibility=if(showMini) View.VISIBLE else View.GONE
        val item=p.currentMediaItem
        library.nowPlaying(item?.mediaMetadata?.extras?.getString("track_id"))
        if(item==null) {
            if(displayedMedia!=null) { onlineScope.cancel(); onlineTask?.cancel(true); displayedMedia=null; onlineMediaId=null; inlineLyrics=null; inlineStatus=""; artwork.setImageDrawable(null); playback.background=null; miniArt.setImageDrawable(Lucide.drawable(this,Icon.MUSIC,color=TvStyle.muted)) }
            TvStyle.changed(title,"选择一首音乐"); TvStyle.changed(artist,""); TvStyle.changed(miniTitle,"选择一首音乐"); favoriteId=null; favorited=false; updateFavoriteIcons()
        }
        if(item!=null) {
            title.text=item.mediaMetadata.title; artist.text=listOfNotNull(item.mediaMetadata.artist,item.mediaMetadata.albumTitle).filter { it.isNotBlank() }.joinToString(" · ")
            TvStyle.changed(miniTitle,listOfNotNull(item.mediaMetadata.title,item.mediaMetadata.artist).joinToString("\n"))
            if(displayedMedia!=item.mediaId) {
                displayedMedia=item.mediaId; artwork.setImageDrawable(null); miniArt.setImageDrawable(Lucide.drawable(this,Icon.MUSIC,color=TvStyle.muted)); playback.background=null
                inlineLyrics=null; inlineStatus="正在查找歌词…"; onlineMediaId=null; lyricRetries=0
                item.mediaMetadata.extras?.getString("artwork_path")?.let { setArtwork(it,item.mediaId) }
                favoriteId=item.mediaMetadata.extras?.getString("track_id"); val id=favoriteId; val source=MusicSource.key()
                worker.execute { val liked=runCatching { id!=null && id in LibraryDatabase.get(this).collections().favorites(source,"track") }.getOrDefault(false); main.post { if(!isDestroyed && favoriteId==id) { favorited=liked; updateFavoriteIcons() } } }
            }
            if(onlineMediaId!=item.mediaId && p.duration>0) enrichPlaying(item,p.duration)
        }
        val playingIcon=if(p.isPlaying) Icon.PAUSE else Icon.PLAY; Lucide.decorate(play,playingIcon); Lucide.decorate(miniPlay,playingIcon)
        play.contentDescription=if(p.isPlaying) "暂停" else "播放"; miniPlay.contentDescription=play.contentDescription
        TvStyle.changed(mode,if(p.shuffleModeEnabled) "随机" else when(p.repeatMode) { Player.REPEAT_MODE_ONE -> "单曲循环"; Player.REPEAT_MODE_ALL -> "列表循环"; else -> "顺序" })
        Lucide.decorate(mode,if(p.shuffleModeEnabled) Icon.SHUFFLE else if(p.repeatMode==Player.REPEAT_MODE_ONE) Icon.REPEAT_ONE else Icon.REPEAT)
        TvStyle.changed(time,"${clock(p.currentPosition)} / ${if(p.duration>0) clock(p.duration) else "--:--"}")
        if(!seeking) progress.progress=if(p.duration>0) (p.currentPosition*1000/p.duration).toInt() else 0
        miniProgress.progress=progress.progress
        progress.isEnabled=p.duration>0 && p.isCurrentMediaItemSeekable
        val doc=inlineLyrics; val active=doc?.indexAt(p.currentPosition+inlineOffset) ?: -1
        lyricRows.forEachIndexed { i,view ->
            val value=if(doc?.lines?.isNotEmpty()==true) doc.lines.getOrNull(active.coerceAtLeast(0)+i-2)?.text.orEmpty() else if(i==2) doc?.plainText?.lineSequence()?.take(3)?.joinToString("\n") ?: inlineStatus else ""
            TvStyle.changed(view,if(prefs.enabled("simplified")) ChineseKeys.simplified(value) else value)
            view.textSize=(prefs.number("lyric_size",22)+if(i==2) 0 else -5).toFloat()
        }
        val startedPlaying=p.isPlaying && !idleWasPlaying; idleWasPlaying=p.isPlaying
        if(startedPlaying) resetIdleNavigation() else if(!p.isPlaying) cancelIdleNavigation() else if(!idleScheduled) scheduleIdleNavigation()
    }
    private fun setArtwork(path: String,id: String) {
        worker.execute { val bitmap=BitmapFactory.decodeFile(path)
            val color=bitmap?.let { var r=0; var g=0; var b=0; for(x in 0..7) for(y in 0..7) { val pixel=it.getPixel(x*it.width/8,y*it.height/8); r+=android.graphics.Color.red(pixel); g+=android.graphics.Color.green(pixel); b+=android.graphics.Color.blue(pixel) }; android.graphics.Color.rgb(r/160,g/160,b/160) }
            main.post { if(!isDestroyed && displayedMedia==id && bitmap!=null) { artwork.setImageBitmap(bitmap); miniArt.setImageBitmap(bitmap); playback.background=TvStyle.artworkBackground(color ?: TvStyle.background) } }
        }
    }
    private fun enrichPlaying(item: MediaItem,duration: Long,forceLyrics: Boolean=false) {
        onlineScope.cancel(); onlineTask?.cancel(true); onlineScope=LookupScope(); val scope=onlineScope; val id=item.mediaId
        onlineMediaId=id; inlineLyrics=null; inlineOffset=0
        onlineTask=onlineWorker.submit {
            val identified=runCatching { OnlineMedia.identify(this,item,duration) }.getOrNull()
            if(identified!=null && item.mediaMetadata.extras?.getString("artwork_path")==null && prefs.enabled("online_art")) onlineWorker.execute {
                runCatching { val q=identified.identity; val path=OnlineMedia.artwork(this,q.album,q.artist,scope) ?: return@runCatching
                    identified.albumKey?.let { LibraryDatabase.get(this).library().fillArtwork(it,path) }
                    main.post { if(!isDestroyed && onlineMediaId==id && !scope.cancelled.get()) setArtwork(path,id) }
                }
            }
            val result=runCatching { LyricsRepository.resolve(this,item,duration,scope,forceLyrics) }
            main.post { if(!isDestroyed && onlineMediaId==id && !scope.cancelled.get()) {
                result.onSuccess { inlineLyrics=it.document; inlineOffset=it.timelineOffsetMs; inlineStatus=if(it.document==null) it.source else "" }
                    .onFailure { inlineStatus="暂未取得歌词，可在全屏歌词中重试" }
                updatePlayback()
                if(inlineLyrics==null && result.getOrNull()?.source!="纯音乐，无歌词" && prefs.enabled("online_lyrics") && lyricRetries<1) {
                    lyricRetries++
                    main.postDelayed({ if(!isDestroyed && idleResumed && onlineMediaId==id && inlineLyrics==null && !scope.cancelled.get()) { onlineMediaId=null; updatePlayback() } },12000)
                }
            } }
        }
    }
    private fun showIndexProblems() {
        worker.execute {
            val db=LibraryDatabase.get(this); val source=MusicSource.key()
            val files=db.audioIndex().files(source).filter { !it.ready }
            val failed=files.filter { it.attempts>=2 }
            val messages=failed.take(30).map { job ->
                val reason=getSharedPreferences("index_errors",0).getString("$source:${MusicSource.digest(job.path)}","文件标签未能读取")
                "${job.path.substringAfterLast('/')}\n$reason"
            }
            main.post { if(!isDestroyed) TvStyle.dialog(this).setTitle("未补全文件")
                .setMessage(if(files.isEmpty()) "所有文件信息已补全" else "${files.size} 个待补全，其中 ${failed.size} 个读取失败\n\n"+messages.joinToString("\n\n"))
                .setNegativeButton("关闭",null).setPositiveButton("重试") { _,_ -> LibraryIndexing.resume(this,true) }.show() }
        }
    }
    private fun loadLibraryAlbum(album: String,index: Int) {
        worker.execute { val tracks=runCatching { LibraryDatabase.get(this).library().albumTracks(album).filter { it.source==MusicSource.key() } }.getOrDefault(emptyList())
            main.post { if(!isDestroyed && tracks.isNotEmpty()) startQueue(tracks.map { it.song() },index) }
        }
    }
    private fun loadFolder(path: String) { showPage("library"); library.showFolder(path) }
    private fun loadCue(path: String) { worker.execute { val songs=runCatching { MusicSource.cue(path) }.getOrDefault(emptyList()); main.post { if(!isDestroyed) startQueue(songs,0) } } }
    private fun connectionDialog(): AlertDialog {
        connectionDialogs?.close()
        val dialogs=ConnectionDialogs(this) {
            queueRequest++; controller?.stop(); controller?.clearMediaItems(); displayedMedia=null; favoriteId=null
            library.refresh(); replaceSettings(); showPage("library")
        }
        connectionDialogs=dialogs
        return dialogs.show()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); intent.getStringExtra("library_album")?.let { if(controller!=null) loadLibraryAlbum(it,intent.getIntExtra("track_index",0)) else pendingAlbum=it to intent.getIntExtra("track_index",0) } }
    @Deprecated("Deprecated in Java") override fun onBackPressed() {
        if(page=="library" && library.back()) return
        if(page=="player") showPage(playerReturnPage) else if(page!="library") showPage("library") else super.onBackPressed()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdleNavigation()
        if(event.action==KeyEvent.ACTION_DOWN && page=="library" && routeLibraryFocus(event.keyCode)) return true
        if(event.action==KeyEvent.ACTION_DOWN) when(event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { controller?.seekToNextMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { controller?.seekToPreviousMediaItem(); return true }
            KeyEvent.KEYCODE_MENU -> when(page) { "player" -> { playingMenu(); return true }; "library" -> { library.showMenu(); return true } }
        }
        return super.dispatchKeyEvent(event)
    }
    private fun enterMini() {
        if(!mini.isShown) return
        miniOrigin=currentFocus; miniFromLibrary=library.hasFocus()
        if(miniFromLibrary) library.captureReturnFocus()
        miniPlay.requestFocus()
    }
    private fun routeLibraryFocus(key: Int): Boolean {
        if(key !in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) return false
        val focused=currentFocus ?: return false
        if(mini.hasFocus()) {
            val index=miniControls.indexOf(focused)
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if(miniFromLibrary && library.restoreReturnFocus()) return true
                    if(miniOrigin?.isAttachedToWindow==true && miniOrigin?.isShown==true && miniOrigin?.requestFocus()==true) return true
                    library.focusEntry(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if(index>0) miniControls[index-1].requestFocus() else navigationToggle.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if(index in 0 until miniControls.lastIndex) miniControls[index+1].requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        if(rail.hasFocus()) {
            if(focused===navigationToggle && key in listOf(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT) && mini.isShown) { enterMini(); return true }
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT) { library.focusEntry(); return true }
        }
        if(library.hasFocus()) return library.routeFocus(key) { if(mini.isShown) enterMini() }
        return false
    }
    private fun immersive() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if(android.os.Build.VERSION.SDK_INT>=28) window.attributes=window.attributes.apply { layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); idleFocused=hasFocus; if(hasFocus) { immersive(); resetIdleNavigation(); updateNavigationHint() } else { cancelIdleNavigation(); if(::navigationHint.isInitialized) navigationHint.visibility=View.GONE } }
    override fun onUserInteraction() { super.onUserInteraction(); resetIdleNavigation() }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean { resetIdleNavigation(); return super.dispatchTouchEvent(event) }
    override fun onResume() { super.onResume(); idleResumed=true; resetIdleNavigation() }
    override fun onPause() { idleResumed=false; cancelIdleNavigation(); super.onPause() }
    private fun setNavigationCollapsed(collapsed: Boolean) {
        navigationCollapsed=collapsed; prefs.set("nav_collapsed",collapsed)
        railHost.layoutParams=railHost.layoutParams.apply { width=dp(if(collapsed) 60 else 136) }
        navigation.forEach { (key,view) ->
            view.text=if(collapsed) "" else navigationLabels[key]
            view.gravity=if(collapsed) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            view.compoundDrawablePadding=if(collapsed) 0 else dp(9)
            Lucide.decorate(view,navigationIcons[key])
        }
        navigationToggle.text=if(collapsed) "" else "收起导航"
        navigationToggle.contentDescription=if(collapsed) "展开导航" else "收起导航"
        navigationToggle.gravity=if(collapsed) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        Lucide.decorate(navigationToggle,if(collapsed) Icon.EXPAND else Icon.COLLAPSE)
        updateNavigationHint()
    }
    private fun updateNavigationHint() {
        if(!::navigationHint.isInitialized) return
        navigationHint.visibility=View.GONE
        if(!navigationCollapsed || page!="library") return
        val focused=currentFocus ?: return
        val label=if(::navigationToggle.isInitialized && focused===navigationToggle) "展开导航" else navigation.entries.firstOrNull { it.value===focused }?.let { navigationLabels[it.key] } ?: return
        focused.post {
            if(isDestroyed || !navigationCollapsed || page!="library" || !focused.hasFocus() || !window.decorView.hasWindowFocus()) return@post
            val position=IntArray(2); val origin=IntArray(2); focused.getLocationInWindow(position); overlay.getLocationInWindow(origin)
            navigationHint.text=label
            navigationHint.measure(View.MeasureSpec.makeMeasureSpec(overlay.width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(overlay.height,View.MeasureSpec.AT_MOST))
            navigationHint.layoutParams=(navigationHint.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin=position[0]-origin[0]+focused.width+dp(12)
                topMargin=(position[1]-origin[1]+(focused.height-navigationHint.measuredHeight)/2).coerceIn(0,(overlay.height-navigationHint.measuredHeight).coerceAtLeast(0))
            }
            navigationHint.visibility=View.VISIBLE
        }
    }
    private fun updateFavoriteIcons() {
        val icon=if(favorited) Icon.HEART_FILLED else Icon.HEART
        val color=if(favorited) TvStyle.accent else TvStyle.text
        Lucide.decorate(miniFavorite,icon,color=color); Lucide.decorate(playerFavorite,icon,color=color)
        miniFavorite.compoundDrawableTintList=ColorStateList.valueOf(color); playerFavorite.compoundDrawableTintList=ColorStateList.valueOf(color)
        miniFavorite.contentDescription=if(favorited) "取消收藏" else "收藏歌曲"; playerFavorite.contentDescription=miniFavorite.contentDescription
    }
    private fun idleDelayLabel(): String = prefs.number("auto_player_delay",30).let { if(it==0) "关闭" else "无操作 $it 秒" }
    private fun idleDelayDialog() {
        val seconds=listOf(0,15,30,60,120,300)
        val labels=seconds.map { if(it==0) "关闭" else "$it 秒" }+"自定义时间"
        TvStyle.dialog(this).setTitle("播放时自动进入播放页").setSingleChoiceItems(labels.toTypedArray(),seconds.indexOf(prefs.number("auto_player_delay",30))) { dialog,index ->
            dialog.dismiss()
            if(index<seconds.size) { prefs.set("auto_player_delay",seconds[index]); resetIdleNavigation(); replaceSettings("idle_delay_setting") }
            else {
                val input=EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER; isSingleLine=true; setText(prefs.number("auto_player_delay",30).toString()); selectAll(); hint="5–3600 秒" }
                val custom=TvStyle.dialog(this).setTitle("无操作等待时间（秒）").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create()
                custom.show(); custom.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value=input.text.toString().toIntOrNull()
                    if(value==null || value !in 5..3600) input.error="请输入 5 到 3600 秒" else { prefs.set("auto_player_delay",value); custom.dismiss(); resetIdleNavigation(); replaceSettings("idle_delay_setting") }
                }
            }
        }.setNegativeButton("取消",null).show()
    }
    private fun cancelIdleNavigation() { main.removeCallbacks(idleNavigation); idleScheduled=false }
    private fun resetIdleNavigation() { idlePolicy.interact(SystemClock.uptimeMillis()); scheduleIdleNavigation() }
    private fun scheduleIdleNavigation() {
        cancelIdleNavigation()
        if(!idleResumed || !idleFocused || page=="player" || controller?.isPlaying!=true) return
        val remaining=idlePolicy.remainingMs(SystemClock.uptimeMillis(),prefs.number("auto_player_delay",30)) ?: return
        main.postDelayed(idleNavigation,remaining); idleScheduled=true
    }
    override fun onStart() { super.onStart(); onlineMediaId=null; main.post(tick) }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page",page); outState.putString("settings_category",settingsCategory)
        outState.putString("settings_focus",currentFocus?.tag as? String); outState.putInt("settings_scroll",settings.scrollY)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { main.removeCallbacks(tick); super.onStop() }
    override fun onDestroy() { connectionDialogs?.close(); queueRequest++; cancelIdleNavigation(); playerMenuDialog?.dismiss(); queueDialog?.dismiss(); onlineScope.cancel(); onlineTask?.cancel(true); library.close(); worker.shutdownNow(); onlineWorker.shutdownNow(); controllerFuture?.let { MediaController.releaseFuture(it) }; super.onDestroy() }
    private fun dp(n: Int)=TvStyle.dp(this,n)
    private fun button(s: String,action: () -> Unit)=TvStyle.button(this,s,action)
    private fun clock(ms: Long)="%02d:%02d".format(ms.coerceAtLeast(0)/60000,ms.coerceAtLeast(0)/1000%60)
    private fun toast(s: String) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show() }
}
