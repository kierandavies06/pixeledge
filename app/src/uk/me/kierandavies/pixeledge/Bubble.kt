package uk.me.kierandavies.pixeledge

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.*
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Room needed around the pill so the widest glow layer fades out instead of being cut off by the view bounds. */
private fun glowPad(fx: Effect, dens: Float): Int {
    val sw = (fx.widthDp ?: 4f).coerceIn(1.5f, 5f) * dens * 0.7f
    return max((10 * dens).toInt(), (sw / 2 + glowSpan(fx, sw)).toInt() + (3 * dens).toInt())
}

/** How far the glow reaches beyond the edge of the bright line. */
private fun glowSpan(fx: Effect, sw: Float) = sw * (fx.glow ?: 0f) * 3.9f / 2 + 2 * sw

class BubbleData(val app: String, val title: String, val text: String, val icon: Drawable?, val open: PendingIntent?)

/** A Samsung-style notification pill that drops from the camera cut-out, with the lighting effect running around its border. */
object Bubble {
    private var view: BubbleView? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    fun fromNotification(c: Context, sbn: StatusBarNotification, fx: Effect, ms: Int) {
        val n = sbn.notification
        val pm = c.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        var title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        // Keep private content private on a locked screen, honouring the system setting.
        val locked = c.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val allowPrivate = Settings.Secure.getInt(c.contentResolver, "lock_screen_allow_private_notifications", 1) == 1
        if (locked && !allowPrivate && n.visibility != Notification.VISIBILITY_PUBLIC) { title = app; text = "Notification" }
        // The small icon is the app's monochrome glyph; we tint it white inside a coloured circle.
        val icon = try { n.smallIcon?.loadDrawable(c) } catch (e: Exception) { null }
            ?: try { pm.getApplicationIcon(sbn.packageName) } catch (e: Exception) { null }
        show(c, BubbleData(app, title, text, icon, n.contentIntent), fx, ms)
    }

    fun preview(c: Context, fx: Effect, ms: Int) {
        Overlay.show(c, fx)   // a bubble rule also runs the screen-edge ring
        show(c, BubbleData("Edge Light", "Preview", "This is how a notification looks as a bubble",
            c.getDrawable(android.R.drawable.stat_notify_chat), null), fx, ms)
    }

    fun show(c: Context, d: BubbleData, fx: Effect, ms: Int) { main.post { showNow(c.applicationContext, d, fx, ms) } }

    private fun showNow(c: Context, d: BubbleData, fx: Effect, ms: Int) {
        val a = Overlay.acc
        if (a == null && !Settings.canDrawOverlays(c)) return
        dismissNow()
        val ctx: Context = a ?: c
        val m = ctx.getSystemService(WindowManager::class.java)
        val dens = ctx.resources.displayMetrics.density
        val metrics = m.currentWindowMetrics
        val v = BubbleView(ctx, d, fx, ms, min(metrics.bounds.width() - (16 * dens).toInt(), (440 * dens).toInt())) { dismissNow() }
        val cutout = metrics.windowInsets.displayCutout
        // The bounding rect is padded out to the status-bar height; the path is the real hole.
        val holeBottom = (if (Build.VERSION.SDK_INT >= 34) cutout?.cutoutPath?.let { p -> RectF().also { p.computeBounds(it, true) }.bottom.toInt() } else null)
            ?: cutout?.boundingRectTop?.bottom ?: (22 * dens).toInt()
        val lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            if (a != null) TYPE_ACCESSIBILITY_OVERLAY else TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = max(0, holeBottom + (6 * dens).toInt() - v.pad)   // card sits just under the camera
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.title = "EdgeLightBubble"
        m.addView(v, lp)
        wm = m; view = v
        v.start()
    }

    fun dismissNow() {
        val v = view ?: return
        view = null
        v.release()
        try { wm?.removeView(v) } catch (e: Exception) {}
    }
}

private class BubbleView(c: Context, private val d: BubbleData, private val fx: Effect, private val ms: Int, maxWidth: Int, private val gone: () -> Unit) : FrameLayout(c) {
    private val dens = c.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    val pad = glowPad(fx, dens)   // room around the pill for the glow
    private val border = BubbleBorder(c, fx, pad)
    private val h = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { dismiss() }
    private var downY = 0f
    private var moved = false
    private var closing = false

