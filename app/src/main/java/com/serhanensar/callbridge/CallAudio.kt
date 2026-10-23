package com.serhanensar.callbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Network
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Telefonda çağrının sesi. İki yönlü UDP; her paket 4 bayt sıra numarası + 20 ms PCM
 * (16 kHz, mono, 16 bit, little-endian):
 *   tablet → telefon (AUDIO_PORT): karşı tarafın sesi, burada çalınır
 *   telefon → tablet (MIC_PORT): telefonun mikrofonu, tablette çağrıya verilir
 *
 * İletişim modunda çalışır: Bluetooth kulaklık bağlıysa ses ve mikrofon onundur, değilse
 * ahize ve telefonun mikrofonu; hoparlör seçilebilir. Yankı engelleme açılır.
 */
class CallAudio(
    private val ctx: Context,
    private val network: () -> Network?,
    private val host: () -> InetAddress?,
    private val onRoute: (String) -> Unit,
) {

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private val audio = ctx.getSystemService(AudioManager::class.java)

    @Volatile var speaker = false
        private set
    @Volatile var dropped = 0L
        private set
    @Volatile var muted = false
    @Volatile var micPackets = 0L
        private set
    @Volatile var micError: String? = null
        private set
    @Volatile var packets = 0L
        private set
    @Volatile var route = "—"
        private set

    val isRunning get() = running

    /**
     * true: çağrı Android'in arama sistemi (Telecom) üzerinden; ses modunu ve yönünü
     * (ahize/hoparlör/Bluetooth) sistemin çağrı ekranı yönetir, burada dokunulmaz.
     */
    @Volatile var systemRouted = false
        private set

    @Synchronized
    fun start(systemRouted: Boolean = false) {
        if (running) return
        this.systemRouted = systemRouted
        running = true
        packets = 0
        dropped = 0
        micPackets = 0
        micError = null
        muted = false
        thread(name = "call-audio", isDaemon = true) { receiveLoop() }
        thread(name = "call-mic", isDaemon = true) { micLoop() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun setSpeaker(on: Boolean) {
        speaker = on
        if (running && !systemRouted) applyRoute()
    }

    private fun receiveLoop() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_BYTES * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val prevMode = audio.mode
        val managed = !systemRouted
        try {
            if (managed) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                applyRoute()
            } else {
                route = ROUTE_SYSTEM
                onRoute(route)
            }
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1000
                bind(InetSocketAddress(Bridge.AUDIO_PORT))
            }
            network()?.bindSocket(s)
            socket = s
            track.play()
            track.write(ByteArray(FRAME_BYTES * 2), 0, FRAME_BYTES * 2) // ilk paketler gelene kadar boşluk
            var written = 2L * FRAME_BYTES / 2 // yazılan örnek sayısı
            val buf = ByteArray(HEADER + FRAME_BYTES * 4)
            val packet = DatagramPacket(buf, buf.size)
            var lastSeq = -1
            Bridge.log("Ses alımı başladı, yol: $route")
            while (running) {
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (packet.length <= HEADER) continue
                val seq = ((buf[0].toInt() and 0xff) shl 24) or ((buf[1].toInt() and 0xff) shl 16) or
                    ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
                if (seq <= lastSeq && lastSeq - seq < 1000) continue // geç gelen ya da yinelenen paket
                lastSeq = seq
                // Gecikme birikmesin: çalınmayı bekleyen ses MAX_QUEUE_MS'yi aştıysa bu paketi atla
                val queued = written - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                if (queued > RATE * MAX_QUEUE_MS / 1000) {
                    dropped++
                    continue
                }
                val n = packet.length - HEADER
                track.write(buf, HEADER, n)
                written += n / 2
                packets++
            }
        } catch (e: Exception) {
            if (running) Bridge.warn("Ses alımı durdu: $e")
        } finally {
            running = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            try { track.stop() } catch (_: Exception) {}
            track.release()
            if (managed) {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                audio.mode = prevMode
            }
            route = "—"
            onRoute(route)
            Bridge.log("Ses alımı bitti, $packets paket, gecikme için $dropped atlandı")
        }
    }

    @SuppressLint("MissingPermission") // izin aşağıda denetleniyor
    private fun micLoop() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micError = "Mikrofon izni yok"
            return
        }
        val dest = host() ?: run { micError = "Tablet adresi yok"; return }
        Thread.sleep(200) // receiveLoop iletişim modunu ve yönü kursun
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_BYTES * 4))
                .build()
        } catch (e: Exception) {
            micError = "Mikrofon açılamadı: ${e.message}"
            return
        }
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        val s = DatagramSocket()
        try {
            network()?.bindSocket(s)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                micError = "Mikrofon başka bir uygulamada ya da arka planda engellendi"
                return
            }
            val buf = ByteArray(HEADER + FRAME_BYTES)
            var seq = 0
            Bridge.log("Mikrofon gönderimi başladı → ${dest.hostAddress}:${Bridge.MIC_PORT}")
            while (running) {
                val n = rec.read(buf, HEADER, FRAME_BYTES)
                if (n <= 0) continue
                if (muted) java.util.Arrays.fill(buf, HEADER, HEADER + n, 0)
                buf[0] = (seq shr 24).toByte(); buf[1] = (seq shr 16).toByte(); buf[2] = (seq shr 8).toByte(); buf[3] = seq.toByte()
                s.send(DatagramPacket(buf, HEADER + n, dest, Bridge.MIC_PORT))
                seq++
                micPackets++
            }
        } catch (e: Exception) {
            if (running) micError = "Mikrofon durdu: ${e.message}"
        } finally {
            s.close()
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            Bridge.log("Mikrofon gönderimi bitti, $micPackets paket")
        }
    }

    /** Bluetooth kulaklık > (hoparlör seçiliyse hoparlör) > ahize. */
    private fun applyRoute() {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker
            route = if (speaker) ROUTE_SPEAKER else ROUTE_EARPIECE
            onRoute(route)
            return
        }
        val devices = audio.availableCommunicationDevices
        val bt = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val pick = when {
            speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            bt != null -> bt
            else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (pick != null && audio.setCommunicationDevice(pick)) {
            route = when (pick.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> ROUTE_SPEAKER
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ROUTE_EARPIECE
                else -> pick.productName?.toString() ?: "Bluetooth"
            }
            onRoute(route)
        }
    }

    companion object {
        const val RATE = 16000
        const val HEADER = 4
        const val FRAME_BYTES = RATE / 50 * 2 // 20 ms
        const val MAX_QUEUE_MS = 100 // bundan fazla birikirse paket atlanır
        const val ROUTE_SPEAKER = "hoparlör"
        const val ROUTE_EARPIECE = "ahize"
        const val ROUTE_SYSTEM = "sistem"
    }
}
