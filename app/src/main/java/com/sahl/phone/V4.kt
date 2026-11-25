package com.sahl.phone

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.CallLog
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject

/** Turn the phone face-down to silence a ringing call. */
object FlipSilence {
    private var sm: SensorManager? = null
    private var listener: SensorEventListener? = null

    fun enabled(c: Context) = Sahl.prefs(c).getBoolean("flip_silence", true)

    @android.annotation.SuppressLint("MissingPermission")
    fun start(c: Context) {
        if (!enabled(c) || listener != null) return
        val m = c.getSystemService(SensorManager::class.java) ?: return
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        var downSince = 0L
        var wasUp = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val z = e.values[2]
                if (z > 3f) wasUp = true                       // only after it was facing up once
                if (wasUp && z < -8.5f) {
                    if (downSince == 0L) downSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - downSince > 500) {
                        try { c.getSystemService(TelecomManager::class.java)?.silenceRinger() } catch (x: SecurityException) { }
                        stop()
                    }
                } else downSince = 0L
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        m.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        sm = m; listener = l
    }

    fun stop() {
        listener?.let { sm?.unregisterListener(it) }
        listener = null; sm = null
    }
}

/** Missed calls during prayer → one gentle "call back" notification right after the prayer window. */
object PrayerMissed {
    /** Current prayer (name, window end ms) from Sahl Adhan, or null. */
    fun current(c: Context, now: Long = System.currentTimeMillis()): Pair<String, Long>? {
        val win = PrayerSilence.minutes(c) * 60_000L
        try {
            c.contentResolver.query(Uri.parse(Sahl.ADHAN_UNION + "/prayers"), null, null, null, null)?.use {
                val n = it.getColumnIndex("name"); val t = it.getColumnIndex("time")
                while (it.moveToNext()) {
                    val start = it.getLong(t)
                    if (now >= start && now < start + win) return (it.getString(n) ?: "") to (start + win)
                }
            }
        } catch (e: Exception) { }
        return null
    }

    fun enabled(c: Context) = Sahl.prefs(c).getBoolean("prayer_callback", true)

    /** Called when an incoming call was missed or declined. */
    fun onMissed(c: Context, number: String) {
        if (!enabled(c) || number.isBlank()) return
        val (name, end) = current(c) ?: return
        val p = Sahl.prefs(c)
        val list = try { JSONArray(p.getString("prayer_missed", "[]")) } catch (e: Exception) { JSONArray() }
        list.put(JSONObject().put("n", number).put("p", name))
        p.edit().putString("prayer_missed", list.toString()).apply()
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 801, Intent(c, PrayerMissedReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end + 30_000L, pi)
    }
}

class PrayerMissedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Sahl.prefs(ctx)
        val list = try { JSONArray(p.getString("prayer_missed", "[]")) } catch (e: Exception) { JSONArray() }
        p.edit().remove("prayer_missed").apply()
        if (list.length() == 0) return
        val numbers = LinkedHashMap<String, String>()   // phone key -> number
        var prayer = ""
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i); val n = o.optString("n")
            numbers.putIfAbsent(Norm.phoneKey(n), n); prayer = o.optString("p")
        }
        val c = Sahl.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(Sahl.chimeChannel(ctx, "reminders_chime", c.getString(R.string.reminders)))
        val names = numbers.values.map { Lookup.caller(ctx, it).name ?: it }
        val b = SahlNotif.apply(ctx, Notification.Builder(ctx, "reminders_chime"))
            .setContentTitle(c.getString(R.string.prayer_missed_title, list.length(), prayer))
            .setContentText(names.joinToString(", "))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 802, Intent(ctx, MainActivity::class.java).putExtra("tab", 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        numbers.values.take(2).forEachIndexed { i, n ->
            val call = PendingIntent.getActivity(ctx, 810 + i, Intent(ctx, CallActivity::class.java).putExtra("number", n)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.call_back_x, names[i].substringBefore(' ')), call).build())
        }
        nm.notify(803, b.build())
    }
}

/** This week's calls in one simple card. */
data class Week(val calls: Int, val talk: Long, val top: List<Pair<String, Int>>, val missedNotReturned: List<String>)

object WeeklySummary {
    private var cached: Week? = null
    private var cachedAt = 0L

    fun peek(): Week? {
        val now = System.currentTimeMillis()
        return cached?.takeIf { now - cachedAt < 5 * 60_000L }
    }

    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    fun compute(c: Context): Week {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < 5 * 60_000L) return it }
        return computeFresh(c).also { cached = it; cachedAt = now }
    }

    private fun computeFresh(c: Context): Week {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        var calls = 0; var talk = 0L
        val count = LinkedHashMap<String, Int>(); val numberOf = HashMap<String, String>()
        val lastOut = HashMap<String, Long>(); val missed = LinkedHashMap<String, Long>()
        try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE}>?", arrayOf(since.toString()), CallLog.Calls.DATE + " DESC")?.use {
                while (it.moveToNext()) {
                    val n = it.getString(0) ?: continue
                    val k = Norm.phoneKey(n); if (k.isEmpty()) continue
                    val type = it.getInt(1); val date = it.getLong(2)
                    calls++; talk += it.getLong(3)
                    numberOf.putIfAbsent(k, n)
                    if (it.getLong(3) > 0) count[k] = (count[k] ?: 0) + 1
                    if (type == CallLog.Calls.OUTGOING_TYPE || (type == CallLog.Calls.INCOMING_TYPE && it.getLong(3) > 0))
                        lastOut[k] = maxOf(lastOut[k] ?: 0L, date)
                    if (type == CallLog.Calls.MISSED_TYPE) missed.putIfAbsent(k, date)
                }
            }
        } catch (e: Exception) { }
        val top = count.entries.sortedByDescending { it.value }.take(3).map { (Lookup.caller(c, numberOf[it.key] ?: "").name ?: numberOf[it.key] ?: "") to it.value }
        val notReturned = missed.filter { (k, t) -> (lastOut[k] ?: 0L) < t }.keys.mapNotNull { numberOf[it] }
        return Week(calls, talk, top, notReturned)
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 850, Intent(c, WeeklyReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Every Saturday around 10:00
        var t = java.time.ZonedDateTime.now().withHour(10).withMinute(0).withSecond(0)
        while (t.dayOfWeek != java.time.DayOfWeek.SATURDAY || t.isBefore(java.time.ZonedDateTime.now())) t = t.plusDays(1)
        am.setInexactRepeating(AlarmManager.RTC, t.toInstant().toEpochMilli(), 7 * AlarmManager.INTERVAL_DAY, pi)
    }
}

class WeeklyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Sahl.prefs(ctx).getBoolean("weekly_summary", true)) return
        val pending = goAsync()
        Thread {
            try {
                val w = WeeklySummary.compute(ctx)
                if (w.calls == 0) return@Thread
                val c = Sahl.wrap(ctx)
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return@Thread
                nm.createNotificationChannel(NotificationChannel("weekly", c.getString(R.string.weekly_title), NotificationManager.IMPORTANCE_LOW))
                val text = c.getString(R.string.weekly_line, w.calls, (w.talk / 60).toInt()) +
                    (if (w.missedNotReturned.isNotEmpty()) " · " + c.getString(R.string.weekly_not_returned, w.missedNotReturned.size) else "")
                nm.notify(851, SahlNotif.apply(ctx, Notification.Builder(ctx, "weekly"))
                    .setContentTitle(c.getString(R.string.weekly_title)).setContentText(text).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(ctx, 852, Intent(ctx, MainActivity::class.java).putExtra("tab", 1)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .build())
            } finally { pending.finish() }
        }.start()
    }
}
