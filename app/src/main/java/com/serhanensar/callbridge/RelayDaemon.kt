package com.serhanensar.callbridge

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.Process
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Tablette shell yetkisiyle çalışan çağrı sesi aktarıcısı. Normal uygulamalar çağrının sesine
 * erişemez; shell kullanıcısı CAPTURE_AUDIO_OUTPUT ve MODIFY_AUDIO_ROUTING izinlerine sahip.
 * CallBridge APK'sının içinden başlatılır (setup.sh relay) ve cihaz yeniden başlayana kadar çalışır:
 *
 *   CLASSPATH=<apk> app_process / com.serhanensar.callbridge.RelayDaemon <callbridge-uid>
 *
 * 127.0.0.1:RELAY_PORT'ta yalnızca CallBridge'in bağlantısını kabul eder; bağlananın uid'i
 * /proc/net/tcp'den doğrulanır. Normal çağrı modunda (IN_CALL) çalışır:
 *   karşı tarafın sesi: VOICE_DOWNLINK kaynağı → UDP AUDIO_PORT (telefon)
 *   telefonun mikrofonu: UDP MIC_PORT → Telephony Tx ucuna yönlendirilmiş AudioTrack
 * Aktarım sürerken tabletin mikrofonu kapatılır ve çağrı sesi en kısığa indirilir; bitince
 * ikisi de eski haline döner.
 */
// Uygulama olarak değil, shell kullanıcısıyla app_process içinde çalışır: uygulamaların izin
// denetimleri ve gizli API kısıtları burada geçerli değil. Çağrı sesi API'leri Android 13+ ister.
@SuppressLint("MissingPermission", "NewApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi")
object RelayDaemon {
    private const val RATE = 16000
    private const val FRAME = RATE / 50 // 20 ms
    private const val MODE_IN_CALL = 2

    private const val MODE_NORMAL = 0
    private const val MODE_CALL_REDIRECT = 5

    private lateinit var svc: Any
    @Volatile private var session: Session? = null

    /**
     * true: aktarım sürerken ses modu CALL_REDIRECT'e alınır; tablet hoparlörü ve mikrofonu çağrıdan
     * ayrılır (en kısık seste bile çalmasın diye). Bu modda sesi alma/verme yolu aynı kalır.
     */
    private var redirect = true
    private val modeToken = android.os.Binder()

