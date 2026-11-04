package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class RuleActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val cfg = Store.load(this)
        val idx = intent.getIntExtra("i", -1)
        val r = cfg.rules.getOrNull(idx) ?: Rule()
        val f = Form(this)
        window.setBackgroundDrawable(ColorDrawable(f.p.bg))
        f.head(if (idx >= 0) "Edit rule" else "New rule")
        lateinit var name: EditText
        f.card(16) { name = f.field("Name", r.name) }

        f.section("When the notification…")
        lateinit var pkgs: EditText; lateinit var notPkgs: EditText; lateinit var anyRe: EditText; lateinit var chans: EditText; lateinit var cats: EditText
        lateinit var title: EditText; lateinit var body: EditText; lateinit var imp: Chips
        val imps = listOf(0, 2, 3, 4)
        f.card {
            pkgs = f.field("From apps (blank = any)", r.pkgs.joinToString(", "))
            f.button("Pick apps…", 1) { pickApps(pkgs) }
            notPkgs = f.field("Not from apps", r.notPkgs.joinToString(", "))
            f.button("Pick apps to exclude…", 1) { pickApps(notPkgs) }
            anyRe = f.field("Anywhere (title, text, messages…) matches regex", r.anyRe)
            chans = f.field("Notification types: channel id contains, or package/channel", r.channels.joinToString(", "))
            f.button("Pick notification types…", 1) {
                val ps = csv(pkgs.text.toString())
                if (ps.isEmpty()) Toast.makeText(this, "Choose From apps first", Toast.LENGTH_SHORT).show()
                else pickChannels(this, ps, csv(chans.text.toString())) { chans.setText(it.joinToString(", ")) }
            }
            cats = f.field("Category (msg, email, call, social, promo, alarm…)", r.categories.joinToString(", "))
            title = f.field("Title matches regex", r.titleRe)
            body = f.field("Text matches regex", r.textRe)
            f.label("Minimum importance")
            imp = f.chips(listOf("Any", "Low+", "Default+", "High"), maxOf(imps.indexOf(r.minImp), 0))
        }

        f.section("And the phone is…")
        var from = r.from; var to = r.to; var days = r.days
        lateinit var chg: Chips; lateinit var scr: Chips; lateinit var dnd: Chips
        f.card {
            f.label("Time window (needs both ends)")
            val fb = f.mkButton("") {}; val tb = f.mkButton("") {}; val clear = f.mkButton("Clear", 0) {}
            fun show() { fb.text = if (from < 0) "From: any" else "From ${hm(from)}"; tb.text = if (to < 0) "Until: any" else "Until ${hm(to)}" }
            fun ask(cur: Int, set: (Int) -> Unit) = TimePickerDialog(this, { _, h, m -> set(h * 60 + m); show() }, maxOf(cur, 0) / 60, maxOf(cur, 0) % 60, true).show()
            fb.setOnClickListener { ask(from) { from = it } }; tb.setOnClickListener { ask(to) { to = it } }
            clear.setOnClickListener { from = -1; to = -1; show() }
            show()
            f.add(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(fb, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = f.dp(8) })
                addView(tb, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = f.dp(8) }); addView(clear)
            }, 6)

            f.label("Days")
            val bits = intArrayOf(1, 2, 3, 4, 5, 6, 0)
            val row = LinearLayout(this)
            "MTWTFSS".forEachIndexed { k, ch ->
                val v = f.tv(ch.toString(), 14f, bold = true)
                v.gravity = Gravity.CENTER
                fun paint() {
                    val on = days shr bits[k] and 1 == 1
                    v.setTextColor(if (on) f.p.onPrimary else f.p.text)
                    v.background = f.ripple(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(if (on) f.p.primary else 0); if (!on) setStroke(f.dp(1), f.p.outline)
                    })
                }
                v.setOnClickListener { days = days xor (1 shl bits[k]); paint() }
                paint()
                row.addView(v, LinearLayout.LayoutParams(0, f.dp(40), 1f).also { it.rightMargin = f.dp(4) })
            }
            f.add(row, 6)

            val tri = listOf("Any", "Yes", "No")
            f.label("Charging"); chg = f.chips(tri, r.charging)
            f.label("Screen on"); scr = f.chips(tri, r.screen)
            f.label("Do Not Disturb active"); dnd = f.chips(tri, r.dnd)
        }

        f.section("Then")
        lateinit var sup: Switch; lateinit var stop: Switch; lateinit var bub: Chips
        f.card {
            sup = f.switchRow("Suppress", "Show no light at all", r.suppress)
            stop = f.switchRow("Stop here", "Don't evaluate later rules", r.stop)
            f.label("Popup style")
            bub = f.chips(listOf("Inherit", "Bubble", "Bubble + replace native", "Off"), r.bubble)
            f.note("Bubble: a pill drops from the camera with the effect around its border. Replace native also removes the app's own popup and leaves a quiet copy in your shade.")
        }
        f.section("Override effect")
        f.note("Anything left on “inherit” follows the default effect.")
        lateinit var fx: EffectForm
        f.card {
            fx = EffectForm(f, r.fx, true) { cfg.base }
            f.button("Preview as bubble", 1) { Bubble.preview(this, cfg.base.over(fx.read()), cfg.bubbleMs) }
        }

        f.button("Save", 2) {
            val n = Rule()
            n.name = name.text.toString().ifBlank { "Rule" }; n.on = r.on
            n.pkgs = csv(pkgs.text.toString()); n.notPkgs = csv(notPkgs.text.toString()); n.anyRe = anyRe.text.toString(); n.channels = csv(chans.text.toString()); n.categories = csv(cats.text.toString())
            n.titleRe = title.text.toString(); n.textRe = body.text.toString(); n.minImp = imps[imp.sel]
            n.from = from; n.to = to; n.days = days
            n.charging = chg.sel; n.screen = scr.sel; n.dnd = dnd.sel
            n.suppress = sup.isChecked; n.stop = stop.isChecked; n.bubble = bub.sel; n.fx = fx.read()
            if (idx >= 0) cfg.rules[idx] = n else cfg.rules.add(n)
            Store.save(this, cfg)
            finish()
        }
        if (idx >= 0) f.button("Delete rule", 0) { cfg.rules.removeAt(idx); Store.save(this, cfg); finish() }
        setContentView(f.scroll())
    }

    private fun pickApps(field: EditText) = pickApps(this, csv(field.text.toString())) { field.setText(it.joinToString(", ")) }
}
