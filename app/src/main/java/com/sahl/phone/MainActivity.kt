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
import android.view.MotionEvent
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
    private lateinit var callBar: LinearLayout
    private val callListener: () -> Unit = { renderCallBar() }
    private var contacts: List<Entry> = emptyList()
    private var people: List<Person> = emptyList()
    private var groups: Map<String, Set<Long>> = emptyMap()
    private var members: Map<Long, Set<Long>> = emptyMap()
    private var bdayIds: Set<Long> = emptySet()
    private var hiddenLookups: Set<String> = emptySet()
    private var groupFilter: String? = null
    private var contactQuery = ""
    private var recents: List<Recent> = emptyList()
    private var typed = ""
    private var missedOnly = false
    private var recentQuery = ""
    private var dateFilter = 0                 // 0 any · 1 today · 2 yesterday · 3 last 7 days · 4 last 30 days · 5 picked day
    private var pickedDay: LocalDate? = null
    private var blockedView = false
    private var stopped: List<Guard.Stopped> = emptyList()
    private var renderedMissed = -1
    private var weekHasCalls = false
    private var refreshWeek: (() -> Unit)? = null

    private val perms = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS)

    private fun granted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.app_name), false, listOf(
            R.drawable.ic_tune to { startActivity(Intent(this, SettingsActivity::class.java)) }
        )))
        banner = card().apply { background = round(col(R.color.tonal), 22f); elevation = 0f; visibility = View.GONE }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })
        // "Return to call" bar while a call is going on
        callBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(col(R.color.ok), 22f); visibility = View.GONE
            setPadding(px(16), px(12), px(16), px(12)); elevation = 4f * dp
            setOnClickListener { SahlInCallService.openScreen(this@MainActivity) }
        }
        root.addView(callBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })
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
        renderTabs(); show()          // keypad appears at once — it needs no data
        // Draw first, then do the background chores
        root.post {
            Updater.dailyCheck(this)
            Updater.checkOnStart(this)
            WeeklySummary.schedule(this)
            if (!crashOpen && !granted()) askPermissions()
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); if (::content.isInitialized) show() }

    /** tel: links and "Dial" from other apps open the keypad with the number. */
    private fun handleIntent(i: Intent?) {
        val d = i?.data
        if (d != null && d.scheme == "tel") { typed = d.schemeSpecificPart ?: ""; tab = 0 }
        if (i?.hasExtra("tab") == true) tab = i.getIntExtra("tab", 0)
    }

    override fun onResume() {
        super.onResume()
        renderTabs(); renderCallBar()
        CallManager.listeners.add(callListener)
        content.post { renderBanner(); load() }
        // Opening the app on Recents clears the missed-call notice and badge
        if (tab == 1) markRecentsSeen()
    }

    override fun onPause() { super.onPause(); CallManager.listeners.remove(callListener) }

    private fun renderCallBar() {
        val call = CallManager.primary()
        if (call == null) { callBar.visibility = View.GONE; return }
        callBar.removeAllViews(); callBar.visibility = View.VISIBLE
        callBar.addView(icon(R.drawable.ic_call, 0xFFFFFFFF.toInt(), 22))
        val n = call.number()
        callBar.addView(text(getString(R.string.return_to_call, Lookup.caller(this, n).name ?: call.displayName() ?: n.ifBlank { getString(R.string.private_number) }),
            15f, 0xFFFFFFFF.toInt(), true).apply { setPaddingRelative(px(12), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
        callBar.addView(icon(R.drawable.ic_back, 0xFFFFFFFF.toInt(), 20).apply { rotation = if (rtl) 0f else 180f })
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
            Build.VERSION.SDK_INT >= 34 && !getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() -> {
                msg = R.string.need_fullscreen; btn = R.string.allow
                go = { try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (e: Exception) { } }
            }
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

    private fun markRecentsSeen() {
        val t = maxOf(System.currentTimeMillis(), recents.maxOfOrNull { it.date } ?: 0L)
        Sahl.prefs(this).edit().putLong("recents_seen_at", t).apply()
        Missed.clear(this)
        if (renderedMissed != 0) renderTabs()
    }

    private fun missedCount(list: List<Recent>): Int {
        val since = maxOf(System.currentTimeMillis() - 86_400_000L, Sahl.prefs(this).getLong("recents_seen_at", 0L))
        return list.count { it.type == CallLog.Calls.MISSED_TYPE && it.date > since }
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        val missed = missedCount(recents)
        renderedMissed = missed
        listOf(Triple(R.drawable.ic_dialpad, R.string.keypad, 0), Triple(R.drawable.ic_history, R.string.recents, missed),
            Triple(R.drawable.ic_people, R.string.contacts, 0)).forEachIndexed { i, (ic, label, badge) ->
            val on = i == tab
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setOnClickListener { if (tab != i) { tab = i; if (i == 1) markRecentsSeen(); renderTabs(); show() } }
            }
            val pillBox = FrameLayout(this).apply { background = if (on) round(col(R.color.tonal), 16f) else null }
            pillBox.addView(ImageView(this).apply {
                setImageResource(ic); imageTintList = ColorStateList.valueOf(if (on) col(R.color.emerald) else col(R.color.sub))
            }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
            if (badge > 0) pillBox.addView(text(badge.toString(), 10f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER; background = oval(col(R.color.danger))
            }, FrameLayout.LayoutParams(px(16), px(16), Gravity.TOP or Gravity.END))
            b.addView(pillBox, LinearLayout.LayoutParams(px(64), px(32)))
            b.addView(text(getString(label), 12f, if (on) col(R.color.emerald) else col(R.color.sub), on).apply {
                gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0)
            })
            tabBar.addView(b.press(16f), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    /** Recents are saved so the list shows instantly next time while it refreshes. */
    private fun saveCache(r: List<Recent>) {
        val a = org.json.JSONArray()
        for (x in r.take(80)) a.put(org.json.JSONObject().put("n", x.number).put("nm", x.name ?: "").put("t", x.type).put("d", x.date)
            .put("du", x.duration).put("c", x.count))
        Sahl.prefs(this).edit().putString("recents_cache", a.toString()).apply()
    }

    private fun readCache(): List<Recent> = try {
        val a = org.json.JSONArray(Sahl.prefs(this).getString("recents_cache", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Recent(emptyList(), it.getString("n"), it.getString("nm").ifEmpty { null }, it.getInt("t"), it.getLong("d"), it.getLong("du"), it.getInt("c"))
        }
    } catch (e: Exception) { emptyList() }

    private fun sameRecents(a: List<Recent>, b: List<Recent>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.number != y.number || x.name != y.name || x.type != y.type ||
                x.date != y.date || x.duration != y.duration || x.count != y.count) return false
        }
        return true
    }

    private fun load() {
        if (!granted()) { show(); return }
        if (recents.isEmpty()) {
            val cached = readCache()
            if (cached.isNotEmpty()) {
                recents = cached
                renderTabs()
                if (tab == 1 && !blockedView) {
                    if (refreshRecents != null) refreshRecents?.invoke() else show()
                }
            }
        }
        Thread {
            // Recents first (that's what you look at), with every caller name looked up here — not while scrolling
            val r = try { Data.recents(this) } catch (e: Exception) { emptyList() }
            for (n in r.map { it.number }.distinct()) if (n.isNotBlank()) Lookup.caller(this, n)
            runOnUiThread {
                val changed = r != recents
                val visualChange = !sameRecents(r, recents)
                recents = r
                if (missedCount(r) != renderedMissed) renderTabs()
                if (tab == 1 && !blockedView) {
                    if (refreshRecents != null) {
                        if (changed) refreshRecents?.invoke()
                    } else {
                        show()
                    }
                }
                if (visualChange) {
                    WeeklySummary.invalidate()
                    refreshWeek?.invoke()
                }
            }
            saveCache(r)
            val c = try { Data.contacts(this) } catch (e: Exception) { emptyList() }
            val ppl = try { Data.people(this) } catch (e: Exception) { emptyList() }
            val grp = try { Data.groups(this) } catch (e: Exception) { emptyMap() }
            val mem = try { Data.members(this) } catch (e: Exception) { emptyMap() }
            val bd = try { Data.birthdaysThisMonth(this) } catch (e: Exception) { emptySet() }
            val hid = Data.hiddenLookups(this)
            val g = Guard.log(this, 100)
            runOnUiThread {
                val peopleChanged = ppl != people || grp != groups || mem != members || hid != hiddenLookups
                val stoppedChanged = g != stopped
                contacts = c; people = ppl; groups = grp; members = mem; bdayIds = bd; hiddenLookups = hid; stopped = g
                if (groupFilter != null && !groupFilter!!.startsWith("*") && !groups.containsKey(groupFilter)) groupFilter = null
                when {
                    tab == 0 -> refreshPad()
                    tab == 2 && peopleChanged -> show()
                    tab == 1 && blockedView && stoppedChanged -> show()
                }   // never rebuild the keypad
                MissedWidget.refresh(this)
            }
        }.start()
    }

    private fun show() {
        refreshRecents = null
        refreshWeek = null
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
        val numTv = text(typed, 32f, col(R.color.text)).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR; maxLines = 1; setPadding(px(16), px(2), px(16), 0)
            setOnLongClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                if (!clip.isNullOrBlank()) { typed = clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }; refreshPad() }
                true
            }
        }
        val hint = text("", 13f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(2)) }
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
                k.addView(text(ch.toString(), 26f, col(R.color.text)).apply { gravity = Gravity.CENTER })
                k.addView(text(letters[ch] ?: " ", 9f, col(R.color.sub)).apply { gravity = Gravity.CENTER; letterSpacing = 0.15f })
                r.addView(k, LinearLayout.LayoutParams(px(64), px(64)).apply { setMargins(px(12), px(4), px(12), px(4)) })
            }
            pad.addView(r)
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(6), px(24), px(10)); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(iconButton(R.drawable.ic_call, col(R.color.ok), 0xFFFFFFFF.toInt(), 64, 28) {
            if (typed.isEmpty()) { typed = recents.firstOrNull { r -> r.type == CallLog.Calls.OUTGOING_TYPE }?.number ?: ""; refreshPad() }
            else { val n = typed; typed = ""; refreshPad(); call(n) }
        }.apply { elevation = 4f * dp })
        val back = LinearLayout(this).apply { gravity = Gravity.CENTER }
        back.addView(iconButton(R.drawable.ic_backspace, 0, col(R.color.sub), 52, 22) { typed = typed.dropLast(1); refreshPad() }.apply {
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
        chip(R.string.all, !missedOnly && !blockedView) { missedOnly = false; blockedView = false; show() }
        chip(R.string.missed, missedOnly) { missedOnly = true; blockedView = false; show() }
        markRecentsSeen()
        if (Guard.installed(this)) chip(R.string.stopped, blockedView) { blockedView = true; missedOnly = false; show() }
        box.addView(chips)
        if (blockedView) { stoppedView(box); content.addView(box); return }
        // Date filter chips
        val dates = LinearLayout(this).apply { setPaddingRelative(px(16), 0, px(16), px(6)) }
        fun dchip(label: String, on: Boolean, go: () -> Unit) = dates.addView(text(label, 12f,
            if (on) col(R.color.emerald) else col(R.color.sub), on).apply {
            background = if (on) round(col(R.color.tonal), 14f) else round(0, 14f, col(R.color.line))
            setPadding(px(12), px(6), px(12), px(6)); setOnClickListener { go() }
        }.press(14f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        fun pick(i: Int) { dateFilter = i; show() }
        dchip(getString(R.string.any_time), dateFilter == 0) { pick(0) }
        dchip(getString(R.string.today_t), dateFilter == 1) { pick(1) }
        dchip(getString(R.string.yesterday_t), dateFilter == 2) { pick(2) }
        dchip(getString(R.string.last_7), dateFilter == 3) { pick(3) }
        dchip(getString(R.string.last_30), dateFilter == 4) { pick(4) }
        dchip(if (dateFilter == 5 && pickedDay != null) pickedDay!!.format(DateTimeFormatter.ofPattern("d MMM", resources.configuration.locales[0]))
              else getString(R.string.pick_date), dateFilter == 5) {
            val d = pickedDay ?: LocalDate.now()
            android.app.DatePickerDialog(this, { _, y, m, day -> pickedDay = LocalDate.of(y, m + 1, day); pick(5) },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dates) })
        val (search, searchEt) = searchBox(getString(R.string.search_history)) { q -> recentQuery = q; refreshRecents?.invoke() }
        if (recentQuery.isNotEmpty()) searchEt.setText(recentQuery)
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), 0, px(16), px(6)) })
        val lv = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0); clipToPadding = false; setPadding(0, 0, 0, px(12)) }
        val week = if (!missedOnly && dateFilter == 0) weekCard() else null
        if (week != null) lv.addHeaderView(FrameLayout(this).apply { addView(week, FrameLayout.LayoutParams(-1, -2)) }, null, false)
        box.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(box)
        val adapter = RecentsAdapter(filteredRecents())
        lv.adapter = adapter
        refreshRecents = {
            val next = filteredRecents()
            val visualChange = !sameRecents(next, adapter.list)
            adapter.list = next
            if (visualChange) adapter.notifyDataSetChanged()
            week?.visibility = if (recentQuery.isBlank() && weekHasCalls) View.VISIBLE else View.GONE
        }
        week?.visibility = if (recentQuery.isBlank() && weekHasCalls) View.VISIBLE else View.GONE
    }

    private var refreshRecents: (() -> Unit)? = null

    /** Recents filtered by chip and by search (name, number or a note you wrote on a call). */
    private fun filteredRecents(): List<Recent> {
        val today = LocalDate.now()
        val base = recents.filter { !missedOnly || it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
            .filter { r ->
                val d = dayOf(r.date)
                when (dateFilter) {
                    1 -> d == today
                    2 -> d == today.minusDays(1)
                    3 -> !d.isBefore(today.minusDays(6))
                    4 -> !d.isBefore(today.minusDays(29))
                    5 -> d == pickedDay
                    else -> true
                }
            }
        val q = recentQuery.trim()
        if (q.isEmpty()) return base
        val words = Norm.text(q).split(' ').filter { it.isNotBlank() }
        val dq = Norm.digits(q)
        val noteKeys = CallNotes.numbersMatching(this, q)
        return base.filter { r ->
            val name = Norm.text(Lookup.caller(this, r.number).name ?: r.name ?: "")
            words.all { name.contains(it) } || (dq.length >= 3 && Norm.digits(r.number).contains(dq)) || Norm.phoneKey(r.number) in noteKeys
        }
    }

    private inner class RecentsAdapter(var list: List<Recent>) : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(p: Int) = list[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val r = list[p]
            val outer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val day = dayOf(r.date)
            if (p == 0 || dayOf(list[p - 1].date) != day) outer.addView(text(dayLabel(r.date), 13f, col(R.color.emerald), true).apply {
                setPaddingRelative(px(22), px(14), px(22), px(4))
            })
            val name = Lookup.caller(this@MainActivity, r.number).name ?: r.name
            val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
            val unanswered = r.type == CallLog.Calls.OUTGOING_TYPE && r.duration == 0L
            val (ic, color) = when (r.type) {
                CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_arrow_out to col(if (unanswered) R.color.gold else R.color.emerald)
                CallLog.Calls.BLOCKED_TYPE -> R.drawable.ic_block to col(R.color.sub)
                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_call_missed to col(R.color.danger)
                else -> R.drawable.ic_arrow_in to col(R.color.ok)
            }
            val title = (name ?: r.number.ifBlank { getString(R.string.private_number) }) + if (r.count > 1) "  (${r.count})" else ""
            val subText = (if (name != null) r.number + "  ·  " else (Countries.of(r.number, Sahl.countryCode(this@MainActivity), rtl)?.let { "$it  ·  " } ?: "")) +
                time(r.date) + (if (unanswered) "  ·  " + getString(R.string.didnt_answer) else "")
            val row = personRow(title, subText, if (missed) col(R.color.danger) else col(R.color.text), name ?: "#", null, ic, color)
            if (r.number.isNotBlank()) row.addView(iconButton(R.drawable.ic_call, 0, col(R.color.emerald), 44, 22) { call(r.number) })
            row.setOnClickListener { if (r.number.isNotBlank()) startActivity(Intent(this@MainActivity, CallDetailActivity::class.java).putExtra("number", r.number)) }
            row.setOnLongClickListener { recentMenu(r, name); true }
            outer.addView(swipe(row, r.number))
            return outer
        }
    }

    /** "This week" card at the top of Recents. */
    private fun weekCard(): View {
        val box = LinearLayout(this).apply { setPaddingRelative(px(16), px(4), px(16), px(4)); visibility = View.GONE }
        val c = card().apply { background = round(col(R.color.tonal), 22f); elevation = 0f }
        box.addView(c, LinearLayout.LayoutParams(-1, -2))
        fun bind(w: Week) {
            weekHasCalls = w.calls > 0
            if (w.calls == 0) { box.visibility = View.GONE; return }
            c.removeAllViews()
            c.addView(label(getString(R.string.this_week)))
            c.addView(text(getString(R.string.weekly_line, w.calls, (w.talk / 60).toInt()), 15f, col(R.color.text), true))
            if (w.top.isNotEmpty()) c.addView(text(getString(R.string.most_talked) + " " +
                w.top.joinToString(" · ") { "${it.first.substringBefore(' ')} (${it.second})" }, 13f, col(R.color.sub)).apply { setPadding(0, px(4), 0, 0) })
            if (w.missedNotReturned.isNotEmpty()) {
                c.addView(text(getString(R.string.weekly_not_returned, w.missedNotReturned.size), 13f, col(R.color.danger), true).apply { setPadding(0, px(8), 0, px(2)) })
                val row = LinearLayout(this)
                for (n in w.missedNotReturned.take(4)) {
                    val nm = (Lookup.caller(this, n).name ?: n).substringBefore(' ')
                    row.addView(text(nm, 13f, col(R.color.emerald), true).apply {
                        background = round(col(R.color.card), 16f); setPadding(px(12), px(6), px(12), px(6))
                        setOnClickListener { call(n) }
                    }.press(16f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
                }
                c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
            box.visibility = if (recentQuery.isBlank()) View.VISIBLE else View.GONE
        }
        fun fetch() {
            Thread {
                val w = WeeklySummary.compute(this)
                runOnUiThread { bind(w) }
            }.start()
        }
        refreshWeek = { fetch() }
        val cached = WeeklySummary.peek()
        if (cached != null) bind(cached) else fetch()
        return box
    }

    /** Calls that Call Guard (Sahl Tools) blocked or silenced. */
    private fun stoppedView(box: LinearLayout) {
        if (stopped.isEmpty()) { box.addView(empty(R.drawable.ic_block, R.string.no_stopped), LinearLayout.LayoutParams(-1, 0, 1f)); return }
        val lv = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0) }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = stopped.size
            override fun getItem(p: Int) = stopped[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val g = stopped[p]
                val title = g.number.ifBlank { getString(R.string.private_number) }
                val sub = g.label + "  ·  " + dayLabel(g.time) + " " + time(g.time)
                val row = personRow(title, sub, col(R.color.text), "#", null,
                    if (g.action == Guard.BLOCK) R.drawable.ic_block else R.drawable.ic_volume, col(R.color.sub))
                row.setOnClickListener { if (g.number.isNotBlank()) startActivity(Intent(this@MainActivity, CallDetailActivity::class.java).putExtra("number", g.number)) }
                return row
            }
        }
        box.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addView(text(getString(R.string.guard_settings), 14f, col(R.color.emerald), true).apply {
            gravity = Gravity.CENTER; setPadding(0, px(12), 0, px(12)); setOnClickListener { Guard.openSettings(this@MainActivity) }
        }.press(14f))
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
            Triple(R.drawable.ic_history, getString(R.string.call_details)) {
                startActivity(Intent(this, CallDetailActivity::class.java).putExtra("number", r.number))
            },
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
        items.add(Triple(R.drawable.ic_block, getString(R.string.block)) { toast(if (Guard.block(this, r.number)) R.string.blocked_done else R.string.make_default) })
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

    // ---------- Contacts (mirrors Sahl Contacts criteria & behaviors) ----------
    private fun whatsapp(n: String) {
        val d = Norm.international(n, Sahl.countryCode(this))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { packageInstalled(it) }
        if (pkg == null) { toast(R.string.no_whatsapp); return }
        go(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$d")).setPackage(pkg))
    }

    private fun shareContact(lookup: String) {
        if (lookup.isEmpty()) return
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup)
        val i = Intent(Intent.ACTION_SEND).setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, getString(R.string.share))) } catch (e: Exception) { }
    }

    private fun pickNumber(title: String, iconRes: Int, numbers: List<String>, go: (String) -> Unit) {
        if (numbers.size == 1) { go(numbers[0]); return }
        sheet(title, numbers.map { num -> Triple(iconRes, num) { go(num) } })
    }

    private fun openContact(p: Person) {
        if (packageInstalled("com.sahl.contacts")) {
            go(Intent("com.sahl.contacts.action.OPEN").setPackage("com.sahl.contacts").putExtra("id", p.id))
            return
        }
        when {
            p.numbers.size == 1 -> call(p.numbers[0])
            p.numbers.size > 1 -> pickNumber(p.name, R.drawable.ic_call, p.numbers) { call(it) }
            else -> go(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(p.id, p.lookup)))
        }
    }

    private fun contactActions(p: Person) {
        val items = ArrayList<Triple<Int, String, () -> Unit>>()
        if (p.numbers.isNotEmpty()) {
            items.add(Triple(R.drawable.ic_call, getString(R.string.call)) { pickNumber(getString(R.string.call), R.drawable.ic_call, p.numbers) { call(it) } })
            items.add(Triple(R.drawable.ic_chat, getString(R.string.message)) { pickNumber(getString(R.string.message), R.drawable.ic_chat, p.numbers) { sms(it) } })
            items.add(Triple(R.drawable.ic_whatsapp, getString(R.string.whatsapp)) { pickNumber(getString(R.string.whatsapp), R.drawable.ic_whatsapp, p.numbers) { whatsapp(it) } })
            items.add(Triple(R.drawable.ic_bell, getString(R.string.remind_me)) { pickNumber(getString(R.string.remind_me), R.drawable.ic_bell, p.numbers) { remind(it, p.name) } })
            items.add(Triple(R.drawable.ic_copy, getString(R.string.copy)) {
                pickNumber(getString(R.string.copy), R.drawable.ic_copy, p.numbers) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("number", it)); toast(R.string.copied)
                }
            })
        }
        items.add(Triple(R.drawable.ic_edit, getString(R.string.edit_contact)) {
            go(Intent(Intent.ACTION_EDIT, ContactsContract.Contacts.getLookupUri(p.id, p.lookup)))
        })
        items.add(Triple(R.drawable.ic_share, getString(R.string.share)) { shareContact(p.lookup) })
        sheet(p.name, items)
    }

    private fun contactsView() {
        val visibleAll = people.filter { it.lookup !in hiddenLookups }
        if (visibleAll.isEmpty()) { content.addView(empty(R.drawable.ic_people, R.string.no_contacts)); return }

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(this).apply { setPadding(px(12), px(4), px(12), px(4)) }
        val body = FrameLayout(this)
        val lv = ListView(this).apply {
            divider = null; clipToPadding = false; selector = android.graphics.drawable.ColorDrawable(0)
            setPadding(0, 0, 0, px(24)); isVerticalScrollBarEnabled = false
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lv.addHeaderView(top, null, false)
        val count = text("", 12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(14), 0, px(14)) }
        lv.addFooterView(count, null, false)

        val emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            addView(icon(R.drawable.ic_search, col(R.color.line), 64))
            addView(text(getString(R.string.no_results), 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
        }
        val index = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val bubble = text("", 34f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER; background = oval(col(R.color.emerald)); visibility = View.GONE; elevation = 6f * dp
        }
        body.addView(lv, FrameLayout.LayoutParams(-1, -1))
        body.addView(emptyBox, FrameLayout.LayoutParams(-1, -1))
        body.addView(index, FrameLayout.LayoutParams(px(26), -1, Gravity.END).apply { topMargin = px(8); bottomMargin = px(16) })
        body.addView(bubble, FrameLayout.LayoutParams(px(76), px(76), Gravity.CENTER))

        val talkedKeys = recents.mapNotNull { it.number.takeIf(String::isNotBlank)?.let(Norm::phoneKey) }.toSet()
        val byKey = HashMap<String, Person>()
        for (p in visibleAll) for (n in p.numbers) byKey[Norm.phoneKey(n)] = p
        val recentPeople = recents.mapNotNull { byKey[Norm.phoneKey(it.number)] }.distinctBy { it.id }.take(12)

        fun smartGroups() = listOf(
            "*bday" to R.string.sg_birthdays, "*intl" to R.string.sg_international, "*quiet" to R.string.sg_quiet,
            "*nophoto" to R.string.sg_no_photo, "*nonumber" to R.string.sg_no_number)

        fun smartMatch(key: String, p: Person): Boolean = when (key) {
            "*bday" -> p.id in bdayIds
            "*intl" -> p.numbers.any { Countries.of(it, Sahl.countryCode(this), false) != null }
            "*quiet" -> talkedKeys.isNotEmpty() && p.numbers.isNotEmpty() && p.numbers.none { Norm.phoneKey(it) in talkedKeys } && p.starred
            "*nophoto" -> p.photo == null
            "*nonumber" -> p.numbers.isEmpty()
            else -> false
        }

        fun buildTop() {
            top.removeAllViews()
            fun strip(title: Int, list: List<Person>) {
                if (list.isEmpty()) return
                top.addView(label(getString(title)).apply { setPaddingRelative(px(20), px(10), 0, px(6)) })
                val row = LinearLayout(this).apply { setPadding(px(12), 0, px(12), px(6)) }
                for (p in list) {
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(px(6), px(6), px(6), px(6))
                        setOnClickListener { openContact(p) }
                        setOnLongClickListener { contactActions(p); true }
                    }.press(18f)
                    val (f, iv, letter) = avatar(58)
                    f.background = oval(avatarColor(p.name)); letter.text = initial(p.name)
                    if (letter.text.isEmpty()) f.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_people); imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
                    }, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
                    Thumbs.load(this, p.photo, iv, letter)
                    cell.addView(f)
                    cell.addView(text(p.name.substringBefore(' '), 12f).apply {
                        maxLines = 1; gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(px(70), -2))
                    row.addView(cell)
                }
                top.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
            strip(R.string.favorites, visibleAll.filter { it.starred })
            strip(R.string.recent, recentPeople)
        }
        buildTop()

        var shown: List<Person> = emptyList()
        val adapter = PeopleAdapter { shown }
        lv.adapter = adapter

        fun buildIndex() {
            index.removeAllViews()
            if (contactQuery.isNotBlank()) return
            val starts = LinkedHashMap<String, Int>()
            shown.forEachIndexed { i, p -> if (p.section !in starts) starts[p.section] = i }
            if (starts.size < 2) return
            val keys = starts.keys.toList()
            val step = Math.ceil(keys.size / 24.0).toInt().coerceAtLeast(1)
            val shownKeys = keys.filterIndexed { i, _ -> i % step == 0 }
            for (k in shownKeys) index.addView(text(k, 11f, col(R.color.sub), true).apply {
                gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            index.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                    val i = ((e.y / v.height) * keys.size).toInt().coerceIn(0, keys.size - 1)
                    lv.setSelection(starts[keys[i]]!! + lv.headerViewsCount)
                    bubble.text = keys[i]; bubble.visibility = View.VISIBLE
                } else bubble.visibility = View.GONE
                true
            }
        }

        fun filter() {
            val words = Norm.text(contactQuery).split(' ').filter { it.isNotBlank() }
            val dq = Norm.digits(contactQuery)
            val gfKey = groupFilter
            val smart = gfKey != null && gfKey.startsWith("*")
            val inGroup = if (smart) null else gfKey?.let { t -> groups[t]?.flatMap { members[it] ?: emptySet() }?.toSet() ?: emptySet() }
            shown = visibleAll.filter { p ->
                (inGroup == null || p.id in inGroup) && (!smart || smartMatch(gfKey!!, p)) &&
                    (words.isEmpty() || words.all { w -> p.key.contains(w) } || (dq.length >= 3 && p.digits.contains(dq)))
            }
            val showTop = contactQuery.isBlank() && groupFilter == null
            for (i in 0 until top.childCount) top.getChildAt(i).visibility = if (showTop) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            emptyBox.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            count.text = getString(R.string.contacts_count, shown.size)
            buildIndex()
        }

        fun buildChips() {
            chips.removeAllViews()
            fun chip(lbl: String, key: String?) {
                val on = groupFilter == key
                chips.addView(text(lbl, 13f, if (on) 0xFFFFFFFF.toInt() else col(R.color.text), on).apply {
                    background = round(if (on) col(R.color.emerald) else col(R.color.tonal), 18f)
                    setPadding(px(16), px(8), px(16), px(8))
                    setOnClickListener { groupFilter = key; buildChips(); filter() }
                }.press(18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
            }
            chip(getString(R.string.all), null)
            for (t in groups.keys) chip(t, t)
            for ((key, lbl) in smartGroups()) if (visibleAll.any { smartMatch(key, it) }) chip("✦ " + getString(lbl), key)
        }
        buildChips()

        lv.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {
                val headerOnScreen = first == 0 && top.height > px(40) && top.childCount > 0 && top.getChildAt(0).visibility == View.VISIBLE
                index.visibility = if (headerOnScreen) View.INVISIBLE else View.VISIBLE
            }
        })

        val (search, et) = searchBox(getString(R.string.search)) { contactQuery = it; filter() }
        if (contactQuery.isNotEmpty()) et.setText(contactQuery)
        box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(2), px(16), px(6)) })
        box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        box.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(box)
        filter()
    }

    private inner class PeopleAdapter(val getList: () -> List<Person>) : BaseAdapter() {
        override fun getCount() = getList().size
        override fun getItem(position: Int) = getList()[position]
        override fun getItemId(position: Int) = getList()[position].id

        inner class Holder(val head: TextView, val swipe: SwipeRow, val av: FrameLayout, val iv: ImageView, val letter: TextView,
                           val name: TextView, val sub: TextView, val star: ImageView)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val list = getList()
            val p = list[position]
            val outer: LinearLayout
            val h: Holder
            val reuse = convertView?.tag as? Holder
            if (reuse == null) {
                outer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val head = text("", 13f, col(R.color.emerald), true).apply { setPaddingRelative(px(22), px(14), px(22), px(4)) }
                val row = LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(px(18), px(9), px(34), px(9))
                }
                val (f, iv, letter) = avatar(46)
                row.addView(f)
                val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, px(8), 0) }
                val name = text("", 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val sub = text("", 13f, col(R.color.sub)).apply { maxLines = 1; textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
                texts.addView(name); texts.addView(sub)
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                val star = icon(R.drawable.ic_star, col(R.color.gold), 18)
                row.addView(star)
                lateinit var swipe: SwipeRow
                swipe = SwipeRow(this@MainActivity, row.press(0f), col(R.color.bg),
                    R.drawable.ic_call, col(R.color.ok), { (swipe.tag as? Person)?.numbers?.firstOrNull()?.let { call(it) } },
                    R.drawable.ic_chat, col(R.color.emerald), { (swipe.tag as? Person)?.numbers?.firstOrNull()?.let { sms(it) } })
                outer.addView(head); outer.addView(swipe)
                h = Holder(head, swipe, f, iv, letter, name, sub, star)
                outer.tag = h
            } else { outer = convertView as LinearLayout; h = reuse }

            h.swipe.tag = p
            h.swipe.content.setOnClickListener { openContact(p) }
            h.swipe.content.setOnLongClickListener { contactActions(p); true }
            val showHead = contactQuery.isBlank() && (position == 0 || list[position - 1].section != p.section)
            h.head.visibility = if (showHead) View.VISIBLE else View.GONE
            h.head.text = p.section
            h.av.background = oval(avatarColor(p.name))
            h.letter.text = initial(p.name)
            h.name.text = p.name
            h.sub.text = p.numbers.firstOrNull() ?: ""
            h.sub.visibility = if (p.numbers.isEmpty()) View.GONE else View.VISIBLE
            h.star.visibility = if (p.starred) View.VISIBLE else View.GONE
            Thumbs.load(this@MainActivity, p.photo, h.iv, h.letter)
            return outer
        }
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
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(px(18), px(9), px(10), px(9)) }.press(0f)
        val (f, iv, letter) = avatar(46)
        f.background = oval(avatarColor(avatarName))
        letter.text = initial(avatarName)
        if (letter.text.isEmpty()) f.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_people); imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
        }, FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
        Thumbs.load(this, photo, iv, letter)
        r.addView(f)
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, px(8), 0) }
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