    /** The rule's first colour, darkened if it would make a white glyph hard to see. */
    private fun circleColor(): Int {
        val c0 = fx.colors?.firstOrNull() ?: 0xFF7C4DFF.toInt()
        val hsv = FloatArray(3); Color.colorToHSV(c0, hsv)
        val lum = (0.299 * Color.red(c0) + 0.587 * Color.green(c0) + 0.114 * Color.blue(c0)) / 255
        if (lum > 0.7) hsv[2] *= 0.55f
        return Color.HSVToColor(hsv)
    }

    init {
        setPadding(pad, pad, pad, pad)
        clipChildren = false; clipToPadding = false   // the border draws in the padding
        val card = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(16), dp(4))
            background = GradientDrawable().apply { setColor(0xF2121215.toInt()); cornerRadius = 100 * dens }   // clamps to a full pill
        }
        val badge = FrameLayout(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(circleColor()) } }
        badge.addView(ImageView(c).apply {
            setImageDrawable(d.icon?.mutate()); setColorFilter(Color.WHITE)   // white glyph whatever the source colours
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        card.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginEnd = dp(10) })
        // One line: bold title, then the message in a softer tone.
        val line = SpannableStringBuilder()
        if (d.title.isNotEmpty()) line.append(d.title).setSpan(StyleSpan(Typeface.BOLD), 0, d.title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (d.text.isNotEmpty()) {
            if (line.isNotEmpty()) line.append("  ")
            val start = line.length
            line.append(d.text.replace('\n', ' '))
            line.setSpan(ForegroundColorSpan(0xFFC9C9D2.toInt()), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        card.addView(TextView(c).apply {
            text = line; textSize = 14f; setTextColor(Color.WHITE); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            this.maxWidth = maxWidth - 2 * pad - dp(4 + 28 + 10 + 16)
        }, LinearLayout.LayoutParams(-2, -2))
        addView(card, LayoutParams(-2, -2))
        // The border sits on top and extends into the padding so the glow can spill outside the pill.
        addView(border, LayoutParams(-1, -1).also { it.setMargins(-pad, -pad, -pad, -pad) })
    }

    override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) { pivotX = w / 2f; pivotY = pad.toFloat() }

    // FrameLayout only re-measures match-parent children when there are two or more, so size the border by hand.
    override fun onMeasure(ws: Int, hs: Int) {
        super.onMeasure(ws, hs)
        border.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
    }

    fun start() {
        alpha = 0f; scaleX = 0.3f; scaleY = 0.5f; translationY = -dp(22).toFloat()   // starts small, up at the camera
        animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(400).setInterpolator(OvershootInterpolator(1.2f)).start()
        border.start()
        h.postDelayed(autoClose, ms.toLong())
    }

    fun release() { h.removeCallbacks(autoClose); animate().cancel(); border.stop() }

    fun dismiss() {
        if (closing) return
        closing = true
        h.removeCallbacks(autoClose)
        animate().translationY(-height.toFloat()).alpha(0f).setDuration(200).withEndAction { gone() }.start()
    }

    private fun open() {
        try {
            val o = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            d.open?.send(context, 0, null, null, null, null, o.toBundle())
        } catch (e: Exception) {}
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; moved = false; h.removeCallbacks(autoClose); animate().cancel() }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (abs(dy) > 8 * dens) moved = true
                translationY = min(0f, dy)
                alpha = 1f + min(0f, dy) / (height * 1.3f)
            }
            MotionEvent.ACTION_UP -> {
                val dy = e.rawY - downY
                when {
                    dy < -18 * dens -> dismiss()
                    !moved -> { open(); dismiss() }
                    else -> { animate().translationY(0f).alpha(1f).setDuration(150).start(); h.postDelayed(autoClose, 2000) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { animate().translationY(0f).alpha(1f).setDuration(150).start(); h.postDelayed(autoClose, 2000) }
        }
        return true
    }
}

/** Draws the configured lighting effect around a rounded card inset by [pad]. */
private class BubbleBorder(c: Context, private val fx: Effect, private val pad: Int) : View(c) {
    private val dens = c.resources.displayMetrics.density
    private val style = fx.style ?: "sweep"
    private val cols = (fx.colors?.takeIf { it.isNotEmpty() } ?: listOf(Color.WHITE)).toIntArray()
    private val sw = (fx.widthDp ?: 4f).coerceIn(1.5f, 5f) * dens * 0.7f   // finer than the screen-edge ring
    private val glow = fx.glow ?: 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
    private val path = Path()
    private val rings = ArrayList<Path>()
    private val mat = Matrix()
    private var shader: Shader? = null
    private var ready = false
    private var t = 0f
    private var env = 0f
    private var anim: ValueAnimator? = null
    private var ringW = 0f
    private val RINGS = 8

