package com.serhanensar.callbridge

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Kilit ekranının üstünde açılan çağrı ekranı. Komutları tablete iletir. */
class IncomingCallActivity : Activity() {

    private lateinit var c: Palette
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var number: TextView
    private lateinit var error: TextView
    private lateinit var ringingRow: LinearLayout
    private lateinit var callRow: LinearLayout
    private lateinit var muteButton: TextView
    private lateinit var speakerButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        c = setupWindow()
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // Tam ekran ya da bildirimden açıldıysa üstten kayan bildirim ekranın üstünde kalmasın
        BridgeClientService.call?.takeIf { it.state == Bridge.STATE_RINGING }?.let {
            CallNotifier.cancelIncoming(this)
            CallNotifier.incoming(this, it, alert = false, quiet = true)
        }
        handler.post(ticker)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val call = BridgeClientService.call
        if (call == null) {
            finish()
            return
        }
        title.setTextIfChanged(call.title)
        number.setTextIfChanged(if (call.name != null) call.number ?: "" else "")
        number.setShown(number.text.isNotEmpty())
        val ringing = call.state == Bridge.STATE_RINGING
        status.setTextIfChanged(when {
            ringing -> "Tablete gelen çağrı" + if (call.test) " (test)" else ""
            else -> {
                val s = (System.currentTimeMillis() - call.since) / 1000
                "Görüşmede  %d:%02d".format(s / 60, s % 60)
            }
        })
        ringingRow.setShown(ringing)
        callRow.setShown(!ringing)
        toggleStyle(muteButton, call.muted)
        toggleStyle(speakerButton, call.speaker)
        val err = BridgeClientService.lastResult
            ?: if (!BridgeClientService.isConnected) "Tablete bağlı değil" else null
        error.setTextIfChanged(err ?: "")
        error.setShown(err != null)
    }

    private fun send(cmd: String) {
        BridgeClientService.lastResult = null
        BridgeClientService.command(cmd)
    }

    // ---- arayüz ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(72), dp(24), dp(48))
            setBackgroundColor(c.background)
        }
        status = label("", 16f, c.secondary).apply { gravity = Gravity.CENTER }
        root.addView(status, matchWidth(bottom = 14))
        title = label("", 32f, c.text, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(title, matchWidth(bottom = 6))
        number = label("", 18f, c.secondary).apply { gravity = Gravity.CENTER }
        root.addView(number, matchWidth())

        root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        error = label("", 14f, c.red).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(error, matchWidth(bottom = 18))

        ringingRow = row().apply {
            addView(circle("Reddet", c.red, 0xFFFFFFFF.toInt()) { send("end") })
            addView(circle("Sessiz", c.neutral, c.text) { send("silence") })
            addView(circle("Yanıtla", c.green, 0xFFFFFFFF.toInt()) { send("accept") })
        }
        root.addView(ringingRow, matchWidth(bottom = 20))

        muteButton = circle("Mikrofonu\nkapat", c.neutral, c.text) {
            send(if (BridgeClientService.call?.muted == true) "unmute" else "mute")
        }
        speakerButton = circle("Hoparlör", c.neutral, c.text) {
            send(if (BridgeClientService.call?.speaker == true) "speaker_off" else "speaker_on")
        }
        callRow = row().apply {
            addView(muteButton)
            addView(circle("Kapat", c.red, 0xFFFFFFFF.toInt()) { send("end") })
            addView(speakerButton)
            visibility = View.GONE
        }
        root.addView(callRow, matchWidth(bottom = 20))

        root.addView(
            label("Ses tablete bağlı Bluetooth kulaklıktan gelir.", 13f, c.secondary).apply { gravity = Gravity.CENTER },
            matchWidth(),
        )
        return root
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun circle(text: String, bg: Int, fg: Int, onClick: () -> Unit) = label(text, 14f, fg, bold = true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
    }

    private fun toggleStyle(v: TextView, on: Boolean) {
        if (v.tag == on) return
        v.tag = on
        (v.background as GradientDrawable).setColor(if (on) c.text else c.neutral)
        v.setTextColor(if (on) c.background else c.text)
    }

    companion object {
        @Volatile var visible = false
            private set
    }
}
