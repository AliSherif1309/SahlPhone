package com.sahl.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.TelecomManager

/** Follow-ups after a call: "no answer / busy → try again", and our own missed-call notice. */
object Followup {
    private const val CH = "followup_chime"

    private fun pi(c: Context, code: Int, i: Intent, activity: Boolean) =
        if (activity) PendingIntent.getActivity(c, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Outgoing call ended without being answered (no answer, busy, rejected). */
    fun onUnanswered(ctx: Context, call: Call) {
        if (!Sahl.prefs(ctx).getBoolean("call_when_free", true)) return
        val number = call.number(); if (number.isBlank()) return
        val cause = try { call.details.disconnectCause?.code } catch (e: Exception) { null }
        // Only real "didn't reach them" endings — not when you hung up yourself quickly
        if (cause == DisconnectCause.LOCAL && call.details.creationTimeMillis > 0 &&
            System.currentTimeMillis() - call.details.creationTimeMillis < 8000) return
        val c = Sahl.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(Sahl.chimeChannel(ctx, CH, c.getString(R.string.followups), NotificationManager.IMPORTANCE_DEFAULT))
        val who = Lookup.caller(ctx, number).name ?: number
        val id = 30_000 + (number.hashCode() and 0xffff)
        val title = c.getString(if (cause == DisconnectCause.BUSY) R.string.was_busy else R.string.no_answer, who)
        nm.notify(id, SahlNotif.apply(ctx, Notification.Builder(ctx, CH))
            .setContentTitle(title).setContentText(c.getString(R.string.try_again_q))
            .setAutoCancel(true).setTimeoutAfter(60 * 60_000L)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.in_minutes, 10),
                pi(ctx, id + 1, Intent(ctx, FollowupReceiver::class.java).setAction("later").putExtra("number", number).putExtra("min", 10).putExtra("id", id), false)).build())
            .addAction(Notification.Action.Builder(null, c.getString(R.string.in_minutes, 30),
                pi(ctx, id + 2, Intent(ctx, FollowupReceiver::class.java).setAction("later").putExtra("number", number).putExtra("min", 30).putExtra("id", id), false)).build())
            .addAction(Notification.Action.Builder(null, c.getString(R.string.call_again),
                pi(ctx, id + 3, Intent(ctx, CallActivity::class.java).putExtra("number", number), true)).build())
            .build())
    }
}

/** Missed calls: Android hands the missed-call notice to the default phone app — this makes it helpful. */
object Missed {
    private const val CH = "missed_chime"
    private const val ID = 40_000

    fun show(ctx: Context, count: Int, number: String?) {
        val c = Sahl.wrap(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (count <= 0) { nm.cancel(ID); return }
        nm.createNotificationChannel(Sahl.chimeChannel(ctx, CH, c.getString(R.string.missed_calls)))
        val n = number ?: ""
        val who = if (n.isNotBlank()) Lookup.caller(ctx, n).name ?: n else c.getString(R.string.private_number)
        val open = PendingIntent.getActivity(ctx, ID, Intent(ctx, MainActivity::class.java).putExtra("tab", 1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = SahlNotif.apply(ctx, Notification.Builder(ctx, CH))
            .setContentTitle(if (count > 1) c.getString(R.string.missed_n, count) else c.getString(R.string.missed_from, who))
            .setContentText(if (count > 1) c.getString(R.string.last_from, who) else c.getString(R.string.tap_to_see))
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MISSED_CALL)
        if (n.isNotBlank()) {
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.call_back),
                PendingIntent.getActivity(ctx, ID + 1, Intent(ctx, CallActivity::class.java).putExtra("number", n).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.message),
                PendingIntent.getActivity(ctx, ID + 2, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.remind_1h),
                PendingIntent.getBroadcast(ctx, ID + 3, Intent(ctx, FollowupReceiver::class.java).setAction("later").putExtra("number", n).putExtra("min", 60).putExtra("id", ID),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        nm.notify(ID, b.build())
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
        try { ctx.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification() } catch (e: Exception) { }
    }
}

/** Telecom asks the default phone app to show missed calls (then Android doesn't show its own). */
class MissedCallsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        Thread { try { Missed.show(c, count, number); MissedWidget.refresh(c) } finally { pending.finish() } }.start()
    }
}

class FollowupReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val number = intent.getStringExtra("number") ?: return
        val min = intent.getIntExtra("min", 10)
        Reminders.schedule(c, number, Lookup.caller(c, number).name, min)
        c.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("id", 0))
        android.widget.Toast.makeText(c, Sahl.wrap(c).getString(R.string.reminder_set), android.widget.Toast.LENGTH_SHORT).show()
    }
}
