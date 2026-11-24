package com.sahl.phone

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract

/**
 * Sahl Union doors of Sahl Phone (only Sahl-signed apps can open them):
 *   content://com.sahl.phone.union/recent?limit=30          -> distinct recent numbers (number, date)
 *   content://com.sahl.phone.union/history/<number>?limit=20 -> number, type, date, duration
 *   action com.sahl.phone.action.CALL (extra "number")      -> places the call (asks SIM if needed)
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 30
        return try {
            when (uri.pathSegments.firstOrNull()) {
                "recent" -> {
                    val out = MatrixCursor(arrayOf("number", "date"))
                    val seen = HashSet<String>()
                    c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                        null, null, CallLog.Calls.DATE + " DESC")?.use {
                        while (it.moveToNext() && out.count < limit) {
                            val n = it.getString(0) ?: continue
                            if (n.isBlank() || !seen.add(Norm.phoneKey(n))) continue
                            out.addRow(arrayOf<Any>(n, it.getLong(1)))
                        }
                    }
                    out
                }
                "history" -> {
                    val out = MatrixCursor(arrayOf("number", "type", "date", "duration"))
                    val key = Norm.phoneKey(uri.pathSegments.getOrNull(1) ?: return out)
                    if (key.isEmpty()) return out
                    c.contentResolver.query(CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                        null, null, CallLog.Calls.DATE + " DESC")?.use {
                        while (it.moveToNext() && out.count < limit) {
                            val n = it.getString(0) ?: continue
                            if (Norm.phoneKey(n) == key) out.addRow(arrayOf<Any>(n, it.getInt(1), it.getLong(2), it.getLong(3)))
                        }
                    }
                    out
                }
                // Sahl Search: numbers/names in your call history and your call notes
                "search" -> {
                    val out = MatrixCursor(arrayOf("kind", "title", "subtitle", "date", "action", "key", "value", "package"))
                    val q = uri.getQueryParameter("q") ?: return out
                    val t = Norm.text(q).trim(); val dq = Norm.digits(q)
                    if (t.length < 2) return out
                    val noteKeys = CallNotes.numbersMatching(c, q)
                    val seen = HashSet<String>()
                    c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                        null, null, CallLog.Calls.DATE + " DESC")?.use {
                        var n = 0
                        while (it.moveToNext() && n++ < 3000 && out.count < 12) {
                            val num = it.getString(0) ?: continue; val k = Norm.phoneKey(num)
                            if (k.isEmpty() || k in seen) continue
                            val name = it.getString(1) ?: ""
                            val byNote = k in noteKeys
                            if (byNote || (dq.length >= 3 && Norm.digits(num).contains(dq)) || (name.isNotBlank() && Norm.text(name).contains(t))) {
                                seen.add(k)
                                out.addRow(arrayOf<Any>(if (byNote) "note" else "call", name.ifBlank { num },
                                    if (byNote) Sahl.wrap(c).getString(R.string.found_in_note) else num, it.getLong(2),
                                    "com.sahl.phone.action.DETAILS", "number", num, c.packageName))
                            }
                        }
                    }
                    out
                }
                // Fetched carrier/network display name for an unsaved number (name, saved)
                "fetched" -> {
                    val out = MatrixCursor(arrayOf("name", "saved"))
                    val num = uri.pathSegments.getOrNull(1) ?: return out
                    val who = Lookup.caller(c, num)
                    if (who.name != null) out.addRow(arrayOf<Any>(who.name, if (who.saved) 1 else 0))
                    out
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** Invisible screen other Sahl apps open to place a call through Sahl Phone. */
class CallActivity : Activity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Sahl.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val n = intent.getStringExtra("number") ?: intent.data?.schemeSpecificPart
        if (n.isNullOrBlank()) { finish(); return }
        Dialer.call(this, n) { finish() }
    }
}

/** Facts about a caller for the call screen. */
data class CallerFacts(val count: Int, val last: Long, val note: String?, val starred: Boolean)

object Facts {
    fun of(c: Context, number: String): CallerFacts {
        val key = Norm.phoneKey(number)
        var count = 0; var last = 0L
        if (key.isNotEmpty()) try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC")?.use {
                var seen = 0
                while (it.moveToNext() && seen++ < 3000) {
                    val n = it.getString(0) ?: continue
                    if (Norm.phoneKey(n) == key) { count++; if (last == 0L) last = it.getLong(1) }
                }
            }
        } catch (e: Exception) { }
        var note: String? = null; var starred = false
        try {
            c.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.STARRED), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0); starred = it.getInt(1) == 1
                    c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.DATA1),
                        "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                        arrayOf(id.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE), null)?.use { n ->
                        while (n.moveToNext()) { val v = n.getString(0); if (!v.isNullOrBlank()) { note = v; break } }
                    }
                }
            }
        } catch (e: Exception) { }
        return CallerFacts(count, last, note, starred)
    }
}
