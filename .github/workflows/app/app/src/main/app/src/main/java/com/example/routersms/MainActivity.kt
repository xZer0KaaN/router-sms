package com.example.routersms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private data class Msg(val id: String, val time: Long, val phone: String, val text: String)

    private val bgColor = Color.parseColor("#0B141A")
    private val headerColor = Color.parseColor("#202C33")
    private val textColor = Color.parseColor("#E9EDEF")
    private val subColor = Color.parseColor("#8696A0")
    private val accent = Color.parseColor("#00A884")

    // Farbe der Sprechblasen: helles Grün. Für Gelb z. B. "#FFF3B0" eintragen.
    private val bubbleColor = Color.parseColor("#DCF8C6")
    private val bubbleText = Color.parseColor("#111B21")

    private val avatarColors = listOf("#00A884", "#5E7CE2", "#C2185B", "#EF6C00", "#7B1FA2", "#00897B")
        .map { Color.parseColor(it) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var screen = 0 // 0 = Liste, 1 = Chat, 2 = Einstellungen
    private var currentPhone = ""
    private var lastInbox = ""

    private val tick = object : Runnable {
        override fun run() {
            val now = prefs().getString("inbox", "[]") ?: "[]"
            if (now != lastInbox && screen != 2) render()
            handler.postDelayed(this, 3000)
        }
    }

    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        window.statusBarColor = headerColor
        window.navigationBarColor = bgColor
        window.decorView.systemUiVisibility = 0

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(tick, 3000)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen != 0) goBack() else super.onBackPressed()
    }

    private fun goBack() {
        screen = 0
        render()
    }

    private fun loadMsgs(): List<Msg> {
        val out = ArrayList<Msg>()
        try {
            val arr = JSONArray(prefs().getString("inbox", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Msg(
                        o.getString("id"),
                        o.optString("time", "0").toLongOrNull() ?: 0L,
                        o.getString("phone"),
                        o.getString("text")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out.sortedBy { it.time }
    }

    private fun render() {
        root.removeAllViews()
        lastInbox = prefs().getString("inbox", "[]") ?: "[]"
        when (screen) {
            1 -> renderChat()
            2 -> renderSettings()
            else -> renderList()
        }
    }

    private fun header(title: String, back: Boolean, gear: Boolean): View {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerColor)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        if (back) {
            h.addView(TextView(this).apply {
                text = "←"
                textSize = 24f
                setTextColor(textColor)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { goBack() }
            })
        }
        h.addView(
            TextView(this).apply {
                text = title
                textSize = 20f
                setTextColor(textColor)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        if (gear) {
            h.addView(TextView(this).apply {
                text = "⚙"
                textSize = 24f
                setTextColor(textColor)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener {
                    screen = 2
                    render()
                }
            })
        }
        return h
    }

    private fun dayKey(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(t * 1000))

    private fun listTime(t: Long): String {
        val today = dayKey(System.currentTimeMillis() / 1000)
        val f = if (dayKey(t) == today) "HH:mm" else "dd.MM.yy"
        return SimpleDateFormat(f, Locale.getDefault()).format(Date(t * 1000))
    }

    private fun renderList() {
        root.addView(header("Router SMS", false, true))

        val running = prefs().getBoolean("running", false)
        root.addView(TextView(this).apply {
            text = if (running) "● Läuft, prüft alle 30 Sekunden" else "● Gestoppt, im ⚙ starten"
            textSize = 12f
            setTextColor(if (running) accent else subColor)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        })

        val groups = loadMsgs().groupBy { it.phone }.values.sortedByDescending { it.last().time }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (groups.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Noch keine SMS."
                textSize = 16f
                setTextColor(subColor)
                setPadding(dp(16), dp(24), dp(16), dp(24))
            })
        }
        for (g in groups) col.addView(row(g))

        val sv = ScrollView(this)
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun row(g: List<Msg>): View {
        val last = g.last()
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                currentPhone = last.phone
                screen = 1
                render()
            }
        }

        val avatar = TextView(this).apply {
            text = last.phone.trim().take(1).uppercase()
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColors[Math.abs(last.phone.hashCode()) % avatarColors.size])
            }
        }
        r.addView(avatar, LinearLayout.LayoutParams(dp(48), dp(48)))

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        mid.addView(TextView(this).apply {
            text = last.phone
            textSize = 17f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        mid.addView(TextView(this).apply {
            text = last.text.replace("\n", " ")
            textSize = 14f
            setTextColor(subColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))

        r.addView(TextView(this).apply {
            text = listTime(last.time)
            textSize = 12f
            setTextColor(subColor)
        })
        return r
    }

    private fun renderChat() {
        root.addView(header(currentPhone, true, false))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }

        var lastDay = ""
        for (m in loadMsgs().filter { it.phone == currentPhone }) {
            val d = dayKey(m.time)
            if (d != lastDay) {
                lastDay = d
                val chip = TextView(this).apply {
                    text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(m.time * 1000))
                    textSize = 12f
                    setTextColor(subColor)
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#182229"))
                        cornerRadius = dp(10).toFloat()
                    }
                }
                val clp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, dp(10), 0, dp(6))
                }
                col.addView(chip, clp)
            }

            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(bubbleColor)
                    cornerRadius = dp(14).toFloat()
                }
            }
            bubble.addView(TextView(this).apply {
                text = m.text
                textSize = 16f
                setTextColor(bubbleText)
                setTextIsSelectable(true)
            })
            bubble.addView(
                TextView(this).apply {
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.time * 1000))
                    textSize = 11f
                    setTextColor(Color.parseColor("#667781"))
                    gravity = Gravity.END
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.START
                setMargins(0, dp(3), dp(48), dp(3))
            }
            col.addView(bubble, lp)
        }

        val sv = ScrollView(this)
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun greenButton(label: String, color: Int): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(24).toFloat()
            }
        }
    }

    private fun renderSettings() {
        root.addView(header("Einstellungen", true, false))

        val p = prefs()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(subColor)
            setPadding(0, dp(12), 0, 0)
        }

        val ip = EditText(this).apply {
            setText(p.getString("ip", "192.168.0.1"))
            setTextColor(textColor)
            setHintTextColor(subColor)
        }
        val pw = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(p.getString("pw", ""))
            setTextColor(textColor)
            setHintTextColor(subColor)
        }
        val status = TextView(this).apply {
            text = if (p.getBoolean("running", false)) "Läuft" else "Gestoppt"
            textSize = 14f
            setTextColor(subColor)
            setPadding(0, dp(12), 0, 0)
        }

        val start = greenButton("Start", accent)
        val stop = greenButton("Stopp", Color.parseColor("#54656F"))

        start.setOnClickListener {
            p.edit()
                .putString("ip", ip.text.toString().trim())
                .putString("pw", pw.text.toString())
                .putBoolean("running", true)
                .apply()
            startForegroundService(Intent(this, PollService::class.java))
            screen = 0
            render()
        }
        stop.setOnClickListener {
            p.edit().putBoolean("running", false).apply()
            stopService(Intent(this, PollService::class.java))
            status.text = "Gestoppt"
        }

        col.addView(label("Router-Adresse"))
        col.addView(ip)
        col.addView(label("Router-Passwort"))
        col.addView(pw)
        col.addView(start, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })
        col.addView(stop, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        col.addView(status)
        col.addView(TextView(this).apply {
            text = "Das Handy muss im WLAN des Routers sein. Die Router-Adresse ist meist 192.168.0.1."
            textSize = 13f
            setTextColor(subColor)
            setPadding(0, dp(20), 0, 0)
        })

        val sv = ScrollView(this)
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }
}
