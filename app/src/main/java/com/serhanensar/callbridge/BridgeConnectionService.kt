package com.serhanensar.callbridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Telefonda CallBridge'i bir "arama hesabı" olarak tanıtır (internet aramalarındaki SIP hesapları
 * gibi). Rehberden ya da telefonun kendi tuş takımından yapılan arama Android tarafından buraya
 * verilir; numarayı tablet arar, ses CallAudio ile gelir. Çağrı sistemin çağrı ekranında görünür,
 * hoparlör/sessiz/kapat oradan yönetilir.
 */
class BridgeConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val number = request.address?.schemeSpecificPart.orEmpty().filter { it.isDigit() || it in "+*#" }
        val conn = BridgeConnection()
        conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.audioModeIsVoip = true // ses iletişim modunda; sistem ahize/hoparlör/Bluetooth yönetir
        conn.connectionCapabilities = Connection.CAPABILITY_MUTE
        if (!BridgeClientService.dialFromSystem(number, conn)) {
            val why = BridgeClientService.lastResult ?: "CallBridge tablete bağlı değil"
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, why, why, why))
        }
        conn.setDialing()
        return conn
    }
}

/** Sistemin çağrı ekranındaki bir giden arama. Durumu BridgeClientService günceller. */
class BridgeConnection : Connection() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var done = false

    override fun onDisconnect() {
        BridgeClientService.command("end")
        finish(DisconnectCause.LOCAL)
    }

    override fun onAbort() = onDisconnect()

    @Deprecated("Android 14'te onMuteStateChanged; bu sürüm tüm sürümlerde çağrılıyor")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        BridgeClientService.audio?.muted = state.isMuted
    }

    /** Tablet aramayı başlattı. Karşı tarafın açtığını bilemiyoruz; süre şimdiden sayılır. */
    fun connected() = main.post { if (!done) setActive() }

    fun finish(code: Int, reason: String? = null) = main.post {
        if (done) return@post
        done = true
        setDisconnected(if (reason != null) DisconnectCause(code, reason, reason, reason) else DisconnectCause(code))
        destroy()
    }
}

object PhoneAccounts {
    private const val ID = "callbridge"

    fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, BridgeConnectionService::class.java), ID)

    fun register(ctx: Context) {
        val account = PhoneAccount.builder(handle(ctx), "CallBridge")
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .setShortDescription("Tabletteki SIM ile ara")
            .setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, "", null))
            .setIcon(Icon.createWithResource(ctx, R.drawable.ic_call))
            .setHighlightColor(Bridge.COLOR_READY)
            .build()
        ctx.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
    }

    fun unregister(ctx: Context) {
        ctx.getSystemService(TelecomManager::class.java).unregisterPhoneAccount(handle(ctx))
    }

    /** Kullanıcı hesabı arama ayarlarından açtı mı. */
    fun isEnabled(ctx: Context): Boolean = try {
        ctx.getSystemService(TelecomManager::class.java).getPhoneAccount(handle(ctx))?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    /** Sistemin "Arama hesapları" ayarı. */
    fun settingsIntent() = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
}
