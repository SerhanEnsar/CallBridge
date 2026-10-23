package com.serhanensar.callbridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build

/** Telefondaki köprü bildirimleri: durum, gelen çağrı (tam ekran) ve süren görüşme. */
object CallNotifier {
    const val STATUS_ID = 20
    private const val INCOMING_ID = 21

    /** Çağrı yokken: bağlıysa zincir (hazır), değilse üstü çizili zincir. Ahize yalnızca çağrıda görünür. */
    fun status(ctx: Context, text: String, connected: Boolean): Notification =
        Notification.Builder(ctx, Bridge.CHANNEL_STATUS)
            .setSmallIcon(if (connected) R.drawable.ic_link else R.drawable.ic_link_off)
            .setColor(if (connected) Bridge.COLOR_READY else Bridge.COLOR_OFFLINE)
            .setContentTitle(if (connected) "Çağrıya hazır" else "Tablete bağlı değil")
            .setContentText(text)
            .setContentIntent(activity(ctx, BridgeActivity::class.java, 0))
            .setOngoing(true)
            .build()

    /**
     * alert=false: üstten kayan bildirimi yeniden göstermeden günceller (ör. numara sonradan gelince).
     * quiet=true: çağrı ekranı zaten açık; bildirim panelde durur ama ekranın üstüne binmez.
     */
    fun incoming(ctx: Context, call: BridgeClientService.Call, alert: Boolean, quiet: Boolean) {
        val screen = activity(ctx, IncomingCallActivity::class.java, 1)
        val answer = command(ctx, "accept", 2)
        val decline = command(ctx, "end", 3)
        val b = Notification.Builder(ctx, if (quiet) Bridge.CHANNEL_CALL_QUIET else Bridge.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_ringing)
            .setColor(Bridge.COLOR_READY)
            .setColorized(true)
            .setContentTitle(call.title)
            .setContentText(subtitle(call, "Tablete gelen çağrı"))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setStyle(Notification.CallStyle.forIncomingCall(person(call), decline, answer))
        } else {
            b.addAction(Notification.Action.Builder(null, "Reddet", decline).build())
            b.addAction(Notification.Action.Builder(null, "Yanıtla", answer).build())
        }
        ctx.getSystemService(NotificationManager::class.java).notify(INCOMING_ID, b.build())
    }

    fun ongoing(ctx: Context, call: BridgeClientService.Call): Notification {
        val hangUp = command(ctx, "end", 4)
        val b = Notification.Builder(ctx, Bridge.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(Bridge.COLOR_READY)
            .setColorized(true)
            .setContentTitle(call.title)
            .setContentText(subtitle(call, "Tablette görüşme sürüyor"))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setWhen(call.since)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setContentIntent(activity(ctx, IncomingCallActivity::class.java, 1))
        if (Build.VERSION.SDK_INT >= 31) {
            b.setStyle(Notification.CallStyle.forOngoingCall(person(call), hangUp))
        } else {
            b.addAction(Notification.Action.Builder(null, "Kapat", hangUp).build())
        }
        return b.build()
    }

    fun cancelIncoming(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(INCOMING_ID)
    }

    /**
     * Çağrı sistemin (Samsung'un) çağrı ekranındayken: o ekran zaten çağrıyı gösteriyor, bu yüzden
     * burada yalnızca sade bir "ses bu telefonda" bildirimi durur (çift çağrı bildirimi olmasın).
     */
    fun relaying(ctx: Context, call: BridgeClientService.Call, route: String): Notification =
        Notification.Builder(ctx, Bridge.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(Bridge.COLOR_READY)
            .setContentTitle("Ses bu telefonda")
            .setContentText(call.title + if (route != CallAudio.ROUTE_SYSTEM && route != "—") " · $route" else "")
            .setContentIntent(activity(ctx, BridgeActivity::class.java, 0))
            .setOngoing(true)
            .build()

    /** Görüşme sürüyor ama ses başka yerde (tablette ya da başka bir cihazda): sesi buraya al. */
    fun elsewhere(ctx: Context, call: BridgeClientService.Call, place: String): Notification =
        Notification.Builder(ctx, Bridge.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(Bridge.COLOR_OFFLINE)
            .setContentTitle(call.title)
            .setContentText("Görüşme $place sürüyor")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setWhen(call.since)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setContentIntent(activity(ctx, IncomingCallActivity::class.java, 1))
            .addAction(Notification.Action.Builder(null, "Bu cihaza al", command(ctx, "audio_on", 5)).build())
            .addAction(Notification.Action.Builder(null, "Kapat", command(ctx, "end", 6)).build())
            .build()

    private fun subtitle(call: BridgeClientService.Call, base: String) = buildString {
        if (call.name != null && call.number != null) append(call.number).append(" · ")
        append(base)
        if (call.test) append(" (test)")
    }

    @android.annotation.TargetApi(31) // yalnızca CallStyle yolunda kullanılır
    private fun person(call: BridgeClientService.Call) =
        Person.Builder().setName(call.title).setImportant(true).build()

    private fun activity(ctx: Context, cls: Class<*>, code: Int) = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun command(ctx: Context, cmd: String, code: Int) = PendingIntent.getService(
        ctx, code, BridgeClientService.cmdIntent(ctx, cmd),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
