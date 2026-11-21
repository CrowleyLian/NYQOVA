package com.nyqova.app.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nyqova.app.storage.MusicSource
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

/** Process-owned indexing: UI queries never wait behind network metadata reads. */
object LibraryIndexing {
    data class State(val source: String="",val message: String="",val active: Boolean=false,val revision: Long=0)
    private val executor=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val listeners=mutableSetOf<(State)->Unit>()
    @Volatile var state=State(); private set
    @Volatile private var task: LibraryScanner?=null
    @Volatile private var serial=0L
    fun observe(listener: (State)->Unit) { listeners+=listener; listener(state) }
    fun remove(listener: (State)->Unit) { listeners-=listener }
    private fun publish(token: Long,value: State) { main.post { if(token==serial) { state=value; listeners.toList().forEach { it(value) } } } }
    fun cancel() { task?.cancelled?.set(true) }
    fun scan(context: Context,force: Boolean=false) = launch(context,true,force,false)
    fun resume(context: Context,retry: Boolean=false) {
        if(state.active && state.source==MusicSource.key()) return
        launch(context,context.getSharedPreferences("indexing",0).getBoolean(MusicSource.key(),false),false,retry)
    }
    private fun launch(context: Context,scan: Boolean,force: Boolean,retry: Boolean) {
        task?.cancelled?.set(true)
        val token=++serial; val source=MusicSource.key(); val app=context.applicationContext
        val saved=app.getSharedPreferences("indexing",0)
        if(scan) saved.edit().putBoolean(source,true).apply()
        val work=LibraryScanner(app); task=work
        state=State(source,if(scan) "正在读取 NAS 目录…" else "正在检查待补全信息…",true,state.revision)
        listeners.toList().forEach { it(state) }
        executor.execute {
            var revision=state.revision
            try {
                if(work.cancelled.get() || source!=MusicSource.key()) throw CancellationException()
                val index=LibraryDatabase.get(app).audioIndex()
                if(scan) {
                    work.scan(force) { message -> publish(token,State(source,message,true,++revision)) }
                    if(work.cancelled.get() || token!=serial) throw CancellationException()
                    saved.edit().remove(source).apply()
                    publish(token,State(source,"歌曲已入库，可以播放；正在补全标签和封面…",true,++revision))
                }
                if(retry) index.retry(source)
                work.enrich { completed,pending,failed ->
                    publish(token,State(source,"补全标签和封面：剩余 $pending 个文件${if(failed>0) " · $failed 个待重试" else ""}",true,if(completed%10==0) ++revision else revision))
                }
                val failed=index.failedCount(source)
                publish(token,State(source,if(failed>0) "$failed 个文件信息未补全，可重试；歌曲仍可播放" else "",false,++revision))
            } catch(error: Exception) {
                publish(token,State(source,if(error is CancellationException) "扫描已暂停，已入库歌曲可播放" else "扫描暂停，请检查连接后继续；已入库歌曲可播放",false,++revision))
            }
        }
    }
}
