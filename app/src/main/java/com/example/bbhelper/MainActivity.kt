package com.example.bbhelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var overlayRow: TextView
    private lateinit var notifyRow: TextView
    private lateinit var startBtn: Button
    private lateinit var accRow: TextView
    private lateinit var accHelp: LinearLayout
    private lateinit var modeHint: TextView
    private lateinit var modeAuto: TextView
    private var auto = false

    private val dp get() = resources.displayMetrics.density
    private fun Int.px() = (this * dp).toInt()

    private fun round(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.px().toFloat()
        if (stroke != 0) setStroke(1.px(), stroke)
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = 0xFF1E1B4B.toInt()
        window.navigationBarColor = 0xFF312E81.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.px(), 40.px(), 24.px(), 24.px())
        }

        // Kopf: Logo aus Blöcken + Titel
        val logo = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (c in intArrayOf(0xFF2F7BF5.toInt(), 0xFF0FA77A.toInt(), 0xFFE8890C.toInt())) {
            logo.addView(View(this).apply { background = round(c, 8) },
                LinearLayout.LayoutParams(34.px(), 34.px()).apply { setMargins(4.px(), 0, 4.px(), 0) })
        }
        root.addView(logo)
        root.addView(text("Block-Helfer", 32f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, 16.px(), 0, 4.px())
        })
        root.addView(text("Zeigt dir den besten nächsten Zug –\nauf Combos und leeres Brett ausgelegt.", 15f, 0xFFC7D2FE.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 24.px())
        })

        // Karte: Modus
        auto = getSharedPreferences("auto", MODE_PRIVATE).getBoolean("mode_auto", false)
        val mode = card()
        mode.addView(text("Modus", 17f, Color.WHITE, true))
        val modes = LinearLayout(this).apply { setPadding(0, 12.px(), 0, 0) }
        modeHint = modeTile("💡", "Nur Hinweise", "Zeigt den Zug, du ziehst selbst") { setMode(false) }
        modeAuto = modeTile("🤖", "Automatisch", "Der Helfer zieht die Teile selbst") { setMode(true) }
        modes.addView(modeHint, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 6.px() })
        modes.addView(modeAuto, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 6.px() })
        mode.addView(modes)
        root.addView(mode)

        // Karte: Berechtigungen
        val perm = card()
        perm.addView(text("Berechtigungen", 17f, Color.WHITE, true))
        overlayRow = text("", 15f, Color.WHITE).apply {
            setPadding(0, 12.px(), 0, 0)
            setOnClickListener { requestOverlay() }
        }
        notifyRow = text("", 15f, Color.WHITE).apply {
            setPadding(0, 10.px(), 0, 0)
            setOnClickListener { requestNotify() }
        }
        perm.addView(overlayRow); perm.addView(notifyRow)
        accRow = text("", 15f, Color.WHITE).apply {
            setPadding(0, 10.px(), 0, 0)
            setOnClickListener { openAccessibility() }
        }
        perm.addView(accRow)
        accHelp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.px(), 10.px(), 12.px(), 12.px())
            background = round(0x22000000, 12)
        }
        accHelp.addView(text("So schaltest du die Automatik frei:", 13f, Color.WHITE, true))
        accHelp.addView(text(
            "1. \"App-Infos öffnen\" antippen, oben rechts auf ⋮ und \"Eingeschränkte Einstellungen zulassen\" wählen " +
            "(nur nötig, wenn Android die Bedienungshilfe sperrt).\n" +
            "2. \"Bedienungshilfen öffnen\" antippen, \"Block-Helfer Automatik\" auswählen und einschalten.",
            13f, 0xFFE0E7FF.toInt()).apply { setPadding(0, 6.px(), 0, 8.px()) })
        val accBtns = LinearLayout(this)
        accBtns.addView(smallBtn("App-Infos öffnen") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(0, 40.px(), 1f).apply { rightMargin = 4.px() })
        accBtns.addView(smallBtn("Bedienungshilfen öffnen") { openAccessibility() },
            LinearLayout.LayoutParams(0, 40.px(), 1f).apply { leftMargin = 4.px() })
        accHelp.addView(accBtns)
        perm.addView(accHelp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.px() })
        perm.addView(text("Beim Start fragt Android zusätzlich nach der Bildschirmaufnahme – bitte \"Gesamter Bildschirm\" wählen.", 13f, 0xFFA5B4FC.toInt()).apply {
            setPadding(0, 12.px(), 0, 0)
        })
        root.addView(perm)

        // Karte: Anleitung
        val help = card()
        help.addView(text("So funktioniert's", 17f, Color.WHITE, true))
        listOf(
            "1" to "Helfer starten und das Spiel öffnen.",
            "2" to "Der farbige Rahmen zeigt, wo das Teil hin soll. Der Balken unter der Ablage zeigt, welches Teil.",
            "3" to "Nach jedem Zug wird das Brett neu gescannt und der nächste Hinweis erscheint.",
            "↻" to "Antippen: neu berechnen und Scan in der Galerie (BlockHelfer) speichern.",
            "🤖" to "Automatik: Der Helfer zieht selbst. Die ersten Züge können danebengehen, bis er den Versatz gelernt hat.",
            "⏸" to "In der Automatik pausiert \"⏸ Auto\" das Spielen, nochmal antippen spielt weiter.",
            "✕" to "Den Knopf lange drücken beendet den Helfer."
        ).forEach { (n, t) -> help.addView(step(n, t)) }
        root.addView(help)

        startBtn = Button(this).apply {
            text = "▶  Helfer starten"; textSize = 18f; isAllCaps = false
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF6366F1.toInt(), 0xFFEC4899.toInt())).apply { cornerRadius = 28.px().toFloat() }
            stateListAnimator = null
            setOnClickListener { start() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, 58.px()).apply { topMargin = 8.px() })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF1E1B4B.toInt(), 0xFF312E81.toInt(), 0xFF4C1D95.toInt()))
            addView(root)
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(0x22FFFFFF, 20, 0x33FFFFFF)
        setPadding(20.px(), 18.px(), 20.px(), 18.px())
    }.also { c ->
        c.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16.px() }
    }

    private fun step(n: String, t: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 12.px(), 0, 0)
        addView(text(n, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = round(0x556366F1, 14)
        }, LinearLayout.LayoutParams(28.px(), 28.px()))
        addView(text(t, 14f, 0xFFE0E7FF.toInt()).apply { setPadding(12.px(), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun modeTile(icon: String, title: String, sub: String, click: () -> Unit) = TextView(this).apply {
        text = "$icon\n$title\n$sub"; textSize = 13f; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER; setPadding(8.px(), 14.px(), 8.px(), 14.px())
        setOnClickListener { click() }
    }

    private fun smallBtn(t: String, click: () -> Unit) = Button(this).apply {
        text = t; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
        background = round(0x556366F1, 10); stateListAnimator = null
        setPadding(4.px(), 0, 4.px(), 0)
        setOnClickListener { click() }
    }

    private fun setMode(a: Boolean) {
        auto = a
        getSharedPreferences("auto", MODE_PRIVATE).edit().putBoolean("mode_auto", a).apply()
        refresh()
    }

    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        overlayRow.text = if (ov) "✅  Über anderen Apps anzeigen" else "⚠️  Über anderen Apps anzeigen – tippen zum Erlauben"
        val nt = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        for ((v, on) in listOf(modeHint to !auto, modeAuto to auto)) {
            v.background = if (on) round(0x666366F1, 14, 0xFFA5B4FC.toInt()) else round(0x14FFFFFF, 14, 0x22FFFFFF)
            v.alpha = if (on) 1f else 0.6f
        }
        val acc = AutoService.isEnabled(this)
        accRow.visibility = if (auto) View.VISIBLE else View.GONE
        accRow.text = if (acc) "✅  Bedienungshilfe für Automatik" else "⚠️  Bedienungshilfe für Automatik – noch nicht eingeschaltet"
        accHelp.visibility = if (auto && !acc) View.VISIBLE else View.GONE
        startBtn.text = if (auto) "▶  Automatisch spielen" else "▶  Helfer starten"
        notifyRow.text = if (nt) "✅  Benachrichtigungen" else "⚠️  Benachrichtigungen – tippen zum Erlauben (optional)"
    }

    private fun requestOverlay() {
        if (!Settings.canDrawOverlays(this))
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestNotify() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    override fun onRequestPermissionsResult(rq: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rq, p, r)
        refresh()
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        if (auto && !AutoService.isEnabled(this)) {
            android.widget.Toast.makeText(this, "Bitte zuerst die Bedienungshilfe \"Block-Helfer Automatik\" einschalten", android.widget.Toast.LENGTH_LONG).show()
            refresh()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rc == RESULT_OK && d != null) {
            startForegroundService(Intent(this, HelperService::class.java).putExtra("code", rc).putExtra("data", d).putExtra("auto", auto))
            moveTaskToBack(true)
        }
    }
}
