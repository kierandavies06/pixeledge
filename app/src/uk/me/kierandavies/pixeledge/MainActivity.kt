package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.ViewTreeObserver
import android.widget.*

class MainActivity : Activity() {
    private var save: () -> Unit = {}
    private lateinit var content: FrameLayout
    private lateinit var nav: NavBar
    private lateinit var shell: LinearLayout

    companion object { private var tab = 0 }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Pal(this)
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        content = FrameLayout(this)
        nav = NavBar(this, p, listOf("Home" to 0, "Rules" to 1, "Look" to 2, "Settings" to 3)) { i ->
            if (i != tab) { save(); tab = i; render() }
        }
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f)); addView(nav, LinearLayout.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { _, ins ->
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(b.left, b.top, b.right, 0); nav.setPadding(b.left, 0, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(shell)
        // First launch: walk new installs through setup. Existing installs that already work are marked done.
        if (!OnboardingActivity.done(this)) {
            if (listenerOn() && Settings.canDrawOverlays(this)) OnboardingActivity.markDone(this)
            else startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() { super.onResume(); importPending(); render(); Sync.push(this, Store.load(this)) }
    override fun onPause() { super.onPause(); save() }

    /** Dev import: rules JSON dropped in the app's external files dir (adb push) is merged by name, then deleted. */
    private fun importPending() {
        val f = java.io.File(getExternalFilesDir(null), "import.json")
        if (!f.exists()) return
        try {
            val j = org.json.JSONObject(f.readText())
            val arr = j.optJSONArray("rules") ?: org.json.JSONArray().put(j)
            val cfg = Store.load(this)
            for (i in 0 until arr.length()) {
                val r = Rule.from(arr.getJSONObject(i))
                cfg.rules.removeAll { it.name == r.name }; cfg.rules.add(r)
            }
            Store.save(this, cfg)
            Toast.makeText(this, "Imported ${arr.length()} rule(s)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
        f.delete()
    }

    private fun listenerOn() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg.substringAfterLast('.') }

    private fun summary(r: Rule): String {
        val s = mutableListOf<String>()
        if (r.pkgs.isNotEmpty()) s += r.pkgs.joinToString(", ") { appLabel(it) }
        if (r.notPkgs.isNotEmpty()) s += "not " + r.notPkgs.joinToString(", ") { appLabel(it) }
        if (r.anyRe.isNotEmpty()) s += "any /${r.anyRe}/"
        if (r.channels.isNotEmpty()) s += "type " + r.channels.joinToString("/") { it.substringAfter('/') }
        if (r.categories.isNotEmpty()) s += r.categories.joinToString("/")
        if (r.titleRe.isNotEmpty()) s += "title /${r.titleRe}/"
        if (r.textRe.isNotEmpty()) s += "text /${r.textRe}/"
        if (r.minImp > 0) s += "≥ " + mapOf(2 to "low", 3 to "default", 4 to "high")[r.minImp]
        if (r.from >= 0 && r.to >= 0) s += "${hm(r.from)}–${hm(r.to)}"
        if (r.days != 127) s += listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").filterIndexed { i, _ -> r.days shr i and 1 == 1 }.joinToString(" ")
        if (r.charging != 0) s += if (r.charging == 1) "charging" else "unplugged"
        if (r.screen != 0) s += if (r.screen == 1) "screen on" else "screen off"
        if (r.dnd != 0) s += if (r.dnd == 1) "DND" else "no DND"
        val cond = if (s.isEmpty()) "Any notification" else s.joinToString(" · ")
        val act = if (r.suppress) "no light" else (r.fx.style ?: "default effect")
        val pop = when (r.bubble) { 1 -> " · bubble"; 2 -> " · bubble, replaces native"; 3 -> " · no bubble"; else -> "" }
        return "$cond  →  $act$pop" + if (r.stop) " (stop)" else ""
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        Ring.register(this); render()
    }

    /** Press-and-hold a rule card, then drag it; the other cards slide aside and the order is saved on release. */
    private fun dragToReorder(card: View, r: Rule, cfg: Config) {
        var on = false
        var downRaw = 0f; var scroll0 = 0; var fromIdx = 0; var toIdx = 0; var lastRaw = 0f
        var slot = 0f
        val box = card.parent as LinearLayout
        fun sv(): ScrollView? { var p = card.parent; while (p != null && p !is ScrollView) p = p.parent; return p as? ScrollView }
        fun dy() = lastRaw - downRaw + ((sv()?.scrollY ?: 0) - scroll0)
        val tick = object : Runnable {
            override fun run() {
                if (!on) return
                val s = sv() ?: return
                val loc = IntArray(2); s.getLocationOnScreen(loc)
                val y = lastRaw - loc[1]; val edge = 110 * resources.displayMetrics.density
                val step = when { y < edge -> -((edge - y) / edge * 28).toInt(); y > s.height - edge -> ((y - (s.height - edge)) / edge * 28).toInt(); else -> 0 }
                if (step != 0) { s.scrollBy(0, step); move() }
                card.postOnAnimation(this)
            }
            fun move() {
                card.translationY = dy()
                val center = card.top + card.height / 2f + dy()
                var t = 0
                for (j in 0 until box.childCount) { val o = box.getChildAt(j); if (o !== card && o.top + o.height / 2f < center) t++ }
                toIdx = t
                for (j in 0 until box.childCount) {
                    val o = box.getChildAt(j); if (o === card) continue
                    val want = when { j > fromIdx && j <= toIdx -> -slot; j < fromIdx && j >= toIdx -> slot; else -> 0f }
                    if (o.translationY != want) o.animate().translationY(want).setDuration(150).start()
                }
            }
        }
        // Re-derive move() through the runnable so scrolling and finger motion share one code path.
        fun move() = tick.move()
        card.setOnLongClickListener {
            val s = sv() ?: return@setOnLongClickListener false
            on = true; fromIdx = box.indexOfChild(card); toIdx = fromIdx
            scroll0 = s.scrollY; downRaw = lastRaw
            slot = card.height + (card.layoutParams as LinearLayout.LayoutParams).topMargin.toFloat()
            card.parent.requestDisallowInterceptTouchEvent(true)
            card.elevation = 12 * resources.displayMetrics.density; card.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            card.postOnAnimation(tick)
            true
        }
        card.setOnTouchListener { _, e ->
            lastRaw = e.rawY
            if (e.actionMasked == MotionEvent.ACTION_DOWN) downRaw = e.rawY
            if (!on) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> move()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    on = false
                    val visual = card.top + card.translationY
                    if (e.actionMasked == MotionEvent.ACTION_UP && toIdx != fromIdx) {
                        cfg.rules.add(toIdx, cfg.rules.removeAt(fromIdx))
                        Store.save(this, cfg)
                        box.removeView(card); box.addView(card, toIdx)
                    }
                    for (j in 0 until box.childCount) box.getChildAt(j).let { if (it !== card) { it.animate().cancel(); it.translationY = 0f } }
                    card.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    card.elevation = 0f
                    // Slide the dropped card from where the finger left it into its slot.
                    card.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            card.viewTreeObserver.removeOnPreDrawListener(this)
                            card.translationY = visual - card.top
                            card.animate().translationY(0f).setDuration(180).start()
                            return true
                        }
                    })
                    card.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun companions(): List<String> = try { getSystemService(android.companion.CompanionDeviceManager::class.java).associations } catch (e: Exception) { emptyList() }

    /**
     * Registers Edge Light as a companion app of a Wi-Fi network. This is a deliberate loophole: Android only lets a notification
     * listener list other apps' notification channels if the app holds a companion-device association, and it appears not to care what
     * kind of "device" that is. A Wi-Fi network needs no Bluetooth pairing and (unlike a connected watch) shows up in the chooser.
     * The association gives Edge Light no access to the network. See "Notification types" in the README.
     */
    @Suppress("DEPRECATION")
    private fun linkWatch() {
        val cdm = getSystemService(android.companion.CompanionDeviceManager::class.java)
        try { cdm.associate(android.companion.AssociationRequest.Builder().addDeviceFilter(android.companion.WifiDeviceFilter.Builder().build()).build(), object : android.companion.CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooser: android.content.IntentSender) {
                try { startIntentSenderForResult(chooser, 77, null, 0, 0, 0) } catch (e: Exception) { Toast.makeText(this@MainActivity, "Couldn't open the device chooser", Toast.LENGTH_LONG).show() }
            }
            override fun onFailure(error: CharSequence?) { Toast.makeText(this@MainActivity, "Linking failed: $error", Toast.LENGTH_LONG).show() }
        }, null) } catch (e: Exception) { Toast.makeText(this, "Linking failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    override fun onActivityResult(code: Int, res: Int, data: Intent?) {
        super.onActivityResult(code, res, data)
        if (code == 77 && res == RESULT_OK) {
            // Reconnect the listener so it picks up its new standing, then refresh.
            android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(this, Svc::class.java))
            render()
        }
    }

    /** Shows the current tab. Each tab builds its own form and sets [save] to write back whatever it edits. */
    private fun render() {
        save = {}
        nav.setSelected(tab)
        val f = Form(this)
        val cfg = Store.load(this)
        when (tab) {
            0 -> home(f, cfg)
            1 -> rules(f, cfg)
            2 -> look(f, cfg)
            else -> settings(f, cfg)
        }
        val keep = (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        val sv = f.scroll(false)
        content.removeAllViews(); content.addView(sv)
        // render() rebuilds the tab, so put the scroll position back (e.g. after deleting a rule).
        if (keep > 0) sv.post { sv.scrollTo(0, keep) }
    }

    private fun missing() = listOf(
        Triple("Notification access", "Lets Edge Light see notifications", listenerOn()),
        Triple("Display over other apps", "Lets the light draw on screen", Settings.canDrawOverlays(this)),
        Triple("Lock screen overlay", "Shows the light over the lock screen", Overlay.acc != null),
        Triple("Call state", "Follows the real ringing state", Ring.hasPermission(this)),
        Triple("Post notifications", "Needed to replace an app's own popup", Quiet.hasPermission(this)),
    )

    private fun fixIt(i: Int) = when (i) {
        0 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        1 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        2 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        3 -> requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 1)
        else -> requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    /** One tappable list row: title, supporting text and a trailing status. */
    private fun statusRow(f: Form, title: String, sub: String, ok: Boolean, click: () -> Unit) {
        f.card(top = 8, click = click) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(f.tv(title, 16f)); addView(f.tv(sub, 13f, f.p.dim)) }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(f.tv(if (ok) "On" else "Turn on", 13f, if (ok) f.p.dim else f.p.primary, true))
            f.add(row)
        }
    }

    private fun home(f: Form, cfg: Config) {
        f.head("Edge Light")
        val ready = missing().take(2).all { it.third }
        val miss = missing().filter { !it.third }
        // The preview is the memorable thing: the user's own effect, lit around a phone.
        val hero = EdgePreview(this, cfg.base)
        hero.isClickable = true; hero.contentDescription = "Preview the light on screen"
        hero.setOnClickListener { Preview.light(this, Store.load(this).base) }
        f.add(hero, 16).layoutParams = LinearLayout.LayoutParams(f.dp(170), f.dp(320)).also { it.topMargin = f.dp(16); it.gravity = Gravity.CENTER_HORIZONTAL }
        f.add(f.tv(
            when { !cfg.enabled -> "Switched off"; !ready -> "Needs setup"; else -> "Ready" }, 28f, bold = true
        ).apply { gravity = Gravity.CENTER }, 8)
        f.add(f.tv(
            when { !cfg.enabled -> "Notifications won't light the screen."; !ready -> "Turn on the permissions below to start."; else -> "Tap the phone to preview your light." },
            14f, f.p.dim
        ).apply { gravity = Gravity.CENTER }, 2)

        f.card(24) {
            f.switchRow("Light up for notifications", null, cfg.enabled) { v -> cfg.enabled = v; Store.save(this, cfg); render() }
        }

        if (miss.isNotEmpty()) {
            f.section("Needs attention")
            miss.forEach { (t, s, ok) -> statusRow(f, t, s, ok) { fixIt(missing().indexOfFirst { m -> m.first == t }) } }
        }

        val callIdx = cfg.rules.indexOfFirst { "call" in it.categories }
        if (callIdx >= 0) {
            val r = cfg.rules[callIdx]
            f.section("Incoming calls")
            ruleRow(f, cfg, r, "How a ringing call lights up")
        }
        val others = cfg.rules.filterIndexed { i, _ -> i != callIdx }
        if (others.isNotEmpty()) {
            f.section("Your rules")
            others.forEach { ruleRow(f, cfg, it, summary(it)) }
        }

        f.section("Try it")
        val row = LinearLayout(this)
        fun chip(t: String, a: () -> Unit) = row.addView(f.mkButton(t, 1, a), LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = f.dp(8) })
        chip("Preview bubble") { val c = Store.load(this); Bubble.preview(this, c.base, c.bubbleMs) }
        chip("Test incoming call") {
            val c = Store.load(this)
            val fx = Engine.resolve(c, Engine.ctxFor(this, "android.telecom", "", "call", "", "", 4, "")) ?: c.base
            Ring.begin(this, c, fx, "test")
            window.decorView.postDelayed({ Ring.end(this, "test") }, 6000)
        }
        f.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 8)
    }

    /** Plays what a rule does: its effect, and a bubble if the rule (or the default popup style) asks for one. */
    private fun previewRule(cfg: Config, r: Rule) {
        val fx = cfg.base.over(r.fx)
        val style = if (r.bubble == 0) cfg.bubble else r.bubble
        if (style in 1..2) Bubble.preview(this, fx, cfg.bubbleMs) else Preview.light(this, fx)
    }

    /** A rule on the home screen: tap to edit, or preview it right there. */
    private fun ruleRow(f: Form, cfg: Config, r: Rule, sub: String) {
        f.card(8, click = { startActivity(Intent(this, RuleActivity::class.java).putExtra("i", cfg.rules.indexOf(r))) }) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(f.tv(r.name, 16f, if (r.on) f.p.text else f.p.dim, true))
                addView(f.tv(if (r.on) sub else "Off", 13f, f.p.dim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(f.mkButton("Preview", 1) { previewRule(cfg, r) }.apply { setPadding(f.dp(18), f.dp(8), f.dp(18), f.dp(8)) },
                LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = f.dp(12) })
            f.add(row)
        }
    }

    private fun look(f: Form, cfg: Config) {
        f.head("Look")
        f.note("The default effect. Rules can change any part of it for particular notifications.")
        lateinit var fx: EffectForm
        f.card(16) { fx = EffectForm(f, cfg.base, false) { Effect.base() } }
        f.button("Preview", 1) { cfg.base = Effect.base().over(fx.read()); Store.save(this, cfg); Preview.light(this, cfg.base) }
        save = { cfg.base = Effect.base().over(fx.read()); Store.save(this, cfg) }
    }

    private fun settings(f: Form, cfg: Config) {
        f.head("Settings")
        lateinit var ong: Switch; lateinit var sum: Switch; lateinit var dnd: Switch; lateinit var wake: Switch; lateinit var mirror: Switch
        lateinit var gap: Slider; lateinit var bubbleDur: Slider
        f.section("Notifications")
        f.card {
            ong = f.switchRow("Ignore ongoing", "Music, timers, navigation", cfg.ignoreOngoing)
            sum = f.switchRow("Ignore group summaries", null, cfg.ignoreSummary)
            dnd = f.switchRow("Respect Do Not Disturb", "Stay dark for notifications DND silences", cfg.respectDnd)
            gap = f.slider("Minimum gap", 0f, 5000f, 100f, cfg.cooldownMs.toFloat(), false, " ms")
        }
        f.section("Screen")
        f.card { wake = f.switchRow("Wake screen", "Turn the display on to show the light", cfg.wake) }
        f.section("Bubbles")
        f.note("A bubble is a small pill that drops from the camera, with the light running around it. Choose the default here. Any rule can override it with its own Popup style.")
        var bubbleStyle = cfg.bubble
        f.card {
            f.label("Default popup style")
            f.chips(listOf("Off", "Bubble", "Bubble + replace native"), cfg.bubble) { v ->
                bubbleStyle = v
                if (v == 2 && !Quiet.hasPermission(this)) fixIt(4)
            }
            f.note("Replace native removes the app's own popup and leaves a quiet copy in your shade. It needs the Post notifications permission.")
            bubbleDur = f.slider("Bubble duration", 2000f, 10000f, 500f, cfg.bubbleMs.toFloat(), false, " ms")
            f.button("Preview bubble", 1) { save(); val c = Store.load(this); Bubble.preview(this, c.base, c.bubbleMs) }
        }
        f.section("Watch")
        f.card { mirror = f.switchRow("Mirror to watch", "Play the same light on your watch", cfg.watch) }
        f.section("Permissions")
        missing().forEachIndexed { i, (t, s, ok) -> statusRow(f, t, s, ok) { fixIt(i) } }
        f.section("Notification types")
        f.note("Linking a Wi-Fi network lets rules list every notification type an app declares.")
        f.button(if (companions().isEmpty()) "Link a Wi-Fi network" else "Wi-Fi linked · link another", 1) { linkWatch() }
        f.section("About")
        f.card {
            f.add(f.tv("Edge Light", 18f, bold = true))
            f.add(f.tv("Version ${BuildInfo.VERSION} (build ${BuildInfo.CODE}) · open source", 13f, f.p.dim))
            f.add(f.tv("Commit ${BuildInfo.COMMIT} · built ${BuildInfo.TIME}", 13f, f.p.dim))
            f.add(f.tv("Made by Kieran Davies", 15f), 12)
            f.button("Run setup again", 1) { startActivity(Intent(this, OnboardingActivity::class.java)) }
            f.button("github.com/kierandavies06", 1) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) } catch (e: Exception) {} }
        }
        save = {
            cfg.ignoreOngoing = ong.isChecked; cfg.ignoreSummary = sum.isChecked; cfg.respectDnd = dnd.isChecked; cfg.wake = wake.isChecked
            cfg.bubble = bubbleStyle; cfg.watch = mirror.isChecked; cfg.cooldownMs = gap.value()?.toInt() ?: 0; cfg.bubbleMs = bubbleDur.value()?.toInt() ?: 4000
            Store.save(this, cfg)
        }
    }

    private fun rules(f: Form, cfg: Config) {
        f.head("Rules")
        f.note("Applied top to bottom. Each matching rule overrides only the settings it sets. Press and hold a rule, then drag to reorder.")
        f.group {
          cfg.rules.toList().forEach { r ->
            val card = f.card(click = { startActivity(Intent(this, RuleActivity::class.java).putExtra("i", cfg.rules.indexOf(r))) }) {
                val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(f.tv(r.name, 16f, bold = true))
                    addView(f.tv(summary(r), 13f, f.p.dim))
                }
                val sw = Switch(this).apply {
                    isChecked = r.on
                    setOnCheckedChangeListener { _, v -> r.on = v; Store.save(this@MainActivity, cfg) }
                }
                val more = f.tv("⋮", 22f, f.p.dim).apply {
                    setPadding(f.dp(14), f.dp(4), f.dp(4), f.dp(4))
                    setOnClickListener { a ->
                        PopupMenu(this@MainActivity, a).apply {
                            menu.add("Duplicate"); menu.add("Delete")
                            setOnMenuItemClickListener {
                                save()
                                val i = cfg.rules.indexOf(r)
                                when (it.title) {
                                    "Duplicate" -> cfg.rules.add(i + 1, Rule.from(r.toJson()).also { d -> d.name = r.name + " copy" })
                                    else -> cfg.rules.removeAt(i)
                                }
                                Store.save(this@MainActivity, cfg); render(); true
                            }
                        }.show()
                    }
                }
                top.addView(col, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(sw); top.addView(more)
                f.add(top)
            }
            dragToReorder(card, r, cfg)
          }
        }
        f.button("Add rule", 2) { startActivity(Intent(this, WizardActivity::class.java)) }
        f.button("Add rule manually", 0) { startActivity(Intent(this, RuleActivity::class.java).putExtra("i", -1)) }
    }
}
