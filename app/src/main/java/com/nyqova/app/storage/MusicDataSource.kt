package com.nyqova.app.storage

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicDataSource(private val httpClient: okhttp3.OkHttpClient = DavRequest.client) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
    override fun open(dataSpec: DataSpec): Long {
        val next = if(dataSpec.uri.scheme == "smb") SmbDataSource() else {
            if(!WebDavSource.sameSource(dataSpec.uri)) throw IOException("音乐地址不属于当前 WebDAV 来源")
            OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(mapOf("Authorization" to WebDavSource.authorization())).createDataSource()
        }
        delegate = next; listeners.forEach(next::addTransferListener)
        return next.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
    class Factory : DataSource.Factory { override fun createDataSource() = MusicDataSource() }
}
