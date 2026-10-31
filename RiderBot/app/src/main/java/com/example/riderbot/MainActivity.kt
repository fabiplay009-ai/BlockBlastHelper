package com.example.riderbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var info: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        info = TextView(this).apply { setPadding(40, 40, 40, 20); textSize = 16f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(Button(this@MainActivity).apply {
                text = "Bot starten"
                setOnClickListener { start() }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        fun ok(b: Boolean) = if (b) "✅" else "❌"
        info.text = "1. Über anderen Apps einblenden: ${ok(Settings.canDrawOverlays(this))}\n" +
            "2. Bedienungshilfe \"Rider-Bot (Tippen)\" an: ${ok(TouchService.instance != null)}\n" +
            "3. Bildschirmaufnahme erlauben (beim Start)\n\n" +
            "Danach Rider öffnen und oben links auf ▶ tippen.\n" +
            "▶/⏸ = Bot an/aus, 📷 = Bildschirmfoto für die Feinabstimmung,\n" +
            "lange auf ▶ drücken = Bot beenden."
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (TouchService.instance == null) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rc == RESULT_OK && d != null) {
            startForegroundService(Intent(this, BotService::class.java).putExtra("code", rc).putExtra("data", d))
            moveTaskToBack(true)
        }
    }
}
