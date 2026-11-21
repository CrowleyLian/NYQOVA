package com.nyqova.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import java.util.concurrent.Executors

/** A remote-friendly drawer: list selection never relies on hovering or a long press. */
class TvQueueDialog(private val activity: Activity, private val player: MediaController) : Dialog(activity) {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var selected=player.currentMediaItemIndex.coerceAtLeast(0)
    private lateinit var list: ListView
    private lateinit var heading: TextView
    private var submenu: AlertDialog?=null
    private val adapter=QueueAdapter()
    private val listener=object : Player.Listener {
        override fun onEvents(player: Player,events: Player.Events) { if(isShowing) { heading.text="播放队列 · ${player.mediaItemCount}"; adapter.notifyDataSetChanged() } }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(20),dp(16),dp(18)); background=TvStyle.shape(activity,TvStyle.panel,18) }
        val top=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        heading=TvStyle.label(activity,"播放队列 · ${player.mediaItemCount}",18,bold=true)
        top.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(Lucide.button(activity,Icon.CLOSE,"关闭队列",true) { dismiss() }); root.addView(top)
        list=ListView(activity).apply {
            dividerHeight=0; selector=TvStyle.selector(activity,true); this.adapter=this@TvQueueDialog.adapter
            setOnItemClickListener { _,_,index,_ -> selected=index; playSelected(index) }
            onItemSelectedListener=object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { selected=position }
                override fun onNothingSelected(parent: AdapterView<*>?)=Unit
            }
            setOnItemLongClickListener { _,_,index,_ -> selected=index; actions(); true }
        }; root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val bottom=LinearLayout(activity)
        bottom.addView(Lucide.button(activity,Icon.TRASH,"清空") { confirmClear() }.apply { tag="queue_clear" },LinearLayout.LayoutParams(0,dp(44),1f))
        root.addView(bottom); setContentView(root)
        window?.apply { setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.END or Gravity.CENTER_VERTICAL); setLayout(dp(350),-1); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes=attributes.apply { dimAmount=.28f } }
        player.addListener(listener); list.setSelection(selected); list.requestFocus()
        setOnDismissListener { submenu?.dismiss(); player.removeListener(listener); worker.shutdownNow() }
    }
    override fun show() {
        super.show(); window?.apply {
            setLayout(dp(350),-1)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            if(android.os.Build.VERSION.SDK_INT>=28) attributes=attributes.apply { layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
    private fun dp(n: Int)=TvStyle.dp(activity,n)
    private fun playSelected(index: Int) {
        if(index !in 0 until player.mediaItemCount) return
        if(index!=player.currentMediaItemIndex) player.seekToDefaultPosition(index)
        else if(player.playbackState==Player.STATE_ENDED) player.seekTo(0)
        player.prepare(); player.play()
    }
    private fun confirmClear() {
        submenu=TvStyle.dialog(activity).setTitle("清空播放队列？")
            .setMessage("将停止播放并移除队列中的 ${player.mediaItemCount} 首歌曲，NAS 文件会保留。")
            .setNegativeButton("取消",null).setPositiveButton("清空") { _,_ -> player.stop(); player.clearMediaItems(); dismiss() }.show()
        submenu?.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }
    private fun actions() {
        val index=selected.coerceIn(0,(player.mediaItemCount-1).coerceAtLeast(0)); if(player.mediaItemCount==0) return
        submenu=TvStyle.dialog(activity).setTitle(player.getMediaItemAt(index).mediaMetadata.title).setItems(arrayOf("移出队列","上移","下移","下一首播放")) { _,i ->
            when(i) {
                0 -> { player.removeMediaItem(index); selected=index.coerceAtMost((player.mediaItemCount-1).coerceAtLeast(0)) }
                1 -> if(index>0) { player.moveMediaItem(index,index-1); selected=index-1 }
                2 -> if(index<player.mediaItemCount-1) { player.moveMediaItem(index,index+1); selected=index+1 }
                3 -> if(index!=player.currentMediaItemIndex) { val target=if(index<player.currentMediaItemIndex) player.currentMediaItemIndex else player.currentMediaItemIndex+1; player.moveMediaItem(index,target.coerceAtMost(player.mediaItemCount-1)); selected=target.coerceAtMost(player.mediaItemCount-1) }
            }; adapter.notifyDataSetChanged(); list.setSelection(selected)
        }.show()
    }
    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount()=player.mediaItemCount
        override fun getItem(position: Int)=player.getMediaItemAt(position)
        override fun getItemId(position: Int)=position.toLong()
        override fun getView(position: Int,recycled: View?,parent: ViewGroup): View {
            val row=(recycled as? LinearLayout) ?: LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8)); minimumHeight=dp(64)
                addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP },LinearLayout.LayoutParams(dp(40),dp(40)))
                addView(LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,dp(4),0)
                    addView(TvStyle.label(activity,"",14,bold=true).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); addView(TvStyle.label(activity,"",11,TvStyle.muted).apply { maxLines=1 })
                },LinearLayout.LayoutParams(0,-2,1f))
            }
            val item=getItem(position); val labels=row.getChildAt(1) as LinearLayout
            (labels.getChildAt(0) as TextView).apply { text=item.mediaMetadata.title; Lucide.decorate(this,if(position==player.currentMediaItemIndex) Icon.PLAY else null,14) }
            (labels.getChildAt(1) as TextView).text=item.mediaMetadata.artist
            row.background=TvStyle.shape(activity,if(position==player.currentMediaItemIndex) TvStyle.accentSurface(.2f) else android.graphics.Color.TRANSPARENT)
            val image=row.getChildAt(0) as ImageView; image.setImageDrawable(Lucide.drawable(activity,Icon.DISC,color=TvStyle.muted)); val path=item.mediaMetadata.extras?.getString("artwork_path"); image.tag=path
            if(path!=null && !worker.isShutdown) worker.execute { val bitmap=BitmapFactory.decodeFile(path); main.post { if(isShowing && image.tag==path && bitmap!=null) image.setImageBitmap(bitmap) } }
            return row
        }
    }
}
