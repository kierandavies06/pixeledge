package uk.me.kierandavies.pixeledge

import android.R
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlin.math.roundToInt

/** Saturation (x) / brightness (y) square for the current hue. */
class SvView(c: Context, private val hsv: FloatArray, private val onChange: () -> Unit) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, 14 * d, 14 * d, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        paint.shader = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, paint)
        cv.restore(); paint.shader = null
        val x = hsv[1] * w; val y = (1 - hsv[2]) * h
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d; paint.color = 0x66000000; cv.drawCircle(x, y, 13 * d, paint)
        paint.strokeWidth = 3 * d; paint.color = Color.WHITE; cv.drawCircle(x, y, 11 * d, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        hsv[1] = (e.x / width).coerceIn(0f, 1f); hsv[2] = 1 - (e.y / height).coerceIn(0f, 1f)
        invalidate(); onChange(); return true
    }
}

class HueBar(c: Context, private val hsv: FloatArray, private val onChange: () -> Unit) : View(c) {
    private val d = c.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val bar = 14 * d; val cy = h / 2
        paint.shader = LinearGradient(0f, 0f, w, 0f, IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(0f, cy - bar / 2, w, cy + bar / 2, bar / 2, bar / 2, paint)
        paint.shader = null
        val x = (hsv[0] / 360f * w).coerceIn(bar / 2, w - bar / 2)
        paint.style = Paint.Style.FILL; paint.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)); cv.drawCircle(x, cy, 12 * d, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = Color.WHITE; cv.drawCircle(x, cy, 12 * d, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        hsv[0] = (e.x / width).coerceIn(0f, 1f) * 360f
        invalidate(); onChange(); return true
    }
}

class Chips(var sel: Int)
class Colors(var list: MutableList<Int>?)
class Slider(val bar: SeekBar, private val min: Float, private val step: Float, var on: Boolean) {
    fun value(): Float? = if (on) min + bar.progress * step else null
}

