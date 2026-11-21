package com.nyqova.app.storage

import android.net.Uri
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object WebDavSource {
    fun authorization(): String = "Basic " + Base64.encodeToString("${MusicSource.username}:${MusicSource.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    fun sameSource(uri: Uri): Boolean {
        val base = MusicSource.webDavBase?.let(Uri::parse) ?: return false
        fun port(u: Uri) = if(u.port != -1) u.port else if(u.scheme == "https") 443 else 80
        return uri.scheme == base.scheme && uri.host == base.host && port(uri) == port(base) &&
            (uri.path.orEmpty() + "/").startsWith(base.path.orEmpty().trimEnd('/') + "/")
    }
    private fun connection(path: String, method: String): HttpURLConnection {
        val uri = MusicSource.uri(path)
        require(sameSource(uri))
        return (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10000; readTimeout = 15000; instanceFollowRedirects = false
            setRequestProperty("Authorization", authorization())
        }
    }
    fun list(path: String, all: Boolean = false): List<RemoteEntry> {
        // HttpURLConnection rejects PROPFIND, so use the platform's supported HTTP client for DAV.
        val uri = MusicSource.uri(path).toString().trimEnd('/') + "/"
        return DavRequest.list(uri, authorization(), MusicSource.webDavBase!!)
            .filter { all || it.directory || SmbSource.isAudio(it.path) || SmbSource.isCue(it.path) }
            .sortedWith(compareBy<RemoteEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    fun readSmall(path: String, limit: Int = 2 * 1024 * 1024): ByteArray {
        val c = connection(path, "GET")
        try {
            if(c.responseCode !in 200..299) throw IOException("WebDAV HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while(true) {
                    val n = input.read(bytes); if(n < 0) break
                    if(output.size() + n > limit) throw IOException("附属文件过大")
                    output.write(bytes, 0, n)
                }
                output.toByteArray()
            }
        } finally { c.disconnect() }
    }
}
