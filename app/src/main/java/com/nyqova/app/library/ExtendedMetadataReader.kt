package com.nyqova.app.library

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.*
import androidx.media3.extractor.mp4.Mp4Extractor
import com.nyqova.app.storage.*
import io.github.eugenedibtsev.media3.ape.ApeExtractor

/** Read container headers/tags through bounded NAS ranges, without decoding a song. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object ExtendedMetadataReader {
    private class HeadersReady : java.io.IOException()
    fun read(entry: RemoteEntry,cover: Boolean,ensureActive: () -> Unit): AudioMetadata {
        var extractor: Extractor=if(entry.path.endsWith(".ape",true)) ApeExtractor() else Mp4Extractor()
        val started=android.os.SystemClock.elapsedRealtime()
        var bytes=0L; var position=0L; var windowEnd=0L; var data: MusicDataSource?=null
        var format: Format?=null; var durationUs=0L; var hasSeekMap=false
        val reader=DataReader { buffer,offset,length ->
            ensureActive()
            if(android.os.SystemClock.elapsedRealtime()-started>10000 || bytes>8*1024*1024) throw AudioMetadataReader.BudgetExceeded()
            if(length==0) 0 else if(position>=entry.size) C.RESULT_END_OF_INPUT else {
                if(data==null || position>=windowEnd) {
                    data?.close(); data=MusicDataSource(DavRequest.metadataClient)
                    val count=minOf(64*1024L,entry.size-position); windowEnd=position+count
                    data!!.open(DataSpec.Builder().setUri(MusicSource.uri(entry.path)).setPosition(position).setLength(count).build())
                }
                val count=data!!.read(buffer,offset,minOf(length.toLong(),windowEnd-position).toInt())
                if(count>0) { position+=count; bytes+=count }; count
            }
        }
        val track=object : TrackOutput {
            override fun format(value: Format) { if(value.sampleMimeType?.startsWith("audio/")==true) format=value }
            override fun sampleData(input: DataReader,length: Int,allowEndOfInput: Boolean,sampleDataPart: Int): Int {
                if(format!=null && hasSeekMap) throw HeadersReady()
                throw java.io.IOException("未读取到音频头部")
            }
            override fun sampleData(data: ParsableByteArray,length: Int,sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long,flags: Int,size: Int,offset: Int,cryptoData: TrackOutput.CryptoData?)=Unit
        }
        val output=object : ExtractorOutput {
            override fun track(id: Int,type: Int): TrackOutput = track
            override fun endTracks()=Unit
            override fun seekMap(map: SeekMap) { hasSeekMap=true; durationUs=map.durationUs.coerceAtLeast(0) }
        }
        try {
            var input=DefaultExtractorInput(reader,0,entry.size); val target=PositionHolder()
            if(entry.path.endsWith(".ape",true)) {
                val candidates=listOf(extractor,androidx.media3.extractor.mp3.Mp3Extractor(),androidx.media3.extractor.flac.FlacExtractor(),Mp4Extractor())
                var matched=false
                for(candidate in candidates) {
                    val accepted=try { candidate.sniff(input) } catch(_: java.io.EOFException) { false }
                    input.resetPeekPosition()
                    if(accepted) { extractor=candidate; matched=true; break } else candidate.release()
                }
                if(!matched) throw java.io.IOException("无法识别文件内容，后缀可能不符合实际格式")
            }
            extractor.init(output)
            for(step in 0 until 128) {
                ensureActive()
                val result=try { extractor.read(input,target) } catch(_: HeadersReady) { break }
                if(format!=null && hasSeekMap) break
                if(result==Extractor.RESULT_END_OF_INPUT) break
                if(result==Extractor.RESULT_SEEK) {
                    require(target.position in 0..entry.size) { "无效音频定位" }
                    data?.close(); data=null; position=target.position
                    input=DefaultExtractorInput(reader,position,entry.size)
                }
            }
            val found=format ?: throw java.io.IOException("未读到音频格式")
            val builder=MediaMetadata.Builder()
            found.metadata?.let { metadata -> for(i in 0 until metadata.length()) metadata[i].populateMediaMetadata(builder) }
            val meta=builder.build()
            return AudioMetadata(meta.title?.toString().orEmpty(),meta.artist?.toString().orEmpty(),meta.albumTitle?.toString().orEmpty(),
                durationUs/1000,if(cover) meta.artworkData?.takeIf { it.size<=5*1024*1024 } else null,meta.trackNumber ?: 0,meta.genre?.toString().orEmpty())
        } finally { extractor.release(); data?.close() }
    }
}
