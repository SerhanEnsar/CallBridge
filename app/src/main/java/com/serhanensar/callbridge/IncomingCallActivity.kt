package com.serhanensar.callbridge

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * CallBridge'in kendi çağrı ekranı: çağrı sistemin (Samsung'un) ekranında değilken açılır
 * (arama hesabı kapalıysa, uygulama içinden aranınca ya da ses başka cihazdayken). Kilit
 * ekranının üstünde de açılır. Komutları tablete iletir; ses bu telefondaysa tuşlama, ses yolu
 * ve sessiz burada yönetilir.
 */
class IncomingCallActivity : Activity() {

    private lateinit var c: Palette
    private lateinit var statusChip: TextView
    private lateinit var avatarView: TextView
    private lateinit var title: TextView
    private lateinit var number: TextView
    private lateinit var dtmfText: TextView
    private lateinit var quality: TextView
    private lateinit var error: TextView
    private lateinit var routeText: TextView

    private lateinit var ringingRow: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var keypad: LinearLayout
    private lateinit var elsewhere: LinearLayout
    private lateinit var elsewhereText: TextView
    private lateinit var endButton: RoundButton

    private lateinit var muteButton: RoundButton
    private lateinit var keypadButton: RoundButton
    private lateinit var routeButton: RoundButton
    private lateinit var releaseButton: RoundButton

    private var keypadOpen = false
    private val dtmf = StringBuilder()
    private var lastPackets = -1L
    private var lastDropped = 0L
    private var lastTick = 0L

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

    // ---- durum ----

    private fun render() {
        val call = BridgeClientService.call
        if (call == null) {
            finish()
            return
        }
        val audio = BridgeClientService.audio
        val ringing = call.state == Bridge.STATE_RINGING
        val here = !ringing && BridgeClientService.audioHere

        title.setTextIfChanged(call.title)
        number.setTextIfChanged(if (call.name != null) call.number ?: "" else "")
        number.setShown(number.text.isNotEmpty())
        if (avatarView.tag != call.title) {
            avatarView.tag = call.title
            avatarView.text = initials(call.name)
            (avatarView.background as android.graphics.drawable.GradientDrawable).setColor(avatarColor(call.name ?: "#"))
        }
        dtmfText.setTextIfChanged(dtmf.toString())
        dtmfText.setShown(dtmf.isNotEmpty())

        val elapsed = (System.currentTimeMillis() - call.since) / 1000
        val time = "%d:%02d".format(elapsed / 60, elapsed % 60)
        val place = placeOf(call)
        statusChip.setTextIfChanged(when {
            ringing -> "Tablete gelen çağrı" + if (call.test) " · test" else ""
            here && call.outgoing -> "Giden arama · $time"
            here -> "Görüşmede · $time"
            call.answeredBy == CallHostService.TABLET && call.audioTo == null -> "Tablette yanıtlandı · $time"
            else -> "${place.replaceFirstChar { it.uppercase() }} sürüyor · $time"
        })

        ringingRow.setShown(ringing)
        controls.setShown(here && !keypadOpen)
        keypad.setShown(here && keypadOpen)
        elsewhere.setShown(!ringing && !here)
        endButton.setShown(!ringing)
        elsewhereText.setTextIfChanged("Görüşme $place sürüyor. Sesi bu telefona alabilirsin.")

        if (here && audio != null) {
            muteButton.setActive(audio.muted)
            muteButton.setText(if (audio.muted) "Sessizde" else "Sessiz")
            keypadButton.setActive(keypadOpen)
            val route = audio.route
            routeButton.setActive(route == CallAudio.ROUTE_SPEAKER)
            routeButton.setIcon(when {
                audio.isBluetooth -> R.drawable.ic_headset
                route == CallAudio.ROUTE_SPEAKER -> R.drawable.ic_speaker
                else -> R.drawable.ic_phone
            })
            routeButton.setText(when (route) {
                CallAudio.ROUTE_SPEAKER -> "Hoparlör"
                CallAudio.ROUTE_EARPIECE, "—", CallAudio.ROUTE_SYSTEM -> "Ahize"
                else -> route.take(12)
            })
        }
        renderQuality(here, audio)

        val routeMsg = when {
            BridgeClientService.audioError != null -> BridgeClientService.audioError!!
            here && !BridgeClientService.audioActive && !call.test -> "Ses telefona aktarılıyor…"
            else -> ""
        }
        routeText.setTextIfChanged(routeMsg)
        routeText.setShown(routeMsg.isNotEmpty())

        val err = audio?.micError?.takeIf { here }
            ?: BridgeClientService.lastResult
            ?: if (!BridgeClientService.isConnected) "Tablete bağlı değil, yeniden bağlanıyor…" else null
        error.setTextIfChanged(err ?: "")
        error.setShown(err != null)
    }

    /** Saniyede gelen ve atlanan paketlerden kaba bir bağlantı kalitesi (beklenen: 50 paket/sn). */
    private fun renderQuality(here: Boolean, audio: CallAudio?) {
        val now = System.currentTimeMillis()
        if (!here || audio == null || !BridgeClientService.audioActive) {
            quality.setShown(false)
            lastPackets = -1
            return
        }
        if (lastPackets < 0) {
            lastPackets = audio.packets
            lastDropped = audio.dropped
            lastTick = now
            return
        }
        if (now - lastTick < 1500) return
        val secs = (now - lastTick) / 1000.0
        val rate = (audio.packets - lastPackets) / secs
        val drops = (audio.dropped - lastDropped) / secs
        lastPackets = audio.packets
        lastDropped = audio.dropped
        lastTick = now
        val (text, color) = when {
            rate < 25 -> "● Bağlantı zayıf" to c.red
            rate < 42 || drops > 3 -> "● Bağlantı orta" to c.orange
            else -> "● Bağlantı iyi" to c.green
        }
        quality.setTextIfChanged(text)
        quality.setTextColor(color)
        quality.setShown(true)
    }

