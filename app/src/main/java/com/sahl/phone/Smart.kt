package com.sahl.phone

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager

/** Decline-with-a-text messages (editable in Settings). */
object QuickReplies {
    fun list(c: Context): List<String> {
        val saved = Sahl.prefs(c).getString("replies", null)
        if (!saved.isNullOrBlank()) return saved.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val w = Sahl.wrap(c)
        return listOf(w.getString(R.string.reply_1), w.getString(R.string.reply_2), w.getString(R.string.reply_3), w.getString(R.string.reply_4))
    }
    fun save(c: Context, items: List<String>) = Sahl.prefs(c).edit().putString("replies", items.joinToString("\n")).apply()
}

/** "Remind me to call back" notifications. */
object Reminders {
    fun schedule(c: Context, number: String, name: String?, minutes: Int) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(c, ReminderReceiver::class.java).putExtra("number", number).putExtra("name", name)
        val pi = PendingIntent.getBroadcast(c, number.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        val name = intent.getStringExtra("name")
        val c = Sahl.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("reminders", c.getString(R.string.reminders), NotificationManager.IMPORTANCE_HIGH))
        val call = PendingIntent.getActivity(ctx, number.hashCode(),
            Intent(ctx, CallActivity::class.java).putExtra("number", number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(20_000 + (number.hashCode() and 0xffff), Notification.Builder(ctx, "reminders")
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(c.getString(R.string.call_back_title, name ?: number))
            .setContentText(c.getString(R.string.call_back_text))
            .setContentIntent(call).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.call), call).build())
            .build())
    }
}

/** Silence ringing during prayer time, using prayer times from Sahl Adhan (Sahl Union). */
object PrayerSilence {
    /** The call that was silenced for prayer (shown on the call screen). */
    var silenced: android.telecom.Call? = null
    fun enabled(c: Context) = Sahl.prefs(c).getBoolean("prayer_silence", false)
    fun minutes(c: Context) = Sahl.prefs(c).getInt("prayer_minutes", 20)
    fun allowFavorites(c: Context) = Sahl.prefs(c).getBoolean("prayer_allow_fav", true)
    fun autoReply(c: Context) = Sahl.prefs(c).getBoolean("prayer_auto_reply", false)

    /** True if Sahl Adhan is installed and answering. */
    fun available(c: Context) = prayerTimes(c).isNotEmpty()

    /** Prayer times (epoch ms) for today and tomorrow from Sahl Adhan. */
    fun prayerTimes(c: Context): List<Long> {
        val out = ArrayList<Long>()
        try {
            c.contentResolver.query(Uri.parse(Sahl.ADHAN_UNION + "/prayers"), null, null, null, null)?.use {
                val col = it.getColumnIndex("time")
                while (it.moveToNext()) if (col >= 0) out.add(it.getLong(col))
            }
        } catch (e: Exception) { }
        return out
    }

    /** Is "now" inside a prayer window (adhan time .. + minutes)? */
    fun inPrayer(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (!enabled(c)) return false
        val win = minutes(c) * 60_000L
        return prayerTimes(c).any { now >= it && now < it + win }
    }

    /** Called when a call starts ringing. Returns true if it was silenced. */
    @android.annotation.SuppressLint("MissingPermission")
    fun onRinging(c: Context, call: android.telecom.Call): Boolean {
        if (!inPrayer(c)) return false
        val num = call.number()
        if (allowFavorites(c) && num.isNotBlank() && Facts.of(c, num).starred) return false
        val tm = c.getSystemService(TelecomManager::class.java)
        try { tm?.silenceRinger() } catch (e: SecurityException) { }
        // Ringing can start a moment later — silence once more
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { tm?.silenceRinger() } catch (e: SecurityException) { } }, 700)
        silenced = call
        if (autoReply(c) && num.isNotBlank()) {
            call.reject(true, Sahl.wrap(c).getString(R.string.praying_reply))
            Reminders.schedule(c, num, Lookup.caller(c, num).name, minutes(c) + 5)
        }
        return true
    }
}
