package com.serhanensar.callbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Telefonun kendi zili. Bildirim kanalı yerine burada çalınır; böylece bildirim
 * sessizce güncellenebilir ve zil yalnızca kullanıcı seçince ya da çağrı bitince durur.
 * Telefonun sessiz/titreşim moduna uyar.
 */
class Ringer(private val ctx: Context) {

    private var ringtone: Ringtone? = null
    private var vibrating = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    val isRinging get() = ringtone != null || vibrating

    @Synchronized
    fun start() {
        if (isRinging) return
        val mode = ctx.getSystemService(AudioManager::class.java).ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = attrs
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            @Suppress("DEPRECATION")
            vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
            vibrating = true
        }
    }

    @Synchronized
    fun stop() {
        ringtone?.stop()
        ringtone = null
        if (vibrating) vibrator().cancel()
        vibrating = false
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
}
