package com.sahl.phone

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
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

    private val perms = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS)

    private fun granted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.app_name), false, listOf(
            "⚙" to { startActivity(Intent(this, SettingsActivity::class.java)) }
        )))
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 16f)
            setPadding(px(16), px(12), px(16), px(12))
        }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(12), px(10), px(12), 0) })
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        tabBar = LinearLayout(this).apply { setBackgroundColor(col(R.color.card)); setPadding(0, px(6), 0, px(6)) }
        root.addView(tabBar)
        setContentView(root)

        handleIntent(intent)
        Updater.dailyCheck(this)
        if (!granted()) askPermissions()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

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
            else -> { (banner.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0; banner.visibility = View.GONE; return }
        }
        banner.visibility = View.VISIBLE
        banner.addView(text(getString(msg), 14f))
        banner.addView(Button(this).apply { text = getString(btn); typeface = font; setOnClickListener { go() } })
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        listOf("⌨" to R.string.keypad, "🕘" to R.string.recents, "👥" to R.string.contacts).forEachIndexed { i, (ic, label) ->
            val on = i == tab
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { tab = i; renderTabs(); show() }
            }
            b.addView(text(ic, 20f, if (on) col(R.color.emerald) else col(R.color.sub)).apply { gravity = Gravity.CENTER })
            b.addView(text(getString(label), 12f, if (on) col(R.color.emerald) else col(R.color.sub), on).apply { gravity = Gravity.CENTER })
            tabBar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun load() {
        if (!granted()) { show(); return }
        Thread {
            val c = try { Data.contacts(this) } catch (e: Exception) { emptyList() }
            val r = try { Data.recents(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { contacts = c; recents = r; show() }
        }.start()
    }

    private fun show() {
        content.removeAllViews()
        when (tab) { 0 -> keypad(); 1 -> recentsView(); else -> contactsView() }
    }

    // ---------- Keypad ----------
    private fun keypad() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val matches = ListView(this).apply { divider = null }
        box.addView(matches, LinearLayout.LayoutParams(-1, 0, 1f))
        val numTv = text(typed, 32f, col(R.color.text)).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1; setPadding(px(16), px(8), px(16), px(8))
        }
        fun refresh() {
            numTv.text = typed
            val q = Norm.digits(typed)
            val list = if (q.isEmpty()) emptyList() else contacts.filter { e ->
                e.digits.contains(q) || e.t9.split(' ').any { w -> w.startsWith(q) } || e.t9.replace(" ", "").startsWith(q)
            }.take(30)
            matches.adapter = EntryAdapter(list) { e -> Dialer.call(this, e.number) }
        }
        box.addView(numTv)
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(px(24), 0, px(24), 0) }
        val letters = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")
        for (row in listOf("123", "456", "789", "*0#")) {
            val r = LinearLayout(this)
            for (ch in row) {
                val k = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6))
                    setOnClickListener { typed += ch; refresh() }
                    if (ch == '0') setOnLongClickListener { typed += "+"; refresh(); true }
                }
                k.addView(text(ch.toString(), 28f, col(R.color.text)).apply { gravity = Gravity.CENTER })
                k.addView(text(letters[ch] ?: " ", 10f, col(R.color.sub)).apply { gravity = Gravity.CENTER })
                r.addView(k, LinearLayout.LayoutParams(0, -2, 1f))
            }
            pad.addView(r)
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(6), px(24), px(14)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(text("✆", 30f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; background = oval(0xFF1E9E63.toInt())
            setOnClickListener {
                if (typed.isEmpty()) typed = recents.firstOrNull { r -> r.type == CallLog.Calls.OUTGOING_TYPE }?.number ?: ""
                if (typed.isNotEmpty() && numTv.text.toString() == typed) { val n = typed; typed = ""; Dialer.call(this@MainActivity, n) }
                refresh()
            }
        }, LinearLayout.LayoutParams(px(70), px(70)))
        actions.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(text("⌫", 24f, col(R.color.sub)).apply {
                setPadding(px(18), px(10), px(18), px(10))
                setOnClickListener { typed = typed.dropLast(1); refresh() }
                setOnLongClickListener { typed = ""; refresh(); true }
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(pad); box.addView(actions)
        content.addView(box)
        refresh()
    }

    // ---------- Recents ----------
    private fun recentsView() {
        if (recents.isEmpty()) { content.addView(empty(R.string.no_recents)); return }
        val lv = ListView(this).apply { divider = null }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = recents.size
            override fun getItem(p: Int) = recents[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val r = recents[p]
                val name = Lookup.caller(this@MainActivity, r.number).name ?: r.name
                val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
                val arrow = when (r.type) {
                    CallLog.Calls.OUTGOING_TYPE -> "↗"; CallLog.Calls.INCOMING_TYPE -> "↙"
                    CallLog.Calls.BLOCKED_TYPE -> "⛔"; else -> "↙"
                }
                val title = (name ?: r.number.ifBlank { getString(R.string.private_number) }) + if (r.count > 1) " (${r.count})" else ""
                return row(title, "$arrow  " + (if (name != null) r.number + " · " else "") + whenText(r.date),
                    if (missed) 0xFFD64545.toInt() else col(R.color.text), name ?: "#", null,
                    { if (r.number.isNotBlank()) Dialer.call(this@MainActivity, r.number) }) { recentMenu(r) }
            }
        }
        content.addView(lv)
    }

    private fun whenText(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val loc = resources.configuration.locales[0]
        return when (d.toLocalDate()) {
            today -> d.format(DateTimeFormatter.ofPattern("HH:mm", loc))
            today.minusDays(1) -> getString(R.string.yesterday) + " " + d.format(DateTimeFormatter.ofPattern("HH:mm", loc))
            else -> d.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", loc))
        }
    }

    private fun recentMenu(r: Recent) {
        if (r.number.isBlank()) return
        val items = arrayOf(getString(R.string.call), getString(R.string.message), getString(R.string.copy),
            getString(R.string.add_contact), getString(R.string.block), getString(R.string.delete_entry))
        AlertDialog.Builder(this).setTitle(r.number).setItems(items) { _, i ->
            when (i) {
                0 -> Dialer.call(this, r.number)
                1 -> go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(r.number))))
                2 -> { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", r.number)); toast(R.string.copied) }
                3 -> go(Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, r.number).apply {
                        if (packageInstalled("com.sahl.contacts")) setPackage("com.sahl.contacts")
                    })
                4 -> toast(if (Data.block(this, r.number)) R.string.blocked_done else R.string.make_default)
                5 -> { Data.deleteRecent(this, r); load() }
            }
        }.show()
    }

    // ---------- Contacts ----------
    private fun contactsView() {
        if (contacts.isEmpty()) { content.addView(empty(R.string.no_contacts)); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lv = ListView(this).apply { divider = null }
        val search = EditText(this).apply {
            hint = getString(R.string.search); textSize = 16f; typeface = font; isSingleLine = true
            setTextColor(col(R.color.text)); setHintTextColor(col(R.color.sub))
            background = round(col(R.color.card), 24f); setPadding(px(18), px(12), px(18), px(12))
        }
        fun refresh(q: String) {
            val words = Norm.text(q).split(' ').filter { it.isNotBlank() }
            val dq = Norm.digits(q)
            val list = contacts.filter { e ->
                words.isEmpty() || words.all { e.key.contains(it) } || (dq.length >= 3 && e.digits.contains(dq))
            }.let { l -> if (words.isEmpty()) l.filter { it.starred } + l else l }
            lv.adapter = EntryAdapter(list) { e -> Dialer.call(this, e.number) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString() ?: "") }
        })
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(12), px(10), px(12), px(4)) })
        box.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(box)
        refresh("")
    }

    // ---------- Shared rows ----------
    private inner class EntryAdapter(val list: List<Entry>, val tap: (Entry) -> Unit) : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(p: Int) = list[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val e = list[p]
            return row(e.name + if (e.starred) " ★" else "", e.number, col(R.color.text), e.name, e.photo, { tap(e) }) {
                go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(e.number))))
            }
        }
    }

    private fun row(title: String, sub: String, color: Int, avatarName: String, photo: String?,
                    tap: () -> Unit, long: () -> Unit): View {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(9), px(16), px(9))
            setOnClickListener { tap() }; setOnLongClickListener { long(); true }
        }
        val (f, iv, letter) = avatar(42)
        f.background = oval(avatarColor(avatarName))
        letter.text = Norm.section(avatarName).let { if (it == "#") "👤" else it }
        Thumbs.load(this, photo, iv, letter)
        r.addView(f)
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, 0, 0) }
        t.addView(text(title, 16f, color).apply { maxLines = 1 })
        t.addView(text(sub, 13f, col(R.color.sub)).apply { maxLines = 1 })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return r
    }

    private fun empty(id: Int) = text(getString(id), 15f, col(R.color.sub)).apply {
        gravity = Gravity.CENTER; layoutParams = FrameLayout.LayoutParams(-1, -1)
    }

    private fun go(i: Intent) { try { startActivity(i) } catch (e: Exception) { } }
    private fun packageInstalled(p: String) = try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }
}
