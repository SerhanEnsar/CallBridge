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
    val orange = if (dark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
    val onAccent = 0xFFFFFFFF.toInt()
}

/** Kişi avatarları için sabit renkler; ad aynıysa renk de hep aynı. */
private val avatarColors = intArrayOf(
    0xFF5E5CE6.toInt(), 0xFF0A84FF.toInt(), 0xFF30B0C7.toInt(), 0xFF34C759.toInt(),
    0xFFFF9F0A.toInt(), 0xFFFF375F.toInt(), 0xFFBF5AF2.toInt(), 0xFFAC8E68.toInt(),
)

fun avatarColor(name: String) = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]

/** Adın baş harfleri (en çok iki); ad yoksa ya da numaraysa "#". */
fun initials(name: String?): String {
    val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.firstOrNull()?.isLetter() == true }.orEmpty()
    return when {
        words.isEmpty() -> "#"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** Baş harfli, addan türetilen renkte yuvarlak avatar. */
fun Context.avatar(name: String?, sizeDp: Int) = TextView(this).apply {
    text = initials(name)
    gravity = android.view.Gravity.CENTER
    setTextColor(0xFFFFFFFF.toInt())
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.38f)
    typeface = Typeface.DEFAULT_BOLD
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(avatarColor(name ?: "#"))
    }
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Renklendirilmiş vektör simge. */
fun Context.icon(res: Int, color: Int, sizeDp: Int = 24) = android.widget.ImageView(this).apply {
    setImageResource(res)
    imageTintList = android.content.res.ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Küçük, hap biçiminde durum etiketi. */
fun Context.chip(text: String, fg: Int, bg: Int) = label(text, 13f, fg, bold = true).apply {
    background = rounded(bg, 14f)
    setPadding(dp(12), dp(5), dp(12), dp(5))
}

/** Rengin saydamlığını değiştirir (ör. yumuşak arka plan için). */
fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

fun Context.card(c: Palette) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(c.card, 16f)
    setPadding(dp(16), dp(14), dp(16), dp(14))
}

fun Context.sectionTitle(text: String, c: Palette) = label(text.uppercase(), 12f, c.secondary, bold = true).apply {
    letterSpacing = 0.08f
    setPadding(dp(4), 0, 0, dp(6))
}

/**
 * Çağrı ekranlarındaki yuvarlak simge düğmesi ve altındaki etiket. active=true iken ters renkli
 * (açık) görünür; örneğin sessiz ya da hoparlör açıkken.
 */
class RoundButton(
    ctx: Context,
    private val c: Palette,
    iconRes: Int,
    text: String,
    private val bg: Int = c.neutral,
    private val fg: Int = c.text,
    sizeDp: Int = 68,
    onClick: () -> Unit,
) : LinearLayout(ctx) {
    private val circle = android.widget.FrameLayout(ctx)
    private val image = ctx.icon(iconRes, fg, (sizeDp * 0.42f).toInt())
    private val caption = ctx.label(text, 12f, c.secondary)
    private var active: Boolean? = null

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        circle.addView(image, android.widget.FrameLayout.LayoutParams(image.layoutParams.width, image.layoutParams.height, android.view.Gravity.CENTER))
        addView(circle, LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp)))
        caption.gravity = android.view.Gravity.CENTER
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        circle.setOnClickListener { onClick() }
        setActive(false)
    }

    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) c.text else bg)
        }
        image.imageTintList = android.content.res.ColorStateList.valueOf(if (on) c.background else fg)
    }

    fun setIcon(res: Int) = image.setImageResource(res)

    fun setText(text: String) = caption.setTextIfChanged(text)
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
