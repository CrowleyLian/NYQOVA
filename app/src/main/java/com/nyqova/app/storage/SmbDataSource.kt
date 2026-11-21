package com.nyqova.app.storage

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Reads directly at ExoPlayer's requested byte offset; never downloads a whole album to RAM. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {
    private var handle: SmbSource.Handle? = null
    private var file: com.hierynomus.smbj.share.File? = null
    private var currentUri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            currentUri = dataSpec.uri
            val source = SmbSource.settings
            require(dataSpec.uri.host == source.host && dataSpec.uri.pathSegments.firstOrNull() == source.share) {
                "音乐源已变更，请重新选择曲目"
            }
            val path = dataSpec.uri.pathSegments.drop(1).joinToString("/")
            handle = SmbSource.Handle(source)
            file = handle!!.open(path)
            position = dataSpec.position
            val length = file!!.fileInformation.standardInformation.endOfFile
            if (position > length) throw IOException("读取位置超出文件长度")
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) length - position
                        else minOf(dataSpec.length, length - position)
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { close(); throw IOException("NAS 读取失败：${e.message}", e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val count = file!!.read(buffer, position, offset, minOf(length.toLong(), remaining).toInt())
            if (count <= 0) throw IOException("NAS 文件在预期结束前中断")
            position += count
            remaining -= count
            bytesTransferred(count)
            return count
        } catch (e: Exception) { throw IOException("NAS 连接中断，请检查网络后重试", e) }
    }
    override fun getUri() = currentUri
    override fun close() {
        runCatching { file?.close() }; file = null
        runCatching { handle?.close() }; handle = null
        currentUri = null
        if (opened) { opened = false; transferEnded() }
    }
    class Factory : DataSource.Factory { override fun createDataSource() = SmbDataSource() }
}
