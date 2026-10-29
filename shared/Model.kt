package uk.me.kierandavies.pixeledge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val GITHUB = "https://github.com/kierandavies06/"

fun csv(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
fun hm(s: String): Int {
    val p = s.trim().split(':')
    val h = p.getOrNull(0)?.toIntOrNull() ?: return -1
    val m = p.getOrNull(1)?.toIntOrNull() ?: return -1
    return h * 60 + m
}
fun hm(m: Int) = if (m < 0) "" else "%02d:%02d".format(m / 60, m % 60)
private fun strs(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

/** Every field nullable so the same class serves as a full effect and as a partial rule override. */
class Effect {
    var style: String? = null
    var colors: List<Int>? = null
    var widthDp: Float? = null
    var durationMs: Int? = null
    var repeats: Int? = null
    var glow: Float? = null

    fun over(o: Effect) = Effect().also {
        it.style = o.style ?: style
        it.colors = o.colors ?: colors
        it.widthDp = o.widthDp ?: widthDp
        it.durationMs = o.durationMs ?: durationMs
        it.repeats = o.repeats ?: repeats
        it.glow = o.glow ?: glow
    }

    fun toJson() = JSONObject().also { j ->
        style?.let { j.put("style", it) }
        colors?.let { j.put("colors", JSONArray(it)) }
        widthDp?.let { j.put("width", it.toDouble()) }
        durationMs?.let { j.put("duration", it) }
        repeats?.let { j.put("repeats", it) }
        glow?.let { j.put("glow", it.toDouble()) }
    }

    companion object {
        val STYLES = listOf("sweep", "chase", "rainbow", "pulse", "breathe", "strobe", "solid")

        fun base() = Effect().also {
            it.style = "sweep"
            it.colors = listOf(0xFF00E5FF.toInt(), 0xFFD500F9.toInt(), 0xFFFF4081.toInt())
            it.widthDp = 4f; it.durationMs = 1400; it.repeats = 2; it.glow = 1f
        }

        fun from(j: JSONObject?) = Effect().also { e ->
            if (j == null) return@also
            e.style = j.optString("style").ifEmpty { null }
            j.optJSONArray("colors")?.let { a -> e.colors = List(a.length()) { a.getInt(it) } }
            if (j.has("width")) e.widthDp = j.getDouble("width").toFloat()
            if (j.has("duration")) e.durationMs = j.getInt("duration")
            if (j.has("repeats")) e.repeats = j.getInt("repeats")
            if (j.has("glow")) e.glow = j.getDouble("glow").toFloat()
        }
    }
}

class Rule {
    var name = "New rule"
    var on = true
    var pkgs = listOf<String>()
    var notPkgs = listOf<String>()       // never match these apps
    var channels = listOf<String>()      // substring match on channel id
    var categories = listOf<String>()    // exact match on Notification.category
    var anyRe = ""                       // regex over title, text, expanded text, sub-text, message lines…
    var titleRe = ""
    var textRe = ""
    var minImp = 0                       // 0 = any
    var from = -1; var to = -1           // minutes since midnight, -1 = any time
    var days = 127                       // bit0 = Sunday
    var charging = 0; var screen = 0; var dnd = 0   // 0 any, 1 yes, 2 no
    var suppress = false                 // matching notifications show nothing
    var stop = false                     // don't evaluate later rules
    var fx = Effect()

    fun toJson() = JSONObject().also {
        it.put("name", name); it.put("on", on)
        it.put("pkgs", JSONArray(pkgs)); it.put("notPkgs", JSONArray(notPkgs)); it.put("anyRe", anyRe); it.put("channels", JSONArray(channels)); it.put("cats", JSONArray(categories))
        it.put("titleRe", titleRe); it.put("textRe", textRe); it.put("minImp", minImp)
        it.put("from", from); it.put("to", to); it.put("days", days)
        it.put("charging", charging); it.put("screen", screen); it.put("dnd", dnd)
        it.put("suppress", suppress); it.put("stop", stop); it.put("fx", fx.toJson())
    }

    companion object {
        fun from(j: JSONObject) = Rule().also {
            it.name = j.optString("name", "Rule"); it.on = j.optBoolean("on", true)
            it.pkgs = strs(j.optJSONArray("pkgs")); it.notPkgs = strs(j.optJSONArray("notPkgs")); it.anyRe = j.optString("anyRe"); it.channels = strs(j.optJSONArray("channels"))
            it.categories = strs(j.optJSONArray("cats"))
            it.titleRe = j.optString("titleRe"); it.textRe = j.optString("textRe"); it.minImp = j.optInt("minImp")
            it.from = j.optInt("from", -1); it.to = j.optInt("to", -1); it.days = j.optInt("days", 127)
            it.charging = j.optInt("charging"); it.screen = j.optInt("screen"); it.dnd = j.optInt("dnd")
            it.suppress = j.optBoolean("suppress"); it.stop = j.optBoolean("stop")
            it.fx = Effect.from(j.optJSONObject("fx"))
        }
    }
}

class Config {
    var enabled = true
    var ignoreOngoing = true
    var ignoreSummary = true
    var respectDnd = false
    var wake = false
    var watch = true                     // phone only: forward fired effects to the watch
    var cooldownMs = 0
    var base = Effect.base()
    val rules = mutableListOf<Rule>()

    fun toJson() = JSONObject().also {
        it.put("enabled", enabled); it.put("ignoreOngoing", ignoreOngoing); it.put("ignoreSummary", ignoreSummary)
        it.put("respectDnd", respectDnd); it.put("wake", wake); it.put("watch", watch); it.put("cooldown", cooldownMs); it.put("base", base.toJson())
        it.put("rules", JSONArray().also { a -> rules.forEach { r -> a.put(r.toJson()) } })
    }

    companion object {
        fun from(j: JSONObject) = Config().also { c ->
            c.enabled = j.optBoolean("enabled", true); c.ignoreOngoing = j.optBoolean("ignoreOngoing", true)
            c.ignoreSummary = j.optBoolean("ignoreSummary", true); c.respectDnd = j.optBoolean("respectDnd"); c.wake = j.optBoolean("wake"); c.watch = j.optBoolean("watch", true)
            c.cooldownMs = j.optInt("cooldown"); c.base = Effect.base().over(Effect.from(j.optJSONObject("base")))
            j.optJSONArray("rules")?.let { a -> for (i in 0 until a.length()) c.rules.add(Rule.from(a.getJSONObject(i))) }
        }

        fun default() = Config().also { c ->
            c.rules.add(Rule().also {
                it.name = "Urgent (high importance)"; it.minImp = 4
                it.fx.style = "strobe"; it.fx.colors = listOf(0xFFFF1744.toInt(), 0xFFFF9100.toInt()); it.fx.repeats = 3
            })
            c.rules.add(Rule().also {
                it.name = "Incoming call"; it.categories = listOf("call")
                it.fx.style = "pulse"; it.fx.colors = listOf(0xFF00E676.toInt(), 0xFF69F0AE.toInt())
                it.fx.durationMs = 1000; it.fx.widthDp = 6f; it.fx.glow = 2f
            })
            c.rules.add(Rule().also {
                it.name = "Night: slow white breathe"; it.on = false; it.from = 22 * 60; it.to = 7 * 60
                it.fx.style = "breathe"; it.fx.colors = listOf(0xFFFFFFFF.toInt()); it.fx.durationMs = 2500
                it.fx.repeats = 1; it.fx.widthDp = 2f
            })
        }
    }
}

object Store {
    private var cache: Config? = null
    private var stamp = -1L
    /** Phone sets this to mirror every save to the watch. */
    var hook: ((Context, Config) -> Unit)? = null
    private fun file(c: Context) = File(c.applicationContext.filesDir, "config.json")

    @Synchronized fun load(c: Context): Config {
        val f = file(c)
        val m = if (f.exists()) f.lastModified() else 0L
        cache?.let { if (m == stamp) return it }
        val cfg = try { Config.from(JSONObject(f.readText())) } catch (e: Exception) { Config.default() }
        cache = cfg; stamp = m
        return cfg
    }

    @Synchronized fun save(c: Context, cfg: Config) {
        val f = file(c)
        f.writeText(cfg.toJson().toString(1))
        cache = cfg; stamp = f.lastModified()
        hook?.invoke(c, cfg)
    }

    fun savedAt(c: Context) = file(c).lastModified()

    /** Watch side: accept a config pushed from the phone (validated by parsing first). */
    fun import(c: Context, json: String) {
        val cfg = Config.from(JSONObject(json))
        val prev = hook; hook = null
        try { save(c, cfg) } finally { hook = prev }
    }
}
