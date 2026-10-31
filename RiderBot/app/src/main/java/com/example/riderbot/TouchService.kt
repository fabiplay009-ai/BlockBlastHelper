package com.example.riderbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Hält einen Finger auf dem Bildschirm gedrückt bzw. lässt ihn los.
 * Ein langer Druck wird aus kurzen, aneinandergehängten Teilstücken (continueStroke) gebaut,
 * damit jederzeit (spätestens nach SEG_MS) losgelassen werden kann.
 * Alle Methoden auf dem Haupt-Thread aufrufen.
 */
class TouchService : AccessibilityService() {
    companion object {
        @Volatile var instance: TouchService? = null
        private const val SEG_MS = 40L
    }

    private var want = false        // soll gedrückt sein?
    private var busy = false        // läuft gerade ein Teilstück?
    private var stroke: GestureDescription.StrokeDescription? = null
    private var px = 0f
    private var py = 0f
    private var odd = false         // Pfad wechselt um 1 px, damit Anfang = Ende des vorigen Stücks

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun press(down: Boolean, x: Float, y: Float) {
        if (stroke == null) { px = x; py = y }
        want = down
        if (!busy) step()
    }

    private fun seg(): Path {
        val x0 = px + if (odd) 1f else 0f
        odd = !odd
        val x1 = px + if (odd) 1f else 0f
        return Path().apply { moveTo(x0, py); lineTo(x1, py) }
    }

    private fun step() {
        val cur = stroke
        val next = when {
            cur == null && !want -> return
            cur == null -> { odd = false; GestureDescription.StrokeDescription(seg(), 0, SEG_MS, true) }
            want -> cur.continueStroke(seg(), 0, SEG_MS, true)
            else -> cur.continueStroke(seg(), 0, 1, false)   // loslassen
        }
        stroke = if (next.willContinue()) next else null
        busy = true
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(next).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { busy = false; step() }
            override fun onCancelled(g: GestureDescription?) { busy = false; stroke = null; step() }
        }, null)
        if (!ok) { busy = false; stroke = null }
    }
}
