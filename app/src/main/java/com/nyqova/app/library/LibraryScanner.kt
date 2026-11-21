package com.nyqova.app.library

import android.content.Context
import com.nyqova.app.storage.*
import com.nyqova.core.MusicText
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class ScanResult(val albums: Int,val tracks: Int,val changedDirectories: Int,val reusedDirectories: Int,val warnings: Int,val pendingMetadata: Int=0)

/** Directory/CUE indexing publishes complete folders. Audio tags are a separate, resumable pass. */
class LibraryScanner(private val context: Context,val cancelled: AtomicBoolean=AtomicBoolean(false)) {
    companion object { private val running=AtomicBoolean(false) }
    private fun check(source: String) {
        if(cancelled.get() || Thread.currentThread().isInterrupted || source!=MusicSource.key()) throw CancellationException("扫描已停止，已入库歌曲会保留")
    }
    fun scan(force: Boolean=false,progress: (String)->Unit={}): ScanResult {
        check(running.compareAndSet(false,true)) { "已有音乐索引任务正在运行" }
        return try { fastScan(force,progress) } finally { running.set(false) }
    }
    private fun fastScan(force: Boolean,progress: (String)->Unit): ScanResult {
        val source=MusicSource.key(); val db=LibraryDatabase.get(context); val dao=db.library(); val index=db.audioIndex()
        val previous=dao.directories(source).associateBy { it.path }
        val oldRows=db.collections().tracks(TrackScope(source).query()).associateBy { it.id }
        val oldFiles=index.files(source).associateBy { it.path }
        val queue=java.util.ArrayDeque<String>().apply { add("") }; val visited=mutableSetOf<String>(); val seenAudio=mutableSetOf<String>()
        var changed=0; var reused=0; var warnings=0; var found=0
        while(queue.isNotEmpty()) {
            check(source); val folder=queue.removeFirst(); if(!visited.add(folder)) continue
            require(visited.size<=10000 && folder.count { it=='/' }<32) { "目录过多或过深，请缩小音乐源范围" }
            val entries=MusicSource.listAll(folder)
            entries.filter { it.directory && !it.name.startsWith('.') && !it.name.startsWith('@') }.forEach { queue.add(it.path) }
            val lyricFolder=entries.firstOrNull { it.directory && it.name.equals("lyrics",true) }
            val lyrics=entries.filter { it.name.endsWith(".lrc",true) }+(lyricFolder?.let { MusicSource.listAll(it.path).filter { f -> f.name.endsWith(".lrc",true) } } ?: emptyList())
            val digest=MusicSource.digest((entries+lyrics).sortedBy { it.path }.joinToString("\n") { "${it.path}:${it.size}:${it.modified}" })
            val fingerprint="fast-v6:$digest"
            val unchanged=!force && previous[folder]?.fingerprint==fingerprint
            val audio=entries.filter { !it.directory && SmbSource.isAudio(it.path) }
            seenAudio.addAll(audio.map { it.path })
            if(unchanged) {
                found+=dao.folderTracks(source,folder).size; reused++
                progress("快速建库：$found 首 · ${visited.size} 个目录"); continue
            }
            changed++
            val cover=entries.filter { !it.directory && it.name.substringAfterLast('.').lowercase() in setOf("jpg","jpeg","png","webp") }
                .sortedBy { if(it.name.substringBeforeLast('.').lowercase() in setOf("cover","folder","front","album")) 0 else 1 }.firstOrNull()
            val localArt=cover?.let { ArtworkCache.cached(context,"$source:${it.path}:${it.modified}:${it.size}") }
            val rows=mutableListOf<LibraryTrack>(); val referenced=mutableSetOf<String>()
            fun lyric(names: List<String>)=names.firstNotNullOfOrNull { name -> lyrics.firstOrNull { it.name.equals(name,true) }?.path }
            entries.filter { !it.directory && SmbSource.isCue(it.path) }.forEach { cue ->
                check(source)
                val bytes=runCatching { MusicSource.readSmall(cue.path) }.onFailure { warnings++ }.getOrNull() ?: return@forEach
                val songs=runCatching { SmbSource.cueSongs(cue.path,bytes) }.onFailure { warnings++ }.getOrNull() ?: return@forEach
                if(songs.any { song -> audio.none { it.path==song.path } }) { warnings++; return@forEach }
                val genre=Regex("(?im)^REM\\s+GENRE\\s+\"?([^\"\\r\\n]+)").find(MusicText.decode(bytes))?.groupValues?.get(1)?.trim().orEmpty()
                songs.forEachIndexed { i,song ->
                    val file=audio.first { it.path==song.path }; val id=MusicSource.digest("$source:${cue.path}:$i"); val old=oldRows[id]
                    val album=song.album.ifBlank { old?.album ?: folder.substringAfterLast('/').ifBlank { "未分类音乐" } }
                    val perTrack=lyric(listOf("${song.title}.lrc","%02d.lrc".format(i+1),"%02d ${song.title}.lrc".format(i+1)))
                    val whole=lyric(listOf("${file.name.substringBeforeLast('.')}.lrc"))
                    rows+=LibraryTrack(id,source,folder,MusicSource.digest("$source:$folder:$album"),song.title,song.artist.ifBlank { old?.artist.orEmpty() },album,
                        song.path,cue.path,i+1,song.startUs,song.endUs,song.endUs?.minus(song.startUs)?.div(1000) ?: old?.durationMs ?: 0,
                        localArt ?: old?.artworkPath,perTrack ?: whole,if(perTrack==null && whole!=null) song.startUs/1000 else 0,
                        genre.ifBlank { old?.genre.orEmpty() },old?.addedAt?.takeIf { it>0 } ?: System.currentTimeMillis(),file.size)
                    referenced+=song.path
                }
            }
            audio.filter { it.path !in referenced }.forEachIndexed { i,entry ->
                check(source); val id=MusicSource.digest("$source:${entry.path}"); val old=oldRows[id]
                val album=old?.album ?: folder.substringAfterLast('/').ifBlank { "散曲" }
                val name=entry.name.substringBeforeLast('.')
                rows+=LibraryTrack(id,source,folder,MusicSource.digest("$source:$folder:$album"),old?.title ?: name,old?.artist.orEmpty(),album,
                    entry.path,null,old?.number ?: i+1,0,null,old?.durationMs ?: 0,old?.artworkPath?.takeUnless { audio.size>1 && it==localArt },lyric(listOf("$name.lrc")),0,
                    old?.genre.orEmpty(),old?.addedAt?.takeIf { it>0 } ?: System.currentTimeMillis(),entry.size)
            }
            val jobs=audio.map { entry ->
                val metadataVersion=if(entry.path.substringAfterLast('.').lowercase() in setOf("ape","m4a")) "native-v1" else "art-v2"
                val fileFingerprint=MusicSource.digest("$metadataVersion:${entry.size}:${entry.modified}")
                val old=oldFiles[entry.path]?.takeIf { it.fingerprint==fileFingerprint && !force }
                IndexedAudio(source,entry.path,folder,entry.size,entry.modified,fileFingerprint,
                    old?.ready ?: false,old?.attempts ?: 0,cover?.path,cover?.modified.orEmpty(),cover?.size ?: 0)
            }
            check(source)
            db.runInTransaction {
                dao.deleteFolderTracks(source,folder); dao.insertTracks(rows); index.save(jobs)
                dao.insertDirectories(listOf(IndexedDirectory(source,folder,fingerprint)))
            }
            found+=rows.size; progress("快速建库：$found 首 · ${visited.size} 个目录")
        }
        check(source)
        // Only a successful complete traversal removes directories/files that are no longer present.
        db.runInTransaction {
            previous.keys.filter { it !in visited }.forEach { dao.deleteFolderTracks(source,it); dao.deleteDirectory(source,it) }
            oldFiles.keys.filter { it !in seenAudio }.forEach { index.delete(source,it) }
        }
        val albums=dao.albums(source)
        return ScanResult(albums.size,albums.sumOf { it.trackCount },changed,reused,warnings,index.pendingCount(source))
    }

