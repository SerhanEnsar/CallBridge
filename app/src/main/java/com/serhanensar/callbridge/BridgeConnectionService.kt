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
 * gibi). Böylece çağrılar sistemin (Samsung'un) çağrı ekranında görünür:
 *   giden: rehberden ya da sistem tuş takımından yapılan arama buraya verilir, numarayı tablet arar;
 *   gelen: tablete gelen çağrı buradan sisteme bildirilir, Samsung'un gelen arama ekranında çalar.
 * Ses CallAudio ile gelir; ses yolu (ahize/hoparlör/Bluetooth) ve sessiz sistem ekranından yönetilir.
 */
class BridgeConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val number = request.address?.schemeSpecificPart.orEmpty().filter { it.isDigit() || it in "+*#" }
        val conn = BridgeConnection(incoming = false)
        conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        if (!BridgeClientService.dialFromSystem(number, conn)) {
            val why = BridgeClientService.lastResult ?: "CallBridge tablete bağlı değil"
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, why, why, why))
        }
        conn.setDialing()
        return conn
    }

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val conn = BridgeConnection(incoming = true)
        conn.setRinging()
        BridgeClientService.incomingConnection(conn)
        return conn
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        BridgeClientService.incomingConnection(null)
    }
}

/** Sistemin çağrı ekranındaki bir çağrı. Durumu BridgeClientService günceller. */
class BridgeConnection(private val incoming: Boolean) : Connection() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var done = false
    @Volatile private var active = false

    init {
        audioModeIsVoip = true // ses iletişim modunda; sistem ahize/hoparlör/Bluetooth yönetir
        connectionCapabilities = CAPABILITY_MUTE
    }

    /** Numara ve ad sonradan gelebilir (tablet ikinci yayında yollar). */
    fun update(c: BridgeClientService.Call) = main.post {
        if (done) return@post
        c.number?.let { setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, it, null), TelecomManager.PRESENTATION_ALLOWED) }
        if (c.name != null) setCallerDisplayName(c.name, TelecomManager.PRESENTATION_ALLOWED)
    }

    override fun onAnswer() {
        BridgeClientService.command("accept")
        connected()
    }

    override fun onAnswer(videoState: Int) = onAnswer()

    override fun onReject() {
        BridgeClientService.command("end")
        finish(DisconnectCause.REJECTED)
    }

    override fun onSilence() {
        BridgeClientService.command("silence")
    }

    override fun onDisconnect() {
        BridgeClientService.command("end")
        finish(DisconnectCause.LOCAL)
    }

    override fun onAbort() = onDisconnect()

    @Deprecated("Android 14'te onMuteStateChanged; bu sürüm tüm sürümlerde çağrılıyor")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        BridgeClientService.audio?.muted = state.isMuted
    }

    /** Görüşme bu telefonda başladı. Giden aramada karşı tarafın açtığını bilemiyoruz; süre şimdiden sayılır. */
    fun connected() = main.post {
        if (done || active) return@post
        active = true
        setActive()
    }

    fun finish(code: Int, reason: String? = null) = main.post {
        if (done) return@post
        done = true
        setDisconnected(if (reason != null) DisconnectCause(code, reason, reason, reason) else DisconnectCause(code))
        destroy()
    }

    override fun toString() = if (incoming) "gelen" else "giden"
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

    /**
     * Kullanıcı hesabı arama ayarlarından açtı mı. Samsung getPhoneAccount için READ_PHONE_NUMBERS
     * istiyor; arama yapabilen hesaplar listesi READ_PHONE_STATE ile okunabiliyor ve yalnızca açık
     * hesapları içeriyor.
     */
    @android.annotation.SuppressLint("MissingPermission") // READ_PHONE_STATE yoksa SecurityException yakalanır
    fun isEnabled(ctx: Context): Boolean = try {
        ctx.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.contains(handle(ctx))
    } catch (_: SecurityException) {
        false
    }

    /** Sistemin "Arama hesapları" ayarı. */
    fun settingsIntent() = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
}
