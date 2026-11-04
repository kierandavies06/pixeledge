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
    override fun onListenerConnected() {
        Ring.register(this); live = this
        // Seed the list of known notification types from what's in the shade right now.
        try {
            val rk = Ranking()
            activeNotifications?.forEach { if (currentRanking.getRanking(it.key, rk)) learn(this, it.packageName, rk.channel) }
        } catch (e: Exception) {}
    }
    override fun onListenerDisconnected() { if (live === this) live = null }

    companion object {
        @Volatile var live: Svc? = null

        /**
         * The notification types (channels) of an app. Android only lets a listener enumerate other apps' channels if its app holds a
         * companion-device association (see MainActivity.linkWatch: we link a Wi-Fi network for this). Without one the query throws,
         * so we also learn types as notifications arrive and remember them; both sources are merged.
         */
        fun channels(c: android.content.Context, pkg: String): List<Pair<String, String>> {
            val out = LinkedHashMap<String, String>()
            try {
                live?.getNotificationChannels(pkg, android.os.Process.myUserHandle())
                    ?.filter { android.os.Build.VERSION.SDK_INT < 30 || it.conversationId == null }
                    ?.forEach { out[it.id] = it.name.toString() }
            } catch (e: Exception) { Log.d("EdgeLight", "getNotificationChannels($pkg) failed: $e") }
            val j = try { org.json.JSONObject(c.getSharedPreferences("seen", 0).getString("channels", "{}")!!) } catch (e: Exception) { org.json.JSONObject() }
            j.optJSONObject(pkg)?.let { o -> o.keys().forEach { k -> out.putIfAbsent(k, o.optString(k)) } }
            return out.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
        }

        @Synchronized fun learn(c: android.content.Context, pkg: String, ch: android.app.NotificationChannel?) {
            if (ch == null || (android.os.Build.VERSION.SDK_INT >= 30 && ch.conversationId != null)) return
            val sp = c.getSharedPreferences("seen", 0)
            val j = try { org.json.JSONObject(sp.getString("channels", "{}")!!) } catch (e: Exception) { org.json.JSONObject() }
            val o = j.optJSONObject(pkg) ?: org.json.JSONObject().also { j.put(pkg, it) }
            if (o.optString(ch.id) == ch.name.toString()) return
            o.put(ch.id, ch.name.toString())
            sp.edit().putString("channels", j.toString()).apply()
        }
    }
    private var last = 0L

    private fun skip(sbn: StatusBarNotification, why: String) { Log.d("EdgeLight", "skip ${sbn.packageName}: $why") }

    override fun onNotificationPosted(sbn: StatusBarNotification, rm: RankingMap) {
        Log.d("EdgeLight", "posted ${sbn.packageName} flags=${sbn.notification.flags}")
        if (sbn.packageName == packageName) return
        Ranking().also { if (rm.getRanking(sbn.key, it)) learn(this, sbn.packageName, it.channel) }
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
        val out = Engine.decide(cfg, ctx) ?: return skip(sbn, "rule suppressed")
        val fx = out.fx
        Log.d("EdgeLight", "fire ${sbn.packageName} style=${fx.style}")
        last = now
        if (incomingCall) { Ring.begin(this, cfg, fx, sbn.key); return }
        val bubble = Light.bubble
        if (out.bubble != 0 && bubble != null) {
            Log.d("EdgeLight", "bubble ${sbn.packageName} replace=${out.bubble == 2}")
            Light.fire(this, cfg, fx)                       // the screen-edge ring runs too...
            Light.wake(this, cfg, cfg.bubbleMs + 2500L)     // ...and an idle screen stays on for the whole bubble
            bubble(this, sbn, fx, cfg.bubbleMs)
            if (out.bubble == 2 && n.flags and Notification.FLAG_ONGOING_EVENT == 0) {
                Light.replaceNative?.invoke(this, sbn)   // keep a quiet copy in the shade...
                cancelNotification(sbn.key)              // ...and remove the original so its popup goes away
            }
        } else Light.fire(this, cfg, fx)
        if (cfg.watch) Light.mirror?.invoke(this, fx)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        Ring.end(this, sbn.key)
    }
}
