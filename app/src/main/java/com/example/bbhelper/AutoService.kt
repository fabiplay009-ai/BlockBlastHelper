package com.example.bbhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

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

    // Ziehen in Einzelschritten (Finger bleibt zwischen den Schritten auf dem Bildschirm),
    // damit der Helfer zwischendurch nachsehen kann, wo das Teil gerade schwebt.
    private var cur: GestureDescription.StrokeDescription? = null
    private var fx = 0f
    private var fy = 0f
    private var wiggle = false
    // Android prüft, ob ein Folgeschritt exakt dort beginnt, wo der vorige endete -> nur ganze Pixel
    private fun px(v: Float) = v.roundToInt().toFloat()

    private fun send(s: GestureDescription.StrokeDescription, done: (Boolean) -> Unit) {
        cur = s
        val ok = try {
            dispatchGesture(GestureDescription.Builder().addStroke(s).build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done(true) }
                override fun onCancelled(g: GestureDescription?) { cur = null; done(false) }
            }, null)
        } catch (e: Exception) { false }
        if (!ok) { cur = null; done(false) }
    }

    /** Finger auf (x, y) setzen und [ms] lang halten. */
    fun down(x: Float, y: Float, ms: Long, done: (Boolean) -> Unit) {
        val x0 = px(x); val y0 = px(y)
        fx = x0 + 1; fy = y0 + 1
        val s = try { GestureDescription.StrokeDescription(line(x0, y0, fx, fy), 0, ms, true) } catch (e: Exception) { return done(false) }
        send(s, done)
    }

    /** Finger in [ms] nach (x, y) bewegen (bleibt danach unten). */
    fun move(x: Float, y: Float, ms: Long, done: (Boolean) -> Unit) {
        val c = cur ?: return done(false)
        val nx = px(x).coerceAtLeast(0f); val ny = px(y).coerceAtLeast(0f)
        val s = try { c.continueStroke(line(fx, fy, nx, ny), 0, ms, true) } catch (e: Exception) { cur = null; return done(false) }
        fx = nx; fy = ny
        send(s, done)
    }

    /** Finger [ms] lang ruhig halten. */
    fun hold(ms: Long, done: (Boolean) -> Unit) {
        wiggle = !wiggle
        move(if (wiggle) fx + 1 else fx - 1, fy, ms, done)
    }

    /** Finger loslassen. */
    fun up(done: (Boolean) -> Unit) {
        val c = cur ?: return done(false)
        val s = try { c.continueStroke(line(fx, fy, fx + 1, fy), 0, 60, false) } catch (e: Exception) { cur = null; return done(false) }
        send(s) { ok -> cur = null; done(ok) }
    }
}