fun parseColors(s: String) = csv(s.replace(' ', ',')).mapNotNull { try { Color.parseColor(it) } catch (e: Exception) { null } }
private fun num(f: Float) = if (f == Math.rint(f.toDouble()).toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0')

class Form(val c: Context) {
    val p = Pal(c)
    val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(40)) }
    private var cur = root

    fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * c.resources.displayMetrics.density
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun <T : View> add(v: T, top: Int = 0): T {
        cur.addView(v, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(top) })
        return v
    }
    fun rr(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dpf(r) }
    fun ripple(d: Drawable) = RippleDrawable(ColorStateList.valueOf((p.text and 0xFFFFFF) or 0x22000000), d, null)

    fun tv(t: String, sp: Float, color: Int = p.text, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) typeface = medium
    }
    fun head(t: String) = add(tv(t, 34f).apply { setPadding(dp(8), dp(16), 0, 0) }, 8)
    fun section(t: String) = add(tv(t, 14f, p.primary, true).apply { setPadding(dp(12), 0, 0, 0) }, 24)
    fun note(t: String) = add(tv(t, 14f, p.dim).apply { setPadding(dp(12), 0, dp(12), 0) }, 4)
    fun label(t: String) = add(tv(t, 12f, p.dim, true), 14)

    fun card(top: Int = 8, click: (() -> Unit)? = null, body: () -> Unit): LinearLayout {
        val l = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = if (click != null) ripple(rr(p.card, 28)) else rr(p.card, 28)
            setPadding(dp(20), dp(12), dp(20), dp(16))
            if (click != null) { isClickable = true; setOnClickListener { click() } }
        }
        add(l, top)
        val prev = cur; cur = l; body(); cur = prev
        return l
    }

    fun field(label: String, v: String): EditText {
        label(label)
        return add(EditText(c).apply {
            setText(v); setSingleLine(); setTextColor(p.text); textSize = 16f
            background = rr(p.field, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
        }, 4)
    }

    fun switchRow(title: String, sub: String?, on: Boolean, change: (Boolean) -> Unit = {}): Switch {
        val sw = Switch(c).apply { isChecked = on; setOnCheckedChangeListener { _, v -> change(v) } }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 16f)); if (sub != null) addView(tv(sub, 13f, p.dim))
        }
        add(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
            isClickable = true; setOnClickListener { sw.toggle() }
            addView(col, LinearLayout.LayoutParams(0, -2, 1f)); addView(sw)
        })
        return sw
    }

    /** kind: 0 text, 1 tonal, 2 filled */
    fun mkButton(t: String, kind: Int = 1, onClick: () -> Unit) = TextView(c).apply {
        text = t; textSize = 14f; typeface = medium; gravity = Gravity.CENTER
        setPadding(dp(24), dp(12), dp(24), dp(12))
        setTextColor(when (kind) { 2 -> p.onPrimary; 1 -> p.onTonal; else -> p.primary })
        background = ripple(rr(when (kind) { 2 -> p.primary; 1 -> p.tonal; else -> 0 }, 100))
        isClickable = true; setOnClickListener { onClick() }
    }
    fun button(t: String, kind: Int = 1, onClick: () -> Unit) = add(mkButton(t, kind, onClick), 12)

    fun pill(t: String, ok: Boolean, click: () -> Unit) = tv((if (ok) "✓  " else "!  ") + t, 13f, if (ok) p.onTonal else p.onErr, true).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = ripple(rr(if (ok) p.tonal else p.err, 100))
        isClickable = true; setOnClickListener { click() }
    }

    fun chips(items: List<String>, sel: Int, change: (Int) -> Unit = {}): Chips {
        val st = Chips(sel)
        val views = items.map { tv(it, 14f).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); isClickable = true } }
        fun paint() = views.forEachIndexed { i, v ->
            val on = i == st.sel
            v.setTextColor(if (on) p.onTonal else p.text)
            v.background = ripple(rr(if (on) p.tonal else 0, 12).also { if (!on) it.setStroke(dp(1), p.outline) })
        }
        val row = LinearLayout(c)
        views.forEachIndexed { i, v ->
            v.setOnClickListener { st.sel = i; paint(); change(i) }
            row.addView(v, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(8) })
        }
        paint()
        add(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 6)
        return st
    }

    fun slider(label: String, min: Float, max: Float, step: Float, v: Float?, inherit: Boolean, unit: String): Slider {
        val bar = SeekBar(c).apply {
            this.max = ((max - min) / step).roundToInt()
            progress = (((v ?: min) - min) / step).roundToInt()
            progressTintList = ColorStateList.valueOf(p.primary); thumbTintList = ColorStateList.valueOf(p.primary)
            progressBackgroundTintList = ColorStateList.valueOf(p.tonal)
        }
        val s = Slider(bar, min, step, v != null || !inherit)
        val value = tv("", 14f, p.dim, true).apply { setPadding(dp(8), dp(4), 0, dp(4)) }
        fun show() {
            value.text = if (s.on) num(s.value()!!) + unit + (if (inherit) "  ✕" else "") else "inherit"
            bar.alpha = if (s.on) 1f else 0.4f
        }
        value.setOnClickListener { if (inherit && s.on) { s.on = false; show() } }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, pr: Int, user: Boolean) { if (user) s.on = true; show() }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        show()
        add(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 15f), LinearLayout.LayoutParams(0, -2, 1f)); addView(value)
        }, 14)
        add(bar)
        return s
    }

    private fun swatch(col: Int) = View(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(dp(2), p.outline) }
    }

    fun colors(init: List<Int>?, inherit: Boolean): Colors {
        val st = Colors(init?.toMutableList() ?: if (inherit) null else mutableListOf(p.primary))
        val holder = add(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }, 6)
        fun draw() {
            holder.removeAllViews()
            val l = st.list
            if (l == null) {
                holder.addView(mkButton("Override colors") { st.list = mutableListOf(p.primary); draw() })
                return
            }
            val row = LinearLayout(c)
            fun lp() = LinearLayout.LayoutParams(dp(44), dp(44)).also { it.rightMargin = dp(10) }
            l.forEachIndexed { i, col ->
                row.addView(swatch(col).apply {
                    setOnClickListener { pickColor(l[i]) { n -> l[i] = n; draw() } }
                    setOnLongClickListener {
                        if (l.size > 1 || inherit) { l.removeAt(i); if (l.isEmpty()) st.list = null; draw() }
                        true
                    }
                }, lp())
            }
            row.addView(tv("+", 22f, p.onTonal).apply {
                gravity = Gravity.CENTER; background = ripple(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.tonal) })
                setOnClickListener { l.add(l.last()); draw() }
            }, lp())
            holder.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            holder.addView(tv("Tap to edit · long-press to remove", 12f, p.dim).apply { setPadding(0, dp(6), 0, 0) })
            if (inherit) holder.addView(mkButton("Use default colors", 0) { st.list = null; draw() })
        }
        draw()
        return st
    }

    fun pickColor(init: Int, done: (Int) -> Unit) {
        val hsv = FloatArray(3); Color.colorToHSV(init, hsv)
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), 0) }
        val prev = View(c)
        val hex = EditText(c).apply { setSingleLine(); setTextColor(p.text); textSize = 16f; typeface = Typeface.MONOSPACE }
        var syncing = false
        lateinit var sv: SvView; lateinit var hue: HueBar
        fun refresh(setHex: Boolean) {
            val col = Color.HSVToColor(hsv)
            prev.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col); setStroke(dp(2), p.outline) }
            if (setHex) { syncing = true; hex.setText("#%06X".format(col and 0xFFFFFF)); syncing = false }
            sv.invalidate(); hue.invalidate()
        }
        sv = SvView(c, hsv) { refresh(true) }
        hue = HueBar(c, hsv) { refresh(true) }
        box.addView(LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)).also { it.rightMargin = dp(14) })
            addView(hex, LinearLayout.LayoutParams(0, -2, 1f))
        })
        box.addView(sv, LinearLayout.LayoutParams(-1, dp(210)).also { it.topMargin = dp(14) })
        box.addView(hue, LinearLayout.LayoutParams(-1, dp(34)).also { it.topMargin = dp(16) })
        val presets = longArrayOf(0xFFFF1744, 0xFFFF9100, 0xFFFFEA00, 0xFF00E676, 0xFF00E5FF, 0xFF2979FF, 0xFF7C4DFF, 0xFFD500F9, 0xFFFF4081, 0xFFFFFFFF)
            .map { it.toInt() }
        val row = LinearLayout(c)
        for (col in presets) row.addView(swatch(col).apply {
            setOnClickListener { Color.colorToHSV(col, hsv); refresh(true) }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).also { it.rightMargin = dp(10) })
        box.addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(16) })
        hex.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (!syncing) parseColors(e.toString()).firstOrNull()?.let { Color.colorToHSV(it, hsv); refresh(false) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refresh(true)
        AlertDialog.Builder(c).setView(ScrollView(c).apply { addView(box) })
            .setPositiveButton("Done") { _, _ -> done(Color.HSVToColor(hsv)) }
            .setNegativeButton("Cancel", null).show()
    }

    fun scroll() = ScrollView(c).apply {
        addView(root)
        clipToPadding = false
        setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
    }
}

