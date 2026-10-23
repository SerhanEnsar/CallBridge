package com.serhanensar.callbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Çağrı köprüsü: SIM'li cihaz (host) çağrı durumunu hotspot ağı üzerinden
 * yanıtlayan cihaza (client) iletir, client'tan gelen komutları uygular.
 *
 * Protokol: TCP üzerinde satır başına bir JSON nesnesi.
 *   host → client  {"t":"hello","nonce":…}
 *   client → host  {"t":"auth","mac":HMAC(PIN, nonce),"name":…}
 *   host → client  {"t":"auth_ok"} | {"t":"auth_fail"}
 *   host → client  {"t":"state","state":"ringing|offhook|idle","number":…,"name":…,"muted":…,"speaker":…,"test":…}
 *   client → host  {"t":"cmd","cmd":"accept|end|silence|mute|unmute|speaker_on|speaker_off|test"}
 *   host → client  {"t":"result","cmd":…,"ok":…,"error":…}
 *   her iki yön    {"t":"ping"} / {"t":"pong"}
 */
object Bridge {
    const val TAG = "CallBridge"
    const val PORT = 47123

    const val ROLE_NONE = ""
    const val ROLE_HOST = "host"
    const val ROLE_CLIENT = "client"

    const val STATE_RINGING = "ringing"
    const val STATE_OFFHOOK = "offhook"
    const val STATE_IDLE = "idle"

    const val CHANNEL_STATUS = "bridge_status"
    const val CHANNEL_CALL = "bridge_call"
    const val CHANNEL_CALL_SILENT = "bridge_call_silent"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("callbridge", Context.MODE_PRIVATE)

    fun role(ctx: Context): String = prefs(ctx).getString("role", ROLE_NONE) ?: ROLE_NONE
    fun pin(ctx: Context): String = prefs(ctx).getString("pin", "") ?: ""
    fun manualHost(ctx: Context): String = prefs(ctx).getString("host", "") ?: ""
    fun autoSilence(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_silence", true)
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun newPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

    fun nonce(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.hex()
    }

    fun mac(pin: String, nonce: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest("callbridge-v1:$pin".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(nonce.toByteArray()).hex()
    }

    fun macEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun msg(type: String, vararg fields: Pair<String, Any?>) = JSONObject().apply {
        put("t", type)
        fields.forEach { (k, v) -> if (v != null) put(k, v) }
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Köprü durumu", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Gelen çağrı", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL_SILENT, "Gelen çağrı (sessiz)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** Satır başına bir JSON nesnesi okuyup yazan bağlantı. Yazma iş parçacığı güvenlidir. */
class Line(val socket: Socket) {
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
    private val lock = Any()

    /** Bağlantı kapanınca null döner. */
    fun read(): JSONObject? {
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isBlank()) continue
            return try { JSONObject(line) } catch (_: Exception) { continue }
        }
    }

    fun send(obj: JSONObject): Boolean = try {
        synchronized(lock) {
            writer.write(obj.toString())
            writer.write("\n")
            writer.flush()
        }
        true
    } catch (_: Exception) {
        close()
        false
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
