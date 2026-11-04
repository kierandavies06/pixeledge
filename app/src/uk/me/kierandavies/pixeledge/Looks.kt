package uk.me.kierandavies.pixeledge

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** A named effect preset. [fx] only sets what it needs to; an empty one means "use the default". */
class Look(val name: String, val blurb: String, val fx: Effect)

object Looks {
    private fun fx(style: String, cols: List<Int>, w: Float, ms: Int, reps: Int, glow: Float) = Effect().also {
        it.style = style; it.colors = cols.takeIf { c -> c.isNotEmpty() }; it.widthDp = w; it.durationMs = ms; it.repeats = reps; it.glow = glow
    }

    val all = listOf(
        Look("Urgent", "Fast red strobe", fx("strobe", listOf(0xFFFF1744.toInt(), 0xFFFF9100.toInt()), 5f, 1200, 3, 0.8f)),
        Look("Calm", "Slow blue breathing", fx("breathe", listOf(0xFF40C4FF.toInt(), 0xFF7C4DFF.toInt()), 4f, 2400, 2, 1f)),
        Look("Friendly", "Green pulse", fx("pulse", listOf(0xFF00E676.toInt()), 4f, 1800, 2, 1f)),
        Look("Party", "Rainbow ring", fx("rainbow", listOf(), 4f, 2200, 2, 1f)),
        Look("Warm", "Sunset sweep", fx("sweep", listOf(0xFFFF6D00.toInt(), 0xFFFF4081.toInt(), 0xFFFFD740.toInt()), 4f, 1600, 2, 1f)),
        Look("Default", "Use your default effect", Effect()),
    )
}

/** A tappable card with a title, supporting text and a tick when chosen. */
fun Form.choice(title: String, sub: String, on: Boolean, click: () -> Unit) {
    card(click = click) {
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 16f, bold = true)); if (sub.isNotEmpty()) addView(tv(sub, 13f, p.dim))
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (on) row.addView(tv("✓", 20f, p.primary, true))
        add(row)
    }
}

/** A [choice] for a [Look], with a colour swatch. [fallback] colours stand in for presets that keep the default. */
fun Form.lookCard(l: Look, on: Boolean, fallback: List<Int>?, click: () -> Unit) {
    card(click = click) {
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val cols = (l.fx.colors ?: fallback ?: listOf(Color.WHITE)).let { if (l.fx.style == "rainbow") listOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA) else it }
        val sw = View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, (if (cols.size > 1) cols else cols + cols).toIntArray()).apply { cornerRadius = dp(12).toFloat() }
        }
        row.addView(sw, LinearLayout.LayoutParams(dp(56), dp(36)).also { it.rightMargin = dp(16) })
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; addView(tv(l.name, 16f, bold = true)); addView(tv(l.blurb, 13f, p.dim)) }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (on) row.addView(tv("✓", 20f, p.primary, true))
        add(row)
    }
}
