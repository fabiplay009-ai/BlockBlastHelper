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
import kotlin.math.max
import kotlin.math.min

class HelperService : Service() {
    // Erkennung und Kalibrierung stecken in Vision.java, Lernen/Ausmessen der Automatik in AutoBrain.java
    // (beide ohne Android, damit sie sich am PC mit dem Simulator in sim/ testen lassen).
    private val BX = Vision.BX
    private val BY = Vision.BY
    private val BS = Vision.BS
    private val TY0 = Vision.TY0
    private val TY1 = Vision.TY1
    private val EMPTY = Vision.EMPTY
    private val FULL = Vision.FULL
    // So lange muss das Bild unverändert sein, bevor neu gerechnet wird (Zeit statt Bilderzahl, damit eine
    // schnellere Abfrage nicht versehentlich Animationen, z. B. einfliegende Teile, als fertig liest)
    private val STABLE_NEW_MS = 700L     // neuer Satz / Brett geändert
    private val STABLE_FEWER_MS = 1050L  // ein Teil fehlt (könnte gerade gezogen werden)
    private val POLL_MS = 350L           // so oft schaut der Helfer auf die Ablage

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

    private var running = true
    // Ablauf: Brett + Ablage werden ständig gescannt. Sobald sich etwas geändert hat und das Bild
    // ruhig ist, wird mit dem echten Brett neu gerechnet und nur der nächste Zug angezeigt.
    private var analyzedKey: String? = null
    private var analyzedBoard = ""
    private var analyzedCount = 0
    private var lastKey = ""
    private var stableSince = 0L
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
    private var lastPieces: List<List<IntArray>> = emptyList()
    private var refFb = ByteArray(0)                 // Bild vor dem Greifen (zum Vergleich beim Suchen)
    private val brain = AutoBrain()                  // Gelerntes: Landefehler, Vermessung je Form, Verstärkung
    private var landingDone = false

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
        if (auto) brain.load(prefs().getString("brain3", ""))
        btn = TextView(this).apply {
            text = if (auto) "⏸ Auto" else "↻ Zug"; setTextColor(Color.WHITE); setPadding(32, 20, 32, 20)
            setBackgroundColor(0xCC2D3280.toInt())
            setOnClickListener {
                if (auto) togglePause()
                else { analyzedKey = null; analyzedCount = 0; stableSince = 0; saveNext = true }  // neu berechnen + Scan speichern
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

    private fun frame() = Vision.Frame(fb, frs, W, H)

    private fun readCells(): IntArray = Vision.readCells(frame())

    private fun readTray(): List<List<IntArray>> {
        val boxes = arrayOfNulls<Vision.Box>(3)
        val tray = Vision.readTray(frame(), boxes)
        for (s in 0..2) trayBox[s] = boxes[s]?.let { RectF(it.left, it.top, it.right, it.bottom) }
        return tray
    }

    // ---------- Ablauf ----------

    private fun tick() {
        if (auto && dragging) return   // Geste läuft gerade (Bilder liest dann der Mess-Thread)
        grab()
        if (!hasFrame) return
        val tray = readTray()
        val count = tray.count { it.isNotEmpty() }
        val sig = Vision.signature(tray)
        val cells = readCells()
        val board = cells.joinToString("")
        val key = "$board/$sig"
        val now = SystemClock.uptimeMillis()
        if (key != lastKey) { lastKey = key; stableSince = now }
        val stableMs = now - stableSince
        if (key == analyzedKey) {
            // Automatik: nach der Geste hat sich nichts geändert -> Teil wurde nicht gesetzt
            if (auto && !paused && dragAt > 0 && SystemClock.uptimeMillis() - dragAt > 3000) dragFailed()
            return
        }

        if (count == 0) {
            // alle Teile gesetzt: Hinweis weg, auf neuen Satz warten
            if (ov.items.isNotEmpty()) { ov.items.clear(); ov.invalidate() }
            if (auto && dragAt > 0 && stableMs >= STABLE_FEWER_MS) learn(cells, count)
            return
        }
        // Teil fehlt, Brett aber unverändert: wird gerade gezogen -> Hinweis stehen lassen
        if (count < analyzedCount && board == analyzedBoard) return
        val need = if (count < analyzedCount) STABLE_FEWER_MS else STABLE_NEW_MS
        if (stableMs < need) return
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
        else { tryIdx = 0; brain.misses = 0; dragAt = 0; analyzedKey = null; analyzedCount = 0; stableSince = 0 }
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

    private var measureInfo = ""
    private var lastFloat: RectF? = null              // zuletzt gefundene Fläche (fürs Fehlerbild)
    private val worker = HandlerThread("bb-messen").apply { start() }
    private val wh by lazy { Handler(worker.looper) }

    /**
     * Ziehen mit Hinsehen (Ablauf und Entscheidungen in AutoBrain.Plan):
     *  1. Teil greifen und halten, bis es ruhig unter dem Brett schwebt -> Lage messen
     *  2. Finger etwas zur Seite (und nach unten, wenn Platz) -> erneut messen -> Verstärkung
     *  3. Finger so hinbewegen, dass die Teil-Mitte genau über dem Ziel liegt, loslassen.
     *
     * Wichtig: Android bricht die Geste ab, wenn zwischen zwei Teilschritten eine Lücke entsteht.
     * Deshalb wird beim Halten der nächste kurze Halteschritt sofort angehängt, und gemessen wird
     * währenddessen im Mess-Thread. Erst wenn das Ergebnis da ist, folgt statt "weiter halten" der
     * nächste Schritt.
     */
    private fun doDrag() {
        if (!running || paused || dragging) return
        val m = planned ?: return
        val svc = AutoService.inst
        if (svc == null) { pauseWith("Automatik: Bedienungshilfe \"Block-Helfer Automatik\" ist nicht eingeschaltet"); return }
        grab()                                           // frisches Bild als Vergleich (mit Hinweis-Rahmen)
        // Liegt in der Ablage noch genau das geplante Teil? Sonst (z. B. Teil flog noch ein) neu rechnen.
        val now = readTray()
        val want = lastPieces.getOrNull(m.piece)
        if (want == null || Vision.signature(listOf(now[m.piece])) != Vision.signature(listOf(want))) {
            // Fehlerbild zur Analyse (z. B. Schriftzug/Animation über der Ablage für ein Teil gehalten)
            try { saveDebug("Auto: Ablage hat sich vor dem Greifen geändert – neu gerechnet", now, readCells(), null) } catch (_: Exception) {}
            analyzedKey = null; stableSince = SystemClock.uptimeMillis()
            return
        }
        val box = trayBox[m.piece] ?: return
        if (refFb.size != fb.size) refFb = ByteArray(fb.size)
        System.arraycopy(fb, 0, refFb, 0, fb.size)
        dragging = true
        lastFloat = null
        landingDone = false
        brain.retry = tryIdx                             // bei Wiederholung Ziel probeweise etwas versetzen
        val pc = Vision.pieceColor(Vision.Frame(refFb, frs, W, H), Vision.Box(box.left, box.top, box.right, box.bottom))
        DragJob(svc, m, box.centerX(), box.centerY(), pc).start()
    }

    private inner class DragJob(val svc: AutoService, val m: Solver.Move, val sx: Float, val sy: Float, val pc: IntArray?) {
        val rows = m.cells.maxOf { it[0] } + 1
        val cols = m.cells.maxOf { it[1] } + 1
        val plan = brain.Plan(W, H, sx, sy, rows, cols, m.r, m.c)
        var next: (() -> Unit)? = null     // statt weiter zu halten als Nächstes ausführen
        var decided = false                // Ergebnis dieser Phase steht fest
        var busy = false                   // Messung läuft gerade im Mess-Thread

        fun start() {
            brain.lastKey = plan.key
            val t = plan.fastTarget()
            if (t != null) { fast(t); return }
            brain.lastFast = false
            svc.down(sx, sy, 150) { ok -> if (!ok) h.post { dragging = false; dragFailed() } else startPhase() }
        }

        /** Form schon vermessen: greifen, kurz anheben lassen, direkt zum Ziel, loslassen. */
        fun fast(t: FloatArray) {
            brain.lastFast = true
            svc.down(sx, sy, 150) { ok ->
                if (!ok) h.post { dragging = false; dragFailed() }
                else svc.hold(150) { ok1 ->
                    if (!ok1) h.post { dragging = false; dragFailed() } else release(t[0], t[1])
                }
            }
        }

        /** Zum Ziel bewegen, kurz halten (Spiel zieht das Teil nach), loslassen, Landung prüfen. */
        fun release(fx: Float, fy: Float) {
            svc.move(fx, fy, 350) { ok ->
                if (!ok) h.post { stopWith("Geste abgebrochen (Zielbewegung)") }
                else svc.hold(250) { _ -> svc.up { _ -> h.post {
                    dragging = false; dragAt = SystemClock.uptimeMillis()
                    saveBrain()
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

        fun startPhase() {
            decided = false; busy = false
            keepHolding()
        }

        /** Kurzen Halteschritt ausführen; danach sofort den nächsten anhängen oder den beschlossenen Schritt. */
        fun keepHolding() {
            svc.hold(90) { ok ->
                if (!ok) { h.post { if (dragging) stopWith("Geste abgebrochen (Schritt ${plan.phase})") } }
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
                val raw = try { grab(); Vision.findFloating(frame(), Vision.Frame(refFb, frs, W, H), pc) } catch (e: Exception) { null }
                h.post {
                    busy = false
                    try { evaluate(raw) } catch (e: Exception) { if (!decided) fail("Fehler: ${e.javaClass.simpleName}") }
                }
            }
        }

        fun fail(msg: String) = decide { svc.up { h.post { stopWith(msg) } } }

        /** Eine Messung an AutoBrain.Plan geben und dessen Entscheidung ausführen (Hauptthread). */
        fun evaluate(raw: Vision.Box?) {
            if (decided || !dragging) return
            lastFloat = raw?.let { RectF(it.left, it.top, it.right, it.bottom) }
            val phase = plan.phase
            when (plan.onMeasure(raw)) {
                AutoBrain.Plan.MOVE -> {
                    val x = plan.ax; val y = plan.ay
                    decide {
                        svc.move(x, y, 200) { ok ->
                            if (!ok) h.post { stopWith("Geste abgebrochen (Bewegung in Schritt $phase)") } else startPhase()
                        }
                    }
                }
                AutoBrain.Plan.RELEASE -> { val x = plan.ax; val y = plan.ay; decide { release(x, y) } }
                AutoBrain.Plan.FAIL -> fail(plan.msg)
            }
            measureInfo = plan.info
        }
    }

    /** Das Teil liegt nach der Geste noch in der Ablage: nochmal versuchen, sonst pausieren. */
    private fun dragFailed() {
        dragAt = 0
        brain.notPlaced()      // Form beim nächsten Versuch neu vermessen (gespeicherte Werte passten nicht)
        saveBrain()
        tryIdx++
        if (tryIdx >= 5) { tryIdx = 0; pauseWith("Automatik pausiert: Teil ließ sich nicht setzen"); return }
        h.postDelayed({ doDrag() }, 400)
    }

    /** Nach einem Zug (Brett wieder ruhig): Landung prüfen, falls das nicht schon direkt nach dem Loslassen ging. */
    private fun learn(cells: IntArray, count: Int) {
        dragAt = 0
        if (count >= plannedCount) return   // nichts gesetzt (z. B. neuer Satz) – nicht lernen
        tryIdx = 0
        if (!landingDone) checkLanding(cells)
    }

    /** Wo ist das Teil wirklich gelandet? Lernen steckt in AutoBrain.checkLanding. */
    private fun checkLanding(cells: IntArray): Boolean {
        val m = planned ?: return false
        val before = plannedBoard ?: return false
        brain.checkLanding(before, cells, m.cells, m.r, m.c) ?: return false
        landingDone = true
        saveBrain()
        if (brain.misses >= 6) { brain.misses = 0; pauseWith("Automatik pausiert: Teile landen mehrmals daneben") }
        return true
    }

    // Gespeichert unter neuem Namen (brain3); ältere Werte werden nicht übernommen.
    private fun saveBrain() = prefs().edit().putString("brain3", brain.save()).apply()

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
