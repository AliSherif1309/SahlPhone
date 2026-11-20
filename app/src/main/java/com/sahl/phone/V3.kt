package com.sahl.phone

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/** Call Guard lives in Sahl Tools; Sahl Phone talks to it through Sahl Union. */
object Guard {
    const val ALLOW = 0; const val SILENCE = 1; const val BLOCK = 2
    data class Verdict(val action: Int, val reason: String, val label: String)
    data class Stopped(val number: String, val action: Int, val label: String, val time: Long)

    private val base = Uri.parse("content://com.sahl.tools.union/guard")

    fun installed(c: Context) = try { c.packageManager.getPackageInfo("com.sahl.tools", 0); true } catch (e: Exception) { false }

    fun active(c: Context): Boolean = try {
        c.contentResolver.query(Uri.withAppendedPath(base, "status"), null, null, null, null)?.use { it.moveToFirst() && it.getInt(0) == 1 } ?: false
    } catch (e: Exception) { false }

    fun check(c: Context, number: String): Verdict? = try {
        c.contentResolver.query(Uri.withAppendedPath(base, "check/" + Uri.encode(number)), null, null, null, null)?.use {
            if (it.moveToFirst()) Verdict(it.getInt(0), it.getString(1) ?: "", it.getString(2) ?: "") else null
        }
    } catch (e: Exception) { null }

    fun log(c: Context, limit: Int = 100): List<Stopped> {
        val out = ArrayList<Stopped>()
        try {
            c.contentResolver.query(Uri.parse("$base/log?limit=$limit"), null, null, null, null)?.use {
                while (it.moveToNext()) out.add(Stopped(it.getString(0) ?: "", it.getInt(1), it.getString(2) ?: "", it.getLong(3)))
            }
        } catch (e: Exception) { }
        return out
    }

    /** One shared block list: Call Guard's if Sahl Tools is there, otherwise Android's. */
    fun block(c: Context, number: String): Boolean {
        try {
            if (c.contentResolver.insert(Uri.withAppendedPath(base, "block"), ContentValues().apply { put("number", number) }) != null) {
                Data.block(c, number); return true
            }
        } catch (e: Exception) { }
        return Data.block(c, number)
    }

    fun allow(c: Context, number: String) = try {
        c.contentResolver.insert(Uri.withAppendedPath(base, "allow"), ContentValues().apply { put("number", number) }) != null
    } catch (e: Exception) { false }

    fun openSettings(a: Activity) {
        try { a.startActivity(Intent("com.sahl.tools.action.CALL_GUARD").setPackage("com.sahl.tools")) } catch (e: Exception) { }
    }
}

/** Warning shown on the ringing screen for numbers that aren't in your contacts. */
object Warning {
    /** Returns (text, isDanger) or null when the caller is known. */
    fun of(c: Context, number: String, knownName: String?, facts: CallerFacts): Pair<String, Boolean>? {
        if (knownName != null) return null
        val w = Sahl.wrap(c)
        if (number.isBlank()) return w.getString(R.string.warn_hidden) to true
        val parts = ArrayList<String>()
        var danger = false
        val v = Guard.check(c, number)
        if (v != null && v.action != Guard.ALLOW) { parts.add(v.label); danger = true }
        else if (v != null && v.reason == "cg_r_repeat") parts.add(v.label)
        Countries.of(number, Sahl.countryCode(c), w.resources.configuration.locales[0].language == "ar")?.let {
            parts.add(w.getString(R.string.warn_international, it)); danger = true
        }
        parts.add(if (facts.count == 0) w.getString(R.string.warn_new) else w.getString(R.string.warn_seen, facts.count))
        return parts.joinToString("  ·  ") to danger
    }
}

/** Notes on individual calls (stored in Sahl Phone). Key = number key + call time. */
object CallNotes {
    private fun key(number: String, date: Long) = Norm.phoneKey(number) + "@" + date / 1000
    private fun all(c: Context): JSONObject = try { JSONObject(Sahl.prefs(c).getString("call_notes", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun get(c: Context, number: String, date: Long): String? = all(c).optString(key(number, date)).takeIf { it.isNotBlank() }

    fun set(c: Context, number: String, date: Long, text: String) {
        val j = all(c)
        if (text.isBlank()) j.remove(key(number, date)) else j.put(key(number, date), text.trim())
        Sahl.prefs(c).edit().putString("call_notes", j.toString()).apply()
    }

    /** After an answered call ends: offer to add a note. */
    fun offer(ctx: Context, number: String) {
        if (number.isBlank() || !Sahl.prefs(ctx).getBoolean("note_prompt", true)) return
        val c = Sahl.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("notes", c.getString(R.string.call_notes), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(ctx, 900, Intent(ctx, CallDetailActivity::class.java)
            .putExtra("number", number).putExtra("addNote", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val who = Lookup.caller(ctx, number).name ?: number
        nm.notify(901, Notification.Builder(ctx, "notes").setSmallIcon(R.drawable.ic_note)
            .setContentTitle(c.getString(R.string.note_prompt_title, who))
            .setContentText(c.getString(R.string.note_prompt_text))
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(15 * 60_000L).build())
    }
}

/** Home-screen widget: today's missed calls, tap one to call back. */
class MissedWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(ctx) }

    companion object {
        private val rows = intArrayOf(R.id.m0, R.id.m1, R.id.m2)
        private val names = intArrayOf(R.id.mn0, R.id.mn1, R.id.mn2)
        private val times = intArrayOf(R.id.mt0, R.id.mt1, R.id.mt2)

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MissedWidget::class.java))
            if (ids.isEmpty()) return
            Thread {
                val c = Sahl.wrap(ctx)
                val since = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val missed = try { Data.recents(ctx, 200).filter { it.type == CallLog.Calls.MISSED_TYPE && it.date >= since } } catch (e: Exception) { emptyList() }
                val rv = RemoteViews(ctx.packageName, R.layout.widget_missed)
                val total = missed.sumOf { it.count }
                rv.setTextViewText(R.id.mtitle, if (total == 0) c.getString(R.string.no_missed) else c.getString(R.string.missed_today, total))
                rv.setOnClickPendingIntent(R.id.mtitle, PendingIntent.getActivity(ctx, 700,
                    Intent(ctx, MainActivity::class.java).putExtra("tab", 1), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                for (i in 0 until 3) {
                    val r = missed.getOrNull(i)
                    if (r == null) { rv.setViewVisibility(rows[i], View.GONE); continue }
                    rv.setViewVisibility(rows[i], View.VISIBLE)
                    rv.setTextViewText(names[i], (Lookup.caller(ctx, r.number).name ?: r.number.ifBlank { c.getString(R.string.private_number) }) +
                        if (r.count > 1) " (${r.count})" else "")
                    rv.setTextViewText(times[i], java.time.Instant.ofEpochMilli(r.date).atZone(java.time.ZoneId.systemDefault()).format(fmt))
                    if (r.number.isNotBlank()) rv.setOnClickPendingIntent(rows[i], PendingIntent.getActivity(ctx, 710 + i,
                        Intent(ctx, CallActivity::class.java).putExtra("number", r.number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                mgr.updateAppWidget(ids, rv)
            }.start()
        }
    }
}
