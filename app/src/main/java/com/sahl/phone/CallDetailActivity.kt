package com.sahl.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Details of one number: who, where from, and every call with them. */
class CallDetailActivity : SahlActivity() {
    private lateinit var number: String
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra("number") ?: run { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header("", true, listOf(R.drawable.ic_delete to { deleteAll() })))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(16), px(4), px(16), px(40)) }
        root.addView(ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); load() }

    private data class Row(val id: Long, val type: Int, val date: Long, val duration: Long)

    private fun load() {
        Thread {
            val who = Lookup.caller(this, number)
            val facts = Facts.of(this, number)
            val key = Norm.phoneKey(number)
            val rows = ArrayList<Row>()
            try {
                contentResolver.query(CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                    null, null, CallLog.Calls.DATE + " DESC")?.use {
                    while (it.moveToNext() && rows.size < 200) {
                        val n = it.getString(1) ?: continue
                        if (Norm.phoneKey(n) == key) rows.add(Row(it.getLong(0), it.getInt(2), it.getLong(3), it.getLong(4)))
                    }
                }
            } catch (e: Exception) { }
            runOnUiThread { build(who, facts, rows) }
        }.start()
    }

    private fun build(who: Caller, facts: CallerFacts, rows: List<Row>) {
        body.removeAllViews()
        val name = who.name
        val (av, iv, letter) = avatar(104)
        av.background = oval(avatarColor(name ?: number))
        letter.text = if (name != null) Norm.section(name).let { if (it == "#") "" else it } else ""
        Thumbs.load(this, who.photo, iv, letter)
        body.addView(av, LinearLayout.LayoutParams(px(104), px(104)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(text(name ?: number, 24f, col(R.color.text), true).apply { gravity = Gravity.CENTER; setPadding(0, px(14), 0, 0) })
        val sub = listOfNotNull(if (name != null) number else null, Countries.of(number, Sahl.countryCode(this), rtl)).joinToString("  ·  ")
        if (sub.isNotEmpty()) body.addView(text(sub, 14f, col(R.color.sub)).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR })
        val totalTalk = rows.sumOf { it.duration }
        body.addView(text(getString(R.string.detail_facts, rows.size, dur(totalTalk)), 13f, col(R.color.emerald)).apply {
            gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0)
        })

        // Actions
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(20), 0, px(8)) }
        fun action(ic: Int, label: Int, go: () -> Unit) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            b.addView(iconButton(ic, col(R.color.tonal), col(R.color.emerald), 56, 24, go))
            b.addView(text(getString(label), 12f).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
            actions.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        action(R.drawable.ic_call, R.string.call) { Dialer.call(this, number) }
        action(R.drawable.ic_chat, R.string.message) { go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))) }
        action(R.drawable.ic_bell, R.string.remind_me) { remind() }
        if (name == null) action(R.drawable.ic_person_add, R.string.add_contact) { addContact() }
        else action(R.drawable.ic_copy, R.string.copy) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", number)); toast(R.string.copied)
        }
        body.addView(actions)

        val note = facts.note
        if (!note.isNullOrBlank()) {
            val c = card()
            c.addView(label(getString(R.string.note)))
            c.addView(text(note, 15f))
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        }

        // Every call with this number
        body.addView(label(getString(R.string.call_history)).apply { setPaddingRelative(px(8), px(18), 0, px(2)) })
        val card = card()
        var lastDay: LocalDate? = null
        for (r in rows) {
            val day = Instant.ofEpochMilli(r.date).atZone(ZoneId.systemDefault()).toLocalDate()
            if (day != lastDay) {
                lastDay = day
                card.addView(text(dayLabel(day), 12f, col(R.color.sub), true).apply { setPaddingRelative(0, px(10), 0, px(2)) })
            }
            val (ic, color, label) = when (r.type) {
                CallLog.Calls.OUTGOING_TYPE -> Triple(R.drawable.ic_arrow_out, col(R.color.emerald), R.string.outgoing)
                CallLog.Calls.MISSED_TYPE -> Triple(R.drawable.ic_call_missed, col(R.color.danger), R.string.missed)
                CallLog.Calls.REJECTED_TYPE -> Triple(R.drawable.ic_call_missed, col(R.color.danger), R.string.rejected)
                CallLog.Calls.BLOCKED_TYPE -> Triple(R.drawable.ic_block, col(R.color.sub), R.string.blocked)
                else -> Triple(R.drawable.ic_arrow_in, col(R.color.ok), R.string.incoming_t)
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, px(8)) }
            row.addView(icon(ic, color, 18))
            row.addView(text(getString(label), 15f).apply { setPaddingRelative(px(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(listOf(time(r.date), if (r.duration > 0) dur(r.duration) else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
                13f, col(R.color.sub)))
            card.addView(row)
        }
        if (rows.isEmpty()) card.addView(text(getString(R.string.no_recents), 14f, col(R.color.sub)))
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })

        body.addView(text(getString(R.string.block), 15f, col(R.color.danger)).apply {
            gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(14))
            setOnClickListener { toast(if (Data.block(this@CallDetailActivity, number)) R.string.blocked_done else R.string.make_default) }
        }.press(16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
    }

    private fun loc() = resources.configuration.locales[0]
    private fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.today_t)
            today.minusDays(1) -> getString(R.string.yesterday_t)
            else -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", loc()))
        }
    }
    private fun time(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", loc()))
    private fun dur(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    private fun remind() {
        val labels = arrayOf(getString(R.string.in_minutes, 15), getString(R.string.in_hour), getString(R.string.in_hours, 2))
        val mins = intArrayOf(15, 60, 120)
        android.app.AlertDialog.Builder(this).setTitle(R.string.remind_me)
            .setItems(labels) { _, i -> Reminders.schedule(this, number, Lookup.caller(this, number).name, mins[i]); toast(R.string.reminder_set) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun addContact() {
        val i = Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
        try { packageManager.getPackageInfo("com.sahl.contacts", 0); i.setPackage("com.sahl.contacts") } catch (e: Exception) { }
        go(i)
    }

    private fun deleteAll() {
        android.app.AlertDialog.Builder(this).setMessage(R.string.delete_history_q)
            .setPositiveButton(R.string.delete_entry) { _, _ ->
                Thread {
                    val key = Norm.phoneKey(number)
                    val ids = ArrayList<Long>()
                    try {
                        contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER), null, null, null)?.use {
                            while (it.moveToNext()) if (Norm.phoneKey(it.getString(1) ?: "") == key) ids.add(it.getLong(0))
                        }
                        if (ids.isNotEmpty()) contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString(",")})", null)
                    } catch (e: Exception) { }
                    runOnUiThread { finish() }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun go(i: Intent) { try { startActivity(i) } catch (e: Exception) { } }
}
