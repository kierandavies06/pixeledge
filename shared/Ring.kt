package uk.me.kierandavies.pixeledge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/** A light that runs for as long as something is ringing: the phone network (call state) or an incoming-call notification. */
object Ring {
    @Volatile var active = false
    private var owner: String? = null
    private var app: Context? = null
    private var cb: TelephonyCallback? = null
    private val h = Handler(Looper.getMainLooper())
    private val cap = Runnable { app?.let { end(it) } }   // safety net so a missed "stopped" event can't leave it on

    fun hasPermission(c: Context) = c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun register(c: Context) {
        if (cb != null || !hasPermission(c)) return
        val a = c.applicationContext
        app = a
        val l = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) { h.post { onState(a, state) } }
        }
        try { a.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(a.mainExecutor, l); cb = l } catch (e: Exception) {}
    }

    private fun onState(c: Context, state: Int) {
        if (state != TelephonyManager.CALL_STATE_RINGING) return end(c, "tel")
        val cfg = Store.load(c)
        if (!cfg.enabled) return
        val fx = Engine.resolve(cfg, Engine.ctxFor(c, "android.telecom", "", "call", "", "", 4, "")) ?: return
        begin(c, cfg, fx, "tel")
    }

    fun begin(c: Context, cfg: Config, fx: Effect, owner: String, mirror: Boolean = true) {
        if (active) return
        app = c.applicationContext
        active = true; this.owner = owner
        Light.fire(c, cfg, fx, loop = true)
        if (mirror && cfg.watch) Light.mirrorRing?.invoke(c, fx)
        h.postDelayed(cap, 90_000)
    }

    fun end(c: Context, owner: String? = null, mirror: Boolean = true) {
        if (!active || (owner != null && owner != this.owner)) return
        active = false
        h.removeCallbacks(cap)
        Overlay.stop(); Light.release()
        if (mirror) Light.mirrorRingStop?.invoke(c)
    }
}
