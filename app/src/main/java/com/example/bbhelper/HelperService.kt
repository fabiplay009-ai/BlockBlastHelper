package com.example.bbhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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
    private val MIND_EMPTY = 35   // Brettfeld sicher leer, wenn Farbabstand kleiner ist (dazwischen = unsicher)
    private val STABLE_NEW = 2    // so viele gleiche Bilder hintereinander, bevor neu gerechnet wird
    private val STABLE_FEWER = 3  // ... wenn ein Teil fehlt (könnte gerade gezogen werden)
    private val MIND = 50         // Teil erkannt, wenn Farbabstand zum Ablage-Hintergrund größer ist
    private val NEED = 8          // Feld belegt, wenn mind. so viele von 25 Stichproben "Teil" sind
                                  // (leere Felder: 0, volle: 16-25 – auch bei dunkel schattierten Blöcken)
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
    // Ablauf: Brett + Ablage werden ständig gescannt. Sobald sich etwas geändert hat und das Bild
    // ruhig ist, wird mit dem echten Brett neu gerechnet und nur der nächste Zug angezeigt.
    private var analyzedKey: String? = null
    private var analyzedBoard = ""
    private var analyzedCount = 0
    private var lastKey = ""
    private var stable = 0
    private var saveNext = false

    // ---- Automatik-Modus (nur aktiv, wenn auf dem Startbildschirm "Automatisch spielen" gewählt) ----
    private var auto = false
    private var paused = false
    private val trayBox = arrayOfNulls<RectF>(3)    // Lage der Teile in der Ablage (Bildschirm-Pixel)
    private var planned: Solver.Move? = null
    private var plannedBoard: IntArray? = null
    private var plannedCount = 0
    private var grabAt: RectF? = null
    private var dragging = false
    private var dragAt = 0L                          // Zeitpunkt der letzten Geste, 0 = keine offen
    private var tryIdx = 0                           // Fehlversuche mit diesem Zug
    private var misses = 0                           // Teile hintereinander an falscher Stelle gelandet
    private var lastPieces: List<List<IntArray>> = emptyList()
    // Selbstlernende Korrektur: nach jedem Zug wird gespeichert, wie weit (in Brettfeldern) das Teil bei
    // welcher Zugstrecke danebenlag. Daraus wird je Richtung "Fehler = a + b * Strecke" geschätzt
    // und beim nächsten Zug vorab ausgeglichen. Die letzten 12 Züge zählen, gespeichert über Neustarts.
    private val learnX = ArrayList<FloatArray>()
    private val learnY = ArrayList<FloatArray>()
    private var refFb = ByteArray(0)                 // Bild vor dem Greifen (zum Vergleich beim Suchen)
    private var usedCorrX = 0f
    private var usedCorrY = 0f
    private var usedTravelX = 0f
    private var usedTravelY = 0f
    // Vermessung je Teilform ("ReihenxSpalten"): Lage der Teil-Mitte zum Finger nach dem Greifen (Felder)
    // und Verstärkung der Zugbewegung. Damit geht es ab dem zweiten Teil einer Form ohne Ausmessen.
    private val calOff = HashMap<String, FloatArray>()
    private var calK: FloatArray? = null
    private val fastMiss = HashMap<String, Int>()
    private var lastDragKey = ""
    private var lastDragFast = false
    private var landingDone = false

    private val EMPTY = 0
    private val FULL = 1
    private val UNSURE = 2

    private class Item(val r: RectF, val color: Int, val frame: Boolean)

    // Hinweise werden nur als Rahmen gezeichnet: die Feldmitte bleibt frei, damit das Brett
    // auch mit eingeblendetem Hinweis zuverlässig gescannt werden kann.
    private class Ov(c: Context) : View(c) {
        val items = ArrayList<Item>()
        var stroke = 10f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            for (i in items) {
                p.color = i.color or 0xFF000000.toInt()
                if (i.frame) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = stroke
                    cv.drawRoundRect(i.r, 12f, 12f, p)
                } else {
                    p.style = Paint.Style.FILL
                    cv.drawRoundRect(i.r, 6f, 6f, p)
                }
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick() } catch (e: Exception) { /* nächster Versuch */ }
            h.postDelayed(this, if (auto) 200L else POLL_MS)
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
        auto = i.getBooleanExtra("auto", false)
        if (auto) loadLearning()
        btn = TextView(this).apply {
            text = if (auto) "⏸ Auto" else "↻ Zug"; setTextColor(Color.WHITE); setPadding(32, 20, 32, 20)
            setBackgroundColor(0xCC2D3280.toInt())
            setOnClickListener {
                if (auto) togglePause()
                else { analyzedKey = null; analyzedCount = 0; stable = STABLE_NEW; saveNext = true }  // neu berechnen + Scan speichern
            }
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

    // Brett: je Feld 9 Stichproben aus der Feldmitte. Leer = Farbe der dunkelsten Felder.
    // Ein Feld ist nur dann LEER bzw. BELEGT, wenn die klare Mehrheit der Stichproben das sagt,
    // sonst UNSICHER (z. B. Animation, Effekt, halb verdeckt).
    private fun readCells(): IntArray {
        val bx = BX * W; val by = BY * H; val cs = BS * W / 8
        val smp = Array(64) { i ->
            Array(9) { k ->
                val dx = k % 3 - 1; val dy = k / 3 - 1
                rgb((bx + (i % 8 + .5f + dx * .2f) * cs).toInt(), (by + (i / 8 + .5f + dy * .2f) * cs).toInt())
            }
        }
        val avg = Array(64) { i -> IntArray(3) { k -> smp[i].sumOf { it[k] } / 9 } }
        val darkest = avg.minByOrNull { it[0] * 3 + it[1] * 6 + it[2] }!!
        val acc = IntArray(3); var cnt = 0
        for (a in avg) if (dist(a, darkest) < 40) { cnt++; for (k in 0..2) acc[k] += a[k] }
        val emptyCol = IntArray(3) { acc[it] / cnt }
        return IntArray(64) { i ->
            val full = smp[i].count { dist(it, emptyCol) > MIND_BOARD }
            val empty = smp[i].count { dist(it, emptyCol) < MIND_EMPTY }
            when {
                full >= 7 -> FULL
                empty >= 7 -> EMPTY
                else -> UNSURE
            }
        }
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
            trayBox[s] = null
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
                trayBox[s] = RectF(left, top, left + w, top + ph)
                if (abs(fw - fw.roundToInt()) <= 0.3f && abs(fh - fh.roundToInt()) <= 0.3f) {
                    val cols = fw.roundToInt().coerceIn(1, 5)
                    val rows = fh.roundToInt().coerceIn(1, 5)
                    val rw = w / cols / t; val rh = ph / rows / t
                    // Feldgröße muss zur Ablage passen (sonst: Teil noch mitten in der Animation)
                    if (rw in 0.8f..1.3f && rh in 0.8f..1.3f) {
                        for (r in 0 until rows) for (c in 0 until cols) {
                            val cx = left + (c + .5f) * w / cols; val cy = top + (r + .5f) * ph / rows
                            var n = 0
                            for (dx in -2..2) for (dy in -2..2)
                                if (on((cx + dx * .15f * t).toInt(), (cy + dy * .15f * t).toInt())) n++
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

    private fun tick() {
        if (auto && dragging) return   // Geste läuft gerade (Bilder liest dann der Mess-Thread)
        grab()
        if (!hasFrame) return
        val tray = readTray()
        val count = tray.count { it.isNotEmpty() }
        val sig = tray.joinToString("|") { cells -> cells.joinToString(",") { "${it[0]}${it[1]}" } }
        val cells = readCells()
        val board = cells.joinToString("")
        val key = "$board/$sig"
        if (key == lastKey) stable++ else { stable = 0; lastKey = key }
        if (key == analyzedKey) {
            // Automatik: nach der Geste hat sich nichts geändert -> Teil wurde nicht gesetzt
            if (auto && !paused && dragAt > 0 && SystemClock.uptimeMillis() - dragAt > 3000) dragFailed()
            return
        }

        if (count == 0) {
            // alle Teile gesetzt: Hinweis weg, auf neuen Satz warten
            if (ov.items.isNotEmpty()) { ov.items.clear(); ov.invalidate() }
            if (auto && dragAt > 0 && stable >= STABLE_FEWER) learn(cells, count)
            return
        }
        // Teil fehlt, Brett aber unverändert: wird gerade gezogen -> Hinweis stehen lassen
        if (count < analyzedCount && board == analyzedBoard) return
        val need = if (count < analyzedCount) STABLE_FEWER else STABLE_NEW
        if (stable < need) return
        if (auto && dragAt > 0) learn(cells, count)
        analyzedKey = key; analyzedBoard = board; analyzedCount = count
        show(tray, cells)
        if (auto) { lastPieces = tray; planDrag(cells, count) }
    }

    /** Prüft, ob der Zug auf dem gescannten Brett wirklich passt (alle Zielfelder sicher leer). */
    private fun fits(cells: IntArray, m: Solver.Move): Boolean = m.cells.all { x ->
        val r = m.r + x[0]; val c = m.c + x[1]
        r in 0..7 && c in 0..7 && cells[r * 8 + c] == EMPTY
    }

    private fun show(pieces: List<List<IntArray>>, cells: IntArray) {
        val bx = BX * W; val by = BY * H; val cs = BS * W / 8
        // 1. Versuch: unsichere Felder als leer. Passt der Zug nicht sicher, unsichere Felder als belegt.
        var res = Solver.solve(BooleanArray(64) { cells[it] == FULL }, pieces)
        if (res != null && res.moves.isNotEmpty() && !fits(cells, res.moves[0]))
            res = Solver.solve(BooleanArray(64) { cells[it] != EMPTY }, pieces)
        ov.items.clear()
        val m = res?.moves?.firstOrNull()
        planned = m?.takeIf { fits(cells, it) }
        var problem: String? = null
        when {
            res == null -> problem = "Keine Teile erkannt"
            m == null || !fits(cells, m) -> problem = "Kein Teil passt sicher aufs Brett"
            else -> {
                ov.stroke = max(6f, cs * 0.08f)
                val col = COL[m.piece]
                for (x in m.cells) {
                    val l = bx + (m.c + x[1]) * cs; val t = by + (m.r + x[0]) * cs
                    val in0 = ov.stroke / 2 + 2
                    ov.items.add(Item(RectF(l + in0, t + in0, l + cs - in0, t + cs - in0), col, true))
                }
                // Markierung unter dem Teil in der Ablage, das gesetzt werden soll
                val sx = W * (2 * m.piece + 1) / 6f; val y1 = TY1 * H
                ov.items.add(Item(RectF(sx - cs * .7f, y1 - 26, sx + cs * .7f, y1 - 12), col, false))
                if (res.moves.size < pieces.count { it.isNotEmpty() }) problem = "Achtung: nicht alle Teile passen"
            }
        }
        ov.invalidate()
        if (problem != null) toast(problem)
        if (problem != null || saveNext) {
            saveNext = false
            saveDebug(problem ?: "manuell", pieces, cells, m?.takeIf { fits(cells, it) })
        }
    }

    // ---------- Automatik ----------

    private fun prefs() = getSharedPreferences("auto", Context.MODE_PRIVATE)

    private fun togglePause() {
        paused = !paused
        btn.text = if (paused) "▶ Auto" else "⏸ Auto"
        if (paused) toast("Automatik pausiert – antippen zum Weiterspielen")
        else { tryIdx = 0; misses = 0; dragAt = 0; analyzedKey = null; analyzedCount = 0; stable = STABLE_NEW }
    }

    private fun pauseWith(msg: String) {
        if (!paused) togglePause()
        toast(msg)
    }

    private fun planDrag(cells: IntArray, count: Int) {
        val m = planned ?: return
        if (paused) return
        val box = trayBox[m.piece] ?: return
        plannedBoard = cells; plannedCount = count; grabAt = RectF(box)
        h.postDelayed({ doDrag() }, 250)
    }

    /**
     * Sucht das gerade gezogene (schwebende) Teil zwischen oberem Brettrand und Ablage.
     * Teil-Pixel = weicht vom Ablage-Hintergrund ab UND hat sich seit dem Greifen verändert; so fallen
     * Brett, andere Teile und Hinweis-Balken von selbst weg. null = nicht (vollständig) gefunden.
     * Läuft im Mess-Thread, deshalb ohne Speicher-Anlegen pro Pixel (schnell).
     */
    /** Farbe des Teils in der Ablage (Median der Teil-Pixel im Bild vor dem Greifen). */
    private fun pieceColor(box: RectF, f: ByteArray): IntArray? {
        val cs = BS * W / 8
        val bgTop = (BY * H + 8 * cs + 6).toInt(); val bot = (TY1 * H - 34).toInt(); val st = frs
        if (st <= 0 || f.isEmpty()) return null
        fun c(x: Int, y: Int): IntArray? {
            val o = y.coerceIn(0, H - 1) * st + x.coerceIn(0, W - 1) * 4
            return if (o + 2 >= f.size) null else intArrayOf(f[o].toInt() and 255, f[o + 1].toInt() and 255, f[o + 2].toInt() and 255)
        }
        val bgs = ArrayList<IntArray>()
        for (y in bgTop until bot step 16) for (x in 4 until W - 4 step 16) c(x, y)?.let { bgs.add(it) }
        if (bgs.isEmpty()) return null
        val bg = IntArray(3) { k -> bgs.map { it[k] }.sorted()[bgs.size / 2] }
        val ps = ArrayList<IntArray>()
        var y = box.top.toInt()
        while (y < box.bottom) {
            var x = box.left.toInt()
            while (x < box.right) { c(x, y)?.takeIf { dist(it, bg) > MIND * 1.5 }?.let { ps.add(it) }; x += 3 }
            y += 3
        }
        if (ps.size < 10) return null
        return IntArray(3) { k -> ps.map { it[k] }.sorted()[ps.size / 2] }
    }

    private fun findFloating(pc: IntArray? = null): RectF? {
        val cs = BS * W / 8
        val top = (BY * H + 4).toInt(); val bot = (TY1 * H - 34).toInt()
        val bgTop = (BY * H + 8 * cs + 6).toInt()            // Hintergrundfarbe nur unter dem Brett bestimmen
        val f = fb; val rf = refFb; val st = frs
        if (st <= 0 || f.isEmpty()) return null
        fun off(x: Int, y: Int) = y.coerceIn(0, H - 1) * st + x.coerceIn(0, W - 1) * 4
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in bgTop until bot step 12) for (x in 4 until W - 4 step 12) {
            val o = off(x, y); if (o + 2 >= f.size) continue
            rs.add(f[o].toInt() and 255); gs.add(f[o + 1].toInt() and 255); bs.add(f[o + 2].toInt() and 255)
        }
        if (rs.isEmpty()) return null
        rs.sort(); gs.sort(); bs.sort()
        val bgR = rs[rs.size / 2]; val bgG = gs[gs.size / 2]; val bgB = bs[bs.size / 2]
        val er = max(2, (6f * W / 1080f).roundToInt())
        val hasRef = rf.size == f.size
        val md2 = MIND * MIND; val rd2 = 30 * 30; val pd2 = 60 * 60
        // Nur Pixel in der Farbe des Teils: der dunklere "Schatten", den das Spiel an der Landestelle
        // zeichnet, zählt so nicht zum Teil (sonst wird die Fläche zu groß gemessen).
        fun on(x: Int, y: Int): Boolean {
            val o = off(x, y); if (o + 2 >= f.size) return false
            val r = f[o].toInt() and 255; val g = f[o + 1].toInt() and 255; val b = f[o + 2].toInt() and 255
            val dr = r - bgR; val dg = g - bgG; val db = b - bgB
            if (dr * dr + dg * dg + db * db <= md2) return false
            if (pc != null) {
                val pr = r - pc[0]; val pg = g - pc[1]; val pb = b - pc[2]
                if (pr * pr + pg * pg + pb * pb > pd2) return false
            }
            if (!hasRef) return true
            val er2 = r - (rf[o].toInt() and 255); val eg = g - (rf[o + 1].toInt() and 255); val eb = b - (rf[o + 2].toInt() and 255)
            return er2 * er2 + eg * eg + eb * eb > rd2
        }
        var mnX = Int.MAX_VALUE; var mnY = Int.MAX_VALUE; var mxX = -1; var mxY = -1
        for (y in top until bot step 5) {
            for (x in 4 until W - 4 step 5) {
                if (on(x, y) && on(x - er, y) && on(x + er, y) && on(x, y - er) && on(x, y + er)) {
                    mnX = min(mnX, x); mxX = max(mxX, x); mnY = min(mnY, y); mxY = max(mxY, y)
                }
            }
        }
        if (mxX < 0) return null
        if (mnY <= top + 8 || mxY >= bot - 8) return null    // ragt aus dem Suchbereich: Lage unsicher
        return RectF(mnX.toFloat(), mnY.toFloat(), mxX.toFloat(), mxY.toFloat())
    }

    /** Passt die gefundene Fläche zur Form des Teils (ungefähr Brett-Feldgröße)? */
    private fun plausible(r: RectF, rows: Int, cols: Int): Boolean {
        val cs = BS * W / 8
        val fw = r.width() / cols / cs; val fh = r.height() / rows / cs
        return fw in 0.45f..1.4f && fh in 0.45f..1.4f && abs(fw - fh) < 0.25f
    }

    private var measureInfo = ""
    private var lastFloat: RectF? = null              // zuletzt gefundene Fläche (fürs Fehlerbild)
    private val worker = HandlerThread("bb-messen").apply { start() }
    private val wh by lazy { Handler(worker.looper) }

    /**
     * Ziehen mit Hinsehen:
     *  1. Teil greifen und halten, bis es ruhig schwebt -> Lage messen (P0 bei Finger F0)
     *  2. Finger etwas zur Mitte (und je nach Platz hoch/runter) bewegen -> erneut messen (P1 bei F1)
     *  3. Daraus Verstärkung k und Versatz berechnen: P = P0 + k * (F - F0)
     *  4. Finger so hinbewegen, dass die Teil-Mitte genau über dem Ziel liegt, loslassen.
     *
     * Wichtig: Android bricht die Geste ab, wenn zwischen zwei Teilschritten eine Lücke entsteht.
     * Deshalb wird beim Halten der nächste kurze Halteschritt sofort angehängt, und gemessen wird
     * währenddessen im Mess-Thread. Erst wenn das Ergebnis da ist, folgt statt "weiter halten" der
     * nächste Schritt.
     */
    private fun doDrag() {
        if (!running || paused || dragging) return
        val m = planned ?: return
        val box = grabAt ?: return
        val svc = AutoService.inst
        if (svc == null) { pauseWith("Automatik: Bedienungshilfe \"Block-Helfer Automatik\" ist nicht eingeschaltet"); return }
        grab()                                           // frisches Bild als Vergleich (mit Hinweis-Rahmen)
        if (refFb.size != fb.size) refFb = ByteArray(fb.size)
        System.arraycopy(fb, 0, refFb, 0, fb.size)
        dragging = true
        lastFloat = null
        landingDone = false
        DragJob(svc, m, box.centerX(), box.centerY(), pieceColor(box, refFb)).start()
    }

    private inner class DragJob(val svc: AutoService, val m: Solver.Move, val sx: Float, val sy: Float, val pc: IntArray?) {
        val cs = BS * W / 8
        val rows = m.cells.maxOf { it[0] } + 1
        val cols = m.cells.maxOf { it[1] } + 1
        val key = "${rows}x$cols"
        val rawX = BX * W + (m.c + cols / 2f) * cs
        val rawY = BY * H + (m.r + rows / 2f) * cs
        val top = BY * H
        val bot = TY1 * H - 34

        var phase = 1                      // 1 = Messung nach dem Greifen, 2 = nach der Testbewegung
        var next: (() -> Unit)? = null     // statt weiter zu halten als Nächstes ausführen
        var decided = false                // Ergebnis dieser Phase steht fest
        var busy = false                   // Messung läuft gerade im Mess-Thread
        var ticks = 0
        var last: RectF? = null
        val good = ArrayList<RectF>()
        var p0: RectF? = null
        var dxF = 0f
        var dyF = 0f

        fun start() {
            lastDragKey = key
            val k = calK; val off = calOff[key]
            if (k != null && off != null) { fast(k, off); return }
            lastDragFast = false
            svc.down(sx, sy, 150) { ok -> if (!ok) h.post { dragging = false; dragFailed() } else startPhase(1) }
        }

        /** Form schon vermessen: greifen, kurz anheben lassen, direkt zum Ziel, loslassen. */
        fun fast(k: FloatArray, off: FloatArray) {
            lastDragFast = true
            val p0x = sx + off[0] * cs; val p0y = sy + off[1] * cs
            val (fx, fy) = fingerFor(p0x, p0y, k[0], k[1]) ?: run { calOff.remove(key); lastDragFast = false
                svc.down(sx, sy, 150) { ok -> if (!ok) h.post { dragging = false; dragFailed() } else startPhase(1) }; return }
            svc.down(sx, sy, 150) { ok ->
                if (!ok) h.post { dragging = false; dragFailed() }
                else svc.hold(150) { ok1 ->
                    if (!ok1) h.post { dragging = false; dragFailed() } else release(fx, fy)
                }
            }
        }

        /** Fingerziel, damit die Teil-Mitte (bei Finger S an Stelle p0) über dem Ziel liegt; mit gelernter Korrektur. */
        fun fingerFor(p0x: Float, p0y: Float, kx: Float, ky: Float): Pair<Float, Float>? {
            usedTravelX = (rawX - p0x) / cs; usedTravelY = (rawY - p0y) / cs
            usedCorrX = -predict(learnX, usedTravelX); usedCorrY = -predict(learnY, usedTravelY)
            val tx = rawX + usedCorrX * cs; val ty = rawY + usedCorrY * cs
            val fx = sx + (tx - p0x) / kx; val fy = sy + (ty - p0y) / ky
            return if (fx < 2 || fx > W - 2 || fy < 2 || fy > H - 2) null else Pair(fx, fy)
        }

        /** Zum Ziel bewegen, kurz halten (Spiel zieht das Teil nach), loslassen, Landung prüfen. */
        fun release(fx: Float, fy: Float) {
            svc.move(fx, fy, 350) { ok ->
                if (!ok) h.post { stopWith("Geste abgebrochen (Zielbewegung)") }
                else svc.hold(250) { _ -> svc.up { _ -> h.post {
                    dragging = false; dragAt = SystemClock.uptimeMillis()
                    // Landung sofort prüfen, bevor abgeräumte Reihen verschwinden
                    for (d in longArrayOf(150, 350)) h.postDelayed({
                        if (!landingDone && !dragging) try { grab(); checkLanding(readCells()) } catch (_: Exception) {}
                    }, d)
                } } }
            }
        }

        fun stopWith(msg: String) {
            dragging = false; dragAt = 0
            try { saveDebug("Auto: $msg", lastPieces, readCells(), m) } catch (_: Exception) {}
            pauseWith("Automatik pausiert: $msg")
        }

        fun decide(action: () -> Unit) { decided = true; next = action }

        fun fail(msg: String) = decide { svc.up { h.post { stopWith(msg) } } }

        fun startPhase(p: Int) {
            phase = p; decided = false; busy = false; ticks = 0; last = null; good.clear()
            keepHolding()
        }

        /** Kurzen Halteschritt ausführen; danach sofort den nächsten anhängen oder den beschlossenen Schritt. */
        fun keepHolding() {
            svc.hold(90) { ok ->
                if (!ok) { h.post { if (dragging) stopWith("Geste abgebrochen (Schritt $phase)") } }
                else {
                    val a = next
                    if (a != null) { next = null; a() } else { keepHolding(); measureTick() }
                }
            }
        }

        /** Messung im Mess-Thread anstoßen (Hauptthread bleibt frei für die Halteschritte). */
        fun measureTick() {
            if (decided || busy) return
            busy = true
            wh.post {
                val raw = try { grab(); findFloating(pc) } catch (e: Exception) { null }
                h.post {
                    busy = false
                    try { evaluate(raw) } catch (e: Exception) { if (!decided) fail("Fehler: ${e.javaClass.simpleName}") }
                }
            }
        }

        /** Eine Messung auswerten (Hauptthread). */
        fun evaluate(raw: RectF?) {
            if (decided || !dragging) return
            ticks++
            val p = raw?.takeIf { plausible(it, rows, cols) }
            lastFloat = raw
            measureInfo = if (raw == null) "nichts gefunden" else
                "Fläche %.2f x %.2f Felder (erwartet %d x %d), passend: %d".format(
                    raw.width() / cs, raw.height() / cs, cols, rows, good.size + (if (p != null) 1 else 0))
            if (p != null) {
                val l = last
                good.add(p)
                val tol = cs * 0.15f
                if (l != null && abs(p.centerX() - l.centerX()) <= tol &&
                    abs(p.centerY() - l.centerY()) <= tol && abs(p.width() - l.width()) <= tol) {
                    result(RectF((p.left + l.left) / 2, (p.top + l.top) / 2, (p.right + l.right) / 2, (p.bottom + l.bottom) / 2))
                    return
                }
            }
            last = p
            if (ticks >= 15) {
                if (good.size >= 3) {
                    val n = good.takeLast(4)
                    result(RectF(n.map { it.left }.average().toFloat(), n.map { it.top }.average().toFloat(),
                        n.map { it.right }.average().toFloat(), n.map { it.bottom }.average().toFloat()))
                } else fail("gezogenes Teil nicht gefunden ($phase) – $measureInfo")
            }
        }

        fun result(r: RectF) {
            if (phase == 1) {
                p0 = r
                val roomDown = bot - r.bottom; val roomUp = r.top - top
                dxF = (if (sx < W / 2f) 0.8f else -0.8f) * cs
                dyF = if (roomDown > roomUp) min(roomDown * 0.3f, cs) else -min(roomUp * 0.3f, cs)
                if (abs(dyF) < 0.15f * cs) dyF = 0f        // kein Platz: senkrecht wie waagerecht annehmen
                decide {
                    svc.move(sx + dxF, sy + dyF, 200) { ok ->
                        if (!ok) h.post { stopWith("Geste abgebrochen (Testbewegung)") } else startPhase(2)
                    }
                }
                return
            }
            val q0 = p0!!
            val kx = (r.centerX() - q0.centerX()) / dxF
            val ky = if (dyF != 0f) (r.centerY() - q0.centerY()) / dyF else (calK?.get(1) ?: kx)
            if (kx !in 0.5f..5f || ky !in 0.5f..5f) { fail("Zugverhalten nicht messbar (k=%.2f/%.2f)".format(kx, ky)); return }
            val target = fingerFor(q0.centerX(), q0.centerY(), kx, ky)
            if (target == null) { fail("Ziel außerhalb des Bildschirms"); return }
            // Messung merken: beim nächsten Teil dieser Form geht es ohne Ausmessen
            calK = if (dyF != 0f || calK == null) floatArrayOf(kx, ky) else floatArrayOf(kx, calK!![1])
            calOff[key] = floatArrayOf((q0.centerX() - sx) / cs, (q0.centerY() - sy) / cs)
            saveLearning()
            decide { release(target.first, target.second) }
        }
    }

    /** Das Teil liegt nach der Geste noch in der Ablage: nochmal versuchen, sonst pausieren. */
    private fun dragFailed() {
        dragAt = 0
        tryIdx++
        if (tryIdx >= 3) { tryIdx = 0; pauseWith("Automatik pausiert: Teil ließ sich nicht setzen"); return }
        h.postDelayed({ doDrag() }, 400)
    }

    /** Nach einem Zug (Brett wieder ruhig): Landung prüfen, falls das nicht schon direkt nach dem Loslassen ging. */
    private fun learn(cells: IntArray, count: Int) {
        dragAt = 0
        if (count >= plannedCount) return   // nichts gesetzt (z. B. neuer Satz) – nicht lernen
        tryIdx = 0
        if (!landingDone) checkLanding(cells)
    }

    /**
     * Wo ist das Teil wirklich gelandet? Abweichung (in Feldern) zusammen mit der Zugstrecke speichern.
     * Landet eine bereits vermessene Form zweimal daneben, wird sie beim nächsten Mal neu vermessen.
     */
    private fun checkLanding(cells: IntArray): Boolean {
        val m = planned ?: return false
        val before = plannedBoard ?: return false
        val fresh = (0 until 64).filter { cells[it] == FULL && before[it] == EMPTY }
        if (fresh.size != m.cells.size) return false
        val r0 = fresh.minOf { it / 8 }; val c0 = fresh.minOf { it % 8 }
        val shape = fresh.map { (it / 8 - r0) * 8 + (it % 8 - c0) }.toSet()
        if (shape != m.cells.map { it[0] * 8 + it[1] }.toSet()) return false
        landingDone = true
        val dr = r0 - m.r; val dc = c0 - m.c
        // tatsächlicher Fehler = Landestelle minus Zielpunkt, auf den gezielt wurde
        addSample(learnX, usedTravelX, dc - usedCorrX)
        addSample(learnY, usedTravelY, dr - usedCorrY)
        if (dr == 0 && dc == 0) {
            misses = 0; fastMiss.remove(lastDragKey)
        } else {
            misses++
            if (lastDragFast) {
                val n = (fastMiss[lastDragKey] ?: 0) + 1
                if (n >= 2) { calOff.remove(lastDragKey); fastMiss.remove(lastDragKey) } else fastMiss[lastDragKey] = n
            }
            if (misses >= 6) { misses = 0; pauseWith("Automatik pausiert: Teile landen mehrmals daneben") }
        }
        saveLearning()
        return true
    }

    private fun addSample(l: ArrayList<FloatArray>, travel: Float, err: Float) {
        l.add(floatArrayOf(travel, err))
        while (l.size > 12) l.removeAt(0)
    }

    /** Geschätzter Fehler (Felder) bei dieser Zugstrecke: Gerade durch die gespeicherten Züge. */
    private fun predict(l: List<FloatArray>, travel: Float): Float {
        if (l.isEmpty()) return 0f
        val n = l.size
        val mt = l.sumOf { it[0].toDouble() } / n; val me = l.sumOf { it[1].toDouble() } / n
        var sxx = 0.0; var sxy = 0.0
        for (p in l) { sxx += (p[0] - mt) * (p[0] - mt); sxy += (p[0] - mt) * (p[1] - me) }
        val b = if (n >= 4 && sxx / n > 0.5) (sxy / sxx).coerceIn(-0.5, 0.5) else 0.0
        return (me + b * (travel - mt)).toFloat().coerceIn(-3f, 3f)
    }

    // Gespeichert unter neuen Namen (v2): ältere Werte stammen aus der Zeit, als der Schatten des
    // Spiels die Messung verfälscht hat, und werden deshalb nicht übernommen.
    private fun loadLearning() {
        try {
            fun parse(k: String, l: ArrayList<FloatArray>) {
                l.clear()
                prefs().getString(k, "")!!.split(';').filter { it.contains(':') }.forEach {
                    val (t, e) = it.split(':'); l.add(floatArrayOf(t.toFloat(), e.toFloat()))
                }
            }
            parse("learnX2", learnX); parse("learnY2", learnY)
            calOff.clear()
            prefs().getString("calOff2", "")!!.split(';').filter { it.contains('=') }.forEach {
                val (k, v) = it.split('='); val (a, b) = v.split(':'); calOff[k] = floatArrayOf(a.toFloat(), b.toFloat())
            }
            calK = prefs().getString("calK2", "")!!.takeIf { it.contains(':') }?.split(':')?.let { floatArrayOf(it[0].toFloat(), it[1].toFloat()) }
        } catch (_: Exception) { learnX.clear(); learnY.clear(); calOff.clear(); calK = null }
    }

    private fun saveLearning() {
        fun str(l: List<FloatArray>) = l.joinToString(";") { "${it[0]}:${it[1]}" }
        prefs().edit().putString("learnX2", str(learnX)).putString("learnY2", str(learnY))
            .putString("calOff2", calOff.entries.joinToString(";") { "${it.key}=${it.value[0]}:${it.value[1]}" })
            .putString("calK2", calK?.let { "${it[0]}:${it[1]}" } ?: "")
            .apply()
    }

    // ---------- Fehlerbild ----------

    /**
     * Speichert das Bildschirmbild mit dem, was der Helfer gelesen hat, in der Galerie
     * (Bilder/BlockHelfer): Punkt je Brettfeld (grün = leer, rot = belegt, gelb = unsicher),
     * die erkannten Teile oben in der Ablage und den vorgeschlagenen Zug.
     */
    private fun saveDebug(reason: String, pieces: List<List<IntArray>>, cells: IntArray, m: Solver.Move?) {
        if (!hasFrame || frs <= 0) return
        val data = fb.copyOf(); val stride = frs; val w = W; val hh = H
        val bx = BX * W; val by = BY * H; val cs = BS * W / 8
        Thread {
            try {
                val raw = Bitmap.createBitmap(stride / 4, min(hh, data.size / stride), Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(data, 0, raw.byteCount))
                val bmp = Bitmap.createBitmap(raw, 0, 0, min(w, raw.width), raw.height).copy(Bitmap.Config.ARGB_8888, true)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                // Lesung des Bretts
                for (i in 0 until 64) {
                    p.style = Paint.Style.FILL
                    p.color = when (cells[i]) { EMPTY -> Color.GREEN; FULL -> Color.RED; else -> Color.YELLOW }
                    cv.drawCircle(bx + (i % 8 + .5f) * cs, by + (i / 8 + .5f) * cs, cs * .1f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.BLACK
                    cv.drawCircle(bx + (i % 8 + .5f) * cs, by + (i / 8 + .5f) * cs, cs * .1f, p)
                }
                // vorgeschlagener Zug
                if (m != null) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.WHITE
                    for (x in m.cells) {
                        val l = bx + (m.c + x[1]) * cs; val t = by + (m.r + x[0]) * cs
                        cv.drawRect(l + 8, t + 8, l + cs - 8, t + cs - 8, p)
                    }
                }
                // erkannte Teile, klein über der Ablage
                val ms = cs * .22f; val ty = TY0 * hh - 5 * ms - 10
                pieces.forEachIndexed { s, pc ->
                    val sx = w * s / 3f + 20
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.WHITE
                    cv.drawRect(sx - 4, ty - 4, sx + 5 * ms + 4, ty + 5 * ms + 4, p)
                    p.style = Paint.Style.FILL; p.color = COL[s] or 0xFF000000.toInt()
                    for (x in pc) cv.drawRect(sx + x[1] * ms + 1, ty + x[0] * ms + 1, sx + (x[1] + 1) * ms - 1, ty + (x[0] + 1) * ms - 1, p)
                }
                // Grund + Rahmen von Brett und Ablage
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.MAGENTA
                cv.drawRect(bx, by, bx + 8 * cs, by + 8 * cs, p)
                cv.drawRect(0f, TY0 * hh, w.toFloat(), TY1 * hh, p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = 40f
                p.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                val parts = reason.split(" – ", limit = 2)
                if (parts.size == 2) {
                    cv.drawText(parts[0], 30f, by - 64f, p)
                    p.textSize = 30f; cv.drawText(parts[1], 30f, by - 20f, p)
                } else cv.drawText(reason, 30f, by - 20f, p)
                lastFloat?.let { r ->
                    p.clearShadowLayer(); p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.CYAN
                    cv.drawRect(r, p)
                }

                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "blockhelfer_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BlockHelfer")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    h.post { Toast.makeText(this, "Scan gespeichert (Galerie > BlockHelfer)", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                h.post { toast("Scan konnte nicht gespeichert werden: ${e.message}") }
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(poll)
        worker.quitSafely()
        try { wm.removeView(btn); wm.removeView(ov) } catch (_: Exception) {}
        mp?.stop()
        super.onDestroy()
    }
}
