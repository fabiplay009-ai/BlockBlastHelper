package com.example.riderbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Auto-Player für Rider: liest den Bildschirm, erkennt das Motorrad und entscheidet,
 * ob der Finger gedrückt (Gas / Rückwärtssalto) oder losgelassen wird.
 *
 * Winkel-Konvention: phi = Neigung des Motorrads in Grad, 0 = aufrecht,
 * positiv = gegen den Uhrzeigersinn (Vorderrad hoch, wenn es nach rechts fährt).
 */
class BotService : Service() {
    // ---- Einstellungen (Anteile von Bildschirmbreite W bzw. -höhe H) ----
    private val CAP_W = 540          // Breite des ausgewerteten Bildes (verkleinert = schneller)
    private val BIKE_X = 0.33f       // ungefähre waagerechte Position des Motorrads (x W)
    private val WIN_HALF = 0.20f     // halbe Breite des Suchfensters (x W)
    private val WIN_Y0 = 0.14f       // Suchfenster oben (x H) – darüber liegen Knöpfe und Anzeige
    private val WIN_Y1 = 0.95f       // Suchfenster unten (x H)
    private val LUM_MIN = 90         // Pixel zählt als "hell" (Strecke/Motorrad) ab dieser Helligkeit ...
    private val LUM_DELTA = 60       // ... bzw. wenn er so viel heller als der Hintergrund ist
    private val MIN_PIX = 0.0025f    // kleinstes Motorrad: Pixelanzahl relativ zu (Bildbreite)²
    private val MIN_LEN = 0.05f      // kleinstes Motorrad: Länge (x W)
    private val MAX_JUMP = 0.18f     // so weit darf das Motorrad zwischen zwei Bildern springen (x W)
    private val FLIP_H = 0.22f       // höher als das über der Strecke (x H) -> Salto drehen
    private val TOL = 25f            // so genau muss die Landung passen (Grad)
    private val LEAD = 0.12f         // Vorhersage: Reaktionszeit Finger + Spiel (Sekunden)
    private val GROUND_HOLD = true   // am Boden gedrückt halten (Gas geben)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var work: Handler
    private lateinit var thread: HandlerThread
    private lateinit var wm: WindowManager
    private lateinit var reader: ImageReader
    private lateinit var status: TextView
    private lateinit var bar: LinearLayout
    private lateinit var playBtn: TextView
    private var mp: MediaProjection? = null
    private var W = 0; private var H = 0          // echter Bildschirm
    private var cw = 0; private var ch = 0        // ausgewertetes Bild
    private var fb = ByteArray(0); private var rs = 0

    @Volatile private var active = false
    @Volatile private var snapWanted = false

    // Zustand der Erkennung
    private var lastX = -1f; private var lastY = -1f
    private var phi = 0f; private var omega = 0f; private var groundAng = 0f
    private var air = false; private var airFrames = 0; private var lastT = 0L
    private var pressed = false
    private var frames = 0; private var fpsT = 0L; private var fps = 0

    override fun onBind(i: Intent?): IBinder? = null

