package uk.me.kierandavies.pixeledge

import android.app.Notification
import android.app.NotificationManager
import android.os.BatteryManager
import android.telecom.TelecomManager
import android.util.Log
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Calendar

class Svc : NotificationListenerService() {
    private val seen = HashMap<String, Int>()

    override fun onCreate() { super.onCreate(); Ring.register(this) }
    override fun onListenerConnected() { Ring.register(this) }
    private var last = 0L

    private fun skip(sbn: StatusBarNotification, why: String) { Log.d("EdgeLight", "skip ${sbn.packageName}: $why") }

    override fun onNotificationPosted(sbn: StatusBarNotification, rm: RankingMap) {
        Log.d("EdgeLight", "posted ${sbn.packageName} flags=${sbn.notification.flags}")
        if (sbn.packageName == packageName) return
        val cfg = Store.load(this)
        if (!cfg.enabled) return skip(sbn, "disabled")
        val n = sbn.notification
        // Incoming call (CallStyle / full-screen intent): ring for as long as the notification exists.
        // The dialer's own calls are handled by the telephony ring state instead.
        val incomingCall = n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null
        if (incomingCall && sbn.packageName == getSystemService(TelecomManager::class.java).defaultDialerPackage) return skip(sbn, "dialer call (ring state)")
        if (!incomingCall && cfg.ignoreOngoing && n.flags and Notification.FLAG_ONGOING_EVENT != 0) return skip(sbn, "ongoing")
        if (cfg.ignoreSummary && n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return skip(sbn, "summary")
        val rk = Ranking()
        if (!rm.getRanking(sbn.key, rk) || rk.importance <= NotificationManager.IMPORTANCE_NONE) return skip(sbn, "importance/ranking")

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val all = buildString {
            for (k in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_CONVERSATION_TITLE))
                ex.getCharSequence(k)?.let { append(it).append('\n') }
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { append(it).append('\n') }
            @Suppress("DEPRECATION")
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { m ->
                (m as? android.os.Bundle)?.let { b -> b.getCharSequence("text")?.let { append(it).append('\n') }; b.getCharSequence("sender")?.let { append(it).append('\n') } }
            }
        }
        // Updates to an existing notification (progress ticks, re-posts) shouldn't re-fire.
        val sig = (title + "\u0000" + text + "\u0000" + n.`when`).hashCode()
        if (seen.put(sbn.key, sig) == sig) return skip(sbn, "duplicate")

        val dnd = getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        if (cfg.respectDnd && dnd && !rk.matchesInterruptionFilter()) return skip(sbn, "dnd")
        val now = SystemClock.elapsedRealtime()
        if (now - last < cfg.cooldownMs) return skip(sbn, "cooldown")

        val cal = Calendar.getInstance()
        val ctx = Ctx(
            sbn.packageName, rk.channel?.id ?: "", n.category ?: "", title, text, rk.importance,
            getSystemService(BatteryManager::class.java).isCharging,
            getSystemService(PowerManager::class.java).isInteractive, dnd,
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), cal.get(Calendar.DAY_OF_WEEK) - 1, all,
        )
        val fx = Engine.resolve(cfg, ctx) ?: return skip(sbn, "rule suppressed")
        Log.d("EdgeLight", "fire ${sbn.packageName} style=${fx.style}")
        last = now
        if (incomingCall) { Ring.begin(this, cfg, fx, sbn.key); return }
        Light.fire(this, cfg, fx)
        if (cfg.watch) Light.mirror?.invoke(this, fx)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        Ring.end(this, sbn.key)
    }
}
