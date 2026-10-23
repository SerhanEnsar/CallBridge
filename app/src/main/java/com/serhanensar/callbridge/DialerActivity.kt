package com.serhanensar.callbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Telefonda arama ekranı: tuş takımı ve bu telefonun rehberi. Seçilen numarayı tablet arar,
 * ses çağrı ekranındaki gibi telefona gelir. Başka uygulamalardan gelen tel: bağlantıları ve
 * arama istekleri burada açılır; arama yine de kullanıcı Ara'ya basınca başlar.
 */
class DialerActivity : Activity() {

    private class Contact(val name: String, val number: String, val label: String) {
        val digits = number.filter { it.isDigit() }
    }

    private lateinit var c: Palette
    private lateinit var keypadTab: TextView
    private lateinit var contactsTab: TextView
    private lateinit var keypadPane: LinearLayout
    private lateinit var contactsPane: LinearLayout
    private lateinit var numberText: TextView
    private lateinit var matchText: TextView
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private lateinit var banner: TextView

    private var number = StringBuilder()
    private var contacts: List<Contact> = emptyList()
    private var showContacts = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderBanner()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = setupWindow()
        setContentView(buildUi())
        handleIntent(intent)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) loadContacts()
        else requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadContacts()
    }

    /** tel: bağlantısıyla açıldıysa numarayı tuş takımına yazar. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "tel") return
        number = StringBuilder(data.schemeSpecificPart.filter { it.isDigit() || it in "+*#" })
        selectTab(contacts = false)
        renderNumber()
    }

    // ---- arama ----

    private fun call(dialNumber: String, name: String?) {
        if (dialNumber.isBlank()) return
        if (BridgeClientService.dial(dialNumber, name)) {
            startActivity(Intent(this, IncomingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            number.clear()
            renderNumber()
        } else {
            renderBanner()
        }
    }

    private fun press(ch: Char) {
        number.append(ch)
        renderNumber()
    }

    private fun backspace() {
        if (number.isNotEmpty()) number.deleteCharAt(number.length - 1)
        renderNumber()
    }

    // ---- rehber ----

    private fun loadContacts() {
        thread(isDaemon = true) {
            val out = mutableListOf<Contact>()
            val seen = HashSet<String>()
            try {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL,
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val name = cur.getString(0) ?: continue
                        val num = cur.getString(1) ?: continue
                        if (!seen.add(name + "|" + num.filter { it.isDigit() })) continue // aynı numara iki kez
                        val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, cur.getInt(2), cur.getString(3)).toString()
                        out += Contact(name, num, label)
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                contacts = out
                renderList()
                renderNumber()
            }
        }
    }

    private fun renderList() {
        list.removeAllViews()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            list.addView(
                label("Rehberi göstermek için izin ver", 15f, c.green).apply {
                    setPadding(0, dp(14), 0, dp(14))
                    setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) }
                },
                matchWidth(),
            )
            return
        }
        val q = search.text.toString().trim().lowercase()
        val qDigits = q.filter { it.isDigit() }
        val matches = contacts.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || (qDigits.length >= 3 && it.digits.contains(qDigits))
        }.take(200)
        if (matches.isEmpty()) {
            list.addView(label(if (contacts.isEmpty()) "Rehber boş" else "Sonuç yok", 15f, c.secondary).apply {
                setPadding(0, dp(14), 0, dp(14))
            }, matchWidth())
            return
        }
        var lastName: String? = null
        matches.forEach { ct ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { call(ct.number, ct.name) }
            }
            // Aynı kişinin ikinci numarasında ad tekrar yazılmaz
            if (ct.name != lastName) row.addView(label(ct.name, 17f, c.text, bold = true), matchWidth())
            row.addView(label("${ct.label} · ${ct.number}", 14f, c.secondary), matchWidth())
            list.addView(row, matchWidth())
            lastName = ct.name
        }
    }

    // ---- görünüm ----

    private fun selectTab(contacts: Boolean) {
        showContacts = contacts
        keypadPane.setShown(!contacts)
        contactsPane.setShown(contacts)
        keypadTab.setFill(if (!contacts) c.green else c.card, 10f)
        keypadTab.setTextColor(if (!contacts) 0xFFFFFFFF.toInt() else c.text)
        contactsTab.setFill(if (contacts) c.green else c.card, 10f)
        contactsTab.setTextColor(if (contacts) 0xFFFFFFFF.toInt() else c.text)
    }

    private fun renderNumber() {
        numberText.text = number.toString()
        // Yazılan numara rehberdeki biriyse adını göster
        val d = number.filter { it.isDigit() }
        val match = if (d.length >= 7) contacts.firstOrNull { it.digits.endsWith(d.takeLast(10)) || d.endsWith(it.digits.takeLast(10)) } else null
        matchText.text = match?.name ?: ""
    }

    private fun renderBanner() {
        val text = when {
            !BridgeClientService.isRunning -> "Köprü kapalı: CallBridge'i aç ve Başlat'a bas"
            !BridgeClientService.isConnected -> "Tablete bağlı değil"
            BridgeClientService.lastResult != null && BridgeClientService.call == null -> BridgeClientService.lastResult!!
            else -> null
        }
        banner.setTextIfChanged(text ?: "")
        banner.setShown(text != null)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keypadTab = tab("Tuş takımı") { selectTab(contacts = false) }
        contactsTab = tab("Rehber") { selectTab(contacts = true) }
        tabs.addView(keypadTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        tabs.addView(contactsTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(tabs, matchWidth(bottom = 10))

        banner = label("", 14f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            background = rounded(c.red, 10f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(banner, matchWidth(bottom = 10))

        keypadPane = buildKeypad()
        contactsPane = buildContacts()
        root.addView(keypadPane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(contactsPane, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        selectTab(contacts = false)

        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop,
                dp(16) + insets.systemWindowInsetRight, dp(16) + insets.systemWindowInsetBottom)
            insets
        }
        return root
    }

    private fun buildKeypad() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        numberText = label("", 34f, c.text, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        addView(numberText, matchWidth(bottom = 2))
        matchText = label("", 15f, c.secondary).apply { gravity = Gravity.CENTER }
        addView(matchText, matchWidth(bottom = 16))

        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowKeys.forEach { (digit, letters) -> row.addView(key(digit, letters)) }
            addView(row, matchWidth(bottom = 10))
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(View(context), LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(10); marginEnd = dp(10) })
        actions.addView(label("Ara", 17f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.green) }
            setOnClickListener { call(number.toString(), matchText.text.toString().ifEmpty { null }) }
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(10); marginEnd = dp(10) })
        actions.addView(label("⌫", 26f, c.text).apply {
            gravity = Gravity.CENTER
            setOnClickListener { backspace() }
            setOnLongClickListener { number.clear(); renderNumber(); true }
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(10); marginEnd = dp(10) })
        addView(actions, matchWidth(bottom = 8))
    }

    private fun key(digit: String, letters: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.card) }
        addView(label(digit, 28f, c.text).apply { gravity = Gravity.CENTER })
        if (letters.isNotEmpty()) addView(label(letters, 10f, c.secondary).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        })
        setOnClickListener { press(digit[0]) }
        if (digit == "0") setOnLongClickListener { press('+'); true }
        layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(10); marginEnd = dp(10) }
    }

    private fun buildContacts() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        search = EditText(context).apply {
            hint = "Ad ya da numara ara"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 17f
            setTextColor(c.text)
            setHintTextColor(c.secondary)
            background = rounded(c.card, 10f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderList()
            })
        }
        addView(search, matchWidth(bottom = 8))
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun tab(text: String, onClick: () -> Unit) = label(text, 15f, c.text, bold = true).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setOnClickListener { onClick() }
    }
}