    // Take no space while the parent is still wrapping its content; otherwise a match-parent child measures as full screen height.
    override fun onMeasure(ws: Int, hs: Int) {
        val w = if (MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY) MeasureSpec.getSize(ws) else 0
        val h = if (MeasureSpec.getMode(hs) == MeasureSpec.EXACTLY) MeasureSpec.getSize(hs) else 0
        setMeasuredDimension(w, h)
    }

    fun start() {
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (fx.durationMs ?: 1400).toLong()
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t = it.animatedFraction; env = min(1f, it.currentPlayTime / 300f); invalidate() }
            start()
        }
    }

    fun stop() { anim?.cancel(); anim = null }

    private fun setup() {
        ready = true
        val r = RectF(pad.toFloat(), pad.toFloat(), width - pad.toFloat(), height - pad.toFloat())
        val rad = r.height() / 2f   // full pill
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        // Glow = thin rings stepping outwards from the line, each a rounded rect with a bigger radius, so it never paints inside the card or gets squared off.
        rings.clear()
        if (glow > 0f) {
            val span = glowSpan(fx, sw); val n = RINGS; val step = span / n
            for (i in 0 until n) {
                val o = sw / 2 + (i + 0.5f) * step
                rings.add(Path().apply { addRoundRect(RectF(r.left - o, r.top - o, r.right + o, r.bottom + o), rad + o, rad + o, Path.Direction.CW) })
            }
            ringW = step * 1.15f
        }
        shader = edgeShader(style, cols, r.centerX(), r.centerY())
    }

    override fun onDraw(cv: Canvas) {
        if (!ready) setup()
        val a = edgeAlpha(style, t, env)
        val sh = shader
        if (sh != null && (style == "sweep" || style == "chase" || style == "rainbow")) {
            mat.setRotate(t * 360f, width / 2f, height / 2f); sh.setLocalMatrix(mat)
        }
        paint.shader = sh
        paint.strokeWidth = ringW
        for (i in rings.indices.reversed()) {
            val f = 1f - i / rings.size.toFloat()
            tint(a * 0.42f * f * f, sh != null)
            cv.drawPath(rings[i], paint)
        }
        paint.strokeWidth = sw
        tint(a, sh != null)
        cv.drawPath(path, paint)
    }

    private fun tint(a: Float, shaded: Boolean) {
        val ai = (a * 255).toInt()
        if (shaded) paint.alpha = ai else paint.color = (cols[0] and 0xFFFFFF) or (ai shl 24)
    }
}

/** Posts a quiet stand-in for a notification whose native popup we replaced, so it stays in the shade with its actions. */
object Quiet {
    private const val CH = "replaced"

    fun hasPermission(c: Context) = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(c: Context, sbn: StatusBarNotification) {
        if (!hasPermission(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Replaced notifications", NotificationManager.IMPORTANCE_LOW))
        val o = sbn.notification; val ex = o.extras
        val pm = c.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        val b = Notification.Builder(c, CH)
        val si = o.smallIcon
        if (si != null) b.setSmallIcon(si) else b.setSmallIcon(android.R.drawable.ic_dialog_info)
        b.setContentTitle(ex.getCharSequence(Notification.EXTRA_TITLE)).setContentText(ex.getCharSequence(Notification.EXTRA_TEXT))
            .setSubText(app).setWhen(o.`when`).setShowWhen(true).setAutoCancel(true).setColor(o.color).setOnlyAlertOnce(true)
        ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { b.setStyle(Notification.BigTextStyle().bigText(it)) }
        o.getLargeIcon()?.let { b.setLargeIcon(it) }
        o.contentIntent?.let { b.setContentIntent(it) }
        o.deleteIntent?.let { b.setDeleteIntent(it) }
        o.actions?.forEach { b.addAction(it) }   // reply / mark-as-read etc. keep working: they carry the original app's PendingIntents
        nm.notify("ec:${sbn.packageName}:${sbn.tag}", sbn.id, b.build())
    }
}
