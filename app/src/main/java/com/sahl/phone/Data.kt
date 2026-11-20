package com.sahl.phone

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone

data class Entry(val contactId: Long, val name: String, val number: String, val photo: String?, val starred: Boolean,
                 val key: String, val t9: String, val digits: String)

data class Recent(val ids: List<Long>, val number: String, val name: String?, val type: Int, val date: Long,
                  val duration: Long, val count: Int)

object Data {
    private const val T9 = "22233344455566677778889999"

    fun t9(s: String) = Norm.text(s).map { ch -> if (ch in 'a'..'z') T9[ch - 'a'] else if (ch == ' ') ' ' else ch }.joinToString("")

    /** One row per (contact, number). */
    fun contacts(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        c.contentResolver.query(Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED),
            null, null, null)?.use {
            while (it.moveToNext()) {
                val num = it.getString(2) ?: continue
                val id = it.getLong(0)
                if (!seen.add("$id:" + Norm.phoneKey(num))) continue
                val name = it.getString(1)?.takeIf { s -> s.isNotBlank() } ?: num
                out.add(Entry(id, name, num, it.getString(3), it.getInt(4) == 1, Norm.text(name), t9(name), Norm.digits(num)))
            }
        }
        out.sortWith(compareBy<Entry>({ Norm.group(Norm.section(it.name)) }, { it.key }))
        return out
    }

    fun recents(c: Context, limit: Int = 300): List<Recent> {
        val out = ArrayList<Recent>()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DATE + " DESC")?.use {
            while (it.moveToNext() && out.size < limit) {
                val id = it.getLong(0); val num = it.getString(1) ?: ""; val type = it.getInt(3)
                val last = out.lastOrNull()
                // Group back-to-back calls from the same number of the same kind
                if (last != null && Norm.phoneKey(last.number) == Norm.phoneKey(num) && last.type == type) {
                    out[out.size - 1] = last.copy(ids = last.ids + id, count = last.count + 1)
                } else out.add(Recent(listOf(id), num, it.getString(2)?.takeIf { s -> s.isNotBlank() }, type,
                    it.getLong(4), it.getLong(5), 1))
            }
        }
        return out
    }

    fun deleteRecent(c: Context, r: Recent) {
        c.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${r.ids.joinToString(",")})", null)
    }

    fun block(c: Context, number: String): Boolean = try {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(c)) false
        else {
            c.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) })
            true
        }
    } catch (e: Exception) { false }
}
