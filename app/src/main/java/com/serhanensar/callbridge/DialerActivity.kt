package com.serhanensar.callbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Telefonda arama ekranı: son aramalar (tabletin arama kaydı), bu telefonun rehberi ve tuş takımı.
 * Seçilen numarayı tablet arar, ses çağrı ekranındaki gibi telefona gelir. Başka uygulamalardan
 * gelen tel: bağlantıları ve arama istekleri tuş takımında açılır; arama yine de kullanıcı Ara'ya
 * basınca başlar.
 */
class DialerActivity : Activity() {

    private class Contact(val name: String, val number: String, val label: String, val starred: Boolean) {
        val digits = number.filter { it.isDigit() }
        /** Adın tuş takımı karşılığı (T9): "Ali Veli" → "254 8354". */
        val t9 = name.lowercase().map { T9[it] ?: if (it.isDigit()) it else ' ' }.joinToString("")
    }

    private enum class Tab { RECENTS, CONTACTS, KEYPAD }

    private lateinit var c: Palette
    private val tabs = mutableMapOf<Tab, TextView>()
    private val panes = mutableMapOf<Tab, View>()
    private lateinit var statusChip: TextView
    private lateinit var banner: TextView

    private lateinit var recentsList: LinearLayout
    private lateinit var contactsList: LinearLayout
    private lateinit var search: EditText
    private lateinit var numberText: TextView
    private lateinit var suggestions: LinearLayout

