package com.nyqova.app.storage

import android.net.Uri
import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

object DavRequest {
    // Redirects are rejected so a NAS cannot forward credentials to a different destination.
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    val metadataClient=client.newBuilder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS)
        .callTimeout(5,TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    fun list(url: String, auth: String, base: String): List<RemoteEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).header("Authorization", auth).header("Depth", "1").method("PROPFIND", body).build()
        client.newCall(request).execute().use { response ->
            if(response.code != 207 && !response.isSuccessful) throw IOException("WebDAV HTTP ${response.code}${if(response.code == 401) "，请检查账号密码" else ""}")
            val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            val input = response.body?.byteStream() ?: throw IOException("WebDAV 未返回目录")
            parser.setInput(input, "UTF-8")
            val result = mutableListOf<RemoteEntry>()
            var href = ""; var name = ""; var size = 0L; var directory = false; var modified = ""
            val basePath = Uri.parse(base).path.orEmpty().trimEnd('/') + "/"
            val currentPath = Uri.parse(url).path.orEmpty().trimEnd('/')
            var event = parser.eventType
            while(event != XmlPullParser.END_DOCUMENT) {
                if(event == XmlPullParser.START_TAG) when(parser.name) {
                    "response" -> { href = ""; name = ""; size = 0; directory = false; modified = "" }
                    "href" -> href = parser.nextText()
                    "displayname" -> name = parser.nextText()
                    "getcontentlength" -> size = parser.nextText().toLongOrNull() ?: 0
                    "getlastmodified" -> modified = parser.nextText()
                    "collection" -> directory = true
                }
                if(event == XmlPullParser.END_TAG && parser.name == "response" && href.isNotEmpty()) {
                    val resolved = runCatching { Uri.parse(URI(url).resolve(href).toString()) }.getOrNull()
                    val decoded = resolved?.path.orEmpty().trimEnd('/')
                    if(resolved != null && WebDavSource.sameSource(resolved) && decoded != currentPath &&
                        decoded.startsWith(basePath) && decoded.substringBeforeLast('/', "") == currentPath) {
                        val relative = decoded.removePrefix(basePath)
                        if(relative.split('/').none { it == ".." || it == "." })
                            result += RemoteEntry(name.ifBlank { decoded.substringAfterLast('/') }, relative, directory, size, modified)
                    }
                }
                event = parser.next()
            }
            return result.distinctBy { it.path }
        }
    }
}