/** Effect editor. With [inherit], every control can be left on "inherit" (use the default effect). */
class EffectForm(f: Form, e: Effect, private val inherit: Boolean, base: () -> Effect) {
    private val style: Chips
    private val colors: Colors
    private val width: Slider
    private val dur: Slider
    private val reps: Slider
    private val glow: Slider

    init {
        f.label("Style")
        val i = Effect.STYLES.indexOf(e.style)
        style = f.chips((if (inherit) listOf("Inherit") else listOf()) + Effect.STYLES.map { it.replaceFirstChar(Char::uppercase) },
            if (inherit) i + 1 else maxOf(i, 0))
        f.label("Colors")
        colors = f.colors(e.colors, inherit)
        width = f.slider("Width", 1f, 16f, 0.5f, e.widthDp, inherit, " dp")
        dur = f.slider("Cycle duration", 300f, 4000f, 100f, e.durationMs?.toFloat(), inherit, " ms")
        reps = f.slider("Repeats", 1f, 8f, 1f, e.repeats?.toFloat(), inherit, "×")
        glow = f.slider("Glow", 0f, 3f, 0.25f, e.glow, inherit, "")
        f.button("Preview", 2) {
            val fx = base().over(read())
            Overlay.show(f.c, fx)
            if (Store.load(f.c).watch) Light.mirror?.invoke(f.c, fx)
        }
    }

    fun read() = Effect().also { e ->
        e.style = Effect.STYLES.getOrNull(style.sel - (if (inherit) 1 else 0))
        e.colors = colors.list?.toList()
        e.widthDp = width.value(); e.durationMs = dur.value()?.toInt()
        e.repeats = reps.value()?.toInt(); e.glow = glow.value()
    }
}
