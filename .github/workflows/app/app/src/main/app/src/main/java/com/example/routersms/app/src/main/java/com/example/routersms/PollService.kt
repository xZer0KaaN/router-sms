package com.example.routersms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

class PollService : Service() {

    data class Sms(val id: String, val time: String, val phone: String, val text: String)

    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CookieHandler.setDefault(CookieManager())
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("service", "Dienst", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("sms", "Neue SMS", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Router SMS")
            .setContentText("Prüft auf neue SMS")
            .setContentIntent(openAppIntent())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        if (running) return START_STICKY
        running = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "routersms:poll").apply { acquire() }

        thread {
            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            while (running) {
                try {
                    val base = "http://" + prefs.getString("ip", "192.168.0.1")
                    val pw = prefs.getString("pw", "") ?: ""
                    val list = fetch(base, pw)
                    saveInbox(list)
                    val seen = prefs.getStringSet("seen", null)
                    if (seen == null) {
                        prefs.edit().putStringSet("seen", list.map { it.id }.toSet()).apply()
                    } else {
                        val newSeen = HashSet(seen)
                        for (s in list) {
                            if (!seen.contains(s.id)) {
                                newSeen.add(s.id)
                                notifySms(s)
                            }
                        }
                        prefs.edit().putStringSet("seen", newSeen).apply()
                    }
                } catch (e: Exception) {
                    // Fehler ignorieren, nächster Versuch in 30 Sekunden
                }
                try {
                    Thread.sleep(30000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun saveInbox(list: List<Sms>) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val old = try {
            JSONArray(prefs.getString("inbox", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val ids = HashSet<String>()
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("time", s.time)
            o.put("phone", s.phone)
            o.put("text", s.text)
            arr.put(o)
            ids.add(s.id)
        }
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (!ids.contains(o.getString("id"))) arr.put(o)
        }
        prefs.edit().putString("inbox", arr.toString()).apply()
    }

    private fun http(url: String, referer: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Referer", referer)
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val s = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return s
    }

    private fun login(base: String, pw: String) {
        val md5 = MessageDigest.getInstance("MD5").digest(pw.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = "{\"userName\":\"admin\",\"password\":\"$md5\"}"
        http("$base/login/Auth", "$base/login.html", body)
    }

    private fun getList(base: String): JSONObject? {
        return try {
            val s = http("$base/goform/getModules?modules=smsList", "$base/index.html", null)
            val j = JSONObject(s)
            if (j.has("smsList")) j else null
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(hex: String): String {
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.UTF_16BE)
    }

    private fun fetch(base: String, pw: String): List<Sms> {
        var j = getList(base)
        if (j == null) {
            login(base, pw)
            j = getList(base) ?: throw Exception("keine Antwort")
        }
        val out = ArrayList<Sms>()
        val arr = j.getJSONArray("smsList")
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val phone = c.getString("phone")
            val msgs = c.getJSONArray("list")
            for (k in 0 until msgs.length()) {
                val m = msgs.getJSONObject(k)
                if (m.optBoolean("isSend", false)) continue
                out.add(
                    Sms(
                        m.getString("id"),
                        m.optString("time", "0"),
                        phone,
                        decode(m.getString("content"))
                    )
                )
            }
        }
        return out
    }

    private fun notifySms(s: Sms) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, "sms")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SMS: " + s.phone)
            .setContentText(s.text)
            .setStyle(Notification.BigTextStyle().bigText(s.text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(("" + s.id + s.time).hashCode(), n)
    }
}
