package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*

/** Step-by-step rule creation: what to light up for, when, how it looks, and what popup to show. */
class WizardActivity : Activity() {
    private lateinit var cfg: Config
    private var step = 0
    private var kind = -1                 // 0 app, 1 words, 2 calls, 3 important
    private var pkgs = listOf<String>()
    private var types = listOf<String>()   // "package/channelId"; empty = every type
    private var skip = listOf<String>()
    private var words = ""
    private var timeKind = 0              // 0 any, 1 night, 2 work hours, 3 custom
    private var from = 22 * 60; private var to = 7 * 60
    private var look = 0
    private var popup = 0
    private var name = ""
    private val last = 5

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Store.load(this)
        show()
    }

    override fun onBackPressed() { if (step > 0) { step--; show() } else super.onBackPressed() }

    private fun appLabel(p: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }

    private fun typeName(t: String): String {
        val p = t.substringBefore('/'); val id = t.substringAfter('/')
        val n = Svc.channels(this, p).firstOrNull { it.first == id }?.second ?: id
        return if (pkgs.size > 1) "${appLabel(p)}: $n" else n
    }

    private fun escape(w: String) = w.replace(Regex("[^\\p{L}\\p{N}\\s]")) { "\\" + it.value }
    private fun wordList() = csv(words)

    private fun canNext() = when (step) {
        0 -> kind >= 0
        1 -> when (kind) { 0 -> pkgs.isNotEmpty(); 1 -> wordList().isNotEmpty(); else -> true }
        else -> true
    }

    private fun suggestName() = when (kind) {
        0 -> pkgs.joinToString(", ") { appLabel(it) }
        1 -> wordList().joinToString(", ")
        2 -> "Incoming calls"
        else -> "Important"
    }.ifBlank { "Rule" }

    private fun show() {
        val f = Form(this)
        window.setBackgroundDrawable(ColorDrawable(f.p.bg))
        f.head(when (step) { 0 -> "New rule"; 1 -> "Details"; 2 -> "When"; 3 -> "Look"; 4 -> "Popup"; else -> "All set" })
        f.note("Step ${step + 1} of ${last + 1}")
        lateinit var nameField: EditText
        when (step) {
            0 -> {
                f.section("What should light up?")
                listOf(
                    Triple(0, "An app", "Every notification from chosen apps"),
                    Triple(1, "A name or word", "Notifications that mention something, from any app"),
                    Triple(2, "Calls", "Incoming-call notifications"),
                    Triple(3, "Important notifications", "Anything the app marks as high priority"),
                ).forEach { (k, t, s) -> f.choice(t, s, kind == k) { kind = k; step = 1; show() } }
            }
            1 -> when (kind) {
                0 -> {
                    f.section("Which apps?")
                    f.card { f.add(f.tv(if (pkgs.isEmpty()) "No apps chosen yet" else pkgs.joinToString(", ") { appLabel(it) }, 16f)) }
                    f.button("Choose apps…", 1) { pickApps(this, pkgs) { pkgs = it; types = types.filter { t -> t.substringBefore('/') in it }; show() } }
                    if (pkgs.isNotEmpty()) {
                        f.section("Which notifications?")
                        f.note("Apps send different types of notification. Pick the ones you want, or leave it on all. The full list needs a Wi-Fi network linked on the main screen; otherwise types appear as the app sends them. You can also type one in.")
                        f.card { f.add(f.tv(if (types.isEmpty()) "All types" else types.joinToString(", ") { typeName(it) }, 16f)) }
                        f.button("Choose types…", 1) { pickChannels(this, pkgs, types) { types = it; show() } }
                    }
                }
                1 -> {
                    f.section("Which names or words?")
                    f.note("Separate with commas. Matches the start of a word, ignoring case, in the title, text and messages.")
                    lateinit var w: EditText
                    f.card { w = f.field("Words", words) }
                    w.addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(e: android.text.Editable?) { words = e.toString() }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    })
                    f.section("Ignore these apps (optional)")
                    f.card { f.add(f.tv(if (skip.isEmpty()) "None" else skip.joinToString(", ") { appLabel(it) }, 16f)) }
                    f.button("Choose apps to ignore…", 1) { pickApps(this, skip) { skip = it; show() } }
                }
                else -> {
                    f.section(if (kind == 2) "Calls" else "Important notifications")
                    f.note(if (kind == 2) "Lights up as incoming-call notifications arrive. The ring itself still follows the real call state." else "Matches notifications with high importance, such as messages and calls.")
                }
            }
            2 -> {
                f.section("When should it apply?")
                val names = listOf("Any time", "At night", "Work hours", "Custom")
                val subs = listOf("", "22:00 to 07:00", "09:00 to 17:00, Monday to Friday", "Pick your own times")
                names.forEachIndexed { i, n ->
                    f.choice(n, subs[i], timeKind == i) {
                        timeKind = i
                        when (i) { 1 -> { from = 22 * 60; to = 7 * 60 }; 2 -> { from = 9 * 60; to = 17 * 60 }; 3 -> if (from < 0) { from = 9 * 60; to = 17 * 60 } }
                        show()
                    }
                }
                if (timeKind == 3) {
                    val fb = f.mkButton("From ${hm(from)}") {}; val tb = f.mkButton("Until ${hm(to)}") {}
                    fb.setOnClickListener { TimePickerDialog(this, { _, h, m -> from = h * 60 + m; fb.text = "From ${hm(from)}" }, from / 60, from % 60, true).show() }
                    tb.setOnClickListener { TimePickerDialog(this, { _, h, m -> to = h * 60 + m; tb.text = "Until ${hm(to)}" }, to / 60, to % 60, true).show() }
                    f.add(LinearLayout(this).apply {
                        addView(fb, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = f.dp(8) }); addView(tb)
                    }, 12)
                }
            }
            3 -> {
                f.section("How should it look?")
                Looks.all.forEachIndexed { i, l -> f.lookCard(l, look == i, cfg.base.colors) { look = i; show() } }
                f.button("Preview", 1) { preview(Looks.all[look]) }
            }
            4 -> {
                f.section("Show a popup?")
                val opts = listOf(
                    Triple("Just the light", "The screen-edge light only", 0),
                    Triple("Light and bubble", "A pill drops from the camera with the effect around it. The app's own popup still shows.", 1),
                    Triple("Bubble replaces the app's popup", "Removes the app's own popup and leaves a quiet copy in your shade. Needs the Replace permission.", 2),
                )
                opts.forEach { (t, s, v) -> f.choice(t, s, popup == v) { popup = v; show() } }
            }
            else -> {
                f.section("Name it")
                f.card { nameField = f.field("Name", name.ifBlank { suggestName() }) }
                f.section("Summary")
                f.card { f.add(f.tv(summary(), 15f)) }
                f.button("Preview", 1) { preview(Looks.all[look]) }
                f.button("Fine-tune in the full editor after saving", 0) { save(nameField.text.toString(), true) }
            }
        }
        // Navigation row.
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = f.mkButton(if (step == 0) "Cancel" else "Back", 0) { if (step == 0) finish() else { step--; show() } }
        nav.addView(back); nav.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (step == last) nav.addView(f.mkButton("Save rule", 2) { save(nameField.text.toString(), false) })
        else if (step > 0) {
            val next = f.mkButton("Next", 2) { if (step == 1 && !canNext()) return@mkButton; step++; show() }
            next.alpha = if (canNext()) 1f else 0.4f
            nav.addView(next)
        }
        f.add(nav, 24)
        setContentView(f.scroll())
    }

    private fun preview(l: Look) {
        val fx = cfg.base.over(l.fx)
        Preview.light(this, fx)
        if (popup != 0) Bubble.preview(this, fx, cfg.bubbleMs)
    }

    private fun summary(): String {
        val what = when (kind) {
            0 -> "Notifications from ${pkgs.joinToString(", ") { appLabel(it) }}" + if (types.isNotEmpty()) " (${types.joinToString(", ") { typeName(it) }} only)" else ""
            1 -> "Notifications mentioning ${wordList().joinToString(", ")}" + if (skip.isNotEmpty()) " (not from ${skip.joinToString(", ") { appLabel(it) }})" else ""
            2 -> "Incoming calls"
            else -> "Important notifications"
        }
        val whenTxt = when (timeKind) { 0 -> "at any time"; 1 -> "at night (22:00–07:00)"; 2 -> "during work hours (09:00–17:00, Mon–Fri)"; else -> "from ${hm(from)} to ${hm(to)}" }
        val pop = when (popup) { 0 -> "just the light"; 1 -> "the light and a bubble"; else -> "the light and a bubble replacing the app's popup" }
        return "$what, $whenTxt: ${Looks.all[look].name.lowercase()} look, $pop."
    }

    private fun save(n: String, advanced: Boolean) {
        val r = Rule()
        r.name = n.ifBlank { suggestName() }
        when (kind) {
            0 -> { r.pkgs = pkgs; r.channels = types }
            1 -> { r.anyRe = "\\b(" + wordList().joinToString("|") { escape(it) } + ")"; r.notPkgs = skip }
            2 -> r.categories = listOf("call")
            3 -> r.minImp = 4
        }
        if (timeKind != 0) { r.from = from; r.to = to }
        if (timeKind == 2) r.days = 0b0111110   // Monday to Friday; bit 0 is Sunday
        r.bubble = popup
        r.fx = Looks.all[look].fx
        cfg.rules.add(r)
        Store.save(this, cfg)
        if (advanced) startActivity(Intent(this, RuleActivity::class.java).putExtra("i", cfg.rules.size - 1))
        finish()
    }
}
