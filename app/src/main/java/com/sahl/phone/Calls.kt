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
data class Caller(val name: String?, val photo: String?, val saved: Boolean = true)

object Lookup {
    private val cache = ConcurrentHashMap<String, Caller>()
    private var clearedAt = System.currentTimeMillis()

    fun caller(c: Context, number: String): Caller {
        if (number.isBlank()) return Caller(null, null, false)
        if (System.currentTimeMillis() - clearedAt > 10 * 60_000L) { cache.clear(); clearedAt = System.currentTimeMillis() }
        cache[number]?.let { return it }
        var r = Caller(null, null, false)
        // 1) Sahl Union — Sahl Contacts answers for the family
        try {
            c.contentResolver.query(Uri.parse("content://com.sahl.contacts.union/lookup/" + Uri.encode(number)),
                null, null, null, null)?.use { if (it.moveToFirst()) r = Caller(it.getString(0), it.getString(2), true) }
        } catch (e: Exception) { }
        // 2) Fallback: Android contacts directly
        if (r.name == null) try {
            c.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) r = Caller(it.getString(0), it.getString(1), true)
            }
        } catch (e: Exception) { }
        // 3) Fallback: previously fetched carrier/network display name (not saved in contacts)
        if (r.name == null) {
            val fetched = fetchedName(c, number)
            if (fetched != null) r = Caller(fetched, null, false)
        }
        cache[number] = r
        return r
    }

    private fun fetchedName(c: Context, number: String): String? = try {
        val k = Norm.phoneKey(number)
        if (k.isEmpty()) null
        else org.json.JSONObject(Sahl.prefs(c).getString("fetched_names", "{}")).optString(k).ifEmpty { null }
    } catch (e: Exception) { null }

    fun remember(c: Context, number: String, name: String?) {
        if (number.isBlank() || name.isNullOrBlank()) return
        val cur = cache[number]
        if (cur != null && cur.name == null) cache[number] = Caller(name, cur.photo, false)
        val k = Norm.phoneKey(number)
        if (k.isNotEmpty()) try {
            val prefs = Sahl.prefs(c)
            val obj = org.json.JSONObject(prefs.getString("fetched_names", "{}"))
            if (obj.optString(k) != name) {
                obj.put(k, name)
                prefs.edit().putString("fetched_names", obj.toString()).apply()
            }
        } catch (e: Exception) { }
    }

    fun clearUnsaved() {
        cache.entries.removeIf { !it.value.saved }
    }

    fun clear() = cache.clear()
}

fun Call.number(): String = details?.handle?.schemeSpecificPart ?: ""

fun Call.displayName(): String? =
    details?.callerDisplayName?.takeIf { it.isNotBlank() }
        ?: details?.contactDisplayName?.takeIf { it.isNotBlank() }

@Suppress("DEPRECATION")
fun Call.st(): Int = if (Build.VERSION.SDK_INT >= 31) details.state else state

/** Holds the live calls and tells the screens when anything changes. */
object CallManager {
    val calls = mutableListOf<Call>()
    val listeners = mutableSetOf<() -> Unit>()
    var service: SahlInCallService? = null
    var audio: CallAudioState? = null
    var lastDisconnectCause: Int? = null
    private val main = Handler(Looper.getMainLooper())

    fun changed() = main.post { listeners.toList().forEach { it() }; service?.let { Notifs.update(it) } }

    /** Calls that rang on this phone (incoming), to spot missed ones. */
    val rang = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Call, Boolean>())

    val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            service?.let { Lookup.remember(it, call.number(), call.displayName()) }
            if (state == Call.STATE_DISCONNECTED) lastDisconnectCause = call.details?.disconnectCause?.code
            if (calls.none { it.st() == Call.STATE_RINGING }) FlipSilence.stop()
            changed()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            service?.let { Lookup.remember(it, call.number(), call.displayName()) }
            if (call.st() == Call.STATE_DISCONNECTED) lastDisconnectCause = details.disconnectCause?.code
            changed()
        }
    }

    /** The call the screen should show (ignoring calls already disconnecting/disconnected). */
    fun primary(): Call? {
        val live = calls.filter { it.st() != Call.STATE_DISCONNECTED && it.st() != Call.STATE_DISCONNECTING }
        return live.firstOrNull { it.st() == Call.STATE_RINGING }
            ?: live.firstOrNull { it.st() in listOf(Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_HOLDING) }
            ?: live.firstOrNull()
    }
}

class SahlInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        if (call.st() == Call.STATE_DISCONNECTED || call.st() == Call.STATE_DISCONNECTING) return
        CallManager.lastDisconnectCause = null
        Lookup.remember(this, call.number(), call.displayName())
        CallManager.service = this
        CallManager.calls.add(call)
        call.registerCallback(CallManager.callback)
        if (call.st() == Call.STATE_RINGING) {
            CallManager.rang.add(call)
            PrayerSilence.onRinging(this, call)
            FlipSilence.start(this)
        }
        Notifs.update(this)
        // Always bring up the Sahl call screen — Android lets the default phone app do this,
        // so incoming calls appear on their own (screen off or on), not only as a notification.
        openScreen(this)
        CallManager.changed()
    }

    override fun onCallRemoved(call: Call) {
        // Answered call ended → offer a note; missed calls → refresh the widget
        try {
            if (call.details.connectTimeMillis > 0) CallNotes.offer(this, call.number())
            else if (call in CallManager.rang) PrayerMissed.onMissed(this, call.number())
            else if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING) Followup.onUnanswered(this, call)
        } catch (e: Exception) { }
        CallManager.rang.remove(call)
        if (CallManager.calls.none { it !== call && it.st() == Call.STATE_RINGING }) FlipSilence.stop()
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

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        if (CallManager.service === this) {
            CallManager.service = null
            CallManager.calls.clear()
            Notifs.cancel(this)
            CallManager.changed()
        }
    }

    companion object {
        fun openScreen(c: Context) {
            c.startActivity(Intent(c, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Incoming / ongoing call notifications (full-screen on the lock screen). */
object Notifs {
    private const val ID_IN = 77
    private const val ID_ON = 78
    private const val CH_IN = "calls_ring"   // new channel id so the high-priority settings apply
    private const val CH_ON = "calls_ongoing"

    private fun pi(c: Context, code: Int, action: String) = PendingIntent.getBroadcast(c, code,
        Intent(c, CallActionReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.let {
            it.cancel(ID_IN)
            it.cancel(ID_ON)
        }
    }

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val call = CallManager.primary()
        if (call == null) { nm.cancel(ID_IN); nm.cancel(ID_ON); return }
        val c = Sahl.wrap(ctx)
        nm.createNotificationChannel(NotificationChannel(CH_IN, c.getString(R.string.incoming), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null as AudioAttributes?)   // Android plays the ringtone itself
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        })
        try { nm.deleteNotificationChannel("incoming") } catch (e: Exception) { }
        nm.createNotificationChannel(NotificationChannel(CH_ON, c.getString(R.string.ongoing), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null as AudioAttributes?)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        try { nm.deleteNotificationChannel("ongoing") } catch (e: Exception) { }
        val num = call.number()
        val who = Lookup.caller(ctx, num).name ?: call.displayName() ?: num.ifBlank { c.getString(R.string.private_number) }
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ringing = call.st() == Call.STATE_RINGING
        val connectedAt = call.details.connectTimeMillis
        val decline = pi(ctx, 2, "decline"); val answer = pi(ctx, 3, "answer"); val hang = pi(ctx, 4, "end")

        fun baseBuilder(channel: String): Notification.Builder {
            val b = SahlNotif.apply(ctx, Notification.Builder(ctx, channel))
                .setOngoing(true).setContentIntent(open)
                .setCategory(Notification.CATEGORY_CALL).setContentTitle(who)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentText(c.getString(if (ringing) R.string.incoming else R.string.ongoing))
            if (!ringing && call.st() == Call.STATE_ACTIVE && connectedAt > 0) {
                b.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true)
            }
            return b
        }

        if (ringing) {
            nm.cancel(ID_ON)
            val b = baseBuilder(CH_IN).setFullScreenIntent(open, true)
            if (Build.VERSION.SDK_INT >= 31) {
                val person = NPerson.Builder().setName(who).setImportant(true).build()
                b.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
            } else {
                b.addAction(Notification.Action.Builder(null, c.getString(R.string.decline), decline).build())
                b.addAction(Notification.Action.Builder(null, c.getString(R.string.answer), answer).build())
            }
            try { nm.notify(ID_IN, b.build()) } catch (e: Exception) { }
        } else {
            nm.cancel(ID_IN)
            val posted = if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val person = NPerson.Builder().setName(who).setImportant(true).build()
                    val b = baseBuilder(CH_ON).setStyle(Notification.CallStyle.forOngoingCall(person, hang))
                    nm.notify(ID_ON, b.build())
                    true
                } catch (e: Exception) { false }
            } else false
            if (!posted) {
                val b = baseBuilder(CH_ON)
                    .addAction(Notification.Action.Builder(null, c.getString(R.string.end), hang).build())
                try { nm.notify(ID_ON, b.build()) } catch (e: Exception) { }
            }
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val call = CallManager.primary()
        if (call == null) { Notifs.cancel(ctx); return }
        when (intent.action) {
            "answer" -> { call.answer(VideoProfile.STATE_AUDIO_ONLY); SahlInCallService.openScreen(ctx) }
            "decline" -> {
                if (call.st() == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
                Notifs.cancel(ctx)
            }
            "end" -> { call.disconnect(); Notifs.cancel(ctx) }
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
            if (a is SahlActivity) {
                val items = accounts.mapIndexed { i, h ->
                    Triple(R.drawable.ic_call, labels[i]) { place(a, n, h); done() }
                }
                a.sheet(a.getString(R.string.choose_sim), items, onCancel = done)
            } else {
                var picked = false
                AlertDialog.Builder(a).setTitle(R.string.choose_sim)
                    .setItems(labels.toTypedArray()) { _, i -> picked = true; place(a, n, accounts[i]); done() }
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener { if (!picked) done() }
                    .show()
            }
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
