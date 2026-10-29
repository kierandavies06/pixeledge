package uk.me.kierandavies.pixeledge

import android.app.Activity
import android.os.Bundle

/** Invisible, momentary activity whose only job is to turn the screen on (used on the watch). */
class Flash : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true); setTurnScreenOn(true)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({ finish() }, 700)
    }
}