    private var number = StringBuilder()
    private var contacts: List<Contact> = emptyList()
    private var tab = Tab.RECENTS
    private var shownHistory = -1

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            if (shownHistory != BridgeClientService.historyVersion) renderRecents()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = setupWindow()
        setContentView(buildUi())
        selectTab(Tab.RECENTS)
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
        BridgeClientService.refreshHistory()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) loadContacts() else renderContacts()
    }

    /** tel: bağlantısıyla açıldıysa numarayı tuş takımına yazar. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "tel") return
        number = StringBuilder(data.schemeSpecificPart.filter { it.isDigit() || it in "+*#" })
        selectTab(Tab.KEYPAD)
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
            renderStatus()
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

    private fun nameFor(num: String): String? {
        val d = num.filter { it.isDigit() }
        if (d.length < 7) return null
        return contacts.firstOrNull { it.digits.takeLast(10) == d.takeLast(10) }?.name
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
                        ContactsContract.CommonDataKinds.Phone.STARRED,
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val name = cur.getString(0) ?: continue
                        val num = cur.getString(1) ?: continue
                        if (!seen.add(name + "|" + num.filter { it.isDigit() }.takeLast(10))) continue // aynı numara iki kez
                        val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(resources, cur.getInt(2), cur.getString(3)).toString()
                        out += Contact(name, num, label, cur.getInt(4) == 1)
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                contacts = out
                renderContacts()
                renderRecents()
                renderNumber()
            }
        }
    }

    // ---- görünüm ----

    private fun selectTab(t: Tab) {
        tab = t
        Tab.entries.forEach { e ->
            panes[e]?.setShown(e == t)
            tabs[e]?.let {
                it.setFill(if (e == t) c.text else c.background, 18f)
                it.setTextColor(if (e == t) c.background else c.secondary)
            }
        }
    }

    private fun renderStatus() {
        val (text, color) = when {
            !BridgeClientService.isRunning -> "Köprü kapalı" to c.red
            !BridgeClientService.isConnected -> "Tablete bağlanıyor" to c.orange
            else -> "Tablete bağlı" to c.green
        }
        statusChip.setTextIfChanged(text)
        statusChip.setTextColor(color)
        statusChip.setFill(withAlpha(color, 0x22), 14f)
        val msg = when {
            !BridgeClientService.isRunning -> "CallBridge köprüsü kapalı. Ana ekrandan Başlat'a bas."
            BridgeClientService.lastResult != null && BridgeClientService.call == null -> BridgeClientService.lastResult
            else -> null
        }
        banner.setTextIfChanged(msg ?: "")
        banner.setShown(msg != null)
    }

    private fun renderRecents() {
        shownHistory = BridgeClientService.historyVersion
        recentsList.removeAllViews()
        val history = BridgeClientService.history
        if (history.isEmpty()) {
            recentsList.addView(empty(if (BridgeClientService.isConnected) "Henüz arama yok" else "Tablete bağlanınca son aramalar burada görünür"), matchWidth())
            return
        }
        var lastDay = ""
        history.forEach { h ->
            val day = dayLabel(h.date)
            if (day != lastDay) {
                recentsList.addView(sectionTitle(day, c).apply { setPadding(dp(4), dp(14), 0, dp(6)) }, matchWidth())
                lastDay = day
            }
            val name = h.name ?: nameFor(h.number)
            val (iconRes, iconColor) = when (h.type) {
                "out" -> R.drawable.ic_call_out to c.secondary
                "missed" -> R.drawable.ic_call_missed to c.red
                else -> R.drawable.ic_call_in to c.green
            }
            val sub = buildString {
                append(DateUtils.formatDateTime(this@DialerActivity, h.date, DateUtils.FORMAT_SHOW_TIME))
                if (h.duration > 0) append(" · ").append(duration(h.duration))
                if (name != null) append(" · ").append(h.number)
            }
            recentsList.addView(
                personRow(name ?: h.number, name, sub, iconRes, iconColor, titleColor = if (h.type == "missed") c.red else c.text) {
                    call(h.number, name)
                },
                matchWidth(),
            )
        }
    }

    private fun renderContacts() {
        contactsList.removeAllViews()
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            contactsList.addView(label("Rehberi göstermek için izin ver", 15f, c.green).apply {
                setPadding(dp(4), dp(16), 0, dp(16))
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) }
            }, matchWidth())
            return
        }
        val q = search.text.toString().trim().lowercase()
        val qDigits = q.filter { it.isDigit() }
        val matches = contacts.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || (qDigits.length >= 3 && it.digits.contains(qDigits))
        }
        if (matches.isEmpty()) {
            contactsList.addView(empty(if (contacts.isEmpty()) "Rehber boş" else "Sonuç yok"), matchWidth())
            return
        }
        // Arama yokken yıldızlı kişiler en üstte
        val favorites = if (q.isEmpty()) matches.filter { it.starred } else emptyList()
        if (favorites.isNotEmpty()) {
            contactsList.addView(sectionTitle("Favoriler", c).apply { setPadding(dp(4), dp(10), 0, dp(6)) }, matchWidth())
            favorites.forEach { addContactRow(it, showName = true) }
        }
        var letter = ""
        var lastName: String? = null
        matches.take(400).forEach { ct ->
            val l = ct.name.take(1).uppercase()
            if (l != letter) {
                contactsList.addView(sectionTitle(l, c).apply { setPadding(dp(4), dp(14), 0, dp(6)) }, matchWidth())
                letter = l
            }
            addContactRow(ct, showName = ct.name != lastName)
            lastName = ct.name
        }
    }

    private fun addContactRow(ct: Contact, showName: Boolean) {
        contactsList.addView(
            personRow(if (showName) ct.name else "", if (showName) ct.name else null, "${ct.label} · ${ct.number}",
                if (ct.starred) R.drawable.ic_star else 0, c.orange, avatar = showName) { call(ct.number, ct.name) },
            matchWidth(),
        )
    }

    private fun renderNumber() {
        numberText.text = number.toString()
        numberText.setShown(number.isNotEmpty())
        // Yazılan numaraya ya da harflere (T9) uyan kişiler
        suggestions.removeAllViews()
        val d = number.filter { it.isDigit() }
        if (d.length < 2) return
        contacts.filter { it.digits.contains(d) || it.t9.split(' ').any { w -> w.startsWith(d) } }
            .distinctBy { it.name }
            .take(3)
            .forEach { ct ->
                suggestions.addView(personRow(ct.name, ct.name, ct.number, 0, 0) { call(ct.number, ct.name) }, matchWidth())
            }
    }

    private fun dayLabel(date: Long): String = when {
        DateUtils.isToday(date) -> "Bugün"
        DateUtils.isToday(date + DateUtils.DAY_IN_MILLIS) -> "Dün"
        else -> {
            val sameYear = Calendar.getInstance().apply { timeInMillis = date }.get(Calendar.YEAR) ==
                Calendar.getInstance().get(Calendar.YEAR)
            DateUtils.formatDateTime(this, date, DateUtils.FORMAT_SHOW_DATE or if (sameYear) DateUtils.FORMAT_NO_YEAR else 0)
        }
    }

    private fun duration(sec: Long) = if (sec < 60) "$sec sn" else "${sec / 60} dk${if (sec % 60 > 0) " ${sec % 60} sn" else ""}"

    // ---- parçalar ----

    /** Avatar, başlık, alt satır ve isteğe bağlı küçük simgeli liste satırı. */
    private fun personRow(
        title: String, avatarName: String?, sub: String, iconRes: Int, iconColor: Int,
        titleColor: Int = c.text, avatar: Boolean = true, onClick: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener { onClick() }
        val av = avatar(avatarName, 42)
        av.visibility = if (avatar) View.VISIBLE else View.INVISIBLE
        addView(av, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (title.isNotEmpty()) texts.addView(label(title, 16f, titleColor, bold = true).apply { isSingleLine = true })
        val subRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (iconRes != 0) subRow.addView(icon(iconRes, iconColor, 14), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(5) })
        subRow.addView(label(sub, 13f, c.secondary).apply { isSingleLine = true })
        texts.addView(subRow)
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(icon(R.drawable.ic_call, c.green, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
    }

    private fun empty(text: String) = label(text, 15f, c.secondary).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(40), 0, dp(40))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("Ara", 28f, c.text, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusChip = chip("", c.green, c.card)
        header.addView(statusChip)
        root.addView(header, matchWidth(bottom = 12))

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(c.card, 20f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf(Tab.RECENTS to "Son aramalar", Tab.CONTACTS to "Rehber", Tab.KEYPAD to "Tuş takımı").forEach { (t, text) ->
            val v = label(text, 14f, c.secondary, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { selectTab(t) }
            }
            tabs[t] = v
            tabRow.addView(v, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        root.addView(tabRow, matchWidth(bottom = 10))

        banner = label("", 14f, c.onAccent).apply {
            gravity = Gravity.CENTER
            background = rounded(c.red, 12f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(banner, matchWidth(bottom = 8))

        recentsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panes[Tab.RECENTS] = ScrollView(this).apply { addView(recentsList) }

        contactsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = "Ad ya da numara ara"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 16f
            setTextColor(c.text)
            setHintTextColor(c.secondary)
            background = rounded(c.card, 12f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderContacts()
            })
        }
        panes[Tab.CONTACTS] = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, matchWidth(bottom = 4))
            addView(ScrollView(context).apply { addView(contactsList) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        panes[Tab.KEYPAD] = buildKeypad()
        Tab.entries.forEach { t ->
            root.addView(panes[t], LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop,
                dp(16) + insets.systemWindowInsetRight, dp(8) + insets.systemWindowInsetBottom)
            insets
        }
        return root
    }

    private fun buildKeypad() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        suggestions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(suggestions, matchWidth())
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        numberText = label("", 34f, c.text, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            visibility = View.GONE
        }
        addView(numberText, matchWidth(bottom = 14))

        listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        ).forEach { rowKeys ->
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
        val slot = { LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(10); marginEnd = dp(10) } }
        actions.addView(View(context), slot())
        actions.addView(android.widget.FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c.green) }
            addView(icon(R.drawable.ic_call, c.onAccent, 32), android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            setOnClickListener { call(number.toString(), nameFor(number.toString())) }
        }, slot())
        actions.addView(android.widget.FrameLayout(context).apply {
            addView(icon(R.drawable.ic_backspace, c.secondary, 26), android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener { backspace() }
            setOnLongClickListener { number.clear(); renderNumber(); true }
        }, slot())
        addView(actions, matchWidth(bottom = 4))
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

    companion object {
        /** Harf → tuş (Türkçe harfler dahil). */
        private val T9: Map<Char, Char> = buildMap {
            listOf("2" to "abcç", "3" to "def", "4" to "ghıği", "5" to "jkl", "6" to "mnoö", "7" to "pqrsş", "8" to "tuvü", "9" to "wxyz")
                .forEach { (d, letters) -> letters.forEach { put(it, d[0]) } }
        }
    }
}
