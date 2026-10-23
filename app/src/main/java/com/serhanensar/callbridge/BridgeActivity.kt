package com.serhanensar.callbridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Ana ekran. Rol seçilmemişse karşılama; seçildiyse role göre pano:
 *   telefon: durum (tablet, ses aktarıcısı, arama hesabı), Ara, son aramalar, kurulum
 *   tablet: durum, PIN, bağlı cihazlar, test çağrısı, kurulum
 * Kurulum bölümü izinleri ve bağlantı ayarlarını toplar; eksik varken açık başlar.
 */
class BridgeActivity : Activity() {

    private class Perm(val title: String, val granted: () -> Boolean, val request: () -> Unit) {
        lateinit var view: TextView
    }

    /** Durum kartındaki bir satır: simge (✓ ya da uyarı), başlık, açıklama ve isteğe bağlı eylem. */
    private inner class StatusRow(title: String) {
        val view = LinearLayout(this@BridgeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        private val iconView = icon(R.drawable.ic_check, c.green, 22)
        private val detail = label("", 13f, c.secondary)
        private val action = label("", 14f, c.green, bold = true).apply { setPadding(dp(12), dp(6), 0, dp(6)) }
        private var state: Boolean? = null
        private var onAction: (() -> Unit)? = null

        init {
            view.addView(iconView, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(this@BridgeActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(title, 15f, c.text, bold = true))
            texts.addView(detail)
            view.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            view.addView(action)
            action.setOnClickListener { onAction?.invoke() }
        }

        /** ok: true ✓, false uyarı, null bilinmiyor. */
        fun update(ok: Boolean?, text: String, actionText: String? = null, onAction: (() -> Unit)? = null) {
            if (state != ok || iconView.tag == null) {
                state = ok
                iconView.tag = ok
                iconView.setImageResource(if (ok == false) R.drawable.ic_warning else R.drawable.ic_check)
                iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                    when (ok) { true -> c.green; false -> c.orange; null -> c.secondary }
                )
            }
            detail.setTextIfChanged(text)
            action.setTextIfChanged(actionText ?: "")
            action.setShown(actionText != null)
            this.onAction = onAction
        }
    }

    private lateinit var c: Palette
    private val perms = mutableListOf<Perm>()
    private var renderRole: (() -> Unit)? = null
    private var setupOpen: Boolean? = null

    // Telefon panosu
    private var pinInput: EditText? = null
    private var hostInput: EditText? = null

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
        rebuild()
        syncDialerLauncher()
        if (intent.getBooleanExtra(EXTRA_START, false)) startFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) startFromIntent()
    }

    /** Başlatıcıdaki "Ara" simgesi yalnızca telefon rolünde görünsün. */
    private fun syncDialerLauncher() {
        val alias = ComponentName(this, "$packageName.DialerLauncher")
        val want = if (Bridge.role(this) == Bridge.ROLE_CLIENT) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (packageManager.getComponentEnabledSetting(alias) != want) {
            packageManager.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP)
        }
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
        if (Bridge.role(this) == Bridge.ROLE_CLIENT) BridgeClientService.refreshHistory()
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
        syncDialerLauncher()
        if (role != Bridge.ROLE_CLIENT) try { PhoneAccounts.unregister(this) } catch (_: Exception) {}
        setupOpen = null
        rebuild()
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
            val pin = pinInput?.text?.toString()?.trim() ?: Bridge.pin(this)
            if (pin.length != 6) {
                setupOpen = true
                rebuild()
                pinInput?.error = "Tablette görünen 6 haneli PIN"
                return
            }
            Bridge.prefs(this).edit()
                .putString("pin", pin)
                .putString("host", hostInput?.text?.toString()?.trim() ?: Bridge.manualHost(this))
                .putBoolean("enabled", true)
                .apply()
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            BridgeClientService.start(this)
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun sendHostTest() {
        val host = CallHostService.instance ?: return
        if (CallHostService.peerCount == 0) return
        thread(isDaemon = true) { host.startTest() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        renderRole?.invoke()
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

    private fun accountPerm() = Perm(
        "Arama hesabı: Samsung ekranında çağrılar, rehberden arama",
        granted = { PhoneAccounts.isEnabled(this) },
        request = { startActivity(PhoneAccounts.settingsIntent()) },
    )

    private fun hostPerms() = listOf(
        runtime("Telefon durumu (zorunlu)", Manifest.permission.READ_PHONE_STATE),
        runtime("Çağrı yanıtlama / kapatma (zorunlu)", Manifest.permission.ANSWER_PHONE_CALLS),
        runtime("Arama yapma (telefondan aramak için)", Manifest.permission.CALL_PHONE),
        runtime("Arama kaydı (numara ve son aramalar)", Manifest.permission.READ_CALL_LOG),
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
        add(runtime("Mikrofon (telefondan konuşmak)", Manifest.permission.RECORD_AUDIO))
        add(runtime("Rehber (arama ekranında kişiler)", Manifest.permission.READ_CONTACTS))
        add(runtime("Telefon durumu (arama hesabını görmek için)", Manifest.permission.READ_PHONE_STATE))
        add(accountPerm())
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

    private fun rebuild() {
        perms.clear()
        renderRole = null
        pinInput = null
        hostInput = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        when (Bridge.role(this)) {
            Bridge.ROLE_HOST -> buildHost(root)
            Bridge.ROLE_CLIENT -> buildClient(root)
            else -> buildWelcome(root)
        }
        setContentView(insetScroll(root))
        render()
    }

    private fun header(root: LinearLayout, subtitle: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label("CallBridge", 24f, c.text, bold = true))
        texts.addView(label(subtitle, 13f, c.secondary))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chip = chip("", c.green, c.card)
        row.addView(chip)
        root.addView(row, matchWidth(bottom = 18))
        return chip
    }

    private fun setChip(chip: TextView, text: String, color: Int) {
        chip.setTextIfChanged(text)
        chip.setTextColor(color)
        chip.setFill(withAlpha(color, 0x22), 14f)
    }

    private fun buildWelcome(root: LinearLayout) {
        header(root, "Çağrıları cihazlar arasında taşır").setShown(false)
        root.addView(label("Bu cihaz ne olacak?", 20f, c.text, bold = true), matchWidth(bottom = 6))
        root.addView(
            label("CallBridge iki cihaza kurulur. SIM'li cihazın çağrıları, aynı ağdaki diğer cihazda çalar ve oradan konuşulur.", 14f, c.secondary),
            matchWidth(bottom = 18),
        )
        roleCard(root, R.drawable.ic_tablet, "Tablet: SIM burada", "Çağrılar bu cihaza gelir; köprüyü bu cihaz açar.") { setRole(Bridge.ROLE_HOST) }
        roleCard(root, R.drawable.ic_phone, "Telefon: buradan konuş", "Tabletin çağrıları bu cihazda çalar; arama da buradan yapılır.") { setRole(Bridge.ROLE_CLIENT) }
    }

    private fun roleCard(root: LinearLayout, iconRes: Int, title: String, text: String, onClick: () -> Unit) {
        val card = card(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setOnClickListener { onClick() }
        }
        card.addView(icon(iconRes, c.green, 34), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 17f, c.text, bold = true))
        texts.addView(label(text, 13f, c.secondary))
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(card, matchWidth(bottom = 12))
    }

    // ---- telefon ----

    private fun buildClient(root: LinearLayout) {
        val chip = header(root, "Telefon · tabletin çağrıları burada")

        val status = card(c)
        val linkRow = StatusRow("Tablet")
        val relayRow = StatusRow("Ses")
        val accountRow = StatusRow("Arama hesabı")
        listOf(linkRow, relayRow, accountRow).forEach { status.addView(it.view, matchWidth()) }
        root.addView(status, matchWidth(bottom = 14))

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = rounded(c.green, 16f)
                addView(icon(R.drawable.ic_call, c.onAccent, 22), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
                addView(label("Ara", 18f, c.onAccent, bold = true))
                setOnClickListener { startActivity(Intent(this@BridgeActivity, DialerActivity::class.java)) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(18) },
        )

        root.addView(sectionTitle("Son aramalar", c), matchWidth())
        val recents = card(c).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(recents, matchWidth(bottom = 18))
        var recentsKey = ""

        val setup = setupSection(root)
        val pinField = field(setup.body, "Tabletteki PIN", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, Bridge.pin(this), "6 hane")
        val hostField = field(setup.body, "Tablet adresi", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, Bridge.manualHost(this), "Otomatik")
        pinInput = pinField
        hostInput = hostField
        val toggle = bigButton(setup.body) { toggleClient() }
        val test = secondaryButton(setup.body, "Test çağrısı") { if (BridgeClientService.isConnected) BridgeClientService.command("test") }
        addPerms(setup.body, setup.permsBox, clientPerms())
        roleFooter(root, "Telefon")

        renderRole = {
            val running = BridgeClientService.isRunning
            val connected = BridgeClientService.isConnected
            val call = BridgeClientService.call
            when {
                call != null -> setChip(chip, "Çağrıda", c.green)
                connected -> setChip(chip, "Hazır", c.green)
                running -> setChip(chip, "Bağlanıyor", c.orange)
                else -> setChip(chip, "Kapalı", c.red)
            }
            when {
                connected -> linkRow.update(true, BridgeClientService.status.removePrefix("Bağlı: ").let { "Bağlı · $it" })
                running -> linkRow.update(false, BridgeClientService.status)
                else -> linkRow.update(false, "Köprü kapalı", "Başlat") { toggleClient() }
            }
            when (BridgeClientService.relayReady.takeIf { connected }) {
                true -> relayRow.update(true, "Konuşma bu telefondan yapılır")
                false -> relayRow.update(false, "Tablette ses aktarıcısı kapalı; ses tablette kalır. Tableti Mac'e bağlayıp ./setup.sh relay")
                null -> relayRow.update(null, "Tablete bağlanınca görünür")
            }
            if (PhoneAccounts.isEnabled(this)) accountRow.update(true, "Çağrılar Samsung ekranında; rehberden CallBridge ile aranır")
            else accountRow.update(false, "Kapalı: çağrılar CallBridge ekranında", "Aç") { startActivity(PhoneAccounts.settingsIntent()) }

            val gw = BridgeClientService.gateway(this)?.hostAddress
            hostField.setHintIfChanged(if (gw != null) "Otomatik: $gw" else "Otomatik (hotspot'a bağlı değil)")
            toggle.setTextIfChanged(if (running) "Durdur" else "Başlat")
            toggle.setFill(if (running) c.red else c.green, 14f)
            test.alpha = if (connected) 1f else 0.4f

            val history = BridgeClientService.history.take(3)
            val key = history.joinToString { "${it.date}${it.type}" } + connected
            if (key != recentsKey) {
                recentsKey = key
                recents.removeAllViews()
                if (history.isEmpty()) {
                    recents.addView(label(if (connected) "Henüz arama yok" else "Tablete bağlanınca görünür", 14f, c.secondary).apply {
                        setPadding(dp(4), dp(10), 0, dp(10))
                    }, matchWidth())
                } else history.forEach { h -> recents.addView(recentRow(h), matchWidth()) }
            }
            setup.update(perms.count { it.granted() } + (if (running) 1 else 0), perms.size + 1)
        }
    }

    private fun recentRow(h: BridgeClientService.HistoryEntry) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener {
            if (BridgeClientService.dial(h.number, h.name)) {
                startActivity(Intent(this@BridgeActivity, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        addView(avatar(h.name, 36), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(h.title, 15f, if (h.type == "missed") c.red else c.text, bold = true).apply { isSingleLine = true })
        val (iconRes, color) = when (h.type) {
            "out" -> R.drawable.ic_call_out to c.secondary
            "missed" -> R.drawable.ic_call_missed to c.red
            else -> R.drawable.ic_call_in to c.green
        }
        val sub = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sub.addView(icon(iconRes, color, 13), LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(5) })
        sub.addView(label(whenLabel(h.date), 12f, c.secondary))
        texts.addView(sub)
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_call, c.green, 20), LinearLayout.LayoutParams(dp(20), dp(20)))
    }

    // ---- tablet ----

    private fun buildHost(root: LinearLayout) {
        val chip = header(root, "Tablet · SIM burada")

        val status = card(c)
        val bridgeRow = StatusRow("Köprü")
        val relayRow = StatusRow("Ses aktarıcısı")
        status.addView(bridgeRow.view, matchWidth())
        status.addView(relayRow.view, matchWidth())
        root.addView(status, matchWidth(bottom = 14))

        val pinCard = card(c).apply { gravity = Gravity.CENTER_HORIZONTAL }
        pinCard.addView(label("Cihazlarda gireceğin PIN", 13f, c.secondary).apply { gravity = Gravity.CENTER }, matchWidth(bottom = 2))
        val pinText = label("", 42f, c.text, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }
        pinCard.addView(pinText, matchWidth(bottom = 2))
        pinCard.addView(label("Yeni PIN", 14f, c.green).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                Bridge.prefs(this@BridgeActivity).edit().putString("pin", Bridge.newPin()).apply()
                render()
            }
        }, matchWidth())
        root.addView(pinCard, matchWidth(bottom = 18))

        root.addView(sectionTitle("Bağlı cihazlar", c), matchWidth())
        val devices = card(c).apply { setPadding(dp(16), dp(6), dp(16), dp(6)) }
        root.addView(devices, matchWidth(bottom = 14))
        var devicesKey = ""

        val test = secondaryButton(root, "Cihazlara test çağrısı gönder") { sendHostTest() }

        val options = card(c)
        options.addView(Switch(this).apply {
            text = "Cihaz bağlıyken tablet zilini sustur"
            setTextColor(c.text)
            textSize = 15f
            isChecked = Bridge.autoSilence(this@BridgeActivity)
            setOnCheckedChangeListener { _, on -> Bridge.prefs(this@BridgeActivity).edit().putBoolean("auto_silence", on).apply() }
        }, matchWidth())
        root.addView(options, matchWidth(bottom = 18))

        val setup = setupSection(root)
        val toggle = bigButton(setup.body) { toggleHost() }
        addPerms(setup.body, setup.permsBox, hostPerms())
        setup.body.addView(
            label("Ses aktarıcısı tablet her açıldığında bir kez başlatılır: tableti USB ile Mac'e bağla, CallBridge klasöründe ./setup.sh relay çalıştır.", 13f, c.secondary),
            matchWidth(),
        )
        roleFooter(root, "Tablet")

        renderRole = {
            val on = CallHostService.isRunning
            val list = CallHostService.devices
            when {
                !on -> setChip(chip, "Kapalı", c.red)
                list.isEmpty() -> setChip(chip, "Bekliyor", c.orange)
                else -> setChip(chip, "Hazır", c.green)
            }
            when {
                on -> bridgeRow.update(true, if (list.isEmpty()) "Açık · cihaz bekleniyor" else "Açık · ${list.size} cihaz bağlı", "Durdur") { toggleHost() }
                CallHostService.lastError != null -> bridgeRow.update(false, CallHostService.lastError!!, "Başlat") { toggleHost() }
                else -> bridgeRow.update(false, "Kapalı", "Başlat") { toggleHost() }
            }
            when (CallHostService.relayReady.takeIf { on }) {
                true -> relayRow.update(true, "Hazır: konuşma telefondan ya da Mac'ten yapılır")
                false -> relayRow.update(false, "Kapalı: ses tablette kalır. Tableti Mac'e bağlayıp ./setup.sh relay")
                null -> relayRow.update(null, "Köprü açılınca denetlenir")
            }
            pinText.setTextIfChanged(Bridge.pin(this).chunked(3).joinToString(" "))
            toggle.setTextIfChanged(if (on) "Köprüyü durdur" else "Köprüyü başlat")
            toggle.setFill(if (on) c.red else c.green, 14f)
            test.alpha = if (list.isNotEmpty()) 1f else 0.4f

            val key = list.joinToString { "${it.first}${it.second}${it.third}" } + on
            if (key != devicesKey) {
                devicesKey = key
                devices.removeAllViews()
                if (list.isEmpty()) {
                    devices.addView(label(if (on) "Henüz bağlanan yok. Telefonu ya da Mac'i bu tabletin hotspot'una bağla ve PIN'i gir." else "Köprü kapalı", 14f, c.secondary).apply {
                        setPadding(0, dp(10), 0, dp(10))
                    }, matchWidth())
                } else list.forEach { (name, kind, audio) -> devices.addView(deviceRow(name, kind, audio), matchWidth()) }
            }
            setup.update(perms.count { it.granted() } + (if (on) 1 else 0), perms.size + 1)
        }
    }

    private fun deviceRow(name: String, kind: String, audio: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(icon(if (kind == CallHostService.KIND_MAC) R.drawable.ic_desktop else R.drawable.ic_phone, c.text, 24),
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
        addView(label(name, 16f, c.text, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (audio) addView(chip("Ses burada", c.green, withAlpha(c.green, 0x22)))
    }

    // ---- ortak parçalar ----

    private inner class Setup(val body: LinearLayout, val permsBox: LinearLayout, private val title: TextView, private val arrow: ImageView) {
        fun update(done: Int, total: Int) {
            title.setTextIfChanged("Kurulum · $done/$total")
            title.setTextColor(if (done < total) c.orange else c.text)
            if (setupOpen == null) setupOpen = done < total // eksik varken açık başlar
            body.setShown(setupOpen == true)
            arrow.rotation = if (setupOpen == true) 180f else 0f
        }
    }

    private fun setupSection(root: LinearLayout): Setup {
        val card = card(c)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val title = label("Kurulum", 16f, c.text, bold = true)
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val arrow = icon(R.drawable.ic_expand, c.secondary, 24)
        head.addView(arrow)
        card.addView(head, matchWidth())
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(body, matchWidth())
        head.setOnClickListener {
            setupOpen = setupOpen != true
            render()
        }
        root.addView(card, matchWidth(bottom = 14))
        val permsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return Setup(body, permsBox, title, arrow)
    }

    private fun addPerms(body: LinearLayout, box: LinearLayout, list: List<Perm>) {
        body.addView(sectionTitle("İzinler", c).apply { setPadding(0, dp(10), 0, dp(4)) }, matchWidth())
        list.forEach { p ->
            p.view = label("", 15f, c.text).apply {
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { if (!p.granted()) p.request() }
            }
            box.addView(p.view, matchWidth())
            perms.add(p)
        }
        body.addView(box, matchWidth(bottom = 8))
    }

    private fun field(parent: LinearLayout, title: String, type: Int, value: String, hint: String): EditText {
        parent.addView(label(title, 13f, c.secondary), matchWidth(bottom = 4))
        val e = EditText(this).apply {
            inputType = type
            textSize = 16f
            setText(value)
            this.hint = hint
            setTextColor(c.text)
            setHintTextColor(c.secondary)
            background = rounded(c.neutral, 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
        }
        parent.addView(e, matchWidth(bottom = 12))
        return e
    }

    private fun bigButton(parent: LinearLayout, onClick: () -> Unit) = label("", 17f, c.onAccent, bold = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })
    }

    private fun secondaryButton(parent: LinearLayout, text: String, onClick: () -> Unit) = label(text, 16f, c.text).apply {
        gravity = Gravity.CENTER
        background = rounded(c.neutral, 14f)
        setOnClickListener { onClick() }
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(14) })
    }

    private fun roleFooter(root: LinearLayout, current: String) {
        root.addView(label("Bu cihaz: $current · Rolü değiştir", 13f, c.secondary).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                val other = if (Bridge.role(this@BridgeActivity) == Bridge.ROLE_HOST) Bridge.ROLE_CLIENT else Bridge.ROLE_HOST
                android.app.AlertDialog.Builder(this@BridgeActivity)
                    .setTitle("Rolü değiştir")
                    .setMessage(if (other == Bridge.ROLE_HOST) "Bu cihaz tablet (SIM'li cihaz) olacak. Köprü durdurulur." else "Bu cihaz telefon (çağrıların çaldığı cihaz) olacak. Köprü durdurulur.")
                    .setPositiveButton("Değiştir") { _, _ -> setRole(other) }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        }, matchWidth())
    }

    companion object {
        /** Ekran öndeyken sistem tam ekran bildirimi açmaz; çağrı ekranını servis kendisi açar. */
        @Volatile var visible = false
            private set

        const val EXTRA_START = "start"
    }
}
