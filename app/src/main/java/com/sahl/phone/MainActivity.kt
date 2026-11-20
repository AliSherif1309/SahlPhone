package com.sahl.phone

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : SahlActivity() {
    private var tab = 0
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var banner: LinearLayout
    private var contacts: List<Entry> = emptyList()
    private var recents: List<Recent> = emptyList()
    private var typed = ""
    private var missedOnly = false

    private val perms = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS)

    private fun granted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.app_name), false, listOf(
            R.drawable.ic_tune to { startActivity(Intent(this, SettingsActivity::class.java)) }
        )))
        banner = card().apply { background = round(col(R.color.tonal), 22f); elevation = 0f }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        tabBar = LinearLayout(this).apply {
            setPadding(px(12), px(8), px(12), px(10)); setBackgroundColor(col(R.color.card)); elevation = 8f * dp
        }
        root.addView(tabBar)
        setContentView(root)
        systemBars(col(R.color.bg), !night)
        window.navigationBarColor = col(R.color.card)

        handleIntent(intent)
        Updater.dailyCheck(this)
        if (!crashOpen && !granted()) askPermissions()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); if (::content.isInitialized) show() }

    /** tel: links and "Dial" from other apps open the keypad with the number. */
    private fun handleIntent(i: Intent?) {
        val d = i?.data
        if (d != null && d.scheme == "tel") { typed = d.schemeSpecificPart ?: ""; tab = 0 }
    }

    override fun onResume() {
        super.onResume()
        Lookup.clear()
        renderBanner(); renderTabs(); load()
    }

    private fun askPermissions() {
        val p = perms.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r); renderBanner(); load()
    }

    private fun requestDefault() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 5)
            } else {
                startActivityForResult(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName), 5)
            }
        } catch (e: Exception) { }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data); renderBanner(); if (!granted()) askPermissions()
    }

    private fun renderBanner() {
        banner.removeAllViews()
        val msg: Int; val btn: Int; val go: () -> Unit
        when {
            !Dialer.isDefault(this) -> { msg = R.string.make_default; btn = R.string.set_default; go = { requestDefault() } }
            !granted() -> { msg = R.string.need_permission; btn = R.string.allow; go = { askPermissions() } }
            else -> { banner.visibility = View.GONE; return }
        }
        banner.visibility = View.VISIBLE
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(icon(R.drawable.ic_call, col(R.color.emerald), 22))
        row.addView(text(getString(msg), 14f).apply { setPadding(px(12), 0, px(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(getString(btn), 14f, 0xFFFFFFFF.toInt(), true).apply {
            background = round(col(R.color.emerald), 20f); setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { go() }
        }.press(20f))
        banner.addView(row)
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        val missed = recents.count { it.type == CallLog.Calls.MISSED_TYPE && System.currentTimeMillis() - it.date < 86_400_000L }
        listOf(Triple(R.drawable.ic_dialpad, R.string.keypad, 0), Triple(R.drawable.ic_history, R.string.recents, missed),
            Triple(R.drawable.ic_people, R.string.contacts, 0)).forEachIndexed { i, (ic, label, badge) ->
            val on = i == tab
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { if (tab != i) { tab = i; renderTabs(); show() } }
            }
            val pillBox = FrameLayout(this).apply {
                background = if (on) round(col(R.color.tonal), 16f) else null
                setPadding(px(20), px(4), px(20), px(4))
            }
            pillBox.addView(icon(ic, if (on) col(R.color.emerald) else col(R.color.sub), 24))
            if (badge > 0) pillBox.addView(text(badge.toString(), 10f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER; background = oval(col(R.color.danger))
            }, FrameLayout.LayoutParams(px(16), px(16), Gravity.TOP or Gravity.END))
            b.addView(pillBox)
            b.addView(text(getString(label), 12f, if (on) col(R.color.emerald) else col(R.color.sub), on).apply {
                gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0)
            })
            tabBar.addView(b.press(16f), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun load() {
        if (!granted()) { show(); return }
        Thread {
            val c = try { Data.contacts(this) } catch (e: Exception) { emptyList() }
            val r = try { Data.recents(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { contacts = c; recents = r; renderTabs(); show() }
        }.start()
    }

    private fun show() {
        content.removeAllViews()
        when (tab) { 0 -> keypad(); 1 -> recentsView(); else -> contactsView() }
    }

    private fun call(n: String) { if (n.isNotBlank()) Dialer.call(this, n) }
    private fun sms(n: String) = go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))))

    // ---------- Keypad ----------
    private fun keypad() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val matches = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0) }
        box.addView(matches, LinearLayout.LayoutParams(-1, 0, 1f))
        val numTv = text(typed, 34f, col(R.color.text)).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1; setPadding(px(16), px(4), px(16), 0)
            setOnLongClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                if (!clip.isNullOrBlank()) { typed = clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }; refreshPad() }
                true
            }
        }
        val hint = text("", 13f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(4)) }
        padRefresh = {
            numTv.text = typed
            val q = Norm.digits(typed)
            val list = if (q.isEmpty()) emptyList() else contacts.filter { e ->
                e.digits.contains(q) || e.t9.split(' ').any { w -> w.startsWith(q) } || e.t9.replace(" ", "").startsWith(q)
            }.take(30)
            matches.adapter = EntryAdapter(list)
            val exact = list.firstOrNull { Norm.phoneKey(it.number) == Norm.phoneKey(typed) && q.length >= 7 }
            hint.text = exact?.name ?: (if (typed.isNotBlank()) Countries.of(typed, Sahl.countryCode(this), rtl) ?: "" else "")
        }
        box.addView(numTv); box.addView(hint)
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL }
        val letters = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")
        for (row in listOf("123", "456", "789", "*0#")) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (ch in row) {
                val k = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = oval(col(R.color.tonal))
                    setOnClickListener { typed += ch; performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); refreshPad() }
                    if (ch == '0') setOnLongClickListener { typed += "+"; refreshPad(); true }
                }.press(circle = true)
                k.addView(text(ch.toString(), 28f, col(R.color.text)).apply { gravity = Gravity.CENTER })
                if (letters[ch] != null) k.addView(text(letters[ch]!!, 9f, col(R.color.sub)).apply { gravity = Gravity.CENTER; letterSpacing = 0.15f })
                r.addView(k, LinearLayout.LayoutParams(px(74), px(74)).apply { setMargins(px(12), px(6), px(12), px(6)) })
            }
            pad.addView(r)
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(8), px(24), px(14)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(iconButton(R.drawable.ic_call, col(R.color.ok), 0xFFFFFFFF.toInt(), 72, 30) {
            if (typed.isEmpty()) { typed = recents.firstOrNull { r -> r.type == CallLog.Calls.OUTGOING_TYPE }?.number ?: ""; refreshPad() }
            else { val n = typed; typed = ""; refreshPad(); call(n) }
        }.apply { elevation = 4f * dp })
        val back = LinearLayout(this).apply { gravity = Gravity.CENTER }
        back.addView(iconButton(R.drawable.ic_backspace, 0, col(R.color.sub), 56, 24) { typed = typed.dropLast(1); refreshPad() }.apply {
            setOnLongClickListener { typed = ""; refreshPad(); true }
        })
        actions.addView(back, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(pad); box.addView(actions)
        content.addView(box)
        refreshPad()
    }

    private var padRefresh: () -> Unit = {}
    private fun refreshPad() = padRefresh()

    // ---------- Recents ----------
    private fun recentsView() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply { setPadding(px(16), px(2), px(16), px(6)) }
        fun chip(label: Int, on: Boolean, go: () -> Unit) = chips.addView(text(getString(label), 13f,
            if (on) 0xFFFFFFFF.toInt() else col(R.color.text), on).apply {
            background = round(if (on) col(R.color.emerald) else col(R.color.tonal), 18f)
            setPadding(px(16), px(8), px(16), px(8)); setOnClickListener { go() }
        }.press(18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
        chip(R.string.all, !missedOnly) { missedOnly = false; show() }
        chip(R.string.missed, missedOnly) { missedOnly = true; show() }
        box.addView(chips)
        val list = recents.filter { !missedOnly || it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
        if (list.isEmpty()) { box.addView(empty(R.drawable.ic_history, R.string.no_recents), LinearLayout.LayoutParams(-1, 0, 1f)); content.addView(box); return }
        val lv = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0); clipToPadding = false; setPadding(0, 0, 0, px(12)) }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(p: Int) = list[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val r = list[p]
                val outer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val day = dayOf(r.date)
                if (p == 0 || dayOf(list[p - 1].date) != day) outer.addView(text(dayLabel(r.date), 13f, col(R.color.emerald), true).apply {
                    setPadding(px(22), px(14), px(22), px(4))
                })
                val name = Lookup.caller(this@MainActivity, r.number).name ?: r.name
                val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
                val (ic, color) = when (r.type) {
                    CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_arrow_out to col(R.color.emerald)
                    CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_block to col(R.color.sub)
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_call_missed to col(R.color.danger)
                    else -> R.drawable.ic_arrow_in to col(R.color.ok)
                }
                val title = (name ?: r.number.ifBlank { getString(R.string.private_number) }) + if (r.count > 1) "  (${r.count})" else ""
                val subText = (if (name != null) r.number + "  ·  " else (Countries.of(r.number, Sahl.countryCode(this@MainActivity), rtl)?.let { "$it  ·  " } ?: "")) + time(r.date)
                val row = personRow(title, subText, if (missed) col(R.color.danger) else col(R.color.text), name ?: "#", null, ic, color)
                row.setOnClickListener { call(r.number) }
                row.setOnLongClickListener { recentMenu(r, name); true }
                outer.addView(swipe(row, r.number))
                return outer
            }
        }
        box.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(box)
    }

    private fun dayOf(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun dayLabel(t: Long): String {
        val d = dayOf(t); val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.today_t)
            today.minusDays(1) -> getString(R.string.yesterday_t)
            else -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", resources.configuration.locales[0]))
        }
    }
    private fun time(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", resources.configuration.locales[0]))

    private fun recentMenu(r: Recent, name: String?) {
        if (r.number.isBlank()) return
        val items = mutableListOf(
            Triple(R.drawable.ic_call, getString(R.string.call)) { call(r.number) },
            Triple(R.drawable.ic_chat, getString(R.string.message)) { sms(r.number) },
            Triple(R.drawable.ic_bell, getString(R.string.remind_me)) { remind(r.number, name) },
            Triple(R.drawable.ic_copy, getString(R.string.copy)) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", r.number)); toast(R.string.copied)
            }
        )
        if (name == null) items.add(Triple(R.drawable.ic_person_add, getString(R.string.add_contact)) {
            go(Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, r.number).apply {
                    if (packageInstalled("com.sahl.contacts")) setPackage("com.sahl.contacts")
                })
        })
        items.add(Triple(R.drawable.ic_block, getString(R.string.block)) { toast(if (Data.block(this, r.number)) R.string.blocked_done else R.string.make_default) })
        items.add(Triple(R.drawable.ic_delete, getString(R.string.delete_entry)) { Data.deleteRecent(this, r); load() })
        sheet(name ?: r.number, items)
    }

    private fun remind(number: String, name: String?) {
        val labels = arrayOf(getString(R.string.in_minutes, 15), getString(R.string.in_hour), getString(R.string.in_hours, 2))
        val mins = intArrayOf(15, 60, 120)
        AlertDialog.Builder(this).setTitle(R.string.remind_me)
            .setItems(labels) { _, i -> Reminders.schedule(this, number, name, mins[i]); toast(R.string.reminder_set) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    // ---------- Contacts ----------
    private fun contactsView() {
        if (contacts.isEmpty()) { content.addView(empty(R.drawable.ic_people, R.string.no_contacts)); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lv = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0) }
        val favs = contacts.filter { it.starred }.distinctBy { it.contactId }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (favs.isNotEmpty()) {
            strip.addView(label(getString(R.string.favorites)).apply { setPadding(px(20), px(6), 0, px(4)) })
            val row = LinearLayout(this).apply { setPadding(px(12), 0, px(12), px(4)) }
            for (e in favs) {
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(6), px(6), px(6), px(6))
                    setOnClickListener { call(e.number) }
                }.press(18f)
                val (f, iv, letter) = avatar(56)
                f.background = oval(avatarColor(e.name)); letter.text = initial(e.name)
                Thumbs.load(this, e.photo, iv, letter)
                cell.addView(f)
                cell.addView(text(e.name.substringBefore(' '), 12f).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) },
                    LinearLayout.LayoutParams(px(68), -2))
                row.addView(cell)
            }
            strip.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        lv.addHeaderView(strip, null, false)
        val (search, _) = searchBox(getString(R.string.search)) { q ->
            val words = Norm.text(q).split(' ').filter { it.isNotBlank() }
            val dq = Norm.digits(q)
            val list = contacts.filter { e -> words.isEmpty() || words.all { e.key.contains(it) } || (dq.length >= 3 && e.digits.contains(dq)) }
            for (i in 0 until strip.childCount) strip.getChildAt(i).visibility = if (q.isBlank()) View.VISIBLE else View.GONE
            lv.adapter = EntryAdapter(list)
        }
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(2), px(16), px(6)) })
        box.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        lv.adapter = EntryAdapter(contacts)
        content.addView(box)
    }

    // ---------- Shared rows ----------
    private inner class EntryAdapter(val list: List<Entry>) : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(p: Int) = list[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val e = list[p]
            val row = personRow(e.name, e.number, col(R.color.text), e.name, e.photo, if (e.starred) R.drawable.ic_star else 0, col(R.color.gold))
            row.setOnClickListener { call(e.number) }
            row.setOnLongClickListener {
                sheet(e.name, listOf(
                    Triple(R.drawable.ic_call, getString(R.string.call)) { call(e.number) },
                    Triple(R.drawable.ic_chat, getString(R.string.message)) { sms(e.number) },
                    Triple(R.drawable.ic_bell, getString(R.string.remind_me)) { remind(e.number, e.name) },
                    Triple(R.drawable.ic_copy, getString(R.string.copy)) {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", e.number)); toast(R.string.copied)
                    }
                )); true
            }
            return swipe(row, e.number)
        }
    }

    private fun swipe(row: View, number: String): View =
        if (number.isBlank()) row
        else SwipeRow(this, row, col(R.color.bg), R.drawable.ic_call, col(R.color.ok), { call(number) },
            R.drawable.ic_chat, col(R.color.emerald), { sms(number) })

    private fun initial(name: String) = Norm.section(name).let { if (it == "#") "" else it }

    private fun personRow(title: String, sub: String, color: Int, avatarName: String, photo: String?, trailIcon: Int, trailColor: Int): LinearLayout {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(18), px(9), px(18), px(9)) }.press(0f)
        val (f, iv, letter) = avatar(46)
        f.background = oval(avatarColor(avatarName))
        letter.text = initial(avatarName)
        if (letter.text.isEmpty()) f.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_people); imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
        }, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        Thumbs.load(this, photo, iv, letter)
        r.addView(f)
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, px(8), 0) }
        t.addView(text(title, 16f, color).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        t.addView(text(sub, 13f, col(R.color.sub)).apply { maxLines = 1; textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailIcon != 0) r.addView(icon(trailIcon, trailColor, 18))
        return r
    }

    private fun empty(ic: Int, id: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(icon(ic, col(R.color.line), 64))
        addView(text(getString(id), 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
        layoutParams = FrameLayout.LayoutParams(-1, -1)
    }

    private fun go(i: Intent) { try { startActivity(i) } catch (e: Exception) { } }
    private fun packageInstalled(p: String) = try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }
}
