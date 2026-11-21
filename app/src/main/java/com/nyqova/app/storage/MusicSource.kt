package com.nyqova.app.storage

import android.net.Uri

object MusicSource {
    @Volatile var initialized = false
    @Volatile var webDavBase: String? = null
    @Volatile var username: String = ""
    @Volatile var password: String = ""
    fun uri(path: String): Uri = webDavBase?.let { base ->
        Uri.parse(base).buildUpon().apply {
            path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
        }.build()
    } ?: SmbSource.uri(path)
    fun list(path: String) = if(webDavBase != null) WebDavSource.list(path) else SmbSource.list(path)
    fun listAll(path: String) = if(webDavBase != null) WebDavSource.list(path, true) else SmbSource.list(path, true)
    fun readSmall(path: String, limit: Int = 2 * 1024 * 1024) = if(webDavBase != null) WebDavSource.readSmall(path, limit) else SmbSource.readSmall(path, limit)
    fun cue(path: String) = if(webDavBase != null) SmbSource.cueSongs(path, WebDavSource.readSmall(path)) else SmbSource.cue(path)
    fun label() = webDavBase ?: "smb://${SmbSource.settings.host}/${SmbSource.settings.share}"
    fun key() = digest(label().trimEnd('/') + "|" + if(webDavBase != null) username else SmbSource.settings.username)
    fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
