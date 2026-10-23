package com.serhanensar.callbridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Çağrı köprüsü kurulumu: bu cihazın rolü, PIN, izinler ve aç/kapat. */
class BridgeActivity : Activity() {

    private class Perm(val title: String, val granted: () -> Boolean, val request: () -> Unit) {
        lateinit var view: TextView
    }

    private lateinit var c: Palette
    private lateinit var hostRole: TextView
    private lateinit var clientRole: TextView
    private lateinit var hostPanel: LinearLayout
    private lateinit var clientPanel: LinearLayout

    private lateinit var pinText: TextView
    private lateinit var hostToggle: TextView
    private lateinit var hostStatus: TextView

    private lateinit var pinInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var clientToggle: TextView
    private lateinit var clientStatus: TextView
    private lateinit var testButton: TextView

    private val perms = mutableListOf<Perm>()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = setupWindow()
        if (Bridge.pin(this).isEmpty() && Bridge.role(this) != Bridge.ROLE_CLIENT) {
            Bridge.prefs(this).edit().putString("pin", Bridge.newPin()).apply()
        }
        setContentView(buildUi())
        if (intent.getBooleanExtra(EXTRA_START, false)) startFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) startFromIntent()
    }

    /** Kayıtlı role göre köprüyü başlatır (kurulum betiği bunu kullanır). */
    private fun startFromIntent() {
        when (Bridge.role(this)) {
            Bridge.ROLE_HOST -> if (!CallHostService.isRunning) toggleHost()
            Bridge.ROLE_CLIENT -> if (!BridgeClientService.isRunning) toggleClient()
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.post(ticker)
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- davranış ----

    private fun setRole(role: String) {
        if (role == Bridge.role(this)) return
        if (CallHostService.isRunning) CallHostService.stop(this)
        if (BridgeClientService.isRunning) BridgeClientService.stop(this)
        Bridge.prefs(this).edit().putString("role", role).putBoolean("enabled", false).apply()
        if (role == Bridge.ROLE_HOST && Bridge.pin(this).length != 6) {
            Bridge.prefs(this).edit().putString("pin", Bridge.newPin()).apply()
        }
        render()
    }

    private fun toggleHost() {
        if (CallHostService.isRunning) {
            CallHostService.stop(this)
            Bridge.prefs(this).edit().putBoolean("enabled", false).apply()
        } else {
            val missing = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
                .filter { !granted(it) }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 1)
                return
            }
            Bridge.prefs(this).edit().putBoolean("enabled", true).apply()
            CallHostService.start(this)
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun toggleClient() {
        if (BridgeClientService.isRunning) {
            BridgeClientService.stop(this)
            Bridge.prefs(this).edit().putBoolean("enabled", false).apply()
        } else {
            val pin = pinInput.text.toString().trim()
            if (pin.length != 6) {
                clientStatus.text = "Tablette görünen 6 haneli PIN'i gir"
                return
            }
            Bridge.prefs(this).edit()
                .putString("pin", pin)
                .putString("host", hostInput.text.toString().trim())
                .putBoolean("enabled", true)
                .apply()
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            BridgeClientService.start(this)
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun newPin() {
        Bridge.prefs(this).edit().putString("pin", Bridge.newPin()).apply()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        val role = Bridge.role(this)
        roleStyle(hostRole, role == Bridge.ROLE_HOST)
        roleStyle(clientRole, role == Bridge.ROLE_CLIENT)
        hostPanel.setShown(role == Bridge.ROLE_HOST)
        clientPanel.setShown(role == Bridge.ROLE_CLIENT)

        if (role == Bridge.ROLE_HOST) {
            pinText.setTextIfChanged(Bridge.pin(this).chunked(3).joinToString(" "))
            val on = CallHostService.isRunning
            hostToggle.setTextIfChanged(if (on) "Durdur" else "Başlat")
            hostToggle.setFill(if (on) c.red else c.green, 14f)
            hostStatus.setTextIfChanged(when {
                on && CallHostService.peerCount > 0 -> "Açık · ${CallHostService.peerCount} telefon bağlı"
                on -> "Açık · telefon bekleniyor"
                CallHostService.lastError != null -> "Kapalı: ${CallHostService.lastError}"
                else -> "Kapalı"
            })
        }
        if (role == Bridge.ROLE_CLIENT) {
            val on = BridgeClientService.isRunning
            clientToggle.setTextIfChanged(if (on) "Durdur" else "Başlat")
            clientToggle.setFill(if (on) c.red else c.green, 14f)
            val gw = BridgeClientService.gateway(this)?.hostAddress
            hostInput.setHintIfChanged(if (gw != null) "Otomatik: $gw" else "Otomatik (hotspot'a bağlı değil)")
            clientStatus.setTextIfChanged(BridgeClientService.status)
            testButton.alpha = if (BridgeClientService.isConnected) 1f else 0.4f
        }

        perms.forEach { p ->
            val ok = p.granted()
            p.view.setTextIfChanged((if (ok) "✓  " else "○  ") + p.title + if (ok) "" else "  ›")
            p.view.setTextColor(if (ok) c.secondary else c.text)
        }
    }

    // ---- izinler ----

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun runtime(title: String, vararg names: String) = Perm(
        title,
        granted = { names.all { granted(it) } },
        request = { requestPermissions(names.filter { !granted(it) }.toTypedArray(), 3) },
    )

    private fun notificationPerm() = Perm(
        "Bildirimler",
        granted = { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) },
        request = { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3) },
    )

    private fun batteryPerm() = Perm(
        "Pil kısıtlaması yok (bağlantı uykuda kopmasın)",
        granted = { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
        request = {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        },
    )

    private fun hostPerms() = listOf(
        runtime("Telefon durumu (zorunlu)", Manifest.permission.READ_PHONE_STATE),
        runtime("Çağrı yanıtlama / kapatma (zorunlu)", Manifest.permission.ANSWER_PHONE_CALLS),
        runtime("Arama kaydı (arayanın numarası)", Manifest.permission.READ_CALL_LOG),
        runtime("Rehber (arayanın adı)", Manifest.permission.READ_CONTACTS),
        Perm(
            "Rahatsız Etmeyin erişimi (tablet zilini susturmak)",
            granted = { getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            request = { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        ),
        notificationPerm(),
        batteryPerm(),
    )

    private fun clientPerms() = buildList {
        add(notificationPerm())
        if (Build.VERSION.SDK_INT >= 34) {
            add(
                Perm(
                    "Tam ekran çağrı bildirimi",
                    granted = { getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
                    request = {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                    },
                )
            )
        }
        add(batteryPerm())
    }

    // ---- arayüz ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(label("CallBridge", 24f, c.text, bold = true), matchWidth(bottom = 4))
        root.addView(
            label("SIM'li tablete gelen çağrıyı bu hotspot ağı üzerinden telefonda göster ve yanıtla.", 14f, c.secondary),
            matchWidth(bottom = 18),
        )

        root.addView(label("Bu cihaz", 13f, c.secondary), matchWidth(bottom = 6))
        val roles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hostRole = roleButton("Tablet\nSIM burada") { setRole(Bridge.ROLE_HOST) }
        clientRole = roleButton("Telefon\nburadan yanıtla") { setRole(Bridge.ROLE_CLIENT) }
        roles.addView(hostRole, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(6) })
        roles.addView(clientRole, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(6) })
        root.addView(roles, matchWidth(bottom = 20))

        hostPanel = buildHostPanel()
        clientPanel = buildClientPanel()
        root.addView(hostPanel, matchWidth())
        root.addView(clientPanel, matchWidth())
        return insetScroll(root)
    }

    private fun buildHostPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val pinCard = card()
        pinCard.gravity = Gravity.CENTER_HORIZONTAL
        pinCard.addView(label("Telefonda gireceğin PIN", 13f, c.secondary).apply { gravity = Gravity.CENTER }, matchWidth(bottom = 4))
        pinText = label("", 40f, c.text, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }
        pinCard.addView(pinText, matchWidth(bottom = 4))
        pinCard.addView(
            label("Yeni PIN", 14f, c.green).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { newPin() }
            },
            matchWidth(),
        )
        addView(pinCard, matchWidth(bottom = 14))

        hostToggle = bigButton { toggleHost() }
        addView(hostToggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(8) })
        hostStatus = label("", 14f, c.secondary).apply { gravity = Gravity.CENTER }
        addView(hostStatus, matchWidth(bottom = 18))

        val options = card()
        val silence = Switch(this@BridgeActivity).apply {
            text = "Telefon bağlıyken tablet zilini sustur"
            setTextColor(c.text)
            textSize = 15f
            isChecked = Bridge.autoSilence(this@BridgeActivity)
            setOnCheckedChangeListener { _, on -> Bridge.prefs(this@BridgeActivity).edit().putBoolean("auto_silence", on).apply() }
        }
        options.addView(silence, matchWidth())
        addView(options, matchWidth(bottom = 18))

        addView(permCard(hostPerms()), matchWidth(bottom = 18))
        addView(
            label(
                "1. Hotspot'u aç ve telefonu bu tablete bağla.\n" +
                    "2. Başlat'a bas, izinleri ver.\n" +
                    "3. Telefonda CallBridge'i aç, Telefon'u seç ve bu PIN'i gir.\n" +
                    "4. Konuşmak için tablete bir Bluetooth kulaklık bağla.\n\n" +
                    "Android, root olmadan çağrının sesini başka bir uygulamaya vermiyor. " +
                    "Telefon çağrıyı gösterir ve yönetir; ses tabletten ya da ona bağlı kulaklıktan gelir.",
                13f, c.secondary,
            ),
            matchWidth(),
        )
    }

    private fun buildClientPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val inputs = card()
        inputs.addView(label("Tabletteki PIN", 13f, c.secondary), matchWidth(bottom = 4))
        pinInput = input(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).apply {
            setText(if (Bridge.role(this@BridgeActivity) == Bridge.ROLE_CLIENT) Bridge.pin(this@BridgeActivity) else "")
            hint = "6 hane"
        }
        inputs.addView(pinInput, matchWidth(bottom = 12))
        inputs.addView(label("Tablet adresi", 13f, c.secondary), matchWidth(bottom = 4))
        hostInput = input(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(Bridge.manualHost(this@BridgeActivity))
        }
        inputs.addView(hostInput, matchWidth())
        addView(inputs, matchWidth(bottom = 14))

        clientToggle = bigButton { toggleClient() }
        addView(clientToggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(8) })
        clientStatus = label("", 14f, c.secondary).apply { gravity = Gravity.CENTER }
        addView(clientStatus, matchWidth(bottom = 14))

        testButton = label("Test çağrısı", 17f, c.text).apply {
            gravity = Gravity.CENTER
            background = rounded(c.neutral, 12f)
            setOnClickListener { if (BridgeClientService.isConnected) BridgeClientService.command("test") }
        }
        addView(testButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(18) })

        addView(permCard(clientPerms()), matchWidth(bottom = 18))
        addView(
            label(
                "Tablet adresi boş kalırsa bağlı olduğun hotspot'un ağ geçidi kullanılır. " +
                    "Hotspot TTL açıksa bu uygulamayı VPN'in dışında tutar.\n\n" +
                    "Test çağrısı, gerçek bir arama yapmadan iki cihaz arasındaki akışı dener.",
                13f, c.secondary,
            ),
            matchWidth(),
        )
    }

    private fun permCard(list: List<Perm>) = card().apply {
        addView(label("İzinler", 13f, c.secondary), matchWidth(bottom = 4))
        list.forEach { p ->
            p.view = label("", 15f, c.text).apply {
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { if (!p.granted()) p.request() }
            }
            addView(p.view, matchWidth())
            perms.add(p)
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, 14f)
        setPadding(dp(18), dp(12), dp(18), dp(12))
    }

    private fun input(type: Int) = EditText(this).apply {
        inputType = type
        textSize = 17f
        setTextColor(c.text)
        setHintTextColor(c.secondary)
        background = rounded(c.neutral, 10f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = true
    }

    private fun bigButton(onClick: () -> Unit) = label("", 19f, 0xFFFFFFFF.toInt(), bold = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun roleButton(text: String, onClick: () -> Unit) = label(text, 14f, c.text, bold = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun roleStyle(v: TextView, selected: Boolean) {
        v.setFill(if (selected) c.green else c.card, 12f)
        v.setTextColor(if (selected) 0xFFFFFFFF.toInt() else c.text)
    }

    companion object {
        /** Ekran öndeyken sistem tam ekran bildirimi açmaz; çağrı ekranını servis kendisi açar. */
        @Volatile var visible = false
            private set

        const val EXTRA_START = "start"
    }
}
