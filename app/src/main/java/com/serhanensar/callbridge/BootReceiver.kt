package com.serhanensar.callbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Cihaz açıldığında köprü daha önce açık bırakıldıysa yeniden başlatır. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Bridge.enabled(context)) return
        try {
            when (Bridge.role(context)) {
                Bridge.ROLE_HOST -> CallHostService.start(context)
                Bridge.ROLE_CLIENT -> BridgeClientService.start(context)
            }
        } catch (_: Exception) {
            // arka plandan başlatma engellendiyse kullanıcı uygulamayı açınca başlatır
        }
    }
}
