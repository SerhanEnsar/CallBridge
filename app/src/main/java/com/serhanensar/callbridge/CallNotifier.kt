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

    fun status(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, Bridge.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Çağrı köprüsü")
            .setContentText(text)
            .setContentIntent(activity(ctx, BridgeActivity::class.java, 0))
            .setOngoing(true)
            .build()

    /** alert=false: zil yeniden çalmadan bildirimi günceller (ör. numara sonradan geldiğinde). */
    fun incoming(ctx: Context, call: BridgeClientService.Call, alert: Boolean) {
        val silenced = BridgeClientService.ringSilenced
        val screen = activity(ctx, IncomingCallActivity::class.java, 1)
        val answer = command(ctx, "accept", 2)
        val decline = command(ctx, "end", 3)
        val b = Notification.Builder(ctx, if (silenced) Bridge.CHANNEL_CALL_SILENT else Bridge.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_call)
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
        val n = b.build()
        if (!silenced) n.flags = n.flags or Notification.FLAG_INSISTENT // kullanıcı seçene kadar çal
        ctx.getSystemService(NotificationManager::class.java).notify(INCOMING_ID, n)
    }

    fun ongoing(ctx: Context, call: BridgeClientService.Call): Notification {
        val hangUp = command(ctx, "end", 4)
        val b = Notification.Builder(ctx, Bridge.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_call)
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
