package com.serhanensar.callbridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telecom.DisconnectCause
import android.util.Log
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Yanıtlayan cihazda (telefon) çalışır. Tablete bağlı kalır, gelen çağrıyı
 * tam ekran gösterir ve kullanıcının seçimini komut olarak geri yollar.
 *
 * HotspotTTL VPN'i bu paketi dışarıda bırakır (addDisallowedApplication); soket
 * ayrıca Wi-Fi ağına bağlanır.
 */
class BridgeClientService : Service() {

    /** Tabletten gelen son çağrı durumu. */
    data class Call(
        val state: String,
        val number: String?,
        val name: String?,
        val muted: Boolean,
        val speaker: Boolean,
        val test: Boolean,
        val since: Long,
        val outgoing: Boolean = false,
    ) {
        val title get() = name ?: number ?: "Bilinmeyen numara"
    }

    @Volatile private var running = false
    @Volatile private var line: Line? = null
    @Volatile private var hostAddress: InetAddress? = null
    @Volatile private var pinFailed = false
    @Volatile private var ringSilenced = false // bu çağrı için zil susturuldu
    private var pinger: ScheduledExecutorService? = null
    private lateinit var ringer: Ringer
    private lateinit var callAudio: CallAudio
    private var proximity: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Bridge.ensureChannels(this)
        ringer = Ringer(this)
        callAudio = CallAudio(this, network = { wifiNetwork() }, host = { hostAddress }, onRoute = ::updateProximity)
        @Suppress("DEPRECATION")
        proximity = getSystemService(PowerManager::class.java)
            .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "CallBridge:proximity")
        audio = callAudio
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopClient()
                return START_NOT_STICKY
            }
            ACTION_CMD -> {
                startClient()
                intent.getStringExtra(EXTRA_CMD)?.let { command(it) }
            }
            else -> startClient()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopClient()
        super.onDestroy()
    }

    private fun startClient() {
        if (running) return
        val notification = CallNotifier.status(this, "Bağlanıyor…", connected = false)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(CallNotifier.STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(CallNotifier.STATUS_ID, notification)
        }
        running = true
        instance = this
        try { PhoneAccounts.register(this) } catch (e: Exception) { Log.w(Bridge.TAG, "Arama hesabı kaydedilemedi: $e") }
        pinger = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ line?.send(Bridge.msg("ping")) }, PING_MS, PING_MS, TimeUnit.MILLISECONDS)
        }
        thread(name = "bridge-client", isDaemon = true) { connectLoop() }
    }

    private fun stopClient() {
        if (!running) return
        running = false
        pinger?.shutdownNow()
        pinger = null
        line?.close()
        line = null
        setCall(null)
        status = "Kapalı"
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun send(cmd: String, vararg extra: Pair<String, Any?>): Boolean {
        // Telefonun kendi zili hemen dursun; tabletin zili için komut ayrıca gider
        if (cmd == "silence" || cmd == "accept" || cmd == "end") {
            if (call?.state == Bridge.STATE_RINGING) ringSilenced = true
            ringer.stop()
        }
        val ok = line?.send(Bridge.msg("cmd", "cmd" to cmd, *extra)) == true
        if (!ok) lastResult = "Tablete bağlı değil"
        return ok
    }

    /**
     * Tabletin bu numarayı aramasını ister. Çağrı hemen giden arama olarak kurulur (çağrı ekranı
     * açılınca bulabilsin); tablet aramayı başlatamazsa geri alınır.
     */
    /** Sistemin çağrı ekranındaki giden arama (rehberden yapılan); null ise çağrı bizim ekranımızda. */
    @Volatile private var connection: BridgeConnection? = null

    private fun beginDial(number: String, name: String?, conn: BridgeConnection? = null): Boolean {
        if (line == null) {
            lastResult = "Tablete bağlı değil"
            return false
        }
        if (call != null) {
            lastResult = "Süren bir çağrı var"
            return false
        }
        lastResult = null
        connection = conn
        setCall(Call(Bridge.STATE_OFFHOOK, number, name, muted = false, speaker = false, test = false,
            since = System.currentTimeMillis(), outgoing = true))
        thread(isDaemon = true) {
            if (!send("dial", "number" to number, "name" to name)) setCall(null)
        }
        return true
    }

    // ---- bağlantı ----

    private fun connectLoop() {
        var delay = 1000L
        while (running) {
            val host = resolveHost()
            if (host == null) {
                setStatus("Tablet bulunamadı: hotspot'a bağlı mısın?")
            } else {
                if (!pinFailed) setStatus("Bağlanıyor: ${host.hostAddress}")
                try {
                    session(host)
                    delay = 1000L
                } catch (e: Exception) {
                    Log.w(Bridge.TAG, "Bağlantı koptu: $e")
                    line = null
                    if (!pinFailed) setStatus("Bağlantı yok: ${e.message ?: e.javaClass.simpleName}")
                }
                line = null
                setCall(null)
            }
            if (!running) break
            try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
            delay = (delay * 2).coerceAtMost(10_000L)
        }
    }

    private fun session(host: InetAddress) {
        val socket = wifiNetwork()?.socketFactory?.createSocket() ?: Socket()
        socket.use {
            it.tcpNoDelay = true
            it.connect(InetSocketAddress(host, Bridge.PORT), 5000)
            it.soTimeout = READ_TIMEOUT_MS
            val l = Line(it)
            val hello = l.read() ?: throw IllegalStateException("tablet yanıt vermedi")
            val nonce = hello.optString("nonce")
            l.send(Bridge.msg("auth", "mac" to Bridge.mac(Bridge.pin(this), nonce), "name" to Build.MODEL))
            val auth = l.read() ?: throw IllegalStateException("tablet bağlantıyı kapattı")
            if (auth.optString("t") != "auth_ok") {
                pinFailed = true
                setStatus("PIN hatalı: tabletteki PIN'i gir")
                return
            }
            pinFailed = false
            line = l
            hostAddress = host
            setStatus("Bağlı: ${host.hostAddress}")
            while (running) {
                val m = l.read() ?: break
                when (m.optString("t")) {
                    "state" -> onState(m)
                    "result" -> {
                        val ok = m.optBoolean("ok")
                        lastResult = if (ok) null else m.optString("error", "Komut başarısız")
                        if (m.optString("cmd") == "dial" && call?.outgoing == true) {
                            if (ok) {
                                connection?.connected()
                            } else {
                                val error = lastResult
                                connection?.finish(DisconnectCause.ERROR, error)
                                connection = null
                                setCall(null)
                                lastResult = error // setCall(null) siler; arama ekranı gösterebilsin
                            }
                        }
                    }
                    "audio" -> {
                        audioActive = m.optBoolean("active")
                        audioError = m.optString("error").ifEmpty { null }
                    }
                }
            }
        }
    }

    private fun onState(m: JSONObject) {
        val state = m.optString("state", Bridge.STATE_IDLE)
        if (state == Bridge.STATE_IDLE) {
            setCall(null)
            return
        }
        val prev = call
        val since = if (prev != null && prev.state == state) prev.since else System.currentTimeMillis()
        setCall(
            Call(
                state = state,
                number = m.optString("number").ifEmpty { null } ?: prev?.number,
                name = m.optString("name").ifEmpty { null } ?: prev?.name,
                muted = m.optBoolean("muted"),
                speaker = m.optBoolean("speaker"),
                test = m.optBoolean("test"),
                since = since,
                outgoing = m.optBoolean("outgoing"),
            )
        )
    }

    private fun setCall(new: Call?) {
        val prev = call
        call = new
        if (new?.state != Bridge.STATE_RINGING || prev?.state != Bridge.STATE_RINGING) ringSilenced = false
        if (new?.state != Bridge.STATE_RINGING) ringer.stop()
        if (new?.state == Bridge.STATE_OFFHOOK) callAudio.start(systemRouted = connection != null) else {
            callAudio.stop()
            updateProximity("—")
        }
        if (new == null) {
            connection?.finish(DisconnectCause.REMOTE)
            connection = null
        }
        if (prev?.state == Bridge.STATE_OFFHOOK && new?.state != Bridge.STATE_OFFHOOK && running) {
            showForeground(CallNotifier.status(this, status, connected = line != null), microphone = false)
        }
        when {
            new == null -> {
                CallNotifier.cancelIncoming(this)
                if (prev != null) refreshStatus()
            }
            new.state == Bridge.STATE_RINGING -> {
                // Numara ikinci mesajla gelebilir; zili baştan çaldırmadan güncelle
                val fresh = prev?.state != Bridge.STATE_RINGING
                val openScreen = fresh && BridgeActivity.visible
                if (fresh && !ringSilenced) ringer.start()
                CallNotifier.incoming(this, new, alert = fresh, quiet = openScreen || IncomingCallActivity.visible)
                if (openScreen) {
                    startActivity(Intent(this, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> {
                CallNotifier.cancelIncoming(this)
                showForeground(CallNotifier.ongoing(this, new), microphone = true)
            }
        }
        if (new == null) {
            lastResult = null
            audioActive = false
            audioError = null
        }
    }

    /** Ses ahizedeyken telefon kulağa yaklaşınca ekran kapansın (yanlışlıkla dokunmayı önler). */
    @Synchronized
    private fun updateProximity(route: String) {
        val lock = proximity ?: return
        val want = route == CallAudio.ROUTE_EARPIECE && call?.state == Bridge.STATE_OFFHOOK
        if (want && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
        if (!want && lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    /**
     * Görüşmede servis mikrofon türüne geçer (arka planda mikrofon için gerekli). Android bunu
     * yalnızca kullanıcı etkileşiminden sonra izin verir; olmazsa bildirim yine güncellenir.
     */
    private fun showForeground(n: android.app.Notification, microphone: Boolean) {
        if (Build.VERSION.SDK_INT < 30) {
            getSystemService(NotificationManager::class.java).notify(CallNotifier.STATUS_ID, n)
            return
        }
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            startForeground(CallNotifier.STATUS_ID, n, type)
        } catch (e: Exception) {
            Log.w(Bridge.TAG, "Mikrofon türüne geçilemedi: $e")
            getSystemService(NotificationManager::class.java).notify(CallNotifier.STATUS_ID, n)
        }
    }

    private fun setStatus(text: String) {
        status = text
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!running || call != null) return
        getSystemService(NotificationManager::class.java).notify(CallNotifier.STATUS_ID, CallNotifier.status(this, status, connected = line != null))
    }

    // ---- tableti bulma ----

    private fun wifiNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull {
            val caps = cm.getNetworkCapabilities(it) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /** Elle girilen adres, yoksa Wi-Fi ağ geçidi (hotspot'u açan tablet). */
    private fun resolveHost(): InetAddress? {
        Bridge.manualHost(this).takeIf { it.isNotBlank() }?.let {
            return try { InetAddress.getByName(it.trim()) } catch (_: Exception) { null }
        }
        return gateway(this)
    }

    companion object {
        private const val ACTION_STOP = "com.serhanensar.callbridge.CLIENT_STOP"
        private const val ACTION_CMD = "com.serhanensar.callbridge.CLIENT_CMD"
        private const val EXTRA_CMD = "cmd"
        private const val PING_MS = 20_000L
        private const val READ_TIMEOUT_MS = 45_000

        @Volatile var instance: BridgeClientService? = null
            private set
        @Volatile var call: Call? = null
            private set
        @Volatile var status = "Kapalı"
            private set
        @Volatile var lastResult: String? = null
        @Volatile var audio: CallAudio? = null
            private set

        /** Tablet çağrının sesini bu telefona aktarıyor mu; aktaramıyorsa nedeni. */
        @Volatile var audioActive = false
            private set
        @Volatile var audioError: String? = null
            private set

        val isRunning get() = instance != null
        val isConnected get() = instance?.line != null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BridgeClientService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, BridgeClientService::class.java).setAction(ACTION_STOP))
        }

        fun cmdIntent(ctx: Context, cmd: String): Intent =
            Intent(ctx, BridgeClientService::class.java).setAction(ACTION_CMD).putExtra(EXTRA_CMD, cmd)

        fun command(cmd: String) {
            val s = instance
            if (s == null) {
                lastResult = "Köprü kapalı"
                return
            }
            thread(isDaemon = true) { s.send(cmd) }
        }

        /** Rehberden/sistem tuş takımından gelen arama; çağrı sistemin ekranında sürer. */
        fun dialFromSystem(number: String, conn: BridgeConnection): Boolean {
            val s = instance
            if (s == null) {
                lastResult = "CallBridge köprüsü kapalı"
                return false
            }
            return s.beginDial(number, null, conn)
        }

        /** Arama başladıysa true; değilse nedeni lastResult'ta. */
        fun dial(number: String, name: String?): Boolean {
            val s = instance
            if (s == null) {
                lastResult = "Köprü kapalı"
                return false
            }
            return s.beginDial(number, name)
        }

        fun gateway(ctx: Context): InetAddress? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val gw = cm.getLinkProperties(n)?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway
                if (gw != null && !gw.isAnyLocalAddress) return gw
            }
            return null
        }
    }
}
