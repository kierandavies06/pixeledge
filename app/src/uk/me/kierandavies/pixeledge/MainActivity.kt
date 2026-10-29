package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private var save: () -> Unit = {}

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
        if (r.channels.isNotEmpty()) s += "channel ~ " + r.channels.joinToString("/")
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
        return "$cond  →  $act" + if (r.stop) " (stop)" else ""
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        Ring.register(this); render()
    }

    private fun render() {
        val cfg = Store.load(this)
        val f = Form(this)
        window.setBackgroundDrawable(ColorDrawable(f.p.bg))
        f.head("Edge Light")

        val row = LinearLayout(this)
        fun pill(t: String, ok: Boolean, a: () -> Unit) =
            row.addView(f.pill(t, ok, a), LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = f.dp(8) })
        pill("Notifications", listenerOn()) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        pill("Overlay", Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        pill("Lock screen", Overlay.acc != null) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        pill("Calls", Ring.hasPermission(this)) { requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 1) }
        f.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, 12)

        lateinit var en: Switch; lateinit var ong: Switch; lateinit var sum: Switch; lateinit var dnd: Switch; lateinit var wake: Switch; lateinit var mirror: Switch
        lateinit var gap: Slider
        f.card(16) { en = f.switchRow("Enabled", "Light the screen edges for notifications", cfg.enabled) }

        f.section("Behaviour")
        f.card {
            ong = f.switchRow("Ignore ongoing", "Music, timers, navigation", cfg.ignoreOngoing)
            sum = f.switchRow("Ignore group summaries", null, cfg.ignoreSummary)
            dnd = f.switchRow("Respect Do Not Disturb", "Stay dark for notifications DND silences", cfg.respectDnd)
            wake = f.switchRow("Wake screen", "Turn the display on to show the light", cfg.wake)
            f.button("Test incoming call (6 s)", 1) {
                save()
                val c = Store.load(this)
                val fx = Engine.resolve(c, Engine.ctxFor(this, "android.telecom", "", "call", "", "", 4, "")) ?: c.base
                Ring.begin(this, c, fx, "test")
                window.decorView.postDelayed({ Ring.end(this, "test") }, 6000)
            }
            mirror = f.switchRow("Mirror to watch", "Play the same light on your watch", cfg.watch)
            gap = f.slider("Minimum gap", 0f, 5000f, 100f, cfg.cooldownMs.toFloat(), false, " ms")
        }

        f.section("Default effect")
        lateinit var fx: EffectForm
        f.card { fx = EffectForm(f, cfg.base, false) { Effect.base() } }

        f.section("Rules")
        f.note("Applied top to bottom. Each matching rule overrides only the settings it sets.")
        cfg.rules.forEachIndexed { i, r ->
            f.card(click = { save(); startActivity(Intent(this, RuleActivity::class.java).putExtra("i", i)) }) {
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
                            menu.add("Move up").isEnabled = i > 0
                            menu.add("Move down").isEnabled = i < cfg.rules.size - 1
                            menu.add("Duplicate"); menu.add("Delete")
                            setOnMenuItemClickListener {
                                save()
                                when (it.title) {
                                    "Move up" -> cfg.rules.add(i - 1, cfg.rules.removeAt(i))
                                    "Move down" -> cfg.rules.add(i + 1, cfg.rules.removeAt(i))
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
        }
        f.button("Add rule", 2) { save(); startActivity(Intent(this, RuleActivity::class.java).putExtra("i", -1)) }

        f.section("About")
        f.card {
            f.add(f.tv("Edge Light", 18f, bold = true))
            f.add(f.tv("Version ${BuildInfo.VERSION} (build ${BuildInfo.CODE}) · open source", 13f, f.p.dim))
            f.add(f.tv("Commit ${BuildInfo.COMMIT} · built ${BuildInfo.TIME}", 13f, f.p.dim))
            f.add(f.tv("Made by Kieran Davies", 15f), 12)
            f.button("github.com/kierandavies06", 1) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) } catch (e: Exception) {}
            }
        }

        save = {
            cfg.enabled = en.isChecked; cfg.ignoreOngoing = ong.isChecked; cfg.ignoreSummary = sum.isChecked
            cfg.respectDnd = dnd.isChecked; cfg.wake = wake.isChecked; cfg.watch = mirror.isChecked; cfg.cooldownMs = gap.value()?.toInt() ?: 0
            cfg.base = Effect.base().over(fx.read())
            Store.save(this, cfg)
        }
        setContentView(f.scroll())
    }
}
