package com.example.bbhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/** Bedienungshilfe, die für den Automatik-Modus Teile per Wischgeste aus der Ablage aufs Brett zieht. */
class AutoService : AccessibilityService() {
    companion object {
        var inst: AutoService? = null
            private set

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, AutoService::class.java).flattenToString()
            val on = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(i: Intent?): Boolean { inst = null; return super.onUnbind(i) }
    override fun onDestroy() { inst = null; super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = Path().apply { moveTo(x0, y0); lineTo(x1, y1) }

    /**
     * Zieht von (x0, y0) nach (x1, y1): kurz festhalten, gleichmäßig ziehen, am Ziel kurz halten, loslassen.
     * [done] bekommt true, wenn die Geste vollständig ausgeführt wurde.
     */
    fun drag(x0: Float, y0: Float, x1: Float, y1: Float, done: (Boolean) -> Unit) {
        val press = GestureDescription.StrokeDescription(line(x0, y0, x0 + 1, y0 + 1), 0, 120, true)
        val move = press.continueStroke(line(x0 + 1, y0 + 1, x1, y1), 0, 450, true)
        val hold = move.continueStroke(line(x1, y1, x1 + 1, y1 + 1), 0, 150, false)
        val steps = listOf(press, move, hold)
        fun run(i: Int) {
            if (i == steps.size) { done(true); return }
            val ok = dispatchGesture(GestureDescription.Builder().addStroke(steps[i]).build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { run(i + 1) }
                override fun onCancelled(g: GestureDescription?) { done(false) }
            }, null)
            if (!ok) done(false)
        }
        run(0)
    }
}
