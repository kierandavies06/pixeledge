package uk.me.kierandavies.pixeledge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.provider.Settings
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.*
import android.view.animation.LinearInterpolator
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object Overlay {
    private var view: EdgeView? = null
    private var wm: WindowManager? = null
    @Volatile var acc: Acc? = null

    fun show(ctx: Context, fx: Effect, loop: Boolean = false) {
        val app = ctx.applicationContext
        val a = acc
        if (a == null && !Settings.canDrawOverlays(app)) {
            Toast.makeText(app, "Edge Light: overlay permission missing", Toast.LENGTH_SHORT).show()
            return
        }
        hide()
        val m = (a ?: app).getSystemService(WindowManager::class.java)
        val v = EdgeView(a ?: app, fx, loop) { hide() }
        val b = m.maximumWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            b.width(), b.height(), if (a != null) TYPE_ACCESSIBILITY_OVERLAY else TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.setFitInsetsTypes(0)   // don't shrink for the gesture bar / status bar
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.title = "EdgeLight"
        m.addView(v, lp)
        wm = m; view = v
        v.start()
    }

    /** Fade a looping light out and remove it. */
    fun stop() { view?.softStop() }

    fun hide() {
        val v = view ?: return
        view = null
        v.stop()
        try { wm?.removeView(v) } catch (e: Exception) {}
    }
}

class EdgeView(c: Context, private val fx: Effect, private val loop: Boolean, private val done: () -> Unit) : View(c) {
    private val dens = c.resources.displayMetrics.density
    private val style = fx.style ?: "sweep"
    private val cols = (fx.colors?.takeIf { it.isNotEmpty() } ?: listOf(Color.WHITE)).toIntArray()
    private val w = (fx.widthDp ?: 4f) * dens
    private val glow = fx.glow ?: 0f
    private val reps = max(fx.repeats ?: 1, 1)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val path = Path()
    private val mat = Matrix()
    private var shader: Shader? = null
    private var ready = false
    private var t = 0f      // position within the current cycle, 0..1
    private var env = 0f    // overall fade envelope, 0..1
    private var fade = 1f   // 1 while showing, animates to 0 on softStop
    private var anim: ValueAnimator? = null

    fun start() {
        if (loop) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (fx.durationMs ?: 1400).toLong()
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { t = it.animatedFraction; env = min(1f, it.currentPlayTime / 300f); invalidate() }
                start()
            }
            return
        }
        anim = ValueAnimator.ofFloat(0f, reps.toFloat()).apply {
            duration = (fx.durationMs ?: 1400).toLong() * reps
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                val cyc = min(v.toInt(), reps - 1)
                t = v - cyc
                env = min(1f, min(v / (reps * 0.08f), (reps - v) / (reps * 0.15f)))
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { done() }
            })
            start()
        }
    }

    fun stop() { anim?.removeAllListeners(); anim?.cancel(); anim = null }

    fun softStop() {
        stop()
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 250
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { done() } })
            start()
        }
    }

    private fun setup() {
        ready = true
        val W = width.toFloat(); val H = height.toFloat()
        val r = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: (44 * dens)
        // Path runs along the physical screen edge; the stroke is twice the visible width so half of it shows.
        path.reset(); path.addRoundRect(RectF(0f, 0f, W, H), r, r, Path.Direction.CW)
        val cx = W / 2; val cy = H / 2
        shader = when (style) {
            "rainbow" -> SweepGradient(cx, cy, IntArray(8) { Color.HSVToColor(floatArrayOf(it * 360f / 7, 0.85f, 1f)) }, null)
            "chase" -> {
                val n = cols.size
                val clear = cols[0] and 0xFFFFFF
                val pos = FloatArray(n + 2); val cs = IntArray(n + 2)
                cs[0] = clear; pos[0] = 0f
                for (i in 0 until n) { cs[i + 1] = cols[i]; pos[i + 1] = if (n == 1) 0.5f else 0.2f + 0.3f * i / (n - 1) }
                cs[n + 1] = clear; pos[n + 1] = 0.5001f
                SweepGradient(cx, cy, cs, pos)
            }
            else -> if (cols.size > 1) SweepGradient(cx, cy, cols + cols[0], null) else null
        }
    }

    override fun onDraw(cv: Canvas) {
        if (!ready) setup()
        val rot = style == "sweep" || style == "chase" || style == "rainbow"
        val a = when (style) {
            "pulse" -> sq(sin(PI * 3 * t).toFloat())
            "breathe" -> sq(sin(PI * t).toFloat())
            "strobe" -> if ((t * 6).toInt() % 2 == 0) 1f else 0f
            "solid" -> min(1f, min(t / 0.15f, (1 - t) / 0.25f))
            else -> env
        }.coerceIn(0f, 1f) * fade
        val sh = shader
        if (sh != null && rot) { mat.setRotate(t * 360f, width / 2f, height / 2f); sh.setLocalMatrix(mat) }
        paint.shader = sh
        if (glow > 0f) for (k in 3 downTo 1) {
            paint.strokeWidth = 2 * w * (1 + glow * k * 0.9f)
            alpha(a * 0.12f, sh != null)
            cv.drawPath(path, paint)
        }
        paint.strokeWidth = 2 * w
        alpha(a, sh != null)
        cv.drawPath(path, paint)
    }

    private fun alpha(a: Float, shaded: Boolean) {
        val ai = (a * 255).toInt()
        if (shaded) paint.alpha = ai else paint.color = (cols[0] and 0xFFFFFF) or (ai shl 24)
    }

    private fun sq(x: Float) = x * x
}