    fun enrich(progress: (completed: Int,pending: Int,failed: Int)->Unit={_,_,_->}): Int {
        if(!running.compareAndSet(false,true)) return 0
        val source=MusicSource.key(); val db=LibraryDatabase.get(context); val index=db.audioIndex(); val dao=db.library(); var completed=0
        val covers=mutableMapOf<String,String?>(); var networkFailures=0
        val folderFileCounts=mutableMapOf<String,Int>()
        val folderAlbumCounts=mutableMapOf<String,Int>()
        return try {
            while(true) {
                check(source); val jobs=index.pending(source); if(jobs.isEmpty()) break
                for(job in jobs) {
                    check(source)
                    try {
                        val current=dao.fileTracks(source,job.path)
                        val cue=current.any { it.cuePath!=null }
                        val solo=folderFileCounts.getOrPut(job.folder) { dao.folderTracks(source,job.folder).map { it.path }.distinct().size }==1
                        val singleAlbum=folderAlbumCounts.getOrPut(job.folder) { dao.folderTracks(source,job.folder).map { it.album }.distinct().size }==1
                        val entry=RemoteEntry(job.path.substringAfterLast('/'),job.path,false,job.size,job.modified)
                        val meta=AudioMetadataReader.read(entry,true) { check(source) }
                        fun normalized(value: String)=value.lowercase().filter { it.isLetterOrDigit() }
                        val album=normalized(meta.album)
                        val matchesAlbum=album.isNotEmpty() && (normalized(job.folder.substringAfterLast('/'))==album || normalized(job.coverPath?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty())==album)
                        val cover=job.coverPath?.takeIf { (cue && singleAlbum) || solo || matchesAlbum }?.let { path -> covers.getOrPut(path) {
                            val key="$source:$path:${job.coverModified}:${job.coverSize}"
                            ArtworkCache.cached(context,key) ?: runCatching { ArtworkCache.store(context,key,MusicSource.readSmall(path,5*1024*1024)) }.getOrNull()
                        } }
                        val art=cover ?: meta.picture?.let { ArtworkCache.store(context,"$source:${job.path}:${job.modified}:${job.size}",it) }
                        val unrelatedCover=if(cover==null) job.coverPath?.let { ArtworkCache.cached(context,"$source:$it:${job.coverModified}:${job.coverSize}") } else null
                        check(source)
                        db.runInTransaction {
                            val rows=dao.fileTracks(source,job.path).map { row ->
                                val cue=row.cuePath!=null
                                val album=if(cue) row.album else meta.album.ifBlank { row.album }
                                row.copy(title=if(cue) row.title else meta.title.ifBlank { row.title },artist=if(cue) row.artist.ifBlank { meta.artist } else meta.artist.ifBlank { row.artist },
                                    album=album,albumKey=MusicSource.digest("$source:${row.folder}:$album"),number=if(!cue && meta.trackNumber>0) meta.trackNumber else row.number,
                                    durationMs=if(cue) row.endUs?.minus(row.startUs)?.div(1000) ?: (meta.durationMs-row.startUs/1000).coerceAtLeast(0) else meta.durationMs,
                                    artworkPath=art ?: row.artworkPath?.takeUnless { it==unrelatedCover },genre=row.genre.ifBlank { meta.genre })
                            }
                            dao.insertTracks(rows); index.save(listOf(job.copy(ready=true)))
                        }
                        networkFailures=0
                        context.getSharedPreferences("index_errors",0).edit().remove("$source:${MusicSource.digest(job.path)}").apply()
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) {
                        check(source)
                        index.save(listOf(job.copy(attempts=job.attempts+1)))
                        val reason=if(error is AudioMetadataReader.BudgetExceeded) "标签读取超时或超过读取预算" else "标签读取失败：${error.javaClass.simpleName}"
                        context.getSharedPreferences("index_errors",0).edit().putString("$source:${MusicSource.digest(job.path)}",reason).apply()
                        if(error is java.io.IOException && error !is AudioMetadataReader.BudgetExceeded) {
                            networkFailures++
                            if(networkFailures>=3) throw error
                        } else networkFailures=0
                    }
                    completed++; progress(completed,index.pendingCount(source),index.failedCount(source))
                }
            }
            completed
        } finally { running.set(false) }
    }
}
