package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.*
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.MessageEvent
import org.json.JSONObject
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import java.text.DateFormat
import java.util.Date

/** Receives the phone's config and stores it where the shared listener/engine read it. */
class Receiver : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        val main = Handler(Looper.getMainLooper())
        try {
            when (e.path) {
                "/fire" -> { val fx = Effect.base().over(Effect.from(JSONObject(String(e.data))))
                    main.post { Light.fire(this, Store.load(this), fx) } }   // callbacks arrive off the main thread
                "/ring" -> { val fx = Effect.base().over(Effect.from(JSONObject(String(e.data))))
                    main.post { Ring.begin(this, Store.load(this), fx, "remote", mirror = false) } }
                "/ring_stop" -> main.post { Ring.end(this, "remote", mirror = false) }
            }
        } catch (x: Exception) {}
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == "/config") {
            val d = e.dataItem.data ?: continue
            try { Store.import(this, String(d)) } catch (x: Exception) {}
        }
    }
}

/** ScrollView that responds to the crown / rotary bezel (it delivers AXIS_SCROLL, which ScrollView ignores). */
class CrownScroll(c: Context) : ScrollView(c) {
    init { isFocusable = true; isFocusableInTouchMode = true; isVerticalScrollBarEnabled = false }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val px = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(context).getScaledVerticalScrollFactor()
            scrollBy(0, px.toInt())
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return true
        }
        return super.onGenericMotionEvent(e)
    }
}

class WatchMain : Activity() {
    override fun onResume() { super.onResume(); render() }

    private fun listenerOn() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun render() {
        val p = Pal(this, true)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun rr(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = r * d }
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(34), dp(30), dp(64))
        }
        fun add(v: android.view.View, top: Int = 6) = col.addView(v, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(top) })
        fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(color); gravity = Gravity.CENTER; if (bold) typeface = medium
        }
        fun pill(t: String, ok: Boolean) = add(tv((if (ok) "✓  " else "!  ") + t, 14f, if (ok) p.onTonal else p.onErr, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9)); background = rr(if (ok) p.tonal else p.err, 100)
        })

        add(tv("Edge Light", 20f, p.primary, true).apply { setPadding(0, 0, 0, dp(4)) }, 0)
        pill("Notifications", listenerOn())
        pill("Overlay", Settings.canDrawOverlays(this))
        pill("Lock screen", Overlay.acc != null)

        val at = Store.savedAt(this)
        add(tv(if (at > 0) "Synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) else "Waiting for phone…", 13f, p.dim).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = rr(p.card, 22)
        }, 10)

        add(tv("Test light", 15f, p.onPrimary, true).apply {
            setPadding(dp(12), dp(13), dp(12), dp(13)); background = rr(p.primary, 100)
            isClickable = true; setOnClickListener { Overlay.show(this@WatchMain, Store.load(this@WatchMain).base) }
        }, 12)
        add(tv(BuildInfo.LABEL, 11f, p.dim).apply { setPadding(0, dp(8), 0, 0) }, 10)
        add(tv("Made by Kieran Davies", 12f, p.dim).apply { setPadding(0, dp(8), 0, 0) }, 14)
        add(tv("github.com/kierandavies06", 12f, p.primary, true).apply {
            isClickable = true; setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) } catch (e: Exception) {} }
        }, 0)
        setContentView(CrownScroll(this).apply { setBackgroundColor(Color.BLACK); addView(col); post { requestFocus() } })
    }
}
