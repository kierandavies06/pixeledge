package uk.me.kierandavies.pixeledge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager

object Light {
    /** Phone sets these to forward effects to the watch. */
    var mirror: ((Context, Effect) -> Unit)? = null
    var mirrorRing: ((Context, Effect) -> Unit)? = null
    var mirrorRingStop: ((Context) -> Unit)? = null
    private var wl: PowerManager.WakeLock? = null

    fun release() { try { wl?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}; wl = null }

    /** [loop] keeps the light going until [Ring.end]; one-shot lights never interrupt a ringing one. */
    fun fire(c: Context, cfg: Config, fx: Effect, loop: Boolean = false) {
        if (!loop && Ring.active) return
        val screenOn = c.getSystemService(PowerManager::class.java).isInteractive
        if (cfg.wake && !screenOn) {
            if (c.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                // Wear OS ignores wake locks while ambient; a transient turn-screen-on activity wakes it properly.
                Overlay.show(c, fx, loop)   // first, so we hold a visible window and may start an activity from the background
                c.startActivity(Intent(c, Flash::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                return
            }
            val ms = if (loop) 90_000L else (fx.durationMs ?: 1400).toLong() * (fx.repeats ?: 1) + 2500
            release()
            @Suppress("DEPRECATION")
            wl = c.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "edge:wake")
                .also { it.acquire(ms) }
        }
        Overlay.show(c, fx, loop)
    }
}
