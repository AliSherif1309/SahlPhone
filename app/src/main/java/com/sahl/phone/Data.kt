package com.sahl.phone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Groups

data class Entry(val contactId: Long, val name: String, val number: String, val photo: String?, val starred: Boolean,
                 val key: String, val t9: String, val digits: String)

data class Person(
    val id: Long, val lookup: String, val name: String, val photo: String?,
    val starred: Boolean, val numbers: List<String>,
    val key: String, val section: String, val digits: String
)

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

    /** One row per contact — mirrors Sahl Contacts Repo.list() criteria. */
    fun people(c: Context): List<Person> {
        val cr = c.contentResolver
        val nums = HashMap<Long, MutableList<String>>()
        cr.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER), null, null, null)?.use {
            while (it.moveToNext()) {
                val n = it.getString(1) ?: continue
                val l = nums.getOrPut(it.getLong(0)) { mutableListOf() }
                if (l.none { x -> Norm.phoneKey(x) == Norm.phoneKey(n) }) l.add(n)
            }
        }
        val out = ArrayList<Person>()
        cr.query(Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED),
            null, null, null)?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val n = nums[id] ?: emptyList()
                val name = it.getString(2)?.takeIf { s -> s.isNotBlank() } ?: n.firstOrNull() ?: continue
                val sec = Norm.section(name)
                val dig = n.joinToString(" ") { x -> Norm.digits(x) + " " + Norm.phoneKey(x) }
                out.add(Person(id, it.getString(1) ?: "", name, it.getString(3), it.getInt(4) == 1, n,
                    Norm.text(name), sec, dig))
            }
        }
        out.sortWith(compareBy<Person>({ Norm.group(it.section) }, { it.key }))
        return out
    }

    fun groups(c: Context): Map<String, Set<Long>> {
        val byTitle = LinkedHashMap<String, MutableSet<Long>>()
        c.contentResolver.query(Groups.CONTENT_URI, arrayOf(Groups._ID, Groups.TITLE),
            "${Groups.DELETED}=0", null, Groups.TITLE)?.use {
            while (it.moveToNext()) {
                val t = it.getString(1)?.trim() ?: continue
                if (t.isEmpty() || t.startsWith("System Group") || t.startsWith("Starred in") || t == "My Contacts") continue
                if (t.startsWith("Restored from") || t.startsWith("تمت الاستعادة")) continue
                byTitle.getOrPut(t) { mutableSetOf() }.add(it.getLong(0))
            }
        }
        return byTitle
    }

    fun members(c: Context): Map<Long, Set<Long>> {
        val m = HashMap<Long, MutableSet<Long>>()
        c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE}=?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null)?.use {
            while (it.moveToNext()) m.getOrPut(it.getLong(1)) { mutableSetOf() }.add(it.getLong(0))
        }
        return m
    }

    fun birthdaysThisMonth(c: Context): Set<Long> {
        val m = "%02d".format(java.time.LocalDate.now().monthValue)
        val out = HashSet<Long>()
        c.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.CONTACT_ID, Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE}=? AND ${Event.TYPE}=?", arrayOf(Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString()), null)?.use {
            while (it.moveToNext()) {
                val d = (it.getString(1) ?: continue).trim()
                val mm = if (d.startsWith("--")) d.drop(2).take(2) else d.drop(5).take(2)
                if (mm == m) out.add(it.getLong(0))
            }
        }
        return out
    }

    fun hiddenLookups(c: Context): Set<String> = try {
        val out = HashSet<String>()
        c.contentResolver.query(Uri.parse("${Sahl.CONTACTS_UNION}/hidden"), null, null, null, null)?.use {
            while (it.moveToNext()) it.getString(0)?.let { k -> out.add(k) }
        }
        out
    } catch (e: Exception) { emptySet() }

    fun setStar(c: Context, id: Long, on: Boolean): Boolean = try {
        c.contentResolver.update(
            Uri.parse("${Sahl.CONTACTS_UNION}/star/$id"),
            ContentValues().apply { put("starred", if (on) 1 else 0) },
            null, null
        ) > 0
    } catch (e: Exception) { false }

    fun contactForNumber(c: Context, number: String): Pair<Long, String>? {
        if (number.isBlank()) return null
        return try {
            c.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.CONTACT_ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                null, null, null
            )?.use {
                if (it.moveToFirst()) it.getLong(0) to (it.getString(1) ?: "") else null
            }
        } catch (e: Exception) { null }
    }

    fun recents(c: Context, limit: Int = 300): List<Recent> {
        val out = ArrayList<Recent>()
        c.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DATE + " DESC")?.use {
            while (it.moveToNext() && out.size < limit) {
                val id = it.getLong(0); val num = it.getString(1) ?: ""; val type = it.getInt(3); val dur = it.getLong(5)
                val last = out.lastOrNull()
                // Group back-to-back calls from the same number of the same kind
                if (last != null && Norm.phoneKey(last.number) == Norm.phoneKey(num) && last.type == type &&
                    (type != CallLog.Calls.OUTGOING_TYPE || (last.duration > 0) == (dur > 0))) {
                    out[out.size - 1] = last.copy(ids = last.ids + id, count = last.count + 1)
                } else out.add(Recent(listOf(id), num, it.getString(2)?.takeIf { s -> s.isNotBlank() }, type,
                    it.getLong(4), dur, 1))
            }
        }
        return out
    }

    fun deleteRecent(c: Context, r: Recent) {
        if (r.ids.isEmpty()) return   // list still loading from cache
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
