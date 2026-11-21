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

    private var dateFilter = 0          // 0 any · 1 today · 2 yesterday · 3 last 7 days · 4 last 30 days · 5 picked day
    private var pickedDay: LocalDate? = null
    private var last: Triple<Caller, CallerFacts, List<Row>>? = null

    private fun inPeriod(t: Long): Boolean {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate(); val today = LocalDate.now()
        return when (dateFilter) {
            1 -> d == today; 2 -> d == today.minusDays(1)
            3 -> !d.isBefore(today.minusDays(6)); 4 -> !d.isBefore(today.minusDays(29))
            5 -> d == pickedDay; else -> true
        }
    }

    private fun rebuild() { last?.let { build(it.first, it.second, it.third) } }

    private fun build(who: Caller, facts: CallerFacts, allRows: List<Row>) {
        last = Triple(who, facts, allRows)
        val rows = allRows.filter { inPeriod(it.date) }
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

        // Every call with this number — with date filters
        body.addView(label(getString(R.string.call_history)).apply { setPaddingRelative(px(8), px(18), 0, px(2)) })
        val dates = LinearLayout(this).apply { setPaddingRelative(px(4), 0, px(4), px(4)) }
        fun dchip(lbl: String, on: Boolean, go: () -> Unit) = dates.addView(text(lbl, 12f, if (on) col(R.color.emerald) else col(R.color.sub), on).apply {
            background = if (on) round(col(R.color.tonal), 14f) else round(0, 14f, col(R.color.line))
            setPadding(px(12), px(6), px(12), px(6)); setOnClickListener { go() }
        }.press(14f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        fun pick(i: Int) { dateFilter = i; rebuild() }
        dchip(getString(R.string.any_time), dateFilter == 0) { pick(0) }
        dchip(getString(R.string.today_t), dateFilter == 1) { pick(1) }
        dchip(getString(R.string.yesterday_t), dateFilter == 2) { pick(2) }
        dchip(getString(R.string.last_7), dateFilter == 3) { pick(3) }
        dchip(getString(R.string.last_30), dateFilter == 4) { pick(4) }
        dchip(if (dateFilter == 5 && pickedDay != null) pickedDay!!.format(DateTimeFormatter.ofPattern("d MMM", loc())) else getString(R.string.pick_date), dateFilter == 5) {
            val d = pickedDay ?: LocalDate.now()
            android.app.DatePickerDialog(this, { _, y, m, day -> pickedDay = LocalDate.of(y, m + 1, day); pick(5) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dates) })
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
            row.setOnClickListener { editNote(r.date) }
            row.press(12f)
            card.addView(row)
            CallNotes.get(this, number, r.date)?.let { n ->
                card.addView(text(n, 14f, col(R.color.text)).apply {
                    background = round(col(R.color.tonal), 12f); setPadding(px(12), px(8), px(12), px(8))
                    setOnClickListener { editNote(r.date) }
                }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(32), 0, 0, px(6)) })
            }
        }
        if (rows.isEmpty()) card.addView(text(getString(R.string.no_recents), 14f, col(R.color.sub)))
        else card.addView(text(getString(R.string.tap_to_note), 12f, col(R.color.sub)).apply { setPadding(0, px(8), 0, 0) })
        if (intent.getBooleanExtra("addNote", false) && rows.isNotEmpty()) {
            intent.removeExtra("addNote")
            editNote((rows.firstOrNull { it.duration > 0 } ?: rows.first()).date)
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })

        body.addView(text(getString(R.string.block), 15f, col(R.color.danger)).apply {
            gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(14))
            setOnClickListener { toast(if (Guard.block(this@CallDetailActivity, number)) R.string.blocked_done else R.string.make_default) }
        }.press(16f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        if (Guard.installed(this)) body.addView(text(getString(R.string.never_block), 15f, col(R.color.emerald)).apply {
            gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(14))
            setOnClickListener { if (Guard.allow(this@CallDetailActivity, number)) toast(R.string.allowed_done) }
        }.press(16f))
    }

    /** Write or change the note for one call. */
    private fun editNote(date: Long) {
        val et = EditText(this).apply {
            setText(CallNotes.get(this@CallDetailActivity, number, date) ?: ""); hint = getString(R.string.note_hint)
            typeface = font; minLines = 3; setPadding(px(20), px(16), px(20), px(16))
        }
        android.app.AlertDialog.Builder(this).setTitle(R.string.call_note).setView(et)
            .setPositiveButton(R.string.save) { _, _ -> CallNotes.set(this, number, date, et.text.toString()); load() }
            .setNegativeButton(R.string.cancel, null).show()
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
