package com.serhanensar.callbridge

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Ekranların ortak renkleri ve küçük arayüz yardımcıları. */
class Palette(dark: Boolean) {
    val background = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val secondary = if (dark) 0xFF98989D.toInt() else 0xFF6E6E73.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF28A745.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val neutral = if (dark) 0xFF3A3A3C.toInt() else 0xFFE5E5EA.toInt()
}

/** Pencereyi temaya göre hazırlar ve paleti döndürür. */
fun Activity.setupWindow(): Palette {
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val c = Palette(dark)
    window.decorView.setBackgroundColor(c.background)
    if (!dark && Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }
    return c
}

/** İçeriği sistem çubuklarının altında kalmayacak şekilde kaydırılabilir yapar. */
fun Context.insetScroll(root: View): ScrollView {
    val scroll = ScrollView(this).apply {
        addView(root)
        clipToPadding = false
    }
    scroll.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        insets
    }
    return scroll
}

fun Context.label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp * resources.displayMetrics.density
}

fun Context.matchWidth(bottom: Int = 0) =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(bottom)
    }

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

// Ekranlar düzenli aralıklarla yenileniyor. Değişmeyen değeri yeniden atamak görünümü
// boşuna yeniden çizdirir; bu yardımcılar yalnızca fark varsa dokunur.

fun TextView.setTextIfChanged(value: CharSequence) {
    if (text.toString() != value.toString()) text = value
}

fun TextView.setHintIfChanged(value: CharSequence) {
    if (hint?.toString() != value.toString()) hint = value
}

fun View.setShown(shown: Boolean) {
    val v = if (shown) View.VISIBLE else View.GONE
    if (visibility != v) visibility = v
}

/** Yuvarlak köşeli dolguyu yalnızca renk değiştiğinde yeniler. */
fun View.setFill(color: Int, radiusDp: Float) {
    if (tag == color) return
    tag = color
    background = context.rounded(color, radiusDp)
}
