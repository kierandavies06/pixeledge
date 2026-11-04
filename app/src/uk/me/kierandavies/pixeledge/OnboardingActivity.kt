package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout

/** First-run setup: grant the permissions the app needs, then pick a look and a few basics. */
class OnboardingActivity : Activity() {
    companion object {
        fun done(c: Context) = c.getSharedPreferences("app", 0).getBoolean("onboarded", false)
        fun markDone(c: Context) = c.getSharedPreferences("app", 0).edit().putBoolean("onboarded", true).apply()
    }

    private val last = 7
    private var step = 0
    private var look = -1
    private lateinit var cfg: Config

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        step = b?.getInt("step") ?: 0
        look = b?.getInt("look", -1) ?: -1
    }
    override fun onSaveInstanceState(o: Bundle) { super.onSaveInstanceState(o); o.putInt("step", step); o.putInt("look", look) }

    // Coming back from a settings screen or a permission prompt: show the new status.
    override fun onResume() { super.onResume(); show() }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) { Ring.register(this); show() }
    override fun onBackPressed() { if (step > 0) { step--; show() } else super.onBackPressed() }

    private fun listenerOn() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun finishSetup() { markDone(this); finish() }

    /** A permission step: why it's needed, its status, and a button that takes the user to grant it. */
    private fun perm(f: Form, why: String, granted: Boolean, button: String, hint: String?, grant: () -> Unit) {
        f.note(why)
        f.card(16) {
            f.add(f.tv(if (granted) "✓  Turned on" else "Not turned on yet", 16f, if (granted) f.p.primary else f.p.text, true))
            if (hint != null && !granted) f.add(f.tv(hint, 13f, f.p.dim), 6)
        }
        if (!granted) f.button(button, 2, grant)
    }

    private fun show() {
        cfg = Store.load(this)
        val f = Form(this)
        window.setBackgroundDrawable(ColorDrawable(f.p.bg))
        var canNext = true
        var nextLabel = "Next"
        when (step) {
            0 -> {
                f.head("Welcome to Edge Light")
                f.note("A glowing border runs around your screen when a notification arrives or a call rings. You can change how it looks for each app, person or time of day.")
                f.note("This takes about a minute: a few permissions, then a look and some basics. You can change everything later.")
                nextLabel = "Get started"
            }
            1 -> {
                f.head("Notification access")
                perm(f, "So Edge Light knows when a notification arrives. It reads them on your phone and never sends them anywhere.", listenerOn(),
                    "Open notification access", "Find Edge Light in the list and turn it on.") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                canNext = listenerOn(); if (!canNext) nextLabel = "Skip for now"
            }
            2 -> {
                f.head("Draw the light")
                perm(f, "So the light can appear on top of other apps.", Settings.canDrawOverlays(this),
                    "Allow display over other apps", "Turn on Allow display over other apps.") {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                canNext = Settings.canDrawOverlays(this); if (!canNext) nextLabel = "Skip for now"
            }
            3 -> {
                f.head("Lock screen")
                perm(f, "Optional. To show the light over the lock screen, Edge Light uses an accessibility service. It only hosts the light and can't read what's on screen.", Overlay.acc != null,
                    "Open accessibility settings", "Choose Edge Light under installed apps, then turn it on.") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                if (Overlay.acc == null) nextLabel = "Skip"
            }
            4 -> {
                f.head("Calls and popups")
                f.note("Optional. Each of these unlocks one feature.")
                f.card(16) {
                    f.add(f.tv(if (Ring.hasPermission(this)) "✓  Incoming calls" else "Incoming calls", 16f, if (Ring.hasPermission(this)) f.p.primary else f.p.text, true))
                    f.add(f.tv("Keep the light going for as long as the phone is ringing.", 13f, f.p.dim), 4)
                    if (!Ring.hasPermission(this)) f.button("Allow call state", 1) { requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 1) }
                }
                f.card {
                    f.add(f.tv(if (Quiet.hasPermission(this)) "✓  Replacing an app's popup" else "Replacing an app's popup", 16f, if (Quiet.hasPermission(this)) f.p.primary else f.p.text, true))
                    f.add(f.tv("Lets a rule hide an app's own popup and show a bubble instead.", 13f, f.p.dim), 4)
                    if (!Quiet.hasPermission(this)) f.button("Allow notifications", 1) { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2) }
                }
                if (!Ring.hasPermission(this) || !Quiet.hasPermission(this)) nextLabel = "Skip"
            }
            5 -> {
                f.head("Pick a look")
                f.note("Choose how the light looks to start with. Tap one to try it.")
                Looks.all.dropLast(1).forEachIndexed { i, l ->
                    f.lookCard(l, look == i, cfg.base.colors) {
                        look = i
                        cfg.base = Effect.base().over(l.fx); Store.save(this, cfg)
                        Preview.light(this, cfg.base)
                        show()
                    }
                }
            }
            6 -> {
                f.head("A few basics")
                f.card(16) {
                    f.switchRow("Wake the screen", "Turn the display on to show the light", cfg.wake) { cfg.wake = it; Store.save(this, cfg) }
                    f.switchRow("Respect Do Not Disturb", "Stay dark for notifications it silences", cfg.respectDnd) { cfg.respectDnd = it; Store.save(this, cfg) }
                    f.switchRow("Mirror to watch", "Play the same light on a paired Pixel Watch", cfg.watch) { cfg.watch = it; Store.save(this, cfg) }
                }
                f.section("Popup style")
                f.note("A bubble is a small pill that drops from the camera with the light around it.")
                f.chips(listOf("Off", "Bubble"), minOf(cfg.bubble, 1)) { cfg.bubble = it; Store.save(this, cfg) }
            }
            else -> {
                f.head("You're set")
                f.note("Edge Light is ready. Create rules for particular apps or people from the Rules tab, or fine-tune the look and popups any time.")
                f.button("Preview", 1) { Preview.light(this, cfg.base); if (cfg.bubble > 0) Bubble.preview(this, cfg.base, cfg.bubbleMs) }
                nextLabel = "Open Edge Light"
            }
        }

        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(f.mkButton(if (step == 0) "Skip setup" else "Back", 0) { if (step == 0) finishSetup() else { step--; show() } })
        nav.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(f.mkButton(nextLabel, if (canNext && nextLabel != "Skip" && nextLabel != "Skip for now") 2 else 1) { if (step == last) finishSetup() else { step++; show() } })
        f.add(nav, 24)
        f.add(f.tv("Step ${step + 1} of ${last + 1}", 12f, f.p.dim).apply { gravity = Gravity.CENTER }, 16)
        setContentView(f.scroll())
    }
}
