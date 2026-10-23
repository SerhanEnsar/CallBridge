package com.serhanensar.callbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * SIM'li cihazda (tablet) çalışır. Çağrı durumunu dinler, bağlı client'lara iletir
 * ve gelen komutları TelecomManager / AudioManager üzerinden uygular.
 *
 * Root olmadan çağrının sesine erişilemez: ses tablete bağlı Bluetooth kulaklıktan
 * ya da tabletin kendisinden gelir. Bu servis yalnızca çağrıyı uzaktan yönetir.
 */
class CallHostService : Service() {

    private class Peer(val line: Line, val name: String)

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val peers = CopyOnWriteArrayList<Peer>()

    private lateinit var telecom: TelecomManager
    private lateinit var audio: AudioManager

    // Çağrı durumu; `this` ile korunur.
    private var state = Bridge.STATE_IDLE
    private var number: String? = null
    private var name: String? = null
    private var test = false
    private var speaker = false
    private var outgoing = false // telefondan istenen giden arama
    private var ringMutedByUs = false

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extra = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val incoming = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val newState = when (extra) {
                TelephonyManager.EXTRA_STATE_RINGING -> Bridge.STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> Bridge.STATE_OFFHOOK
                else -> Bridge.STATE_IDLE
            }
            onCallState(newState, incoming)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        telecom = getSystemService(TelecomManager::class.java)
        audio = getSystemService(AudioManager::class.java)
        Bridge.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHost()
            return START_NOT_STICKY
        }
        startHost()
        return START_STICKY
    }

    override fun onDestroy() {
        stopHost()
        super.onDestroy()
    }

    private fun startHost() {
        if (running) return
        val notification = statusNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Bridge.PORT))
            }
        } catch (e: Exception) {
            Log.e(Bridge.TAG, "Port açılamadı", e)
            lastError = "Port ${Bridge.PORT} açılamadı"
            stopSelf()
            return
        }
        server = ss
        running = true
        lastError = null
        instance = this

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), RECEIVER_EXPORTED)
        } else {
            registerReceiver(phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        }
        thread(name = "bridge-accept", isDaemon = true) { acceptLoop(ss) }
        thread(name = "relay-check", isDaemon = true) {
            while (running) {
                relayReady = relayRequest(Bridge.msg("status")) != null
                Thread.sleep(10_000)
            }
        }
    }

    private fun stopHost() {
        if (!running) return
        running = false
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
        peers.forEach { it.line.close() }
        peers.clear()
        restoreRinger()
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- ağ ----

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (_: Exception) { break }
            thread(name = "bridge-peer", isDaemon = true) {
                try {
                    socket.tcpNoDelay = true
                    socket.soTimeout = READ_TIMEOUT_MS
                    serve(Line(socket))
                } catch (_: Exception) {
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun serve(line: Line) {
        val nonce = Bridge.nonce()
        line.send(Bridge.msg("hello", "nonce" to nonce, "v" to 1))
        val auth = line.read() ?: return
        val expected = Bridge.mac(Bridge.pin(this), nonce)
        if (auth.optString("t") != "auth" || !Bridge.macEquals(auth.optString("mac"), expected)) {
            Thread.sleep(1000) // PIN denemesini yavaşlat
            line.send(Bridge.msg("auth_fail"))
            return
        }
        val peer = Peer(line, auth.optString("name", "?"))
        line.send(Bridge.msg("auth_ok"))
        line.send(stateMessage())
        peers.add(peer)
        refreshNotification()
        Log.i(Bridge.TAG, "Bağlandı: ${peer.name} ${line.socket.inetAddress.hostAddress}")
        try {
            while (running) {
                val m = try { line.read() } catch (_: SocketTimeoutException) { null } ?: break
                when (m.optString("t")) {
                    "ping" -> line.send(Bridge.msg("pong"))
                    "cmd" -> {
                        val cmd = m.optString("cmd")
                        val error = execute(cmd, peer, m)
                        Log.i(Bridge.TAG, "Komut $cmd: ${error ?: "tamam"}")
                        line.send(Bridge.msg("result", "cmd" to cmd, "ok" to (error == null), "error" to error))
                    }
                }
            }
        } finally {
            peers.remove(peer)
            refreshNotification()
            Log.i(Bridge.TAG, "Ayrıldı: ${peer.name}")
        }
    }

    private fun broadcast() {
        val m = stateMessage()
        peers.forEach { it.line.send(m) }
    }

    private fun stateMessage(): JSONObject = synchronized(this) {
        Bridge.msg(
            "state",
            "state" to state,
            "number" to number,
            "name" to name,
            "muted" to (state == Bridge.STATE_OFFHOOK && audio.isMicrophoneMute),
            "speaker" to speaker,
            "test" to test,
            "outgoing" to outgoing,
        )
    }

    // ---- çağrı durumu ----

    private fun onCallState(newState: String, incoming: String?) {
        var silenceNow = false
        synchronized(this) {
            if (test) { test = false; number = null; name = null }
            val changed = newState != state
            if (changed && newState == Bridge.STATE_RINGING) { number = null; name = null }
            // READ_CALL_LOG verilmişse numara ikinci bir yayında gelir
            if (!incoming.isNullOrEmpty() && incoming != number) {
                number = incoming
                name = contactName(incoming)
            }
            state = newState
            if (changed && newState == Bridge.STATE_RINGING) silenceNow = Bridge.autoSilence(this) && peers.isNotEmpty()
            if (newState == Bridge.STATE_IDLE) {
                number = null
                name = null
                speaker = false
                outgoing = false
            }
        }
        if (silenceNow) silenceRinger()
        if (newState == Bridge.STATE_IDLE) {
            restoreRinger()
            try { audio.isMicrophoneMute = false } catch (_: Exception) {}
            if (audioPeer != null) {
                audioPeer = null
                thread(isDaemon = true) { relayRequest(Bridge.msg("stop")) }
            }
        }
        broadcast()
        // Telefondan yanıtlandıysa ses görüşme başlayınca telefona aktarılır
        if (newState == Bridge.STATE_OFFHOOK) audioPeer?.let { p -> thread(isDaemon = true) { startRelay(p) } }
    }

    // ---- ses aktarımı (shell yetkili RelayDaemon üzerinden) ----

    /** Sesin aktarıldığı telefon; null ise ses tablette kalır. */
    @Volatile private var audioPeer: Peer? = null

    private fun relayRequest(msg: JSONObject): JSONObject? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", Bridge.RELAY_PORT), 1000)
            s.soTimeout = 5000
            val l = Line(s)
            l.send(msg)
            l.read()
        }
    } catch (_: Exception) {
        null
    }

    private fun startRelay(p: Peer) {
        val reply = relayRequest(Bridge.msg("start", "ip" to p.line.socket.inetAddress.hostAddress))
        val error = when {
            reply == null -> "Tablette ses aktarıcısı çalışmıyor; ses tablette kaldı"
            reply.optBoolean("active") -> null
            else -> reply.optString("error", "Ses aktarılamadı")
        }
        relayReady = reply != null
        Log.i(Bridge.TAG, "Ses aktarımı → ${p.name}: ${error ?: "başladı"}")
        p.line.send(Bridge.msg("audio", "active" to (error == null), "error" to error))
    }

    private fun stopRelay(p: Peer) {
        audioPeer = null
        relayRequest(Bridge.msg("stop"))
        p.line.send(Bridge.msg("audio", "active" to false))
    }

    private fun contactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- komutlar ----

    /** Komutu uygular; hata varsa açıklamasını döndürür. */
    private fun execute(cmd: String, peer: Peer, m: JSONObject): String? {
        if (cmd == "test") return startTest()
        if (synchronized(this) { test }) return executeTest(cmd)
        return try {
            when (cmd) {
                "accept" -> {
                    if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return "Tablette çağrı yanıtlama izni yok"
                    audioPeer = peer // görüşme başlayınca ses bu telefona gelir
                    telecom.acceptRingingCall()
                    null
                }
                "dial" -> {
                    if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                        return "Tablette arama izni yok"
                    }
                    val n = m.optString("number").filter { it.isDigit() || it in "+*#" }
                    if (n.isEmpty()) return "Numara boş"
                    synchronized(this) {
                        if (state != Bridge.STATE_IDLE) return "Tablette süren bir çağrı var"
                        number = n
                        name = contactName(n) ?: m.optString("name").ifEmpty { null }
                        outgoing = true
                    }
                    audioPeer = peer // görüşme başlayınca ses bu telefona gelir
                    telecom.placeCall(Uri.fromParts("tel", n, null), Bundle())
                    null
                }
                "audio_on" -> {
                    audioPeer = peer
                    if (synchronized(this) { state } == Bridge.STATE_OFFHOOK) startRelay(peer)
                    null
                }
                "audio_off" -> {
                    stopRelay(peer)
                    null
                }
                "end" -> {
                    if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return "Tablette çağrı yanıtlama izni yok"
                    if (Build.VERSION.SDK_INT < 28) return "Android 9 ve üstü gerekli"
                    @Suppress("DEPRECATION")
                    if (telecom.endCall()) null else "Kapatılacak çağrı yok"
                }
                "silence" -> silenceRinger()
                "mute", "unmute" -> {
                    audio.isMicrophoneMute = cmd == "mute"
                    broadcast()
                    null
                }
                "speaker_on", "speaker_off" -> setSpeaker(cmd == "speaker_on")
                else -> "Bilinmeyen komut: $cmd"
            }
        } catch (e: SecurityException) {
            "İzin reddedildi: ${e.message}"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun silenceRinger(): String? {
        try {
            @android.annotation.SuppressLint("MissingPermission") // yalnızca varsayılan arama uygulamasına izinli; yine de dene
            telecom.silenceRinger()
            return null
        } catch (_: SecurityException) {
        }
        return try {
            if (!audio.isStreamMute(AudioManager.STREAM_RING)) {
                audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                ringMutedByUs = true
            }
            null
        } catch (_: SecurityException) {
            "Zili susturmak için tablette Rahatsız Etmeyin erişimi gerekli"
        }
    }

    private fun restoreRinger() {
        if (!ringMutedByUs) return
        ringMutedByUs = false
        try { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
    }

    private fun setSpeaker(on: Boolean): String? {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                val dev = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?: return "Hoparlör bulunamadı"
                if (!audio.setCommunicationDevice(dev)) return "Hoparlöre geçilemedi"
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
        synchronized(this) { speaker = on }
        broadcast()
        return null
    }

    // Test çağrısı: gerçek bir arama olmadan iki cihaz arasındaki akışı dener.
    // Telefondan ya da tabletin kendi ekranından (telefon kilitliyken denemek için) başlatılır.
    fun startTest(): String? {
        synchronized(this) {
            if (state != Bridge.STATE_IDLE && !test) return "Tablette şu an gerçek bir çağrı var"
            test = true
            state = Bridge.STATE_RINGING
            number = "+90 555 000 00 00"
            name = "Test çağrısı"
        }
        broadcast()
        thread(isDaemon = true) {
            Thread.sleep(30_000)
            synchronized(this) {
                if (!test || state != Bridge.STATE_RINGING) return@thread
            }
            endTest()
        }
        return null
    }

    private fun executeTest(cmd: String): String? {
        when (cmd) {
            "accept" -> synchronized(this) { state = Bridge.STATE_OFFHOOK }
            "end" -> { endTest(); return null }
            "speaker_on", "speaker_off" -> synchronized(this) { speaker = cmd == "speaker_on" }
            "silence", "mute", "unmute" -> {}
            else -> return "Bilinmeyen komut: $cmd"
        }
        broadcast()
        return null
    }

    private fun endTest() {
        synchronized(this) {
            test = false
            state = Bridge.STATE_IDLE
            number = null
            name = null
            speaker = false
        }
        broadcast()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---- bildirim ----

    private fun statusNotification(): Notification {
        val n = peers.size
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, BridgeActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Bridge.CHANNEL_STATUS)
            .setSmallIcon(if (n == 0) R.drawable.ic_link_off else R.drawable.ic_link)
            .setColor(if (n == 0) Bridge.COLOR_OFFLINE else Bridge.COLOR_READY)
            .setContentTitle(if (n == 0) "Köprü açık, telefon bekleniyor" else "Köprü hazır")
            .setContentText(if (n == 0) "PIN: ${Bridge.pin(this)}" else "$n telefon bağlı")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun refreshNotification() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, statusNotification())
    }

    companion object {
        private const val ACTION_STOP = "com.serhanensar.callbridge.HOST_STOP"
        private const val NOTIFICATION_ID = 10
        private const val READ_TIMEOUT_MS = 60_000

        @Volatile var instance: CallHostService? = null
            private set
        @Volatile var lastError: String? = null

        val isRunning get() = instance != null
        val peerCount get() = instance?.peers?.size ?: 0

        /** Ses aktarıcısı (RelayDaemon) son denetimde yanıt verdi mi; null: henüz bilinmiyor. */
        @Volatile var relayReady: Boolean? = null
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CallHostService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CallHostService::class.java).setAction(ACTION_STOP))
        }
    }
}
