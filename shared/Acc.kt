package uk.me.kierandavies.pixeledge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Does nothing but exist: its context is allowed to add TYPE_ACCESSIBILITY_OVERLAY windows, which sit above the keyguard. */
class Acc : AccessibilityService() {
    override fun onServiceConnected() { Overlay.acc = this }
    override fun onUnbind(i: android.content.Intent?): Boolean { if (Overlay.acc === this) Overlay.acc = null; return super.onUnbind(i) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