    private fun placeOf(call: BridgeClientService.Call) = when (val who = call.audioTo ?: call.answeredBy) {
        null, CallHostService.TABLET -> "tablette"
        else -> "$who cihazında"
    }

    private fun send(cmd: String) {
        BridgeClientService.lastResult = null
        BridgeClientService.command(cmd)
    }

    private fun chooseRoute() {
        val audio = BridgeClientService.audio ?: return
        val routes = audio.routes()
        if (routes.size <= 2) {
            audio.setSpeaker(audio.route != CallAudio.ROUTE_SPEAKER)
            render()
            return
        }
        val labels = routes.map {
            when (it) {
                CallAudio.ROUTE_EARPIECE -> "Ahize"
                CallAudio.ROUTE_SPEAKER -> "Hoparlör"
                else -> it
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Ses yolu")
            .setSingleChoiceItems(labels.toTypedArray(), routes.indexOf(audio.route)) { d, i ->
                audio.selectRoute(routes[i])
                d.dismiss()
                render()
            }
            .show()
    }

    private fun press(key: Char) {
        dtmf.append(key)
        BridgeClientService.audio?.dtmf(key)
        render()
    }

    // ---- arayüz ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(40))
            setBackgroundColor(c.background)
        }
        statusChip = chip("", c.text, c.card)
        root.addView(statusChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(28)
        })
        avatarView = avatar(null, 112)
        root.addView(avatarView, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(20) })
        title = label("", 30f, c.text, bold = true).apply { gravity = Gravity.CENTER }
        root.addView(title, matchWidth(bottom = 4))
        number = label("", 17f, c.secondary).apply { gravity = Gravity.CENTER }
        root.addView(number, matchWidth(bottom = 6))
        dtmfText = label("", 22f, c.text).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            visibility = View.GONE
        }
        root.addView(dtmfText, matchWidth(bottom = 4))
        quality = label("", 13f, c.green).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(quality, matchWidth())

        root.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        error = label("", 14f, c.red).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(error, matchWidth(bottom = 12))
        routeText = label("", 13f, c.secondary).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(routeText, matchWidth(bottom = 16))

        ringingRow = row().apply {
            addView(RoundButton(context, c, R.drawable.ic_call_end, "Reddet", bg = c.red, fg = c.onAccent, sizeDp = 76) { send("end") }, cell())
            addView(RoundButton(context, c, R.drawable.ic_speaker, "Sessiz", sizeDp = 76) { send("silence") }, cell())
            addView(RoundButton(context, c, R.drawable.ic_call, "Yanıtla", bg = c.green, fg = c.onAccent, sizeDp = 76) { send("accept") }, cell())
        }
        root.addView(ringingRow, matchWidth(bottom = 12))

        muteButton = RoundButton(this, c, R.drawable.ic_mic_off, "Sessiz") {
            BridgeClientService.audio?.let { it.muted = !it.muted }
            render()
        }
        keypadButton = RoundButton(this, c, R.drawable.ic_dialpad, "Tuşlar") {
            keypadOpen = true
            render()
        }
        routeButton = RoundButton(this, c, R.drawable.ic_phone, "Ahize") { chooseRoute() }
        releaseButton = RoundButton(this, c, R.drawable.ic_tablet, "Tablete bırak") { send("audio_off") }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row().apply {
                addView(muteButton, cell())
                addView(keypadButton, cell())
                addView(routeButton, cell())
            }, matchWidth(bottom = 18))
            addView(row().apply { addView(releaseButton, cell()) }, matchWidth())
        }
        root.addView(controls, matchWidth(bottom = 24))

        keypad = buildKeypad()
        root.addView(keypad, matchWidth(bottom = 16))

        elsewhereText = label("", 15f, c.secondary).apply { gravity = Gravity.CENTER }
        elsewhere = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(elsewhereText, matchWidth(bottom = 16))
            addView(label("Bu cihaza al", 17f, c.onAccent, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(c.green, 14f)
                setOnClickListener { send("audio_on") }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
        }
        root.addView(elsewhere, matchWidth(bottom = 24))

        endButton = RoundButton(this, c, R.drawable.ic_call_end, "Kapat", bg = c.red, fg = c.onAccent, sizeDp = 76) { send("end") }
        root.addView(endButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun buildKeypad() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        listOf("123", "456", "789", "*0#").forEach { keys ->
            addView(row().apply {
                keys.forEach { k ->
                    addView(label(k.toString(), 26f, c.text).apply {
                        gravity = Gravity.CENTER
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(c.card)
                        }
                        setOnClickListener { press(k) }
                    }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(12); marginEnd = dp(12) })
                }
            }, matchWidth(bottom = 10))
        }
        addView(label("Tuş takımını gizle", 15f, c.green).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { keypadOpen = false; render() }
        }, matchWidth())
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun cell() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        @Volatile var visible = false
            private set
    }
}
