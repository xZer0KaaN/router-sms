package com.example.routersms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Router SMS"
            textSize = 24f
        }
        val ip = EditText(this).apply {
            hint = "Router-Adresse"
            setText(prefs.getString("ip", "192.168.0.1"))
        }
        val pw = EditText(this).apply {
            hint = "Router-Passwort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pw", ""))
        }
        val status = TextView(this).apply {
            text = if (prefs.getBoolean("running", false)) "Läuft" else "Gestoppt"
        }
        val start = Button(this).apply { text = "Start" }
        val stop = Button(this).apply { text = "Stopp" }

        start.setOnClickListener {
            prefs.edit()
                .putString("ip", ip.text.toString().trim())
                .putString("pw", pw.text.toString())
                .putBoolean("running", true)
                .apply()
            startForegroundService(Intent(this, PollService::class.java))
            status.text = "Läuft"
        }
        stop.setOnClickListener {
            prefs.edit().putBoolean("running", false).apply()
            stopService(Intent(this, PollService::class.java))
            status.text = "Gestoppt"
        }

        root.addView(title)
        root.addView(ip)
        root.addView(pw)
        root.addView(start)
        root.addView(stop)
        root.addView(status)
        setContentView(root)
    }
}
