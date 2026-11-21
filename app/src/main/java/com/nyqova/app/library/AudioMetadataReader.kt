package com.nyqova.app.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.media3.datasource.DataSpec
import com.nyqova.app.storage.*
import java.io.File

data class AudioMetadata(val title: String, val artist: String, val album: String, val durationMs: Long, val picture: ByteArray?, val trackNumber: Int = 0, val genre: String = "")

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object AudioMetadataReader {
    class BudgetExceeded : java.io.IOException("标签读取达到限制，保留可播放的基础信息")
    fun read(entry: RemoteEntry, embeddedCover: Boolean,ensureActive: () -> Unit = {}): AudioMetadata {
        if(entry.path.substringAfterLast('.').lowercase() in setOf("ape","m4a")) return ExtendedMetadataReader.read(entry,embeddedCover,ensureActive)
        val started=android.os.SystemClock.elapsedRealtime()
        val source = object : MediaDataSource() {
            var data: MusicDataSource? = null
            var next = -1L
            var windowEnd=-1L
            var bytes=0L
            var requests=0
            var failure: Exception?=null
            override fun getSize() = entry.size
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                try {
                ensureActive()
                if(android.os.SystemClock.elapsedRealtime()-started>8000 || bytes>8*1024*1024 || requests>=64) throw BudgetExceeded()
                if(size == 0) return 0
                if(position >= entry.size) return -1
                if(data == null || position != next || position>=windowEnd) {
                    data?.close(); data = MusicDataSource(DavRequest.metadataClient)
                    val length=minOf(entry.size-position,maxOf(size.toLong(),64*1024L))
                    windowEnd=position+length; requests++
                    data!!.open(DataSpec.Builder().setUri(MusicSource.uri(entry.path)).setPosition(position).setLength(length).build())
                }
                val count = data!!.read(buffer, offset, minOf(size.toLong(),windowEnd-position).toInt())
                next = if(count > 0) position + count else -1
                if(count>0) bytes+=count
                return count
                } catch(error: Exception) { failure=error; throw error }
            }
            @Synchronized override fun close() { data?.close(); data = null }
        }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(source)
            fun tag(key: Int) = reader.extractMetadata(key).orEmpty()
            return AudioMetadata(tag(MediaMetadataRetriever.METADATA_KEY_TITLE), tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                tag(MediaMetadataRetriever.METADATA_KEY_ALBUM), tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                if(embeddedCover) reader.embeddedPicture?.takeIf { it.size <= 5 * 1024 * 1024 } else null,
                tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
                tag(MediaMetadataRetriever.METADATA_KEY_GENRE))
        } catch(error: Exception) { throw (source.failure ?: error) }
        finally { runCatching { reader.release() }; source.close() }
    }
}

object ArtworkCache {
    fun cached(context: Context,key: String): String? = File(context.filesDir,"artwork/${MusicSource.digest(key)}.jpg").takeIf { it.isFile }?.absolutePath
    fun store(context: Context, key: String, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if(bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 32768 || bounds.outHeight > 32768) return null
        var sample = 1
        while(maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try {
            val folder = File(context.filesDir, "artwork").apply { mkdirs() }
            val file = File(folder, "${MusicSource.digest(key)}.jpg")
            val temp = File(folder, "${file.name}.tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            check(temp.renameTo(file)) { "封面缓存写入失败" }
            file.absolutePath
        } finally { bitmap.recycle() }
    }
}
