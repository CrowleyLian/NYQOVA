package com.nyqova.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.sqlite.db.SimpleSQLiteQuery
import com.nyqova.app.library.*
import com.nyqova.app.online.OnlineMedia
import com.nyqova.app.online.LookupScope
import com.nyqova.app.settings.TvPreferences
import com.nyqova.app.storage.*
import java.util.concurrent.Executors

/** Database pages feed recycled cards. Navigation state is kept separately from the active queue. */
class TvLibraryPane(private val activity: Activity, private val play: (List<Song>, Int) -> Unit,
    private val enqueue: (Song, Boolean) -> Unit, private val changed: () -> Unit = {}, private val resumePlaying: (Song)->Boolean = { false }) : LinearLayout(activity) {
    private data class Location(val kind: String, val title: String, val target: String = "", val recursive: Boolean = true, val albumKey: String = "")
    private data class Row(val title: String, val subtitle: String = "", val album: AlbumSummary? = null,
        val track: LibraryTrack? = null, val folder: String? = null, val playlist: SavedPlaylist? = null,
        val builtin: String? = null, val raw: Song? = null, val artwork: String? = null,
        val group: String? = null, val section: Boolean = false, val tile: Boolean = false)
    private data class Anchor(val scroll: Parcelable?, val key: String?, val position: Int, val focus: Boolean)
    private data class BackState(val location: Location, val anchor: Anchor, val search: String, val favoriteAlbums: Boolean, val grouping: String, val groupSearch: String, val initial: String, val groupInitial: String)
    private val prefs = TvPreferences(activity)
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Executors.newFixedThreadPool(2)
    private val artWorker=Executors.newFixedThreadPool(2)
    private var artScope=LookupScope()
    private val artPending=mutableSetOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private val db get() = LibraryDatabase.get(activity)
    private val dao get() = db.collections()
    private var location = Location("home", "首页")
    private val history = mutableListOf<BackState>()
    private val rows = mutableListOf<Row>()
    private var selected = -1
    private var generation = 0
    private var loading = false
    private var more = false
    private var offset = 0
    private var search = ""
    private var groupSearch = ""
    private var initial = ""
    private var groupInitial = ""
    private var sort = prefs.text("sort", "title")
    private var albumSort=prefs.text("album_sort","title")
    private var favoriteAlbums = false
    private var likedTracks = emptySet<String>()
    private var likedAlbums = emptySet<String>()
    private var source = MusicSource.key()
    private var playingId: String?=null
    private var indexRevision=-1L
    private var indexRefreshAt=0L
    private val scanLabel=TvStyle.label(activity,"",12,TvStyle.muted)
    private val scanButton=button("暂停") { if(LibraryIndexing.state.active) LibraryIndexing.cancel() else LibraryIndexing.resume(activity,true) }
    private val scanDismiss: Button=Lucide.button(activity,Icon.CLOSE,"收起扫描提示",true) { scanBar.visibility=View.GONE }
    private val scanBar: LinearLayout=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; visibility=View.GONE; tag="scan_status"; addView(scanLabel,LayoutParams(0,-2,1f)); addView(scanButton); addView(scanDismiss) }
    private val indexListener: (LibraryIndexing.State)->Unit = { state ->
        val relevant=state.source==source
        scanBar.visibility=if(relevant && state.message.isNotBlank()) View.VISIBLE else View.GONE
        scanLabel.text=state.message; scanButton.text=if(state.active) "暂停" else "继续"
        scanDismiss.visibility=if(state.active) View.GONE else View.VISIBLE
        if(!state.active && state.message.isNotBlank()) main.postDelayed({ if(!LibraryIndexing.state.active && !scanBar.hasFocus()) scanBar.visibility=View.GONE },8000)
        val now=android.os.SystemClock.elapsedRealtime()
        if(relevant && state.revision!=indexRevision && (!state.active || (selected<0 && !list.hasFocus() && now-indexRefreshAt>1500))) {
            indexRevision=state.revision; indexRefreshAt=now; reloadKeepingPosition(focus=false)
        }
    }
    private var restore: Anchor? = null
    private var returnAnchor: Anchor? = null
    private var activeMenu: AlertDialog? = null
    private var focusFirst = false
    private val cache = object : android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val heading = TvStyle.label(activity, "专辑", 24, bold = true)
    private val quantity=TvStyle.label(activity,"",12,TvStyle.muted).apply { tag="library_quantity"; setPadding(0,0,dp(14),0) }
    private val status = TvStyle.label(activity, "", 12, TvStyle.muted)
    private val tableHeader=LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),0,dp(6),0)
        addView(TvStyle.label(activity,"#",11,TvStyle.muted),LayoutParams(dp(24),-2))
        addView(View(activity),LayoutParams(dp(46),1))
        addView(TvStyle.label(activity,"歌曲 / 歌手",11,TvStyle.muted),LayoutParams(0,-2,2f))
        addView(TvStyle.label(activity,"专辑",11,TvStyle.muted),LayoutParams(0,-2,1.2f))
        addView(TvStyle.label(activity,"时长 · 大小 · 格式",11,TvStyle.muted).apply { gravity=Gravity.END },LayoutParams(dp(174),-2))
    }
    private val list = RecyclerView(activity).apply {
        clipToPadding = false; setPadding(0, 8, 0, 8)
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        itemAnimator = null
    }
    private val adapter = RowsAdapter()
    private val groupRows=mutableListOf<Row>()
    private var grouping=""
    private var groupToken=0
    private var groupLoading=false
    private var groupMore=false
    private val groupAdapter=GroupAdapter()
    private val groupList=RecyclerView(activity).apply { layoutManager=LinearLayoutManager(activity); adapter=groupAdapter; itemAnimator=null }
    private val groupPane=LinearLayout(activity).apply { orientation=VERTICAL; visibility=View.GONE; addView(groupList,LayoutParams(-1,-1)) }
    private val groupPortrait=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=TvStyle.shape(activity,TvStyle.panel,46); clipToOutline=true }
    private val groupName=TvStyle.label(activity,"",24,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
    private val groupCount=TvStyle.label(activity,"",12,TvStyle.muted)
    private val songsTab=button("歌曲") { navigate(Location(if(grouping=="artists") "artist" else "genre",location.title,location.target),false) }
    private val albumsTab=button("专辑") { navigate(Location("artistAlbums",location.title,location.target),false) }
    private val playAll=Lucide.button(activity,Icon.PLAY,"播放全部") { playContext() }.apply { tag="library_play_all" }
    private val viewButton=Lucide.button(activity,Icon.LIBRARY,"视图") { viewMenu() }.apply { tag="library_view" }
    private val searchButton=Lucide.button(activity,Icon.SEARCH,"搜索音乐") {
        searchMenu()
    }.apply { background=TvStyle.selector(activity); textSize=12f; tag="library_search" }
    private fun searchLabel()=when {
        groupPane.visibility==View.VISIBLE -> if(grouping=="artists") "搜索歌手" else "搜索风格"
        albumView() -> "搜索专辑 / 歌手"
        location.kind=="playlists" -> "搜索歌单"
        location.kind=="folders" -> "搜索当前文件夹"
        location.kind=="home" -> "搜索音乐库"
        else -> "搜索当前歌曲列表"
    }
    private fun searchMenu() {
        val form=LinearLayout(activity).apply { orientation=VERTICAL; setPadding(dp(12),dp(4),dp(12),dp(8)) }
        val input=EditText(activity).apply {
            tag="search_input"; isSingleLine=true; textSize=16f; hint="文字或首字母，例如 周杰伦 / ZJL / GD"
            setText(if(groupPane.isShown) groupSearch else search); setSelection(text.length)
            showSoftInputOnFocus=false; imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        form.addView(input,LayoutParams(-1,dp(48)))
        lateinit var dialog: AlertDialog
        fun submit() {
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
            applySearch(input.text.toString()); dialog.dismiss()
        }
        input.setOnClickListener {
            input.requestFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        input.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        fun insert(value: String) {
            val start=input.selectionStart.coerceAtLeast(0); val end=input.selectionEnd.coerceAtLeast(0)
            input.text.replace(minOf(start,end),maxOf(start,end),value); input.setSelection(minOf(start,end)+value.length)
        }
        val grid=GridLayout(activity).apply { columnCount=9; setPadding(0,dp(8),0,dp(4)) }
        (('A'..'Z').map { it.toString() }+('0'..'9').map { it.toString() }).forEach { letter ->
            grid.addView(button(letter) { insert(letter) }.apply { tag="search_key_$letter" },GridLayout.LayoutParams().apply { width=dp(52); height=dp(38); setMargins(dp(2),dp(2),dp(2),dp(2)) })
        }
        form.addView(grid)
        val actions=LinearLayout(activity)
        fun action(label: String,tag: String,block: ()->Unit) { actions.addView(button(label,block).apply { this.tag=tag },LayoutParams(0,dp(44),1f)) }
        action("空格","search_space") { insert(" ") }
        action("退格","search_backspace") {
            val start=input.selectionStart.coerceAtLeast(0); val end=input.selectionEnd.coerceAtLeast(0)
            if(start!=end) { input.text.delete(minOf(start,end),maxOf(start,end)); input.setSelection(minOf(start,end)) }
            else if(start>0) { val previous=Character.offsetByCodePoints(input.text,start,-1); input.text.delete(previous,start); input.setSelection(previous) }
        }
        action("清空","search_clear") { input.setText("") }
        action("搜索","search_submit",::submit)
        form.addView(actions)
        dialog=TvStyle.dialog(activity).setTitle(searchLabel()).setView(form).setNegativeButton("取消",null).create()
        activeMenu=dialog; dialog.show()
        dialog.window?.setLayout(dp(600).coerceAtMost(resources.displayMetrics.widthPixels-dp(64)),ViewGroup.LayoutParams.WRAP_CONTENT)
        grid.findViewWithTag<View>("search_key_A")?.requestFocus()
    }
    private fun applySearch(value: String)=applyFilter(value,"")
    private fun applyFilter(value: String,letter: String) {
        restore=null; focusFirst=false
        if(groupPane.visibility==View.VISIBLE) {
            groupSearch=value.trim(); groupInitial=letter; search=""; initial=""
            location=Location(grouping,if(grouping=="artists") "歌手" else "风格")
        } else {
            search=value.trim(); initial=letter
            if(location.kind=="home") { history+=BackState(location,anchor(),"",favoriteAlbums,grouping,groupSearch,"",groupInitial); location=Location("all","音乐库") }
        }
        reload()
    }
    private val groupHero=LinearLayout(activity).apply {
        gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(8),dp(10)); visibility=View.GONE
        addView(groupPortrait,LayoutParams(dp(82),dp(82)).apply { rightMargin=dp(18) })
        addView(LinearLayout(activity).apply { orientation=VERTICAL; addView(groupName); addView(groupCount)
            val tabs=LinearLayout(activity)
            tabs.addView(songsTab); tabs.addView(albumsTab)
            addView(tabs)
        },LayoutParams(0,-2,1f))
    }
    init {
        orientation = VERTICAL
        val searchBar=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        searchBar.addView(searchButton,LayoutParams(dp(238),dp(36)))
        searchBar.addView(View(activity),LayoutParams(0,1,1f))
        addView(searchBar,LayoutParams(-1,dp(48)))
        val titleBar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        titleBar.addView(heading, LayoutParams(0, dp(44), 1f))
        titleBar.addView(quantity)
        titleBar.addView(playAll); titleBar.addView(viewButton)
        addView(titleBar); addView(scanBar); addView(status); addView(tableHeader,LayoutParams(-1,dp(26)))
        val area=LinearLayout(activity)
        area.addView(groupPane,LayoutParams(dp(154),-1).apply { rightMargin=dp(14) })
        area.addView(LinearLayout(activity).apply { orientation=VERTICAL; addView(groupHero,LayoutParams(-1,dp(108))); addView(list,LayoutParams(-1,0,1f)) },LayoutParams(0,-1,1f))
        addView(area,LayoutParams(-1,0,1f))
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = (rv.layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition() ?: 0
                if(more && !loading && last >= rows.size - 12) page()
            }
        })
        groupList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView,dx: Int,dy: Int) { if(groupMore && !groupLoading && (rv.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=groupRows.size-10) loadGroups() }
        })
        reload()
        LibraryIndexing.observe(indexListener)
        LibraryIndexing.resume(activity)
    }
    private fun dp(n: Int) = TvStyle.dp(activity, n)
    private fun button(s: String, f: () -> Unit) = TvStyle.button(activity, s, f)
    private fun scope() = TrackScope(source, location.kind, location.target, location.recursive, search, sort, location.albumKey, initial)
    private fun playlistContext()=location.kind in listOf("playlist","folder","favorites","recent","added","all")
    private fun playlistViewKey()="playlist_view:$source:${location.kind}:${location.target}"
    private fun albumView()=location.kind in listOf("albums","artistAlbums") || (playlistContext() && location.albumKey.isEmpty() && prefs.text(playlistViewKey(),"songs")=="albums")
    private fun grid() = albumView() && prefs.text("album_layout", "grid") == "grid"
    private var pendingDown: Int?=null
    fun focusEntry(): Boolean {
        if(groupPane.isShown && groupRows.isNotEmpty()) return focusGroup()
        if(rows.isNotEmpty()) { focusRow(selected.takeIf { it in rows.indices } ?: rows.indexOfFirst { !it.section }.coerceAtLeast(0)); return true }
        return searchButton.requestFocus()
    }
    private fun focusGroup(): Boolean {
        val position=groupRows.indexOfFirst { it.group==location.target }.coerceAtLeast(0)
        groupList.findViewHolderForAdapterPosition(position)?.itemView?.let { return it.requestFocus() }
        groupList.scrollToPosition(position); groupList.post { groupList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }; return true
    }
    fun routeFocus(key: Int,bottom: ()->Unit): Boolean {
        val focused=activity.currentFocus ?: return false
        if(groupList.hasFocus() && key==KeyEvent.KEYCODE_DPAD_RIGHT) {
            if(rows.isNotEmpty()) focusRow(selected.takeIf { it in rows.indices } ?: 0)
            return true
        }
        val position=list.findContainingViewHolder(focused)?.bindingAdapterPosition ?: return false
        if(position<0) return false
        val layout=list.layoutManager as? LinearLayoutManager ?: return false
        val gridLayout=layout as? GridLayoutManager
        val spans=gridLayout?.spanCount ?: 1
        val spanIndex=gridLayout?.spanSizeLookup?.getSpanIndex(position,spans) ?: 0
        if(key==KeyEvent.KEYCODE_DPAD_LEFT && spanIndex==0 && groupPane.isShown) return focusGroup()
        if(key==KeyEvent.KEYCODE_DPAD_DOWN) {
            val lastRow=if(gridLayout!=null) gridLayout.spanSizeLookup.getSpanGroupIndex(position,spans)==gridLayout.spanSizeLookup.getSpanGroupIndex(rows.lastIndex,spans) else position==rows.lastIndex
            if(lastRow) {
                if(more || loading) { pendingDown=position+spans; if(!loading) page() }
                else bottom()
                return true
            }
        }
        return false
    }
    fun refresh() { val next=MusicSource.key(); if(next!=source) { history.clear(); search=""; groupSearch=""; initial=""; groupInitial=""; location=Location("home","首页"); source=next; LibraryIndexing.resume(activity) }; reload() }
    fun nowPlaying(id: String?) { if(playingId!=id) { val old=rows.indexOfFirst { it.track?.id==playingId }; playingId=id; val next=rows.indexOfFirst { it.track?.id==id }; if(old>=0) adapter.notifyItemChanged(old); if(next>=0) adapter.notifyItemChanged(next) } }
    fun selectSection(kind: String,title: String) { history.clear(); favoriteAlbums=false; groupSearch=""; groupInitial=""; navigate(Location(kind,title),false) }
    fun showFolder(path: String) { navigate(Location("folders", path.substringAfterLast('/').ifBlank { "文件夹" }, path)) }
    private fun navigate(next: Location, remember: Boolean = true) {
        if(remember) history += BackState(location, anchor(), search, favoriteAlbums, grouping, groupSearch, initial, groupInitial)
        restore=null; focusFirst=remember; location = next; search = ""; initial=""; reload()
    }
    fun back(): Boolean {
        if(history.isEmpty()) return false
        val saved = history.removeAt(history.lastIndex)
        location = saved.location; search = saved.search; favoriteAlbums=saved.favoriteAlbums; grouping=saved.grouping; groupSearch=saved.groupSearch; initial=saved.initial; groupInitial=saved.groupInitial; restore = saved.anchor; reload(); return true
    }
    private fun rowKey(row: Row): String = when {
        row.track!=null -> "track:${row.track.id}"
        row.album!=null -> "album:${row.album.albumKey}"
        row.playlist!=null -> "playlist:${row.playlist.id}"
        row.folder!=null -> "folder:${row.folder}"
        row.raw!=null -> "raw:${row.raw.path}:${row.raw.startUs}"
        else -> "entry:${row.builtin}:${row.title}"
    }
    private fun selectedRow()=rows.getOrNull(selected)
    private fun anchor(forceFocus: Boolean=false)=Anchor(list.layoutManager?.onSaveInstanceState(),selectedRow()?.let(::rowKey),selected,list.hasFocus() || forceFocus)
    fun captureReturnFocus() { returnAnchor=if(list.hasFocus()) anchor(true) else null }
    fun restoreReturnFocus(): Boolean {
        val saved=returnAnchor ?: return false
        val position=rows.indexOfFirst { rowKey(it)==saved.key }.takeIf { it>=0 } ?: saved.position
        if(position !in rows.indices) return false
        focusRow(position); return true
    }
    private fun focusRow(position: Int) {
        selectRow(position)
        list.postOnAnimation {
            val view=list.findViewHolderForAdapterPosition(position)?.itemView
            if(view!=null) view.requestFocus() else {
                list.scrollToPosition(position); list.postOnAnimation { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            }
        }
    }
    private fun selectRow(position: Int) {
        list.findViewHolderForAdapterPosition(selected)?.itemView?.isSelected=false
        selected=position
        list.findViewHolderForAdapterPosition(selected)?.itemView?.isSelected=true
    }
    private fun hasItemActions(row: Row)=row.track!=null || row.raw!=null || row.album!=null || row.folder!=null || row.playlist!=null
    private fun reloadKeepingPosition(removed: Boolean=false,focus: Boolean=true) {
        restore=anchor(focus).let { if(removed) it.copy(key=null) else it }; focusFirst=false; reload()
    }
    private fun updateToolbar() {
        playAll.visibility=if(location.kind in listOf("all","album","artist","genre","favorites","recent","added","playlist","folder","folders") && rows.any { it.track!=null || it.raw!=null || (playlistContext() && it.album!=null) }) View.VISIBLE else View.GONE
        viewButton.visibility=if(location.kind in listOf("albums","artistAlbums","all","artist","genre","favorites","recent","added","folder","playlist") && location.albumKey.isEmpty()) View.VISIBLE else View.GONE
        songsTab.isSelected=location.kind!="artistAlbums"; albumsTab.isSelected=location.kind=="artistAlbums"
        albumsTab.visibility=if(grouping=="artists") View.VISIBLE else View.GONE
        searchButton.text=searchLabel()
    }
    private fun reload() {
        quantity.text=""
        pendingDown=null; generation++; loading = false; rows.clear(); offset = 0; more = false; selected = -1; status.visibility=View.VISIBLE; updateToolbar()
        artScope.cancel(); artScope=LookupScope(); artPending.clear()
        if(location.kind in listOf("artists","genres")) {
            groupToken++; grouping=location.kind; groupLoading=false; groupMore=false; groupRows.clear(); groupAdapter.notifyDataSetChanged(); groupPane.visibility=View.VISIBLE; groupHero.visibility=View.GONE; searchButton.text=searchLabel()
            list.layoutManager=LinearLayoutManager(activity); adapter.notifyDataSetChanged(); heading.text=location.title; status.text="正在读取…"; tableHeader.visibility=View.GONE; loadGroups(); return
        }
        if(location.kind !in listOf("artist","genre","artistAlbums")) { groupToken++; grouping=""; groupPane.visibility=View.GONE }
        else groupPane.visibility=if(groupRows.isEmpty()) View.GONE else View.VISIBLE
        groupHero.visibility=groupPane.visibility
        tableHeader.visibility=if(albumView() || location.kind in listOf("home","albums","artistAlbums","artists","genres","playlists") || groupPane.visibility==View.VISIBLE) View.GONE else View.VISIBLE
        list.layoutManager = if(location.kind=="home") GridLayoutManager(activity,20).apply {
            spanSizeLookup=object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int { val row=rows.getOrNull(position) ?: return 20; return when { row.section -> 20; row.tile -> 5; row.album!=null -> 4; else -> 10 } }
            }
        } else if(grid()) GridLayoutManager(activity, if(resources.configuration.screenWidthDp >= 800) 5 else 4) else LinearLayoutManager(activity)
        adapter.notifyDataSetChanged(); heading.text = if(groupPane.visibility==View.VISIBLE) { if(grouping=="artists") "歌手" else "风格" } else location.title; status.text = "正在读取…"; page()
    }
    private fun loadGroups() {
        if(groupLoading || grouping.isEmpty()) return
        groupLoading=true
        val token=groupToken; val kind=grouping; val start=groupRows.size; val key=source; val letter=groupInitial
        val query=groupSearch
        worker.execute {
            var total=0
            val result=runCatching { val column=if(kind=="artists") "artist" else "genre"
                if(letter.isNotEmpty() || SearchInitials.prefix(query).isNotEmpty()) SearchInitials.ensure(db)
                val initialFilter=if(letter.isEmpty()) "" else "AND EXISTS (SELECT 1 FROM search_initials i WHERE i.value=$column AND i.initial=?)"
                val args=mutableListOf<Any>(key)
                val condition=SearchInitials.predicate(listOf(column),query,args)
                if(letter.isNotEmpty()) args+=letter
                total=dao.count(SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT $column FROM tracks WHERE source=? AND $condition $initialFilter GROUP BY $column)",args.toTypedArray()))
                dao.groups(SimpleSQLiteQuery("SELECT $column AS name,COUNT(*) AS trackCount,MAX(artworkPath) AS artworkPath FROM tracks WHERE source=? AND $condition $initialFilter GROUP BY $column ORDER BY $column COLLATE NOCASE LIMIT 80 OFFSET ?",(args+start).toTypedArray()))
                    .map { Row(it.name.ifBlank { if(kind=="artists") "未知歌手" else "未分类风格" },"${it.trackCount} 首歌曲",artwork=it.artworkPath,group=it.name) }
            }
            main.post { if(!activity.isDestroyed && token==groupToken) {
                groupLoading=false
                result.onSuccess { added ->
                    quantity.text=groupQuantity(total,kind)
                    groupRows.addAll(added); groupMore=added.size==80; groupAdapter.notifyItemRangeInserted(start,added.size)
                    if(start==0) { if(added.isNotEmpty()) selectGroup(added[0]) else { groupHero.visibility=View.GONE; rows.clear(); adapter.notifyDataSetChanged(); quantity.text=groupQuantity(0,kind); status.text=if(groupSearch.isNotBlank() || groupInitial.isNotBlank()) "没有匹配结果" else "音乐库暂无分类，请先扫描音乐"; status.visibility=View.VISIBLE } }
                }.onFailure { status.text="分类读取失败，请重试"; status.visibility=View.VISIBLE }
            } }
        }
    }
    private fun selectGroup(row: Row) {
        groupName.text=row.title; groupCount.text=row.subtitle; groupPortrait.setImageDrawable(Lucide.drawable(activity,if(grouping=="artists") Icon.ARTIST else Icon.GENRE,color=TvStyle.muted)); groupPortrait.tag=row.artwork
        row.artwork?.let { path -> images.execute { val bitmap=cache.get(path) ?: BitmapFactory.decodeFile(path)?.also { cache.put(path,it) }; main.post { if(!activity.isDestroyed && groupPortrait.tag==path && bitmap!=null) groupPortrait.setImageBitmap(bitmap) } } }
        navigate(Location(if(grouping=="artists") "artist" else "genre",row.title,row.group.orEmpty()),false)
        for(i in 0 until groupList.childCount) { val child=groupList.getChildAt(i); val position=groupList.getChildAdapterPosition(child); child.isSelected=groupRows.getOrNull(position)?.group==row.group }
    }
    private fun page() {
        if(loading) return
        loading = true
        val token = generation; val start = offset; val loc = location; val currentScope = scope()
        val favoriteOnly = favoriteAlbums; val queryText = search; val letter=initial; val groupLetter=groupInitial; val key = source
        val groupQuery=groupSearch
        val groupedAlbums=albumView() && playlistContext()
        val groupKind=grouping.takeIf { groupPane.visibility==View.VISIBLE }
        worker.execute {
            var countText: String?=null
            val trackLikes=runCatching { dao.favorites(key,"track").toSet() }.getOrDefault(emptySet())
            val albumLikes=runCatching { dao.favorites(key,"album").toSet() }.getOrDefault(emptySet())
            val result = runCatching {
                if(letter.isNotEmpty() || groupLetter.isNotEmpty() || SearchInitials.prefix(queryText).isNotEmpty() || SearchInitials.prefix(groupQuery).isNotEmpty()) SearchInitials.ensure(db)
                if(groupedAlbums) {
                    if(start==0) countText="${number(dao.count(currentScope.albumCountQuery()))} 张专辑"
                    dao.albums(currentScope.copy(sort=albumSort).albumsQuery(80,start)).map { Row(it.title,"${it.artist.ifBlank { "未知艺术家" }} · ${it.trackCount} 首",album=it,artwork=it.artworkPath) }
                } else when(loc.kind) {
                    "home" -> if(start!=0) emptyList() else {
                        countText="${number(dao.count(TrackScope(key).countQuery()))} 首歌曲"
                        val recent=dao.tracks(TrackScope(key,"recent").query(2))
                        val albums=dao.albums(SimpleSQLiteQuery("SELECT albumKey,album AS title,MAX(artist) AS artist,MAX(artworkPath) AS artworkPath,COUNT(*) AS trackCount FROM tracks WHERE source=? GROUP BY albumKey,album ORDER BY MAX(addedAt) DESC,album COLLATE NOCASE LIMIT 10",arrayOf(key)))
                        listOf(Row("随机漫游","播放整个音乐库",builtin="shuffle",tile=true),Row("收藏","喜欢的歌曲",builtin="favorites",tile=true),Row("最近播放","继续聆听",builtin="recent",tile=true),Row("最近添加","新加入的音乐",builtin="added",tile=true)) +
                            (if(recent.isEmpty()) emptyList() else listOf(Row("最近播放",section=true)) + recent.map { Row(it.title,it.artist,track=it,artwork=it.artworkPath) }) +
                            (if(albums.isEmpty()) emptyList() else listOf(Row("最近入库的专辑",section=true)) + albums.map { Row(it.title,it.artist,album=it,artwork=it.artworkPath) })
                    }
                    "albums", "artistAlbums" -> {
                        val filter = if(favoriteOnly && loc.kind=="albums") "AND EXISTS (SELECT 1 FROM favorites f WHERE f.source=t.source AND f.kind='album' AND f.target=t.albumKey) " else ""
                        val artistFilter=if(loc.kind=="artistAlbums") "AND artist=? " else ""
                        val initialFilter=if(letter.isBlank()) "" else "AND EXISTS (SELECT 1 FROM search_initials i WHERE i.initial=? AND (i.value=t.album OR i.value=t.artist)) "
                        val args=mutableListOf<Any>(key); if(loc.kind=="artistAlbums") args+=loc.target
                        if(letter.isNotBlank()) args+=letter
                        val condition=SearchInitials.predicate(listOf("t.album","t.artist"),queryText,args)
                        args+=start
                        val order=if(albumSort=="artist") "artist COLLATE NOCASE,album COLLATE NOCASE,albumKey" else "album COLLATE NOCASE,albumKey"
                        if(start==0) countText="${number(dao.count(SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT albumKey,album FROM tracks t WHERE source=? $filter $artistFilter $initialFilter AND $condition GROUP BY albumKey,album)",args.dropLast(1).toTypedArray())))} 张专辑"
                        dao.albums(SimpleSQLiteQuery("SELECT albumKey,album AS title,CASE WHEN COUNT(DISTINCT NULLIF(artist,''))>1 THEN '群星' ELSE MAX(artist) END AS artist,MAX(artworkPath) AS artworkPath,COUNT(*) AS trackCount FROM tracks t WHERE source=? $filter $artistFilter $initialFilter AND $condition GROUP BY albumKey,album ORDER BY $order LIMIT 80 OFFSET ?", args.toTypedArray()))
                            .map { Row(it.title, "${it.artist.ifBlank { "未知艺术家" }} · ${it.trackCount} 首", album = it, artwork = it.artworkPath) }
                    }
                    "playlists" -> if(start != 0) emptyList() else {
                        val saved=dao.playlists(key)
                        (listOf(Row("新建歌单",builtin="create")) + saved.map { Row(it.name, if(it.kind == "folder") "文件夹歌单 · ${it.folder.ifBlank { "根目录" }}" else "自建歌单", playlist=it) }).filter { it.builtin=="create" || ((SearchInitials.matches(it.title,queryText) || SearchInitials.matches(it.subtitle,queryText)) && (letter.isBlank() || SearchInitials.of(it.title)==letter)) }.also { visible -> countText="${number(visible.count { it.builtin!="create" })} 个播放列表" }
                    }
                    "folders" -> if(start != 0) emptyList() else {
                        val entries = MusicSource.list(loc.target)
                        val indexed = db.library().folderTracks(key, loc.target)
                        val dirs = entries.filter { it.directory }.map { Row(it.name, "文件夹", folder=it.path) }
                        val songs = if(indexed.isNotEmpty()) indexed.map { Row(it.title,it.artist,track=it,artwork=it.artworkPath) } else {
                            val cue = entries.filter { !it.directory && SmbSource.isCue(it.path) }.flatMap { MusicSource.cue(it.path) }
                            val hidden = cue.map { it.path }.toSet()
                            (cue + entries.filter { !it.directory && SmbSource.isAudio(it.path) && (!prefs.enabled("hide_cue") || it.path !in hidden) }.map { Song(it.name.substringBeforeLast('.'),it.path) }).map { Row(it.title,it.artist,raw=it) }
                        }
                        val rawSources = if(!prefs.enabled("hide_cue")) entries.filter { !it.directory && SmbSource.isAudio(it.path) && indexed.any { t -> t.path == it.path && t.cuePath != null } }.map { Row(it.name,"原始音频",raw=Song(it.name,it.path)) } else emptyList()
                        (dirs + songs + rawSources).filter { (SearchInitials.matches(it.title,queryText) || SearchInitials.matches(it.subtitle,queryText)) && (letter.isBlank() || SearchInitials.of(it.title)==letter) }.also { visible ->
                            countText="${number(visible.count { it.folder!=null })} 个文件夹 · ${number(visible.count { it.track!=null || it.raw!=null })} 首"
                        }
                    }
                    else -> {
                        if(start==0) countText="${number(dao.count(currentScope.countQuery()))} 首歌曲"
                        dao.tracks(currentScope.query(80, start)).map { Row(it.title, it.artist.ifBlank { "未知歌手" }, track=it, artwork=it.artworkPath) }
                    }
                }
            }
            if(start==0 && groupKind!=null) {
                val column=if(groupKind=="artists") "artist" else "genre"
                val initialFilter=if(groupLetter.isEmpty()) "" else "AND EXISTS (SELECT 1 FROM search_initials i WHERE i.value=$column AND i.initial=?)"
                val args=mutableListOf<Any>(key)
                val condition=SearchInitials.predicate(listOf(column),groupQuery,args)
                if(groupLetter.isNotEmpty()) args+=groupLetter
                countText=runCatching { groupQuantity(dao.count(SimpleSQLiteQuery("SELECT COUNT(*) FROM (SELECT $column FROM tracks WHERE source=? AND $condition $initialFilter GROUP BY $column)",args.toTypedArray())),groupKind) }.getOrNull()
            }
            main.post {
                if(token != generation || activity.isDestroyed) return@post
                loading = false
                likedTracks=trackLikes; likedAlbums=albumLikes
                result.onSuccess { found ->
                    if(start==0) quantity.text=countText.orEmpty()
                    val old = rows.size; rows.addAll(found); offset += found.size
                    more = loc.kind !in listOf("home", "folders", "playlists") && found.size == 80
                    adapter.notifyItemRangeInserted(old, found.size)
                    updateToolbar()
                    pendingDown?.let { next ->
                        if(next<rows.size) { pendingDown=null; focusRow(next) }
                        else if(more) page() else { pendingDown=null; if(rows.isNotEmpty()) focusRow(rows.lastIndex) }
                    }
                    if(focusFirst && rows.isNotEmpty()) { focusFirst=false; list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() } }
                    val query=if(groupKind!=null) groupSearch else search
                    val activeInitial=if(groupKind!=null) groupLetter else letter
                    val empty=rows.none { it.builtin!="create" }
                    status.visibility=if(!empty && query.isBlank() && activeInitial.isBlank()) View.GONE else View.VISIBLE
                    status.text=when { empty && (query.isNotBlank() || activeInitial.isNotBlank()) -> "没有匹配结果"; activeInitial.isNotBlank() -> "首字母：$activeInitial"; query.isNotBlank() -> "搜索：$query"; empty && loc.kind=="playlists" -> "创建歌单，按自己的方式整理音乐"; empty -> if(loc.kind in listOf("albums","all")) "音乐库为空，可在设置中扫描 NAS" else "暂无歌曲"; else -> "" }
                    restore?.let { saved ->
                        val target=saved.key?.let { key -> rows.indexOfFirst { rowKey(it)==key }.takeIf { it>=0 } }
                        if(more && (if(saved.key!=null) target==null else rows.size<=saved.position)) page() else {
                            list.layoutManager?.onRestoreInstanceState(saved.scroll); restore=null
                            if(saved.position>=0 && rows.isNotEmpty()) {
                                val position=target ?: saved.position.coerceIn(0,rows.lastIndex)
                                selectRow(position); if(saved.focus) focusRow(position)
                            } else if(saved.focus) searchButton.post { searchButton.requestFocus() }
                        }
                    }
                }.onFailure { status.visibility=View.VISIBLE; status.text = "读取失败，请检查设置中的连接后重试" }
            }
        }
    }
    private fun number(value: Int)=java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(value)
    private fun groupQuantity(total: Int,kind: String)="${number(total)} ${if(kind=="artists") "位歌手" else "种风格"}"
    private fun open(row: Row) {
        when {
            row.section -> Unit
            row.group != null -> navigate(Location(if(location.kind=="artists") "artist" else "genre",row.title,row.group))
            row.builtin=="create" -> createPlaylist()
            row.builtin=="shuffle" -> task({ dao.tracks(TrackScope(source).query()).shuffled().map { it.song() } }) { if(it.isNotEmpty()) play(it,0) else toast("请先扫描音乐库") }
            row.album != null -> navigate(if(playlistContext()) location.copy(title=row.title,albumKey=row.album.albumKey) else Location("album",row.title,row.album.albumKey))
            row.folder != null -> navigate(Location("folders",row.title,row.folder))
            row.builtin != null -> navigate(Location(row.builtin,row.title))
            row.playlist != null -> row.playlist.let { navigate(Location(if(it.kind == "folder") "folder" else "playlist",it.name,if(it.kind == "folder") it.folder else it.id,it.recursive)) }
            else -> { val song=row.track?.song() ?: row.raw; if(song==null || !resumePlaying(song)) playContext(row) }
        }
    }
    private fun playContext(row: Row? = null) {
        val snapshot = scope(); val visible = rows.toList(); val folder = location.kind == "folders"
        task({
            if(folder || snapshot.kind=="home") visible.mapNotNull { it.track?.song() ?: it.raw }
            else dao.tracks(snapshot.query()).map { it.song() }
        }) { songs ->
            val target = row?.track?.id ?: row?.raw?.let { "${it.path}#${it.startUs}" }
            val i = if(target == null) 0 else songs.indexOfFirst { it.libraryId == target || "${it.path}#${it.startUs}" == target }
            if(songs.isNotEmpty()) play(songs,i.coerceAtLeast(0)) else toast("此列表暂无歌曲")
        }
    }
    private fun viewMenu() {
        val actions=mutableListOf<Pair<String,()->Unit>>()
        if(playlistContext() && location.albumKey.isEmpty()) {
            listOf("歌曲" to "songs","专辑" to "albums").forEach { (label,value) ->
                actions += (if(prefs.text(playlistViewKey(),"songs")==value) "当前视图：$label" else "切换为${label}视图") to {
                    prefs.set(playlistViewKey(),value); restore=null; focusFirst=false; reload()
                }
            }
        }
        if(albumView()) {
            actions += (if(grid()) "切换为列表" else "切换为封面网格") to {
                prefs.set("album_layout",if(grid()) "list" else "grid"); reloadKeepingPosition(focus=false)
            }
            actions += "排序：${if(albumSort=="artist") "歌手" else "专辑名称"}" to {
                activeMenu=TvStyle.dialog(activity).setTitle("专辑排序").setSingleChoiceItems(arrayOf("专辑名称","歌手"),if(albumSort=="artist") 1 else 0) { dialog,i ->
                    albumSort=if(i==0) "title" else "artist"; prefs.set("album_sort",albumSort); dialog.dismiss(); reloadKeepingPosition(focus=false)
                }.setNegativeButton("取消",null).show()
            }
            if(location.kind=="albums") actions += (if(favoriteAlbums) "显示所有专辑" else "只看收藏专辑") to {
                favoriteAlbums=!favoriteAlbums; location=location.copy(title=if(favoriteAlbums) "收藏的专辑" else "专辑"); reloadKeepingPosition(focus=false)
            }
        } else if(location.kind in listOf("all","artist","genre","favorites","folder")) {
            listOf("歌名" to "title","歌手" to "artist","专辑" to "album").forEach { (label,key) ->
                actions += (if(sort==key) "当前排序：$label" else "按${label}排序") to { sort=key; prefs.set("sort",sort); reloadKeepingPosition(focus=false) }
            }
        }
        if(actions.isEmpty()) return
        activeMenu=TvStyle.dialog(activity).setTitle("${location.title} · 视图").setItems(actions.map { it.first }.toTypedArray()) { _,i -> actions[i].second() }.show()
    }
    fun showMenu() {
        val focused=activity.currentFocus ?: return
        val position=list.findContainingViewHolder(focused)?.bindingAdapterPosition?.takeIf { it>=0 } ?: return
        rows.getOrNull(position)?.takeIf(::hasItemActions)?.let { selectRow(position); itemOptions(it) }
    }
    private fun itemOptions(row: Row) {
        val actions=mutableListOf<Pair<String,()->Unit>>()
        if(row.album != null) {
            actions += (if(row.album.albumKey in likedAlbums) "取消收藏专辑" else "收藏专辑") to { toggleFavorite("album",row.album.albumKey,true) }
            actions += "添加专辑到歌单" to { val snapshot=scope(); val scoped=playlistContext(); task({ if(scoped) dao.tracks(snapshot.copy(albumKey=row.album.albumKey).query()) else db.library().albumTracks(row.album.albumKey) }) { addToPlaylist(it.map { t -> t.id }) } }
        }
        if(row.folder != null) actions += "将此文件夹建为歌单" to { createPlaylist(row.folder) }
        if(row.track != null || row.raw != null) {
            val song = row.track?.song() ?: row.raw!!
            actions += "下一首播放" to { enqueue(song,true) }
            actions += "加入播放队列" to { enqueue(song,false) }
            row.track?.let { track ->
                actions += (if(track.id in likedTracks) "取消收藏歌曲" else "收藏歌曲") to { toggleFavorite("track",track.id,true) }
                actions += "添加到歌单" to { addToPlaylist(listOf(track.id)) }
                if(location.kind == "playlist") {
                    val id = location.target
                    actions += "从歌单移除" to { task({ dao.remove(id,track.id) }) { reloadKeepingPosition(removed=true) } }
                    actions += "上移" to { moveMember(id,track.id,-1) }
                    actions += "下移" to { moveMember(id,track.id,1) }
                }
            }
        }
        row.playlist?.let { p ->
            actions += "重命名歌单" to { input("歌单名称",p.name) { name -> if(name.isNotBlank()) task({ dao.savePlaylist(p.copy(name=name)) }) { reloadKeepingPosition() } } }
            actions += "删除歌单" to {
                TvStyle.dialog(activity).setTitle("删除“${p.name}”？").setMessage("仅删除歌单，NAS 音乐文件会保留。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") { _, _ -> task({ db.runInTransaction { dao.clearMembers(p.id); dao.deletePlaylist(p.id) } }) { reloadKeepingPosition(removed=true) } }.show()
            }
        }
        if(actions.isNotEmpty()) {
            val saved=anchor(true); val token=generation
            activeMenu=TvStyle.dialog(activity).setTitle(row.title).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.create().apply {
                setOnDismissListener { list.post { if(isShown && token==generation && saved.position in rows.indices) focusRow(saved.position) } }
                show()
            }
        }
    }
    private fun moveMember(id: String, track: String, delta: Int) {
        task({ db.runInTransaction {
            val members = dao.members(id).toMutableList(); val from = members.indexOfFirst { it.trackId == track }; val to=from+delta
            if(from >= 0 && to in members.indices) { val item=members.removeAt(from); members.add(to,item); dao.updateMembers(members.mapIndexed { i,m -> m.copy(position=i) }) }
        } }) { reloadKeepingPosition() }
    }
    fun favoriteTrack(id: String) = toggleFavorite("track",id)
    private fun toggleFavorite(kind: String, target: String, restoreFocus: Boolean=false) {
        val key=source; val token=generation; val saved=anchor(restoreFocus)
        task({ val exists = target in dao.favorites(key,kind); if(exists) dao.unfavorite(key,kind,target) else dao.favorite(Favorite(key,kind,target)); !exists }) {
            if(kind=="track") likedTracks=if(it) likedTracks+target else likedTracks-target else likedAlbums=if(it) likedAlbums+target else likedAlbums-target
            toast(if(it) "已收藏" else "已取消收藏"); changed()
            val removedSelected=!it && (selectedRow()?.track?.id==target || selectedRow()?.album?.albumKey==target)
            if(token!=generation) return@task
            if(location.kind == "favorites" || favoriteAlbums) {
                restore=saved.let { a -> if(removedSelected) a.copy(key=null) else a }; focusFirst=false; reload()
            } else {
                rows.forEachIndexed { index,row -> if(row.track?.id==target || row.album?.albumKey==target) adapter.notifyItemChanged(index) }
                if(restoreFocus && isShown && saved.position in rows.indices) focusRow(saved.position)
            }
        }
    }
    fun addToPlaylist(ids: List<String>) {
        task({ dao.playlists(source).filter { it.kind == "manual" } }) { choices ->
            TvStyle.dialog(activity).setTitle("添加到歌单").setItems((choices.map { it.name } + "新建歌单").toTypedArray()) { _, i ->
                if(i == choices.size) input("新建歌单") { name -> if(name.isNotBlank()) { val p=SavedPlaylist(source=source,name=name); task({ db.runInTransaction { dao.savePlaylist(p); ids.forEachIndexed { n,id -> dao.add(PlaylistMember(p.id,id,n)) } } }) { toast("已添加到歌单") } } }
                else task({ db.runInTransaction { val start=dao.nextPosition(choices[i].id); ids.forEachIndexed { n,id -> dao.add(PlaylistMember(choices[i].id,id,start+n)) } } }) { toast("已添加到歌单") }
            }.show()
        }
    }
    private fun createPlaylist(folder: String? = null) {
        if(folder != null) {
            input("文件夹歌单名称",folder.substringAfterLast('/').ifBlank { "NAS 音乐" }) { name ->
                if(name.isNotBlank()) TvStyle.dialog(activity).setTitle("包含子文件夹？").setItems(arrayOf("包含所有子文件夹", "仅当前文件夹")) { _, i ->
                    task({ dao.savePlaylist(SavedPlaylist(source=source,name=name,kind="folder",folder=folder,recursive=i==0)) }) { toast("已创建文件夹歌单"); reload() }
                }.show()
            }; return
        }
        TvStyle.dialog(activity).setTitle("新建歌单").setItems(arrayOf("手动添加歌曲", "指定 NAS 文件夹")) { _, i ->
            if(i == 0) input("歌单名称") { name -> if(name.isNotBlank()) task({ dao.savePlaylist(SavedPlaylist(source=source,name=name)) }) { reload() } }
            else chooseFolder("")
        }.show()
    }
    private fun chooseFolder(path: String) {
        task({ MusicSource.list(path).filter { it.directory } }) { dirs ->
            val names = listOf("✓ 使用此文件夹", "↑ 上一级") + dirs.map { it.name }
            TvStyle.dialog(activity).setTitle(path.ifBlank { "NAS 根目录" }).setItems(names.toTypedArray()) { _, i ->
                when(i) { 0 -> createPlaylist(path); 1 -> chooseFolder(path.substringBeforeLast('/',"")); else -> chooseFolder(dirs[i-2].path) }
            }.show()
        }
    }
    fun scan(force: Boolean = false) {
        LibraryIndexing.scan(activity,force)
    }
    fun cancelScan() { LibraryIndexing.cancel(); toast("正在暂停扫描，已入库歌曲会保留") }
    private fun input(title: String, value: String = "", action: (String) -> Unit) {
        val field=EditText(activity).apply { setText(value); isSingleLine=true; selectAll() }
        val builder=TvStyle.dialog(activity).setTitle(title).setView(field).setNegativeButton("取消",null).setPositiveButton("确定") { _, _ -> action(field.text.toString().trim()) }
        if(title.startsWith("搜索") && value.isNotBlank()) builder.setNeutralButton("清除搜索") { _,_ -> action("") }
        activeMenu=builder.show()
    }
    private fun <T> task(work: () -> T, done: (T) -> Unit) {
        worker.execute { val result=runCatching(work); main.post { if(!activity.isDestroyed) result.onSuccess(done).onFailure { toast("操作失败，请检查连接后重试") } } }
    }
    private fun toast(s: String) { Toast.makeText(activity,s,Toast.LENGTH_SHORT).show() }
    private inner class Holder(val card: LinearLayout, val image: ImageView, val title: TextView, val subtitle: TextView,
        val album: TextView, val detail: TextView, val index: TextView) : RecyclerView.ViewHolder(card)
    private inner class RowsAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = when { rows[position].section -> 3; rows[position].tile -> 2; grid() || (location.kind=="home" && rows[position].album!=null) -> 1; else -> 0 }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val card=LinearLayout(activity).apply {
                orientation=if(type==1 || type==2) VERTICAL else HORIZONTAL; gravity=if(type==1) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                isFocusable=type!=3; isClickable=type!=3; background=TvStyle.selector(activity,true)
                setPadding(dp(6),dp(if(type==3) 2 else 6),dp(6),dp(if(type==3) 2 else 6))
                layoutParams=RecyclerView.LayoutParams(-1,when(type) { 1 -> dp(176); 2 -> dp(108); 3 -> dp(32); else -> dp(56) }).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }
            }
            val index=TvStyle.label(activity,"",11,TvStyle.muted).apply { gravity=Gravity.CENTER }
            if(type==0) card.addView(index,LayoutParams(dp(24),-1))
            val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=TvStyle.shape(activity,TvStyle.raised,6); clipToOutline=true }
            card.addView(image,LayoutParams(if(type==1) dp(120) else if(type==2) -1 else dp(36),if(type==1) dp(120) else if(type==2) dp(48) else dp(36)))
            if(type==2) image.background=null
            if(type==3) image.visibility=View.GONE
            val labels=LinearLayout(activity).apply { orientation=VERTICAL; setPadding(if(type==1 || type==2) 0 else dp(10),dp(3),if(type==0) dp(12) else 0,0) }
            val title=TvStyle.label(activity,"",if(type==3) 18 else 14,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            val subtitle=TvStyle.label(activity,"",11,TvStyle.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            labels.addView(title); labels.addView(subtitle)
            card.addView(labels,LayoutParams(if(type==1 || type==2) -1 else 0,-2,if(type==1 || type==2) 0f else 2f))
            val album=TvStyle.label(activity,"",12,TvStyle.muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            val detail=TvStyle.label(activity,"",11,TvStyle.muted).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL; maxLines=2 }
            if(type==0) { card.addView(album,LayoutParams(0,-2,1.2f)); card.addView(detail,LayoutParams(dp(174),-1)) }
            return Holder(card,image,title,subtitle,album,detail,index)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row=rows[position]; val liked=row.track?.id in likedTracks || row.album?.albumKey in likedAlbums
            holder.title.text=row.title; Lucide.decorate(holder.title,if(liked) Icon.HEART_FILLED else null,14,TvStyle.accent); holder.subtitle.text=row.subtitle
            val compact=location.kind=="home"; val track=row.track
            val active=track!=null && track.id==playingId
            holder.index.text=if(active) "" else if(track!=null) "%02d".format(position+1) else ""
            Lucide.decorate(holder.index,if(active) Icon.PLAY else null,12,TvStyle.accent)
            holder.index.visibility=if(compact) View.GONE else View.VISIBLE
            holder.album.visibility=if(!compact && track!=null) View.VISIBLE else View.GONE
            holder.detail.visibility=if(!compact && track!=null) View.VISIBLE else View.GONE
            holder.album.text=track?.album
            holder.detail.text=track?.let { val duration=if(it.durationMs>0) "%d:%02d".format(it.durationMs/60000,it.durationMs/1000%60) else "--:--"; val size=if(it.fileSize>0) "%.0f MB".format(it.fileSize/1048576.0) else ""; "$duration   ${if(it.cuePath!=null && size.isNotEmpty()) "整轨 " else ""}$size\n${it.path.substringAfterLast('.').uppercase()}${if(it.cuePath!=null) " · CUE" else ""}" }.orEmpty()
            holder.subtitle.visibility=if(row.section) View.GONE else View.VISIBLE
            if(row.tile) {
                holder.subtitle.setTextColor(TvStyle.text)
                val colors=TvStyle.tileColors(row.builtin)
                holder.card.background=android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,colors).apply { cornerRadius=dp(12).toFloat() },TvStyle.selector(activity,true)))
            }
            holder.card.contentDescription=row.title
            holder.card.setOnClickListener { open(row) }
            holder.card.isSelected=position==selected
            holder.card.setOnLongClickListener { selectRow(holder.bindingAdapterPosition); itemOptions(row); true }
            holder.card.onFocusChangeListener=OnFocusChangeListener { view,focused ->
                if(focused && holder.bindingAdapterPosition >= 0) selectRow(holder.bindingAdapterPosition)
                if(prefs.enabled("animations")) view.animate().scaleX(if(focused) 1.015f else 1f).scaleY(if(focused) 1.015f else 1f).setDuration(100).start()
            }
            val fallbackIcon=when {
                row.folder!=null || row.playlist?.kind=="folder" -> Icon.FOLDER
                row.playlist!=null -> Icon.QUEUE
                row.builtin=="favorites" -> Icon.HEART
                row.builtin=="recent" -> Icon.CLOCK
                row.builtin=="shuffle" -> Icon.SHUFFLE
                row.builtin in listOf("create","added") -> Icon.PLUS
                row.builtin=="all" -> Icon.LIBRARY
                else -> Icon.DISC
            }
            holder.image.scaleType=if(row.tile) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            holder.image.setImageDrawable(Lucide.drawable(activity,fallbackIcon,color=if(row.tile) TvStyle.text else TvStyle.muted))
            val path=row.artwork ?: row.album?.let { OnlineMedia.cachedArtwork(activity,it.title,it.artist) }
            val identity=row.album?.albumKey ?: row.track?.id ?: path
            holder.image.tag=identity
            if(path != null) {
                val bitmap=cache.get(path)
                if(bitmap != null) holder.image.setImageBitmap(bitmap) else images.execute {
                    val decoded=BitmapFactory.decodeFile(path)
                    if(decoded != null) { cache.put(path,decoded); main.post { if(holder.image.tag == identity) holder.image.setImageBitmap(decoded) } }
                }
            } else if(row.album!=null && prefs.enabled("online_art") && artPending.add(row.album.albumKey)) {
                val scope=artScope; val album=row.album
                artWorker.execute { runCatching {
                    val fetched=OnlineMedia.artwork(activity,album.title,album.artist,scope) ?: return@runCatching
                    scope.check(); db.library().fillArtwork(album.albumKey,fetched)
                    val bitmap=BitmapFactory.decodeFile(fetched) ?: return@runCatching; cache.put(fetched,bitmap)
                    main.post { if(!activity.isDestroyed && !scope.cancelled.get() && holder.image.tag==identity) holder.image.setImageBitmap(bitmap) }
                } }
            }
        }
    }
    private inner class GroupHolder(val card: LinearLayout,val image: ImageView,val title: TextView,val count: TextView) : RecyclerView.ViewHolder(card)
    private inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>() {
        override fun getItemCount()=groupRows.size
        override fun onCreateViewHolder(parent: ViewGroup,type: Int): GroupHolder {
            val card=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL; isFocusable=true; isClickable=true; background=TvStyle.selector(activity,true); setPadding(dp(6),dp(4),dp(6),dp(4)); layoutParams=RecyclerView.LayoutParams(-1,dp(58)) }
            val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=TvStyle.shape(activity,TvStyle.panel,24); clipToOutline=true }
            card.addView(image,LayoutParams(dp(34),dp(34)))
            val labels=LinearLayout(activity).apply { orientation=VERTICAL; setPadding(dp(8),0,0,0) }
            val title=TvStyle.label(activity,"",13,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }
            val count=TvStyle.label(activity,"",10,TvStyle.muted); labels.addView(title); labels.addView(count); card.addView(labels,LayoutParams(0,-2,1f))
            return GroupHolder(card,image,title,count)
        }
        override fun onBindViewHolder(holder: GroupHolder,position: Int) {
            val row=groupRows[position]; holder.title.text=row.title; holder.count.text=row.subtitle; holder.card.isSelected=row.group==location.target
            holder.card.setOnClickListener { selectGroup(row) }; holder.image.setImageDrawable(Lucide.drawable(activity,if(grouping=="artists") Icon.ARTIST else Icon.GENRE,color=TvStyle.muted)); holder.image.tag=row.artwork
            row.artwork?.let { path -> images.execute { val bitmap=cache.get(path) ?: BitmapFactory.decodeFile(path)?.also { cache.put(path,it) }; main.post { if(!activity.isDestroyed && holder.image.tag==path && bitmap!=null) holder.image.setImageBitmap(bitmap) } } }
        }
    }
    fun close() { generation++; activeMenu?.dismiss(); LibraryIndexing.remove(indexListener); artScope.cancel(); worker.shutdownNow(); images.shutdownNow(); artWorker.shutdownNow() }
}
