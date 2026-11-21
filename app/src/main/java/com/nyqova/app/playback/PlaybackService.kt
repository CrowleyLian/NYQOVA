package com.nyqova.app.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nyqova.app.storage.MusicDataSource
import com.nyqova.app.storage.ConnectionStore
import com.nyqova.app.storage.MusicSource
import com.nyqova.app.settings.TvPreferences
import com.nyqova.app.library.LibraryDatabase
import com.nyqova.app.library.PlayHistory
import androidx.media3.common.Player
import androidx.media3.common.Timeline

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val storage = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var revision = ""
    private var restoring = false
    private var remembering = true
    private var closing=false
    private var lastCheckpoint=""
    private val preferenceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key=="remember") main.post { if(!closing) {
            remembering=TvPreferences(this).enabled("remember")
            if(remembering) persistQueue() else storage.execute { PlaybackMemory.clear(this) }
        } }
    }
    private fun persistQueue() {
        val player=session?.player ?: return
        if(player.mediaItemCount==0) return
        revision=java.util.UUID.randomUUID().toString()
        val id=revision; val source=MusicSource.key(); val items=(0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        storage.execute { runCatching { PlaybackMemory.saveQueue(this,id,source,items) } }; savePosition()
    }
    private val checkpoint = object : Runnable {
        override fun run() {
            val enabled = TvPreferences(this@PlaybackService).enabled("remember")
            if(enabled && !remembering) session?.player?.let { player ->
                if(player.mediaItemCount>0) {
                    revision=java.util.UUID.randomUUID().toString()
                    val id=revision; val source=MusicSource.key(); val items=(0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    storage.execute { runCatching { PlaybackMemory.saveQueue(this@PlaybackService,id,source,items) } }
                }
            }
            remembering=enabled; savePosition(); main.postDelayed(this, 2000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        remembering = TvPreferences(this).enabled("remember")
        getSharedPreferences("tv_preferences",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
        if(!MusicSource.initialized) runCatching { ConnectionStore.load(this)?.activate() }
        val sources = DefaultDataSource.Factory(this, MusicDataSource.Factory())
        val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val player = ExoPlayer.Builder(this,renderers).setMediaSourceFactory(DefaultMediaSourceFactory(sources,io.github.eugenedibtsev.media3.ape.ApeSupport.extractorsFactory()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        session = MediaSession.Builder(this, player).build()
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if(closing || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || restoring) return
                if(player.mediaItemCount == 0) { revision = ""; storage.execute { PlaybackMemory.clear(this@PlaybackService) }; return }
                revision = java.util.UUID.randomUUID().toString()
                val id = revision; val source = MusicSource.key()
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                storage.execute { runCatching { PlaybackMemory.saveQueue(this@PlaybackService, id, source, items) } }
                savePosition()
            }
            override fun onEvents(player: Player, events: Player.Events) { if(!closing) savePosition() }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                if(closing) return
                val source = item?.mediaMetadata?.extras?.getString("source_key") ?: return
                val track = item.mediaMetadata.extras?.getString("track_id") ?: return
                storage.execute { runCatching { LibraryDatabase.get(this@PlaybackService).collections().history(PlayHistory(source, track, System.currentTimeMillis())) } }
            }
        })
        main.post(checkpoint)
        storage.execute {
            val saved = runCatching { PlaybackMemory.load(this) }.getOrNull()
            main.post {
                if(session == null || player.mediaItemCount != 0 || saved == null || saved.source != MusicSource.key()) return@post
                restoring = true; revision = saved.revision
                player.setMediaItems(saved.items, saved.index, saved.position)
                player.repeatMode = saved.repeat; player.shuffleModeEnabled = saved.shuffle
                player.prepare(); player.playWhenReady = TvPreferences(this).enabled("auto_resume")
                restoring = false
            }
        }
    }
    private fun savePosition() {
        val player = session?.player ?: return
        if(player.mediaItemCount == 0 || revision.isEmpty() || restoring) return
        val id = revision; val index = player.currentMediaItemIndex; val position = player.currentPosition
        val repeat = player.repeatMode; val shuffle = player.shuffleModeEnabled
        val checkpoint="$id:$index:$position:$repeat:$shuffle"
        if(lastCheckpoint==checkpoint) return
        lastCheckpoint=checkpoint
        storage.execute { runCatching { PlaybackMemory.savePosition(this, id, index, position, repeat, shuffle) } }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session?.takeIf { controllerInfo.uid == applicationInfo.uid }
    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    override fun onDestroy() {
        savePosition(); closing=true; main.removeCallbacks(checkpoint)
        getSharedPreferences("tv_preferences",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener)
        session?.let { it.player.release(); it.release() }; session = null
        storage.shutdown()
        super.onDestroy()
    }
}
