package com.serhanensar.callbridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
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
 * Yanıtlayan cihazda (telefon) çalışır. Tablete bağlı kalır; gelen çağrıyı Samsung'un çağrı
 * ekranında (CallBridge arama hesabı üzerinden) ya da kendi ekranında gösterir, seçimi komut
 * olarak tablete yollar ve ses bu telefondaysa CallAudio ile çalar/kaydeder.
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
        val answeredBy: String? = null, // çağrıyı açan cihaz ya da "tablet"
        val audioTo: String? = null, // ses hangi cihazda; null: tablette
    ) {
        val title get() = name ?: number ?: "Bilinmeyen numara"
        val answeredHere get() = answeredBy == ME
    }

    /** Tabletin arama kaydından bir satır. */
    data class HistoryEntry(val number: String, val name: String?, val type: String, val date: Long, val duration: Long) {
        val title get() = name ?: number
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
    private val main = Handler(Looper.getMainLooper())

    /** Sistemin çağrı ekranındaki çağrı (gelen ya da rehberden giden); null ise çağrı bizim ekranımızda. */
    @Volatile private var connection: BridgeConnection? = null
    /** Gelen çağrı sisteme bildirildi, yanıt bekleniyor (gelmezse kendi zilimize dönülür). */
    @Volatile private var incomingPending = false
    /** Bu görüşmede ses buradan bırakıldı ("Tablete bırak" ya da başka cihaza alındı). */
    @Volatile private var audioReleased = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Bridge.initLog(this)
        Bridge.ensureChannels(this)
        ringer = Ringer(this)
        callAudio = CallAudio(this, network = { wifiNetwork() }, host = { hostAddress }, onRoute = ::onRoute)
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
        try { PhoneAccounts.register(this) } catch (e: Exception) { Bridge.warn("Arama hesabı kaydedilemedi: $e") }
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
        if (cmd == "audio_on") audioReleased = false
        if (cmd == "audio_off") audioReleased = true
        val ok = line?.send(Bridge.msg("cmd", "cmd" to cmd, *extra)) == true
        if (!ok) lastResult = "Tablete bağlı değil"
        return ok
    }

    /**
     * Tabletin bu numarayı aramasını ister. Çağrı hemen giden arama olarak kurulur (çağrı ekranı
     * açılınca bulabilsin); tablet aramayı başlatamazsa geri alınır.
     */
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
            since = System.currentTimeMillis(), outgoing = true, answeredBy = ME))
        thread(isDaemon = true) {
            if (!send("dial", "number" to number, "name" to name)) setCall(null)
        }
        return true
    }

    // ---- sistem çağrı ekranı (Telecom) ----

    /** Gelen çağrıyı CallBridge arama hesabı üzerinden sisteme bildirir: Samsung'un ekranında çalar. */
    private fun offerToSystem(c: Call) {
        if (!PhoneAccounts.isEnabled(this)) {
            Bridge.log("Arama hesabı kapalı; gelen çağrı CallBridge ekranında")
            ringHere(c, fresh = true)
            return
        }
        Bridge.log("Gelen çağrı sisteme veriliyor")
        incomingPending = true
        try {
            getSystemService(TelecomManager::class.java).addNewIncomingCall(
                PhoneAccounts.handle(this),
                Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", c.number ?: "", null))
                },
            )
        } catch (e: Exception) {
            Bridge.warn("Gelen çağrı sisteme verilemedi: $e")
            incomingPending = false
            ringHere(c, fresh = true)
            return
        }
        // Sistem yanıt vermezse kendi zilimize dön
        main.postDelayed({
            if (incomingPending && connection == null && call?.state == Bridge.STATE_RINGING) {
                Bridge.warn("Sistem gelen çağrıyı açmadı; CallBridge ekranında çalınıyor")
                incomingPending = false
                call?.let { ringHere(it, fresh = true) }
            }
        }, 1500)
    }

    /** ConnectionService gelen çağrı bağlantısını kurdu. */
    private fun attachIncoming(conn: BridgeConnection) {
        incomingPending = false
        val c = call
        if (c == null || c.state != Bridge.STATE_RINGING) {
            conn.finish(DisconnectCause.MISSED)
            return
        }
        connection = conn
        conn.update(c)
        Bridge.log("Gelen çağrı sistem ekranında")
    }

    private fun incomingFailed() {
        incomingPending = false
        Bridge.warn("Sistem gelen çağrıyı reddetti; CallBridge ekranında çalınıyor")
        call?.takeIf { it.state == Bridge.STATE_RINGING }?.let { ringHere(it, fresh = true) }
    }

    /** Sistem ekranı olmadan: kendi zilimiz ve tam ekran bildirimimiz. */
    private fun ringHere(c: Call, fresh: Boolean) {
        val openScreen = fresh && BridgeActivity.visible
        if (fresh && !ringSilenced) ringer.start()
        CallNotifier.incoming(this, c, alert = fresh, quiet = openScreen || IncomingCallActivity.visible)
        if (openScreen) {
            startActivity(Intent(this, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
                    Bridge.warn("Bağlantı koptu: $e")
                    line = null
                    if (!pinFailed) setStatus("Bağlantı yok: ${e.message ?: e.javaClass.simpleName}")
                }
                line = null
                onLinkLost()
            }
            if (!running) break
            // Görüşme sürerken kısa kopmalar çağrıyı bitirmesin: hemen yeniden bağlan, 10 sn bekle
            val inCall = call?.state == Bridge.STATE_OFFHOOK
            if (inCall && lostAt != 0L && System.currentTimeMillis() - lostAt > LINK_GRACE_MS) {
                Bridge.warn("Tablete ${LINK_GRACE_MS / 1000} sn bağlanılamadı, çağrı kapatıldı")
                setCall(null)
            }
            try { Thread.sleep(if (inCall) 500 else delay) } catch (_: InterruptedException) { break }
            delay = (delay * 2).coerceAtMost(10_000L)
        }
    }

    /** Görüşme sürmüyorsa çağrıyı bırak; sürüyorsa yeniden bağlanınca tabletin durumu belirlesin. */
    @Volatile private var lostAt = 0L

    private fun onLinkLost() {
        if (call?.state == Bridge.STATE_OFFHOOK) {
            if (lostAt == 0L) lostAt = System.currentTimeMillis()
            Bridge.warn("Görüşme sürerken tablet bağlantısı koptu; yeniden bağlanılıyor")
        } else {
            setCall(null)
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
            l.send(Bridge.msg("auth", "mac" to Bridge.mac(Bridge.pin(this), nonce), "name" to ME, "kind" to "phone"))
            val auth = l.read() ?: throw IllegalStateException("tablet bağlantıyı kapattı")
            if (auth.optString("t") != "auth_ok") {
                pinFailed = true
                setStatus("PIN hatalı: tabletteki PIN'i gir")
                return
            }
            pinFailed = false
            line = l
            hostAddress = host
            lostAt = 0L // tablet bağlanınca ilk mesajında güncel çağrı durumunu yollar
            setStatus("Bağlı: ${host.hostAddress}")
            l.send(Bridge.msg("cmd", "cmd" to "history"))
            while (running) {
                val m = l.read() ?: run {
                    Bridge.warn("Tablet bağlantıyı kapattı")
                    null
                } ?: break
                when (m.optString("t")) {
                    "state" -> onState(m)
                    "result" -> onResult(m)
                    "audio" -> {
                        val active = m.optBoolean("active")
                        if (audioActive && !active && call?.state == Bridge.STATE_OFFHOOK) audioReleased = true
                        audioActive = active
                        audioError = m.optString("error").ifEmpty { null }
                        call?.let { c -> main.post { syncCall(c, c) } }
                    }
                    "history" -> onHistory(m)
                }
            }
        }
    }

    private fun onResult(m: JSONObject) {
        val ok = m.optBoolean("ok")
        if (m.optString("cmd") == "history") return
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

    private fun onHistory(m: JSONObject) {
        val arr = m.optJSONArray("calls") ?: return
        history = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryEntry(o.optString("number"), o.optString("name").ifEmpty { null }, o.optString("type"), o.optLong("date"), o.optLong("duration"))
        }
        historyVersion++
    }

    private fun onState(m: JSONObject) {
        if (m.has("relay")) relayReady = m.optBoolean("relay")
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
                answeredBy = m.optString("answered_by").ifEmpty { null } ?: prev?.answeredBy?.takeIf { state == prev.state },
                audioTo = m.optString("audio_to").ifEmpty { null },
            )
        )
    }

    private fun setCall(new: Call?) {
        val prev = call
        call = new
        if (new?.state != Bridge.STATE_RINGING || prev?.state != Bridge.STATE_RINGING) ringSilenced = false
        if (new?.state != Bridge.STATE_RINGING) ringer.stop()
        if (new == null) {
            // Hiç açılmadan biten gelen çağrı cevapsızdır
            connection?.finish(if (prev?.state == Bridge.STATE_RINGING) DisconnectCause.MISSED else DisconnectCause.REMOTE)
            connection = null
            incomingPending = false
            audioReleased = false
            lastResult = null
            audioActive = false
            audioError = null
            callAudio.stop()
            updateProximity()
            CallNotifier.cancelIncoming(this)
            if (prev != null) {
                if (running) showForeground(CallNotifier.status(this, status, connected = line != null), microphone = false)
                thread(isDaemon = true) { line?.send(Bridge.msg("cmd", "cmd" to "history")) } // yeni arama kayda düştü
            }
            return
        }
        main.post { syncCall(prev, new) }
    }

    /** Çağrı ya da ses durumu değişince: sistem ekranı, ses ve bildirimler buna uyar. Ana iş parçacığında. */
    private fun syncCall(prev: Call?, c: Call) {
        if (call !== c && call?.state != c.state) return // bu arada yenisi geldi
        when (c.state) {
            Bridge.STATE_RINGING -> {
                val fresh = prev?.state != Bridge.STATE_RINGING
                val conn = connection
                when {
                    conn != null -> conn.update(c)
                    fresh -> offerToSystem(c)
                    !incomingPending -> ringHere(c, fresh = false) // numara sonradan geldi
                }
            }
            Bridge.STATE_OFFHOOK -> {
                CallNotifier.cancelIncoming(this)
                ringer.stop()
                val here = wantsAudio(c)
                val conn = connection
                if (conn != null) {
                    when {
                        here -> conn.connected()
                        // Başka cihazda açıldı ya da ses başka yere alındı: sistem ekranını komut göndermeden kapat
                        c.answeredBy != null && !c.answeredHere && prev?.state == Bridge.STATE_RINGING ->
                            conn.finish(DisconnectCause.ANSWERED_ELSEWHERE)
                        else -> conn.finish(DisconnectCause.OTHER, "Ses ${placeOf(c)} sürüyor")
                    }
                    if (!here) connection = null
                }
                if (here) callAudio.start(systemRouted = connection != null) else callAudio.stop()
                updateProximity()
                showForeground(
                    when {
                        !here -> CallNotifier.elsewhere(this, c, placeOf(c))
                        connection != null -> CallNotifier.relaying(this, c, callAudio.route)
                        else -> CallNotifier.ongoing(this, c)
                    },
                    microphone = here,
                )
            }
        }
    }

    /** Ses bu telefonda mı: tablet aktarıyorsa ya da çağrıyı biz açtık/aradık ve bırakmadık. */
    private fun wantsAudio(c: Call): Boolean {
        if (c.state != Bridge.STATE_OFFHOOK) return false
        if (c.audioTo != null) return c.audioTo == ME
        if (audioActive) return true
        return c.answeredHere && !audioReleased && audioError == null
    }

    private fun placeOf(c: Call) = when (c.audioTo ?: c.answeredBy) {
        null, CallHostService.TABLET -> "tablette"
        else -> "${c.audioTo ?: c.answeredBy} cihazında"
    }

    private fun onRoute(route: String) {
        updateProximity()
        val c = call ?: return
        if (connection != null && c.state == Bridge.STATE_OFFHOOK && wantsAudio(c)) {
            getSystemService(NotificationManager::class.java).notify(CallNotifier.STATUS_ID, CallNotifier.relaying(this, c, route))
        }
    }

    /** Ses ahizedeyken telefon kulağa yaklaşınca ekran kapansın (yanlışlıkla dokunmayı önler). */
    @Synchronized
    private fun updateProximity() {
        val lock = proximity ?: return
        val want = callAudio.isRunning && callAudio.route == CallAudio.ROUTE_EARPIECE
        if (want && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
        if (!want && lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    /**
     * Ses buradayken servis mikrofon türüne geçer (arka planda mikrofon için gerekli). Android bunu
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
            Bridge.warn("Mikrofon türüne geçilemedi: $e")
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
        private const val LINK_GRACE_MS = 10_000L

        /** Bu cihazın tabletteki adı (answered_by / audio_to ile karşılaştırılır). */
        val ME: String = Build.MODEL

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

        /** Tabletteki ses aktarıcısı çalışıyor mu; null: tablet henüz bildirmedi. */
        @Volatile var relayReady: Boolean? = null
            private set

        /** Tabletin son aramaları; her güncellemede historyVersion artar. */
        @Volatile var history: List<HistoryEntry> = emptyList()
            private set
        @Volatile var historyVersion = 0
            private set

        val isRunning get() = instance != null
        val isConnected get() = instance?.line != null

        /** Ses şu an bu telefonda mı (çağrı ekranı için). */
        val audioHere get() = instance?.let { s -> call?.let { c -> s.wantsAudio(c) } } == true

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

        /** BridgeConnectionService'ten: sistem gelen çağrı bağlantısını kurdu ya da kuramadı. */
        fun incomingConnection(conn: BridgeConnection?) {
            val s = instance
            if (s == null) {
                conn?.finish(DisconnectCause.MISSED)
                return
            }
            if (conn != null) s.attachIncoming(conn) else s.incomingFailed()
        }

        /** Arama kaydını yeniden ister (arama ekranı açılınca). */
        fun refreshHistory() {
            val s = instance ?: return
            thread(isDaemon = true) { s.line?.send(Bridge.msg("cmd", "cmd" to "history")) }
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