    private fun lp(w: Int, h: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rb", "Rider-Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rb").setContentTitle("Rider-Bot läuft")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        if (mp != null) return START_NOT_STICKY

        wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        W = b.width(); H = b.height()
        cw = CAP_W; ch = (H.toLong() * CAP_W / W).toInt()

        thread = HandlerThread("cap").apply { start() }
        work = Handler(thread.looper)
        mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        mp!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, main)
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ onFrame() }, work)
        mp!!.createVirtualDisplay("rb", cw, ch, resources.displayMetrics.densityDpi * cw / W,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, work)

        // Anzeige oben (außerhalb des Suchfensters, damit sie die Erkennung nicht stört)
        fun button(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTextColor(Color.WHITE); setPadding(36, 16, 36, 16)
            setBackgroundColor(0xCC2D3280.toInt())
        }
        playBtn = button("▶").apply {
            setOnClickListener { toggle() }
            setOnLongClickListener { stopSelf(); true }
        }
        val snap = button("📷").apply { setOnClickListener { snapWanted = true } }
        bar = LinearLayout(this).apply { addView(playBtn); addView(snap) }
        wm.addView(bar, lp(-2, -2, 8).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = (0.04f * H).toInt() })
        status = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); setPadding(16, 8, 16, 8)
            text = "bereit"
        }
        wm.addView(status, lp(-2, -2, 8 or 16).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = (0.04f * H).toInt() })
        return START_NOT_STICKY
    }

    private fun toggle() {
        active = !active
        playBtn.text = if (active) "⏸" else "▶"
        work.post { setPress(false); air = false; airFrames = 0; lastX = -1f }
    }

    private fun setPress(p: Boolean) {
        if (p == pressed) return
        pressed = p
        val x = W * 0.5f; val y = H * 0.55f
        main.post { TouchService.instance?.press(p, x, y) }
    }

    // ---------------- Bild ----------------

    private fun onFrame() {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val pl = img.planes[0]
            val buf = pl.buffer
            buf.rewind()
            rs = pl.rowStride
            val need = rs * ch
            if (fb.size != need) fb = ByteArray(need)
            buf.get(fb, 0, min(buf.remaining(), need))
        } finally { img.close() }

        if (snapWanted) { snapWanted = false; saveSnap() }
        val now = SystemClock.uptimeMillis()
        frames++
        if (now - fpsT >= 1000) { fps = frames; frames = 0; fpsT = now }
        if (!active) { show("aus · $fps fps"); return }
        try { decide(now) } catch (e: Exception) { setPress(false); show("Fehler: ${e.message}") }
    }

    private fun lum(x: Int, y: Int): Int {
        val o = y * rs + x * 4
        return ((fb[o].toInt() and 255) * 3 + (fb[o + 1].toInt() and 255) * 6 + (fb[o + 2].toInt() and 255)) / 10
    }

    private fun saveSnap() {
        try {
            val full = Bitmap.createBitmap(rs / 4, ch, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(ByteBuffer.wrap(fb))
            val bmp = Bitmap.createBitmap(full, 0, 0, cw, ch)
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "rider_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RiderBot")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            main.post { Toast.makeText(this, "Gespeichert in Bilder/RiderBot", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            main.post { Toast.makeText(this, "Foto fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Erkennung ----------------

    private class Blob(val xs: IntArray, val ys: IntArray, val n: Int, val edge: Boolean,
                       val cx: Float, val cy: Float, val minY: Int)

    // Fenster-Koordinaten
    private var wx0 = 0; private var wx1 = 0; private var wy0 = 0; private var wy1 = 0
    private var lab = IntArray(0)
    private var mask = BooleanArray(0)

    private fun findBlobs(): List<Blob> {
        val bx = (BIKE_X * cw).toInt(); val hw = (WIN_HALF * cw).toInt()
        wx0 = max(0, bx - hw); wx1 = min(cw - 1, bx + hw)
        wy0 = (WIN_Y0 * ch).toInt(); wy1 = (WIN_Y1 * ch).toInt()
        val ww = wx1 - wx0 + 1; val wh = wy1 - wy0 + 1
        if (mask.size != ww * wh) { mask = BooleanArray(ww * wh); lab = IntArray(ww * wh) }

        // Hintergrund-Helligkeit = Median einer Stichprobe
        val hist = IntArray(256); var cnt = 0
        for (y in wy0..wy1 step 6) for (x in wx0..wx1 step 6) { hist[lum(x, y)]++; cnt++ }
        var acc = 0; var med = 0
        while (med < 255 && acc + hist[med] < cnt / 2) { acc += hist[med]; med++ }
        val thr = max(LUM_MIN, med + LUM_DELTA)

        for (y in 0 until wh) for (x in 0 until ww) {
            mask[y * ww + x] = lum(x + wx0, y + wy0) > thr
            lab[y * ww + x] = 0
        }
        val blobs = ArrayList<Blob>()
        val minPix = (MIN_PIX * cw * cw).toInt()
        val stack = IntArray(ww * wh)
        var next = 1
        for (s in 0 until ww * wh) {
            if (!mask[s] || lab[s] != 0) continue
            var sp = 0; stack[sp++] = s; lab[s] = next
            val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
            var edge = false
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % ww; val y = p / ww
                xs.add(x + wx0); ys.add(y + wy0)
                if (x == 0 || x == ww - 1) edge = true
                for (d in 0..3) {
                    val nx = x + (if (d == 0) 1 else if (d == 1) -1 else 0)
                    val ny = y + (if (d == 2) 1 else if (d == 3) -1 else 0)
                    if (nx < 0 || ny < 0 || nx >= ww || ny >= wh) continue
                    val q = ny * ww + nx
                    if (mask[q] && lab[q] == 0) { lab[q] = next; stack[sp++] = q }
                }
            }
            next++
            if (xs.size < minPix) continue
            blobs.add(Blob(xs.toIntArray(), ys.toIntArray(), xs.size, edge,
                xs.average().toFloat(), ys.average().toFloat(), ys.min()))
        }
        return blobs
    }

    /** Hauptachse (Bildschirm-Koordinaten, y nach unten) und Länge entlang der Achse. */
    private fun axis(b: Blob, sel: (Int) -> Boolean = { true }): FloatArray {
        var n = 0; var mx = 0.0; var my = 0.0
        for (i in 0 until b.n) if (sel(i)) { mx += b.xs[i]; my += b.ys[i]; n++ }
        if (n < 3) return floatArrayOf(0f, 0f)
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until b.n) if (sel(i)) {
            val dx = b.xs[i] - mx; val dy = b.ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val th = 0.5 * atan2(2 * sxy, sxx - syy)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (i in 0 until b.n) if (sel(i)) {
            val t = (b.xs[i] - mx) * cos(th) + (b.ys[i] - my) * sin(th)
            lo = min(lo, t); hi = max(hi, t)
        }
        return floatArrayOf(th.toFloat(), (hi - lo).toFloat())
    }

    private fun wrap(a: Float): Float { var r = a % 360f; if (r > 180f) r -= 360f; if (r <= -180f) r += 360f; return r }
    private fun deg(r: Float) = Math.toDegrees(r.toDouble()).toFloat()

    /** Erster heller Pixel unterhalb von (x, y), der nicht zum Motorrad gehört (= Strecke). */
    private fun groundBelow(x: Int, y: Int, bikeLab: Int): Int {
        if (x < wx0 || x > wx1) return -1
        val ww = wx1 - wx0 + 1
        for (yy in max(y, wy0)..wy1) {
            val q = (yy - wy0) * ww + (x - wx0)
            if (mask[q] && lab[q] != bikeLab) return yy
        }
        return -1
    }

    private fun decide(now: Long) {
        val dt = if (lastT == 0L) 0.033f else ((now - lastT) / 1000f).coerceIn(0.005f, 0.3f)
        lastT = now
        if (lastX < 0) { lastX = BIKE_X * cw; lastY = ch * 0.5f }
        val blobs = findBlobs()
        val maxJump = MAX_JUMP * cw

        // Kandidat für ein Motorrad in der Luft: freistehender Fleck, lang genug, nahe der letzten Position
        var bike: Blob? = null; var bikeAx = floatArrayOf(0f, 0f)
        for (b in blobs) {
            if (b.edge) continue
            val d = sqrt((b.cx - lastX) * (b.cx - lastX) + (b.cy - lastY) * (b.cy - lastY))
            if (d > maxJump) continue
            val ax = axis(b)
            if (ax[1] < MIN_LEN * cw) continue
            if (bike == null || b.n > bike.n) { bike = b; bikeAx = ax }
        }

        if (bike == null) {
            // Am Boden: Motorrad und Strecke hängen zusammen
            val bx = BIKE_X * cw; val band = 0.15f * cw
            val track = blobs.filter { it.edge }.minByOrNull { b ->
                var best = Float.MAX_VALUE
                for (i in 0 until b.n) if (abs(b.xs[i] - bx) < 0.03f * cw) best = min(best, abs(b.ys[i] - lastY))
                best
            }
            if (track == null) {
                setPress(false); air = false
                show("nichts erkannt · $fps fps")
                return
            }
            val ax = axis(track) { abs(track.xs[it] - bx) < band }
            groundAng = -deg(ax[0])
            var top = Int.MAX_VALUE
            for (i in 0 until track.n) if (abs(track.xs[i] - bx) < 0.03f * cw) top = min(top, track.ys[i])
            if (top != Int.MAX_VALUE) { lastX = bx; lastY = top.toFloat() }
            air = false; airFrames = 0; omega = 0f; phi = groundAng
            setPress(GROUND_HOLD)
            show("Boden ${groundAng.roundToInt()}° · ${if (pressed) "Gas" else "los"} · $fps fps")
            return
        }

        // In der Luft: Winkel aus Hauptachse; welche Seite "oben" ist, folgt aus dem vorigen Bild
        val th = bikeAx[0]
        val a0 = -deg(th)                  // Kandidat 1
        val a1 = wrap(a0 + 180f)           // Kandidat 2 (auf dem Kopf)
        val pred = if (air) wrap(phi + omega * dt) else groundAng
        val newPhi = if (abs(wrap(a0 - pred)) <= abs(wrap(a1 - pred))) a0 else a1
        if (air) omega = 0.6f * omega + 0.4f * (wrap(newPhi - phi) / dt)
        phi = newPhi
        air = true; airFrames++
        lastX = bike.cx; lastY = bike.cy

        // Höhe über der Strecke und deren Neigung unter dem Motorrad
        val bikeLab = run {
            val ww = wx1 - wx0 + 1
            lab[(bike.ys[0] - wy0) * ww + (bike.xs[0] - wx0)]
        }
        var lowest = 0
        for (i in 0 until bike.n) lowest = max(lowest, bike.ys[i])
        val cx = bike.cx.toInt(); val dx = (0.06f * cw).toInt()
        val g = groundBelow(cx, lowest + 1, bikeLab)
        val gl = groundBelow(cx - dx, bike.cy.toInt(), bikeLab)
        val gr = groundBelow(cx + dx, bike.cy.toInt(), bikeLab)
        val height = if (g < 0) 1f else (g - lowest).toFloat() / ch
        val target = if (gl >= 0 && gr >= 0) deg(atan2(-(gr - gl).toFloat(), (2 * dx).toFloat())).coerceIn(-60f, 60f) else 0f

        val err = wrap(phi + omega * LEAD - target)
        val hold = when {
            airFrames < 2 -> GROUND_HOLD                // gerade abgehoben
            height > FLIP_H -> true                      // genug Zeit: Salto
            abs(err) <= TOL -> false                     // passt: loslassen und landen
            err < 0f || err > 150f -> true               // noch nicht ganz rum: weiterdrehen
            else -> false                                // zu weit gedreht: loslassen, kippt zurück
        }
        setPress(hold)
        show("Luft ${phi.roundToInt()}° → ${target.roundToInt()}° · h=${(height * 100).roundToInt()}% · " +
            "${if (hold) "drehen" else "los"} · $fps fps")
    }

    private var lastShow = 0L
    private fun show(t: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastShow < 150) return
        lastShow = now
        main.post { status.text = t }
    }

    override fun onDestroy() {
        active = false
        setPress(false)
        try { wm.removeView(bar); wm.removeView(status) } catch (_: Exception) {}
        mp?.stop()
        if (::thread.isInitialized) thread.quitSafely()
        super.onDestroy()
    }
}