    @JvmStatic
    fun main(args: Array<String>) {
        val allowedUid = args.getOrNull(0)?.toIntOrNull() ?: error("kullanım: RelayDaemon <callbridge-uid>")
        check(Build.VERSION.SDK_INT >= 33) { "Android 13 ve üstü gerekli" }
        redirect = args.getOrNull(1) != "noredirect"
        try {
            Process::class.java.getMethod("setArgV0", String::class.java).invoke(null, "callbridge-relay")
        } catch (_: Exception) {
        }
        svc = audioService()
        log("başladı; izinli uid=$allowedUid, çağrı sesi desteği=${call("isPstnCallAudioInterceptable")}, tableti sustur=$redirect")
        Runtime.getRuntime().addShutdownHook(Thread { session?.stop("kapanış") })
        thread(isDaemon = true, name = "call-watch") { watchCallEnd() }

        ServerSocket(Bridge.RELAY_PORT, 4, InetAddress.getByName("127.0.0.1")).use { ss ->
            while (true) {
                val s = ss.accept()
                thread(isDaemon = true) {
                    try {
                        serve(s, allowedUid)
                    } catch (e: Exception) {
                        log("kontrol bağlantısı: $e")
                    } finally {
                        try { s.close() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun serve(s: Socket, allowedUid: Int) {
        val uid = peerUid(s.port)
        if (uid != allowedUid) {
            log("reddedildi: uid=$uid")
            return
        }
        val line = Line(s)
        while (true) {
            val m = line.read() ?: return
            val reply = when (m.optString("t")) {
                "start" -> {
                    val error = start(InetAddress.getByName(m.getString("ip")))
                    Bridge.msg("audio", "active" to (error == null), "error" to error)
                }
                "stop" -> {
                    session?.stop("istek")
                    Bridge.msg("audio", "active" to false)
                }
                else -> Bridge.msg("audio", "active" to (session != null))
            }
            line.send(reply)
        }
    }

    @Synchronized
    private fun start(phone: InetAddress): String? {
        session?.let {
            if (it.phone == phone) return null
            it.stop("yeni hedef")
        }
        if (call("getMode") != MODE_IN_CALL) return "Tablette süren bir çağrı yok"
        return try {
            session = Session(phone).also { it.start() }
            null
        } catch (e: Exception) {
            session = null
            log("başlatılamadı: $e")
            (e.cause ?: e).message ?: e.javaClass.simpleName
        }
    }

    /**
     * Çağrı bitince aktarımı kendiliğinden durdur. CALL_REDIRECT'teyken mod bizim; çağrının
     * sürüp sürmediğine telefon servisinin çağrı durumundan bakılır.
     */
    private fun watchCallEnd() {
        var idle = 0
        while (true) {
            Thread.sleep(1000)
            val s = session ?: continue
            idle = if (callActive()) 0 else idle + 1
            if (idle >= 2) {
                s.stop("çağrı bitti")
                idle = 0
            }
        }
    }

    // ---- aktarım ----

    private class Session(val phone: InetAddress) {
        @Volatile private var running = false
        private val threads = mutableListOf<Thread>()
        private var prevVolume = -1
        private var downPackets = 0L
        private var upPackets = 0L
        private var upDropped = 0L
        private var redirected = false

        fun start() {
            val record = AudioRecord.Builder()
                .setContext(ShellContext())
                .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .build()
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Çağrı sesi alınamadı" }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(FRAME * 2 * 6)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            val tx = telephonyTx() ?: run {
                record.release()
                track.release()
                error("Tablette çağrıya ses verme ucu yok")
            }
            track.setPreferredDevice(tx)

            setMicMute(true)
            prevVolume = call("getStreamVolume", AudioManager.STREAM_VOICE_CALL) as Int
            setVoiceVolume(call("getStreamMinVolume", AudioManager.STREAM_VOICE_CALL) as Int)
            if (redirect) {
                setMode(MODE_CALL_REDIRECT)
                redirected = true
            }

            running = true
            threads += thread(name = "downlink") { downlink(record) }
            threads += thread(name = "uplink") { uplink(track) }
            log("aktarım başladı → ${phone.hostAddress}")
        }

        @Synchronized
        fun stop(reason: String) {
            if (!running) return
            running = false
            threads.forEach { it.join(1500) }
            threads.clear()
            // NORMAL istemek kendi mod isteğimizi geri çeker; çağrı sürüyorsa kendi moduna döner
            if (redirected) try { setMode(MODE_NORMAL) } catch (_: Exception) {}
            redirected = false
            try { setMicMute(false) } catch (_: Exception) {}
            if (prevVolume >= 0) try { setVoiceVolume(prevVolume) } catch (_: Exception) {}
            if (session === this) session = null
            log("aktarım durdu ($reason): $downPackets paket gitti, $upPackets paket geldi, gecikme için $upDropped atlandı")
        }

        private fun downlink(rec: AudioRecord) {
            val sock = DatagramSocket()
            val pcm = ShortArray(FRAME)
            val buf = ByteArray(4 + FRAME * 2)
            var seq = 0
            var sum = 0.0
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(pcm, 0, FRAME)
                    if (n <= 0) continue
                    buf[0] = (seq shr 24).toByte(); buf[1] = (seq shr 16).toByte(); buf[2] = (seq shr 8).toByte(); buf[3] = seq.toByte()
                    for (k in 0 until n) {
                        buf[4 + 2 * k] = pcm[k].toInt().toByte()
                        buf[5 + 2 * k] = (pcm[k].toInt() shr 8).toByte()
                        sum += pcm[k].toDouble() * pcm[k]
                    }
                    if (seq % 250 == 249) { // 5 sn: karşı tarafın ses seviyesi (0'a yakınsa ses gelmiyor)
                        log("  karşı taraf rms=${Math.sqrt(sum / (250.0 * FRAME)).toInt()} mod=${call("getMode")}")
                        sum = 0.0
                    }
                    sock.send(DatagramPacket(buf, 4 + n * 2, phone, Bridge.AUDIO_PORT))
                    seq++
                    downPackets++
                }
            } catch (e: Exception) {
                log("downlink: $e")
            } finally {
                sock.close()
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }

        private fun uplink(track: AudioTrack) {
            val sock = DatagramSocket(Bridge.MIC_PORT).apply { soTimeout = 500 }
            val buf = ByteArray(4 + FRAME * 2 * 4)
            val p = DatagramPacket(buf, buf.size)
            try {
                track.play()
                track.write(ByteArray(FRAME * 2), 0, FRAME * 2)
                var written = FRAME.toLong() // yazılan örnek sayısı
                while (running) {
                    try {
                        sock.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (p.address != phone || p.length <= 4) continue // yalnızca bağlı telefondan
                    // Gecikme birikmesin: çalınmayı bekleyen ses 100 ms'yi aştıysa bu paketi atla
                    val queued = written - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                    if (queued > RATE / 10) {
                        upDropped++
                        continue
                    }
                    val n = p.length - 4
                    track.write(buf, 4, n)
                    written += n / 2
                    upPackets++
                }
            } catch (e: Exception) {
                log("uplink: $e")
            } finally {
                sock.close()
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }
    }

    // ---- sistem çağrıları (gizli API'ler yansımayla) ----

    /** AudioRecord.Builder yalnızca kimlik ve cihaz kimliği soruyor. */
    private class ShellContext : ContextWrapper(null) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName("com.android.shell").build()
        override fun getDeviceId() = 0
    }

    private fun format(channel: Int) = AudioFormat.Builder()
        .setSampleRate(RATE)
        .setChannelMask(channel)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    private fun audioService(): Any {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        return Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, b)!!
    }

    /** IAudioService'te adı ve int argümanları verilen yöntemi çağırır. */
    private fun call(name: String, vararg ints: Int): Any? {
        val m = svc.javaClass.methods.first { it.name == name && it.parameterCount == ints.size }
        return m.invoke(svc, *ints.toTypedArray())
    }

    /** Parametre türlerine göre doldurarak çağırır: değerler sırayla, String yerine paket adı. */
    private fun callFill(name: String, minParams: Int, vararg values: Any) {
        val m = svc.javaClass.methods.filter { it.name == name && it.parameterCount >= minParams }
            .minByOrNull { it.parameterCount } ?: error("$name yok")
        var i = 0
        val args = m.parameterTypes.map { t ->
            when (t) {
                String::class.java -> "com.android.shell"
                else -> if (i < values.size) values[i++] else 0
            }
        }
        m.invoke(svc, *args.toTypedArray())
    }

    private fun setMicMute(on: Boolean) = callFill("setMicrophoneMute", 1, on)

    /** IAudioService.setMode(int mode, IBinder cb, String callingPackage); süreç ölürse istek düşer. */
    private fun setMode(mode: Int) {
        val m = svc.javaClass.methods.first { it.name == "setMode" && it.parameterCount == 3 }
        m.invoke(svc, mode, modeToken, "com.android.shell")
    }

    /** Telefon servisine göre süren bir çağrı var mı (ses modundan bağımsız). */
    private fun callActive(): Boolean {
        if (!redirect) return call("getMode") == MODE_IN_CALL
        return try {
            val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "telecom") as IBinder
            val t = Class.forName("com.android.internal.telecom.ITelecomService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
            t.javaClass.methods.first { it.name == "isInCall" && it.parameterCount == 2 }
                .invoke(t, "com.android.shell", null) as Boolean
        } catch (e: Exception) {
            log("çağrı durumu okunamadı: $e")
            true // emin olamıyorsak durdurma; CallBridge çağrı bitince ayrıca durdurur
        }
    }

    private fun setVoiceVolume(index: Int) = callFill("setStreamVolume", 3, AudioManager.STREAM_VOICE_CALL, index, 0)

    private fun telephonyTx(): AudioDeviceInfo? {
        val m = AudioManager::class.java.getDeclaredMethod("getDevicesStatic", Int::class.javaPrimitiveType)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val outs = m.invoke(null, AudioManager.GET_DEVICES_OUTPUTS) as Array<AudioDeviceInfo>
        return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
    }

    /** Yerel bağlantının sahibini /proc/net/tcp'den bulur (bizim portumuza bağlanan uçtaki port). */
    private fun peerUid(peerPort: Int): Int {
        val want = "%04X".format(peerPort)
        val target = "%04X".format(Bridge.RELAY_PORT)
        for (f in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = try { File(f).readLines() } catch (_: Exception) { continue }
            for (l in lines.drop(1)) {
                val c = l.trim().split(Regex("\\s+"))
                if (c.size < 8) continue
                if (c[1].substringAfter(':') == want && c[2].substringAfter(':') == target) return c[7].toInt()
            }
        }
        return -1
    }

    private fun log(s: String) {
        println("${java.time.LocalTime.now().withNano(0)} $s")
        System.out.flush()
    }
}
