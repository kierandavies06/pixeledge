package uk.me.kierandavies.pixeledge

import android.app.NotificationManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.util.Calendar

/** Snapshot of everything a rule can match on. */
class Ctx(
    val pkg: String, val channel: String, val category: String, val title: String, val text: String,
    val importance: Int, val charging: Boolean, val screenOn: Boolean, val dnd: Boolean,
    val minuteOfDay: Int, val dow: Int, val all: String,
)

object Engine {
    /** Context for events that aren't notifications (e.g. the phone ringing). */
    fun ctxFor(c: Context, pkg: String, channel: String, category: String, title: String, text: String, importance: Int, all: String): Ctx {
        val cal = Calendar.getInstance()
        return Ctx(
            pkg, channel, category, title, text, importance,
            c.getSystemService(BatteryManager::class.java).isCharging,
            c.getSystemService(PowerManager::class.java).isInteractive,
            c.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL,
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), cal.get(Calendar.DAY_OF_WEEK) - 1, all,
        )
    }

    /** Base effect, then every matching rule's overrides applied in list order. Null = show nothing. */
    fun resolve(cfg: Config, c: Ctx): Effect? {
        if (!cfg.enabled) return null
        var fx = cfg.base
        for (r in cfg.rules) {
            if (!r.on || !matches(r, c)) continue
            if (r.suppress) return null
            fx = fx.over(r.fx)
            if (r.stop) break
        }
        return fx
    }

    private fun tri(want: Int, have: Boolean) = want == 0 || (want == 1) == have

    private fun re(p: String, s: String) = p.isEmpty() || try {
        Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(s)
    } catch (e: Exception) { false }

    fun matches(r: Rule, c: Ctx): Boolean {
        if (r.pkgs.isNotEmpty() && c.pkg !in r.pkgs) return false
        if (c.pkg in r.notPkgs) return false
        if (r.channels.isNotEmpty() && r.channels.none { c.channel.contains(it, true) }) return false
        if (r.categories.isNotEmpty() && c.category !in r.categories) return false
        if (c.importance < r.minImp) return false
        if (!re(r.anyRe, c.all)) return false
        if (!re(r.titleRe, c.title) || !re(r.textRe, c.text)) return false
        if (r.days shr c.dow and 1 == 0) return false
        if (r.from >= 0 && r.to >= 0) {
            val m = c.minuteOfDay
            val inside = if (r.from <= r.to) m >= r.from && m < r.to else m >= r.from || m < r.to
            if (!inside) return false
        }
        return tri(r.charging, c.charging) && tri(r.screen, c.screenOn) && tri(r.dnd, c.dnd)
    }
}
