package com.nyqova.app.storage

import android.net.Uri
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.nyqova.core.CueParser
import com.nyqova.core.MusicText
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

data class SourceSettings(val host: String = "", val share: String = "",
                          val username: String = "", val password: String = "", val domain: String = "")
data class RemoteEntry(val name: String, val path: String, val directory: Boolean, val size: Long, val modified: String = "")
data class Song(val title: String, val path: String, val artist: String = "", val album: String = "",
                val startUs: Long = 0, val endUs: Long? = null, val durationMs: Long = 0,
                val artworkPath: String? = null, val lyricsPath: String? = null, val lyricsOffsetMs: Long = 0, val libraryId: String? = null)

object SmbSource {
    @Volatile var settings = SourceSettings()
    private val audioExtensions = setOf("wav", "flac", "mp3", "m4a", "aac", "ogg", "opus", "ape")
    fun isAudio(path: String) = path.substringAfterLast('.').lowercase() in audioExtensions
    fun isCue(path: String) = path.endsWith(".cue", true)
    fun uri(path: String): Uri = Uri.Builder().scheme("smb").authority(settings.host)
        .appendPath(settings.share).apply { path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) } }.build()

    class Handle(val config: SourceSettings) : Closeable {
        private val client = SMBClient(SmbConfig.builder().withTimeout(12, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS).build())
        private var connection: Connection? = null
        lateinit var share: DiskShare
            private set
        init {
            try {
                connection = client.connect(config.host)
                val auth = if (config.username.isBlank()) AuthenticationContext.guest()
                    else AuthenticationContext(config.username, config.password.toCharArray(), config.domain)
                share = connection!!.authenticate(auth).connectShare(config.share) as DiskShare
            } catch (e: Exception) { close(); throw e }
        }
        fun open(path: String) = share.openFile(path.replace('/', '\\'), setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
        override fun close() {
            if (::share.isInitialized) runCatching { share.close() }
            runCatching { connection?.close() }
            runCatching { client.close() }
        }
    }

    fun list(path: String, all: Boolean = false): List<RemoteEntry> = Handle(settings).use { h ->
        h.share.list(path.replace('/', '\\')).filter { it.fileName != "." && it.fileName != ".." }
            .map { RemoteEntry(it.fileName, join(path, it.fileName), it.fileAttributes and 16L != 0L, it.endOfFile, it.lastWriteTime.toString()) }
            .filter { all || it.directory || isAudio(it.path) || isCue(it.path) }
            .sortedWith(compareBy<RemoteEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    fun readSmall(path: String, limit: Int = 2 * 1024 * 1024): ByteArray = Handle(settings).use { h ->
        h.open(path).use { f ->
            require(f.fileInformation.standardInformation.endOfFile <= limit) { "附属文件过大" }
            f.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                while(true) {
                    val n = input.read(bytes); if(n < 0) break
                    require(out.size() + n <= limit) { "附属文件过大" }; out.write(bytes, 0, n)
                }
                out.toByteArray()
            }
        }
    }

    fun cue(path: String): List<Song> {
        val bytes = readSmall(path)
        return cueSongs(path, bytes)
    }
    fun cueSongs(path: String, bytes: ByteArray): List<Song> {
        val text = MusicText.decode(bytes)
        val parent = path.substringBeforeLast('/', "")
        val result = CueParser.parse(text).map { cue ->
            val reference = cue.file.replace('\\', '/')
            require(!reference.startsWith('/') && ':' !in reference && reference.split('/').none { it == ".." }) {
                "CUE 引用必须位于当前共享目录内"
            }
            Song(cue.title, join(parent, reference), cue.artist, cue.album, cue.startUs, cue.endUs)
        }
        require(result.isNotEmpty()) { "没有可用的 CUE 音轨，请检查 INDEX 01" }
        return result
    }
    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}
