package com.example.bbhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class HelperService : Service() {
    // ---- Kalibrierung (Anteile von Bildschirmbreite W bzw. -höhe H), gemessen am Pixel 8a ----
    private val BX = 0.0574f  // Brett: linker Rand (x W)
    private val BY = 0.2417f  // Brett: oberer Rand (x H)
    private val BS = 0.885f   // Brett: Breite (x W)
    private val TY0 = 0.68f   // Ablage der Teile: oben (x H), hoch genug für 5er-Stangen
    private val TY1 = 0.90f   // Ablage der Teile: unten (x H)
    private val TS = 0.45f    // Feldgröße in der Ablage relativ zum Brettfeld
    private val MIND_BOARD = 70   // Brettfeld belegt, wenn Farbabstand zum leeren Feld größer ist
    private val MIND = 50         // Teil erkannt, wenn Farbabstand zum Ablage-Hintergrund größer ist
    private val NEED = 5          // Feld belegt, wenn mind. so viele von 9 Stichproben "Teil" sind
    private val POLL_MS = 350L    // so oft schaut der Helfer auf die Ablage

    private val COL = intArrayOf(0xAA2F7BF5.toInt(), 0xAA0FA77A.toInt(), 0xAAE8890C.toInt())
    private val h = Handler(Looper.getMainLooper())
    private var W = 0
    private var H = 0
    private lateinit var wm: WindowManager
    private lateinit var reader: ImageReader
    private lateinit var btn: TextView
    private lateinit var ov: Ov
    private var mp: MediaProjection? = null

    // letztes Bild (Kopie), damit auch bei unverändertem Bildschirm gelesen werden kann
    private var fb = ByteArray(0)
    private var frs = 0
    private var hasFrame = false

    // Ablauf: neuer Satz in der Ablage -> kurz warten bis alles ruhig ist -> Zug berechnen und anzeigen
    private var running = true
    private var waiting = false
    private var needFresh = false
    private var clearAt = 0L
    private var analyzedSig: String? = null
    private var sawFewer = false
    private var lastSig = ""
    private var lastBoardKey = ""
    private var stable = 0

    private class Item(val r: RectF, val color: Int, val txt: String?)

    private class Ov(c: Context) : View(c) {
        val items = ArrayList<Item>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 44f }
        override fun onDraw(cv: Canvas) {
            for (i in items) {
                p.style = Paint.Style.FILL
                p.color = i.color
                cv.drawRoundRect(i.r, 10f, 10f, p)
                if (i.txt != null) {
                    p.color = Color.WHITE
                    cv.drawText(i.txt, i.r.centerX(), i.r.centerY() + 15f, p)
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick() } catch (e: Exception) { /* nächster Versuch */ }
            h.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun lp(w: Int, hh: Int, flags: Int) =
        WindowManager.LayoutParams(w, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bb", "Block-Helfer", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bb").setContentTitle("Block-Helfer läuft")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        W = b.width(); H = b.height()
        mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        mp!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, h)
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        mp!!.createVirtualDisplay("bb", W, H, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, h)

        ov = Ov(this)
        wm.addView(ov, lp(-1, -1, 8 or 16 or 256 or 512).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        })
        btn = TextView(this).apply {
            text = "↻ Zug"; setTextColor(Color.WHITE); setPadding(32, 20, 32, 20)
            setBackgroundColor(0xCC2D3280.toInt())
            setOnClickListener { analyzedSig = null; sawFewer = false; startWaiting() }  // Zug neu berechnen
            setOnLongClickListener { stopSelf(); true }  // lange drücken = beenden
        }
        wm.addView(btn, lp(-2, -2, 8).apply { gravity = Gravity.TOP or Gravity.START; y = 300 })
        h.postDelayed(poll, 800)
        return START_NOT_STICKY
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- Bild und Farben ----------

    private fun grab(): Boolean {
        val img = reader.acquireLatestImage() ?: return false
        try {
            val pl = img.planes[0]
            val b = pl.buffer
            b.rewind()
            frs = pl.rowStride
            val n = b.remaining()
            if (fb.size != n) fb = ByteArray(n)
            b.get(fb)
            hasFrame = true
        } finally { img.close() }
        return true
    }

    private fun rgb(x: Int, y: Int): IntArray {
        val o = y.coerceIn(0, H - 1) * frs + x.coerceIn(0, W - 1) * 4
        if (o + 2 >= fb.size) return intArrayOf(0, 0, 0)
        return intArrayOf(fb[o].toInt() and 255, fb[o + 1].toInt() and 255, fb[o + 2].toInt() and 255)
    }

    private fun dist(a: IntArray, b: IntArray): Double {
        val dr = a[0] - b[0]; val dg = a[1] - b[1]; val db = a[2] - b[2]
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    // Brett: Durchschnittsfarbe je Feld. Leer = Farbe des dunkelsten Feldes, alles andere = belegt.
    private fun readBoard(): BooleanArray {
        val bx = BX * W; val by = BY * H; val cs = BS * W / 8
        val avg = Array(64) { i ->
            val sum = IntArray(3)
            for (dy in -1..1) for (dx in -1..1) {
                val px = rgb((bx + (i % 8 + .5f + dx * .25f) * cs).toInt(), (by + (i / 8 + .5f + dy * .25f) * cs).toInt())
                for (k in 0..2) sum[k] += px[k]
            }
            IntArray(3) { sum[it] / 9 }
        }
        val darkest = avg.minByOrNull { it[0] * 3 + it[1] * 6 + it[2] }!!
        val acc = IntArray(3); var cnt = 0
        for (a in avg) if (dist(a, darkest) < 40) { cnt++; for (k in 0..2) acc[k] += a[k] }
        val emptyCol = IntArray(3) { acc[it] / cnt }
        return BooleanArray(64) { dist(avg[it], emptyCol) > MIND_BOARD }
    }

    private fun connected(cells: List<IntArray>): Boolean {
        if (cells.isEmpty()) return false
        val seen = BooleanArray(cells.size)
        val stack = ArrayList<Int>()
        stack.add(0); seen[0] = true
        var n = 0
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            n++
            for (j in cells.indices) {
                if (!seen[j] && abs(cells[i][0] - cells[j][0]) + abs(cells[i][1] - cells[j][1]) == 1) {
                    seen[j] = true; stack.add(j)
                }
            }
        }
        return n == cells.size
    }

    // Ablage: Teil = Pixel, der sich deutlich vom Hintergrund des Feldes unterscheidet.
    // Unplausible Formen (zusammengerutscht, mitten in der Animation) zählen als "leer".
    private fun readTray(): List<List<IntArray>> {
        val cs = BS * W / 8
        val t = cs * TS                                   // erwartete Feldgröße in der Ablage (px)
        val er = max(2, (6f * W / 1080f).roundToInt())    // Mindestdicke: dünne Rahmenlinien zählen nicht
        return (0..2).map { s ->
            val x0 = W * s / 3; val x1 = W * (s + 1) / 3
            val y0 = (TY0 * H).toInt(); val y1 = (TY1 * H).toInt()
            // Hintergrund = Median aller Farbwerte im Feld (Teile sind nur ein kleiner Teil der Fläche)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in y0 + 10 until y1 - 30 step 8) for (x in x0 + 10 until x1 - 10 step 8) {
                val p = rgb(x, y); rs.add(p[0]); gs.add(p[1]); bs.add(p[2])
            }
            rs.sort(); gs.sort(); bs.sort()
            val bg = intArrayOf(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
            fun on(x: Int, y: Int) = dist(rgb(x, y), bg) > MIND
            fun thick(x: Int, y: Int) = on(x, y) && on(x - er, y) && on(x + er, y) && on(x, y - er) && on(x, y + er)
            var mnX = Int.MAX_VALUE; var mnY = Int.MAX_VALUE; var mxX = -1; var mxY = -1
            for (y in y0 + 10 until y1 - 30 step 4) for (x in x0 + 10 until x1 - 10 step 4) if (thick(x, y)) {
                mnX = min(mnX, x); mxX = max(mxX, x); mnY = min(mnY, y); mxY = max(mxY, y)
            }
            val cells = ArrayList<IntArray>()
            if (mxX >= 0) {
                val left = (mnX - er - 2).toFloat(); val top = (mnY - er - 2).toFloat()
                val w = (mxX - mnX + 2 * er + 4).toFloat(); val ph = (mxY - mnY + 2 * er + 4).toFloat()
                val fw = w / t; val fh = ph / t
                if (abs(fw - fw.roundToInt()) <= 0.3f && abs(fh - fh.roundToInt()) <= 0.3f) {
                    val cols = fw.roundToInt().coerceIn(1, 5)
                    val rows = fh.roundToInt().coerceIn(1, 5)
                    val rw = w / cols / t; val rh = ph / rows / t
                    // Feldgröße muss zur Ablage passen (sonst: Teil noch mitten in der Animation)
                    if (rw in 0.8f..1.3f && rh in 0.8f..1.3f) {
                        for (r in 0 until rows) for (c in 0 until cols) {
                            val cx = left + (c + .5f) * w / cols; val cy = top + (r + .5f) * ph / rows
                            var n = 0
                            for (dx in -1..1) for (dy in -1..1)
                                if (on((cx + dx * .25f * t).toInt(), (cy + dy * .25f * t).toInt())) n++
                            if (n >= NEED) cells.add(intArrayOf(r, c))
                        }
                        if (!connected(cells)) cells.clear()
                    }
                }
            }
            cells.toList()
        }
    }

    // ---------- Ablauf ----------

    private fun startWaiting() {
        waiting = true; stable = 0; lastSig = ""; lastBoardKey = ""
        if (ov.items.isNotEmpty()) {
            // Anzeige weg, damit das Brett ohne Überlagerung gelesen wird
            needFresh = true; clearAt = SystemClock.uptimeMillis()
            ov.items.clear(); ov.invalidate()
        }
    }

    private fun tick() {
        val got = grab()
        if (!hasFrame) return
        if (needFresh) {
            val age = SystemClock.uptimeMillis() - clearAt
            if (age < 300) return
            if (!got && age < 1500) return
            needFresh = false
        }
        val tray = readTray()
        val count = tray.count { it.isNotEmpty() }
        val sig = tray.joinToString("|") { cells -> cells.joinToString(",") { "${it[0]}${it[1]}" } }
        if (!waiting) {
            if (count < 3) {
                if (analyzedSig != null) sawFewer = true   // Teile wurden gesetzt
                return
            }
            if (sig != analyzedSig || sawFewer) startWaiting()
            return
        }
        val key = readBoard().joinToString("") { if (it) "1" else "0" }
        if (count == 3 && sig == lastSig && key == lastBoardKey) stable++
        else { stable = 0; lastSig = sig; lastBoardKey = key }
        if (stable >= 2) {
            show(tray)
            analyzedSig = sig; sawFewer = false; waiting = false
        }
    }

    private fun show(pieces: List<List<IntArray>>) {
        val bx = BX * W; val by = BY * H; val cs = BS * W / 8
        val res = Solver.solve(readBoard(), pieces)
        ov.items.clear()
        if (res == null) {
            toast("Keine Teile erkannt")
        } else {
            res.moves.forEachIndexed { n, m ->
                for (x in m.cells) {
                    val l = bx + (m.c + x[1]) * cs; val t = by + (m.r + x[0]) * cs
                    ov.items.add(Item(RectF(l + 3, t + 3, l + cs - 3, t + cs - 3), COL[m.piece], "${n + 1}"))
                }
            }
            if (res.moves.size < pieces.count { it.isNotEmpty() }) toast("Achtung: nicht alle Teile passen")
        }
        ov.invalidate()
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(poll)
        try { wm.removeView(btn); wm.removeView(ov) } catch (_: Exception) {}
        mp?.stop()
        super.onDestroy()
    }
}
