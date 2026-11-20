package com.sahl.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person as NPerson
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.PhoneLookup
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import java.util.concurrent.ConcurrentHashMap

/** Who is calling: name from Sahl Contacts (Sahl Union) or Android contacts. */
data class Caller(val name: String?, val photo: String?)

object Lookup {
    private val cache = ConcurrentHashMap<String, Caller>()

    fun caller(c: Context, number: String): Caller {
        if (number.isBlank()) return Caller(null, null)
        cache[number]?.let { return it }
        var r = Caller(null, null)
        // 1) Sahl Union — Sahl Contacts answers for the family
        try {
            c.contentResolver.query(Uri.parse("content://com.sahl.contacts.union/lookup/" + Uri.encode(number)),
                null, null, null, null)?.use { if (it.moveToFirst()) r = Caller(it.getString(0), it.getString(2)) }
        } catch (e: Exception) { }
        // 2) Fallback: Android contacts directly
        if (r.name == null) try {
            c.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) r = Caller(it.getString(0), it.getString(1))
            }
        } catch (e: Exception) { }
        cache[number] = r
        return r
    }

    fun clear() = cache.clear()
}

fun Call.number(): String = details?.handle?.schemeSpecificPart ?: ""

@Suppress("DEPRECATION")
fun Call.st(): Int = if (Build.VERSION.SDK_INT >= 31) details.state else state

/** Holds the live calls and tells the screens when anything changes. */
object CallManager {
    val calls = mutableListOf<Call>()
    val listeners = mutableSetOf<() -> Unit>()
    var service: SahlInCallService? = null
    var audio: CallAudioState? = null
    private val main = Handler(Looper.getMainLooper())

    fun changed() = main.post { listeners.toList().forEach { it() }; service?.let { Notifs.update(it) } }

    val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { changed() }
        override fun onDetailsChanged(call: Call, details: Call.Details) { changed() }
    }

    /** The call the screen should show. */
    fun primary(): Call? =
        calls.firstOrNull { it.st() == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.st() in listOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT) }
            ?: calls.firstOrNull()
}

class SahlInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        CallManager.service = this
        CallManager.calls.add(call)
        call.registerCallback(CallManager.callback)
        if (call.st() == Call.STATE_RINGING) PrayerSilence.onRinging(this, call)
        Notifs.update(this)
        if (call.st() != Call.STATE_RINGING) openScreen(this)
        CallManager.changed()
    }

    override fun onCallRemoved(call: Call) {
        // Answered call ended → offer a note; missed calls → refresh the widget
        try { if (call.details.connectTimeMillis > 0) CallNotes.offer(this, call.number()) } catch (e: Exception) { }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ MissedWidget.refresh(this) }, 1500)
        call.unregisterCallback(CallManager.callback)
        CallManager.calls.remove(call)
        Notifs.update(this)
        CallManager.changed()
    }

    @Deprecated("Deprecated in API 34")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audio = audioState
        CallManager.changed()
    }

    override fun onDestroy() { super.onDestroy(); if (CallManager.service === this) CallManager.service = null }

    companion object {
        fun openScreen(c: Context) {
            c.startActivity(Intent(c, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Incoming / ongoing call notifications (full-screen on the lock screen). */
object Notifs {
    private const val ID = 77
    private const val CH_IN = "incoming"
    private const val CH_ON = "ongoing"

    private fun pi(c: Context, code: Int, action: String) = PendingIntent.getBroadcast(c, code,
        Intent(c, CallActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val call = CallManager.primary()
        if (call == null) { nm.cancel(ID); return }
        val c = Sahl.wrap(ctx)
        nm.createNotificationChannel(NotificationChannel(CH_IN, c.getString(R.string.incoming), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null as AudioAttributes?)   // Android plays the ringtone itself
        })
        nm.createNotificationChannel(NotificationChannel(CH_ON, c.getString(R.string.ongoing), NotificationManager.IMPORTANCE_LOW))
        val num = call.number()
        val who = Lookup.caller(ctx, num).name ?: num.ifBlank { c.getString(R.string.private_number) }
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ringing = call.st() == Call.STATE_RINGING
        val b = Notification.Builder(ctx, if (ringing) CH_IN else CH_ON)
            .setSmallIcon(R.drawable.ic_call).setOngoing(true).setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL).setContentTitle(who)
            .setContentText(c.getString(if (ringing) R.string.incoming else R.string.ongoing))
        val decline = pi(ctx, 2, "decline"); val answer = pi(ctx, 3, "answer"); val hang = pi(ctx, 4, "end")
        if (ringing) b.setFullScreenIntent(open, true)
        if (Build.VERSION.SDK_INT >= 31) {
            val person = NPerson.Builder().setName(who).setImportant(true).build()
            b.setStyle(if (ringing) Notification.CallStyle.forIncomingCall(person, decline, answer)
                       else Notification.CallStyle.forOngoingCall(person, hang))
        } else if (ringing) {
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.decline), decline).build())
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.answer), answer).build())
        } else {
            b.addAction(Notification.Action.Builder(null, c.getString(R.string.end), hang).build())
        }
        try { nm.notify(ID, b.build()) } catch (e: Exception) { }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val call = CallManager.primary() ?: return
        when (intent.action) {
            "answer" -> { call.answer(VideoProfile.STATE_AUDIO_ONLY); SahlInCallService.openScreen(ctx) }
            "decline" -> call.reject(false, null)
            "end" -> call.disconnect()
        }
    }
}

/** Placing calls, with a SIM choice when the phone has two. */
@SuppressLint("MissingPermission")
object Dialer {
    /** Places a call; [done] runs after the call is placed or the SIM choice is cancelled. */
    fun call(a: Activity, number: String, done: () -> Unit = {}) {
        val n = number.trim()
        if (n.isEmpty()) { done(); return }
        val tm = a.getSystemService(TelecomManager::class.java)
        val accounts: List<PhoneAccountHandle> = try { tm.callCapablePhoneAccounts } catch (e: SecurityException) { emptyList() }
        val defaultAcc = try { tm.getDefaultOutgoingPhoneAccount("tel") } catch (e: Exception) { null }
        if (accounts.size > 1 && defaultAcc == null) {
            val labels = accounts.map { h -> try { tm.getPhoneAccount(h)?.label?.toString() } catch (e: Exception) { null } ?: h.id }
            var picked = false
            AlertDialog.Builder(a).setTitle(R.string.choose_sim)
                .setItems(labels.toTypedArray()) { _, i -> picked = true; place(a, n, accounts[i]); done() }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener { if (!picked) done() }
                .show()
        } else { place(a, n, null); done() }
    }

    private fun place(a: Activity, n: String, acc: PhoneAccountHandle?) {
        val uri = Uri.fromParts("tel", n, null)
        try {
            val extras = Bundle()
            if (acc != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, acc)
            a.getSystemService(TelecomManager::class.java).placeCall(uri, extras)
        } catch (e: SecurityException) {
            a.startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    fun isDefault(c: Context) = c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == c.packageName
}
