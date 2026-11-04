package uk.me.kierandavies.pixeledge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Material 3 navigation bar: 80dp container, a 64x32dp pill behind the active icon, labels always shown. */
class NavBar(c: Context, private val p: Pal, items: List<Pair<String, Int>>, private val select: (Int) -> Unit) : LinearLayout(c) {
    private val dens = c.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    private val pills = ArrayList<View>(); private val icons = ArrayList<IconView>(); private val labels = ArrayList<TextView>()
    private val bar = 80

    init {
        orientation = HORIZONTAL
        setBackgroundColor(p.nav)
        items.forEachIndexed { i, (name, kind) ->
            val icon = IconView(c, kind)
            val pill = android.widget.FrameLayout(c).apply { addView(icon, android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)) }
            val label = TextView(c).apply { text = name; textSize = 12f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
            val item = LinearLayout(c).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, dp(16))
                isClickable = true; contentDescription = name
                setOnClickListener { select(i) }
                addView(pill, LayoutParams(dp(64), dp(32)))
                addView(label, LayoutParams(-2, -2).also { it.topMargin = dp(4) })
            }
            pills.add(pill); icons.add(icon); labels.add(label)
            addView(item, LayoutParams(0, dp(bar), 1f))
        }
        setSelected(0)
    }

    /** Extra bottom space so the bar clears the gesture area. */
    fun inset(bottom: Int) = setPadding(0, 0, 0, bottom)

    fun setSelected(sel: Int) {
        pills.forEachIndexed { i, v ->
            val on = i == sel
            v.background = if (on) GradientDrawable().apply { setColor(p.tonal); cornerRadius = dp(16).toFloat() } else null
            icons[i].set(on, if (on) p.onTonal else p.dim)
            labels[i].setTextColor(if (on) p.text else p.dim)
            labels[i].typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

/** 24dp line icons drawn on a 24-unit grid: 0 home, 1 rules, 2 look, 3 settings. */
class IconView(c: Context, private val kind: Int) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var on = false
    fun set(active: Boolean, color: Int) { on = active; paint.color = color; invalidate() }

    override fun onDraw(cv: Canvas) {
        val s = width / 24f
        cv.scale(s, s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = cv.drawLine(x1, y1, x2, y2, paint)
        fun stroke() { paint.style = Paint.Style.STROKE; paint.strokeWidth = if (on) 2.4f else 1.8f }
        fun fill() { paint.style = Paint.Style.FILL }
        when (kind) {
            0 -> {
                val h = Path().apply { moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 20f); lineTo(14.5f, 20f); lineTo(14.5f, 14f); lineTo(9.5f, 14f); lineTo(9.5f, 20f); lineTo(4f, 20f); close() }
                if (on) fill() else stroke()
                cv.drawPath(h, paint)
            }
            1 -> {
                stroke()
                for (y in listOf(6f, 12f, 18f)) { line(9.5f, y, 20f, y); fill(); cv.drawCircle(5f, y, if (on) 2f else 1.5f, paint); stroke() }
            }
            2 -> {
                stroke()
                cv.drawRoundRect(RectF(6f, 2.5f, 18f, 21.5f), 3.5f, 3.5f, paint)
                fill(); cv.drawCircle(12f, 6f, if (on) 1.6f else 1.2f, paint)
                if (on) { stroke(); paint.alpha = 140; cv.drawRoundRect(RectF(2.5f, 1f, 21.5f, 23f), 5.5f, 5.5f, paint) }
            }
            else -> {
                stroke()
                line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
                paint.color.let { col -> fill()
                    for ((x, y) in listOf(15f to 7f, 8f to 12f, 13f to 17f)) {
                        paint.color = col; cv.drawCircle(x, y, 2.6f, paint)
                    }
                }
            }
        }
    }
}

/** A phone-shaped preview of an effect: the lit border animates exactly as it would around the real screen. */
class EdgePreview(c: Context, private var fx: Effect) : View(c) {
    private val dens = c.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0C0C10.toInt() }
    private val path = Path(); private val mat = Matrix()
    private var shader: Shader? = null
    private var t = 0f
    private var anim: ValueAnimator? = null

    fun update(e: Effect) { fx = e; shader = null; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (fx.durationMs ?: 1400).toLong().coerceAtLeast(600) * 2
            repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedFraction; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(cv: Canvas) {
        val style = fx.style ?: "sweep"
        val cols = (fx.colors?.takeIf { it.isNotEmpty() } ?: listOf(Color.WHITE)).toIntArray()
        val m = 14 * dens
        val r = RectF(m, m, width - m, height - m)
        val rad = 26 * dens
        cv.drawRoundRect(r, rad, rad, fill)
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        if (shader == null) shader = edgeShader(style, cols, width / 2f, height / 2f)
        val sh = shader
        if (sh != null && (style == "sweep" || style == "chase" || style == "rainbow")) { mat.setRotate(t * 360f * 2 % 360f, width / 2f, height / 2f); sh.setLocalMatrix(mat) }
        val a = edgeAlpha(style, (t * 2) % 1f, 1f)
        val w = (fx.widthDp ?: 4f).coerceIn(2f, 8f) * dens
        val glow = fx.glow ?: 0f
        paint.shader = sh
        fun tint(al: Float) { val ai = (al * 255).toInt(); if (sh != null) paint.alpha = ai else paint.color = (cols[0] and 0xFFFFFF) or (ai shl 24) }
        if (glow > 0f) for (k in 3 downTo 1) { paint.strokeWidth = w * (1 + glow * k * 1.4f); tint(a * 0.12f); cv.drawPath(path, paint) }
        paint.strokeWidth = w; tint(a); cv.drawPath(path, paint)
        // Camera hole, so it reads as a phone.
        fill.color = 0xFF000000.toInt(); cv.drawCircle(width / 2f, r.top + 16 * dens, 5 * dens, fill); fill.color = 0xFF0C0C10.toInt()
    }
}
