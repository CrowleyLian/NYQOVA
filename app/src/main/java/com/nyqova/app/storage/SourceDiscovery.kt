package com.nyqova.app.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredSource(val url: String, val label: String)

/** Bounded discovery on the active local /24 only. Never sends usernames or passwords. */
class SourceDiscovery(context: Context, private val onFound: (DiscoveredSource) -> Unit, private val onDone: () -> Unit,
    private val durationMs: Long=20000,
    private val nsdProvider: (()->NsdManager?)? = null,
    private val addressProvider: ()->String? = {
        val cm=context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    }, private val probeOverride: ((String,Int)->DiscoveredSource?)?=null) : Closeable {
    companion object {
        // A broken framework lookup can ignore interruption forever. At most ONE daemon lane may
        // be stuck per process; subsequent searches still run their independent port probes.
        private val initializing=AtomicBoolean(false)
        private val nsdLane=java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(16),java.util.concurrent.ThreadFactory { task -> Thread(task,"nyqova-nsd").apply { isDaemon=true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        private fun nsdTask(task: ()->Unit): Boolean=runCatching { nsdLane.execute { runCatching(task) }; true }.getOrDefault(false)
    }
    private val app=context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var nsd: NsdManager?=null
    private val started=AtomicBoolean(false)
    private val nsdExpired=AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(16)
    private val closed = AtomicBoolean(false)
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(350, TimeUnit.MILLISECONDS).readTimeout(600, TimeUnit.MILLISECONDS)
        .callTimeout(1000, TimeUnit.MILLISECONDS).build()
    private fun found(source: DiscoveredSource) {
        if(!closed.get() && seen.add(source.url)) main.post { if(!closed.get()) onFound(source) }
    }
    fun start() {
        if(closed.get() || !started.compareAndSet(false,true)) return
        main.postDelayed({ finish() },durationMs.coerceIn(100,20000))
        main.postDelayed({ if(nsd==null) nsdExpired.set(true) },1500)
        if(initializing.compareAndSet(false,true)) {
            val owner=java.lang.ref.WeakReference(this)
            val appContext=app
            val lookup=nsdProvider ?: { appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }
            val cancelled=closed; val expired=nsdExpired
            if(!nsdTask {
                try {
                    val manager=lookup() ?: return@nsdTask
                    if(cancelled.get() || expired.get()) return@nsdTask
                    owner.get()?.beginNsd(manager)
                } finally { initializing.set(false) }
            }) initializing.set(false)
        }
        // ConnectivityManager lookup, socket connects and HTTP probes all stay off the UI/NSD lane.
        pool.execute {
            val address=runCatching(addressProvider).getOrNull()
            if(address!=null && isPrivate(address) && !closed.get()) {
                val prefix=address.substringBeforeLast('.')
                for(i in 1..254) {
                    if(closed.get()) break
                    runCatching { pool.execute {
                        if(!closed.get()) for(port in intArrayOf(445,8888,5005,5006,80,8080)) {
                            if(closed.get()) break
                            val host="$prefix.$i"
                            if(probeOverride!=null) { runCatching { probeOverride?.invoke(host,port) }.getOrNull()?.let(::found); continue }
                            val socket=Socket(); sockets+=socket
                            val open=try { if(closed.get()) false else { socket.connect(InetSocketAddress(host,port),140); true } } catch(_: Exception) { false }
                                finally { sockets-=socket; runCatching { socket.close() } }
                            if(open && !closed.get()) {
                                if(port==445) found(DiscoveredSource("smb://$host/${SmbSource.settings.share}","SMB · $host（共享名可修改）"))
                                else probeWebDav(host,port)
                            }
                        }
                    } }
                }
            }
        }
    }
    private fun beginNsd(manager: NsdManager) {
        nsd=manager
        listOf("_smb._tcp.","_webdav._tcp.","_webdavs._tcp.").forEach { type ->
            if(closed.get() || nsdExpired.get()) return@forEach
            val listener=object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String)=Unit
                override fun onDiscoveryStopped(type: String)=Unit
                override fun onStartDiscoveryFailed(type: String,code: Int)=Unit
                override fun onStopDiscoveryFailed(type: String,code: Int)=Unit
                override fun onServiceLost(info: NsdServiceInfo)=Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    if(!closed.get() && !nsdExpired.get()) nsdTask { if(!closed.get() && !nsdExpired.get()) { resolveQueue.add(info); resolveNext() } }
                }
            }
            listeners+=listener
            runCatching { manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener) }
        }
        if(closed.get() || nsdExpired.get()) stopNsdOnLane()
    }
    private fun finish() { if(closeOnce()) onDone() }
    private fun isPrivate(host: String): Boolean {
        val p = host.split('.').mapNotNull(String::toIntOrNull)
        return p.size == 4 && (p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31))
    }
    private fun probeWebDav(host: String, port: Int) {
        val url = "${if(port == 5006) "https" else "http"}://$host:$port/"
        runCatching {
            http.newCall(Request.Builder().url(url).method("OPTIONS", null).build()).execute().use { response ->
                if(response.header("DAV") != null || response.code == 401)
                    found(DiscoveredSource(url, "${if(response.header("DAV") != null) "WebDAV" else "WebDAV 候选，需认证"} · $host:$port"))
            }
        }
    }
    private fun resolveNext() {
        if(closed.get() || nsdExpired.get() || resolving || resolveQueue.isEmpty()) return
        resolving = true
        val info = resolveQueue.removeFirst()
        @Suppress("DEPRECATION")
        val manager=nsd ?: return
        runCatching { manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { nsdTask { resolving = false; resolveNext() } }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if(host != null) {
                    val authority = if(':' in host) "[$host]" else host
                    val smb = info.serviceType.contains("smb")
                    val protocol = if(smb) "smb" else if(info.serviceType.contains("webdavs")) "https" else "http"
                    val path = if(smb) "/${SmbSource.settings.share}" else info.attributes["path"]?.let { String(it, Charsets.UTF_8) } ?: "/"
                    found(DiscoveredSource("$protocol://$authority${if(smb) "" else ":${info.port}"}/${path.trim('/')}", "${info.serviceName} · $protocol · $host"))
                }
                nsdTask { resolving = false; resolveNext() }
            }
        }) }.onFailure { resolving=false; resolveNext() }
    }
    private fun stopNsdOnLane() {
        val manager=nsd
        listeners.forEach { listener -> runCatching { manager?.stopServiceDiscovery(listener) } }
        listeners.clear(); resolveQueue.clear()
    }
    private fun stopNsd() { if(nsd!=null) nsdTask { stopNsdOnLane() } }
    private fun closeOnce(): Boolean {
        if(!closed.compareAndSet(false,true)) return false
        main.removeCallbacksAndMessages(null)
        nsdExpired.set(true); stopNsd()
        pool.shutdownNow(); sockets.forEach { runCatching { it.close() } }; http.dispatcher.cancelAll()
        return true
    }
    override fun close() { closeOnce() }
}
