package com.sahl.phone

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class InCallActivity : SahlActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val white = 0xFFFFFFFF.toInt()
    private val soft = 0xBFFFFFFF.toInt()
    private lateinit var nameTv: TextView
    private lateinit var numberTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var factsTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var prayerTv: TextView
    private lateinit var warnTv: TextView
    private lateinit var heldTv: TextView
    private lateinit var extraRow: LinearLayout
    private lateinit var avatarLetter: TextView
    private lateinit var avatarImg: ImageView
    private lateinit var avatarBox: FrameLayout
    private lateinit var ringBox: LinearLayout
    private lateinit var activeBox: LinearLayout
    private lateinit var padBox: LinearLayout
    private lateinit var dtmfTv: TextView
    private val toggles = HashMap<String, FrameLayout>()
    private var shownNumber: String? = null
    private var askedSim: Call? = null
    private var prox: PowerManager.WakeLock? = null
    private var dialingSince = 0L
    private var dialingCall: Call? = null
    private var visible = false

    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable { override fun run() { renderStatus(); main.postDelayed(this, 1000) } }
    private val finishTask = Runnable {
        if (CallManager.primary() == null) finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.listeners.add(listener)
        if (CallManager.primary() == null) { finishAndRemoveTask(); return }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        systemBars(col(R.color.w_right), false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(col(R.color.w_left), col(R.color.w_right)))
            setPadding(px(24), px(48), px(24), px(36))
        }
        prayerTv = pill(getString(R.string.silenced_prayer)).apply { visibility = View.GONE }
        root.addView(prayerTv, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(16) })

        val (f, iv, letter) = avatar(120)
        avatarBox = f; avatarImg = iv; avatarLetter = letter
        root.addView(f, LinearLayout.LayoutParams(px(120), px(120)))
        nameTv = text("", 30f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, px(18), 0, 0); maxLines = 2 }
        numberTv = text("", 16f, soft).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        statusTv = text("", 16f, col(R.color.w_gold), true).apply { gravity = Gravity.CENTER; setPadding(0, px(8), 0, 0) }
        factsTv = text("", 13f, soft).apply { gravity = Gravity.CENTER; setPadding(0, px(10), 0, 0) }
        noteTv = pill("").apply { visibility = View.GONE; maxLines = 3 }
        heldTv = pill("").apply { visibility = View.GONE }
        root.addView(heldTv, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = px(10) })
        root.addView(nameTv); root.addView(numberTv); root.addView(statusTv); root.addView(factsTv)
        root.addView(noteTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        warnTv = pill("").apply { visibility = View.GONE; maxLines = 3 }
        root.addView(warnTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })

        // DTMF keypad (hidden until asked)
        dtmfTv = text("", 24f, white).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        padBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        padBox.addView(dtmfTv)
        for (row in listOf("123", "456", "789", "*0#")) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (ch in row) r.addView(text(ch.toString(), 26f, white).apply {
                gravity = Gravity.CENTER; background = oval(0x1FFFFFFF)
                setOnClickListener {
                    CallManager.primary()?.let { c -> c.playDtmfTone(ch); main.postDelayed({ c.stopDtmfTone() }, 150) }
                    dtmfTv.append(ch.toString())
                }
            }.press(circle = true), LinearLayout.LayoutParams(px(64), px(64)).apply { setMargins(px(10), px(5), px(10), px(5)) })
            padBox.addView(r)
        }
        padBox.addView(text(getString(R.string.hide_keypad), 15f, col(R.color.w_gold), true).apply {
            gravity = Gravity.CENTER; setPadding(0, px(10), 0, px(4)); setOnClickListener { showPad(false) }
        }.press(16f), LinearLayout.LayoutParams(-1, -2))
        root.addView(padBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Active-call controls
        activeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val grid = LinearLayout(this)
        fun toggle(key: String, ic: Int, label: Int, go: () -> Unit) {
            val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(8), 0, px(8)) }
            val btn = iconButton(ic, 0x26FFFFFF, white, 60, 26) { go() }
            b.addView(btn)
            b.addView(text(getString(label), 12f, soft).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
            toggles[key] = btn
            grid.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        toggle("mute", R.drawable.ic_mic, R.string.mute) {
            CallManager.service?.setMuted(CallManager.audio?.isMuted != true)
        }
        toggle("speaker", R.drawable.ic_volume, R.string.speaker) {
            val route = CallManager.audio?.route ?: CallAudioState.ROUTE_EARPIECE
            CallManager.service?.setAudioRoute(if (route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        }
        toggle("bt", R.drawable.ic_bluetooth, R.string.bluetooth) {
            val route = CallManager.audio?.route ?: 0
            CallManager.service?.setAudioRoute(if (route == CallAudioState.ROUTE_BLUETOOTH) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_BLUETOOTH)
        }
        toggle("hold", R.drawable.ic_pause, R.string.hold) {
            val c = CallManager.primary()
            if (c != null) { if (c.st() == Call.STATE_HOLDING) c.unhold() else c.hold() }
        }
        toggle("pad", R.drawable.ic_dialpad, R.string.keypad) { showPad(padBox.visibility != View.VISIBLE) }
        activeBox.addView(grid, LinearLayout.LayoutParams(-1, -2))
        // Second row: add call · swap · merge (swap/merge only with two calls)
        extraRow = LinearLayout(this)
        grid2 = extraRow
        toggle2("add", R.drawable.ic_add, R.string.add_call) {
            startActivity(android.content.Intent(this, MainActivity::class.java).putExtra("tab", 0)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        toggle2("swap", R.drawable.ic_history, R.string.swap) {
            val active = CallManager.calls.firstOrNull { it.st() == Call.STATE_ACTIVE }
            val held = CallManager.calls.firstOrNull { it.st() == Call.STATE_HOLDING }
            if (held != null) { active?.hold(); held.unhold() }
        }
        toggle2("merge", R.drawable.ic_people, R.string.merge) {
            val c = CallManager.calls.firstOrNull { it.st() == Call.STATE_ACTIVE } ?: CallManager.primary()
            val other = c?.conferenceableCalls?.firstOrNull()
            if (c != null && other != null) c.conference(other) else c?.mergeConference()
        }
        activeBox.addView(extraRow, LinearLayout.LayoutParams(-1, -2))
        activeBox.addView(bigButton(R.drawable.ic_call_end, col(R.color.danger), R.string.end) { CallManager.primary()?.disconnect() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(22) })
        root.addView(activeBox, LinearLayout.LayoutParams(-1, -2))

        // Ringing controls: reply · remind, then decline · answer
        ringBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val extra = LinearLayout(this).apply { gravity = Gravity.CENTER }
        extra.addView(smallAction(R.drawable.ic_chat, R.string.reply) { replyAndDecline() })
        extra.addView(smallAction(R.drawable.ic_bell, R.string.remind_me) { remindAndDecline() })
        ringBox.addView(extra, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(26) })
        val big = LinearLayout(this).apply { gravity = Gravity.CENTER }
        big.addView(bigButton(R.drawable.ic_call_end, col(R.color.danger), R.string.decline) { CallManager.primary()?.reject(false, null) },
            LinearLayout.LayoutParams(0, -2, 1f))
        big.addView(bigButton(R.drawable.ic_call, col(R.color.ok), R.string.answer) {
            CallManager.primary()?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ringBox.addView(big, LinearLayout.LayoutParams(-1, -2))
        root.addView(ringBox, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "sahl:prox").apply { setReferenceCounted(false) }
    }

    private var grid2: LinearLayout? = null
    private fun toggle2(key: String, ic: Int, label: Int, go: () -> Unit) {
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6)) }
        val btn = iconButton(ic, 0x26FFFFFF, white, 52, 22) { go() }
        b.addView(btn)
        b.addView(text(getString(label), 12f, soft).apply { gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0) })
        toggles[key] = btn
        grid2?.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** Keypad on: hide the photo and extra info so everything fits; off: bring them back. */
    private fun showPad(on: Boolean) {
        padBox.visibility = if (on) View.VISIBLE else View.GONE
        val info = if (on) View.GONE else View.VISIBLE
        avatarBox.visibility = info; factsTv.visibility = info
        if (on) { noteTv.visibility = View.GONE; warnTv.visibility = View.GONE }
        render()
    }

    @Deprecated("Back")
    override fun onBackPressed() {
        if (padBox.visibility == View.VISIBLE) showPad(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun pill(s: String) = text(s, 13f, white).apply {
        background = round(0x26FFFFFF, 16f); setPadding(px(14), px(7), px(14), px(7)); gravity = Gravity.CENTER
    }

    private fun bigButton(ic: Int, color: Int, label: Int, go: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(iconButton(ic, color, white, 76, 32, go).apply { elevation = 4f * dp })
        addView(text(getString(label), 13f, soft).apply { gravity = Gravity.CENTER; setPadding(0, px(8), 0, 0) })
    }

    private fun smallAction(ic: Int, label: Int, go: () -> Unit): LinearLayout {
        val v = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(0x26FFFFFF, 22f)
            setPadding(px(16), px(10), px(18), px(10))
            addView(icon(ic, white, 18))
            addView(text(getString(label), 14f, white).apply { setPadding(px(8), 0, 0, 0) })
            setOnClickListener { go() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(px(6), 0, px(6), 0) }
        }
        return v.press(22f)
    }

    private fun replyAndDecline() {
        val call = CallManager.primary() ?: return
        val items = QuickReplies.list(this)
        AlertDialog.Builder(this).setTitle(R.string.reply)
            .setItems(items.toTypedArray()) { _, i -> call.reject(true, items[i]) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun remindAndDecline() {
        val call = CallManager.primary() ?: return
        val num = call.number()
        val labels = arrayOf(getString(R.string.in_minutes, 15), getString(R.string.in_hour), getString(R.string.in_hours, 2))
        val mins = intArrayOf(15, 60, 120)
        AlertDialog.Builder(this).setTitle(R.string.remind_me)
            .setItems(labels) { _, i ->
                if (num.isNotBlank()) Reminders.schedule(this, num, Lookup.caller(this, num).name, mins[i])
                call.reject(false, null)
                toast(R.string.reminder_set)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (CallManager.primary() == null) { finishAndRemoveTask(); return }
        main.post(tick)
        render()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        main.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.listeners.remove(listener)
        main.removeCallbacks(finishTask)
        main.removeCallbacks(tick)
        prox?.release()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val call = CallManager.primary()
        if (call == null) {
            val busy = CallManager.lastDisconnectCause == android.telecom.DisconnectCause.BUSY
            statusTv.text = getString(if (busy) R.string.busy else R.string.ended)
            prox?.release()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            main.removeCallbacks(finishTask)
            if (!visible) finishAndRemoveTask() else main.postDelayed(finishTask, 900)
            return
        }
        main.removeCallbacks(finishTask)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val num = call.number()
        val display = Lookup.caller(this, num).name ?: call.displayName()
        if (num != shownNumber || (display != null && nameTv.text.toString() != display)) {
            shownNumber = num
            val who = Lookup.caller(this, num)
            val showName = who.name ?: call.displayName()
            nameTv.text = showName ?: num.ifBlank { getString(R.string.private_number) }
            val country = if (num.isNotBlank()) Countries.of(num, Sahl.countryCode(this), rtl) else null
            numberTv.text = listOfNotNull(if (showName != null) num else null, country).joinToString("  ·  ")
            avatarBox.background = oval(avatarColor(nameTv.text.toString()))
            avatarLetter.text = Norm.section(nameTv.text.toString()).let { if (it == "#") "" else it }
            Thumbs.load(this, who.photo, avatarImg, avatarLetter)
            loadFacts(num)
        }
        val st = call.st()
        val ringing = st == Call.STATE_RINGING
        ringBox.visibility = if (ringing) View.VISIBLE else View.GONE
        activeBox.visibility = if (ringing) View.GONE else View.VISIBLE
        prayerTv.visibility = if (ringing && PrayerSilence.silenced === call) View.VISIBLE else View.GONE
        if (st == Call.STATE_SELECT_PHONE_ACCOUNT && askedSim !== call) { askedSim = call; chooseSim(call) }

        val a = CallManager.audio
        fun on(key: String, active: Boolean) {
            val b = toggles[key] ?: return
            b.background = oval(if (active) white else 0x26FFFFFF)
            (b.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(if (active) col(R.color.w_right) else white)
        }
        on("mute", a?.isMuted == true)
        on("speaker", a?.route == CallAudioState.ROUTE_SPEAKER)
        on("bt", a?.route == CallAudioState.ROUTE_BLUETOOTH)
        on("hold", st == Call.STATE_HOLDING)
        on("pad", padBox.visibility == View.VISIBLE)
        // Two calls: show who is on hold, enable swap/merge
        val others = CallManager.calls.filter { it !== call && it.st() != Call.STATE_DISCONNECTED }
        val two = others.isNotEmpty()
        (toggles["swap"]?.parent as? View)?.visibility = if (two) View.VISIBLE else View.GONE
        (toggles["merge"]?.parent as? View)?.visibility = if (two) View.VISIBLE else View.GONE
        val heldCall = others.firstOrNull { it.st() == Call.STATE_HOLDING }
        if (heldCall != null) {
            val hn = heldCall.number()
            heldTv.text = getString(R.string.on_hold_x, Lookup.caller(this, hn).name ?: hn)
            heldTv.visibility = View.VISIBLE
        } else heldTv.visibility = View.GONE
        (toggles["bt"]?.parent as? View)?.alpha = if (a != null && (a.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0) 1f else 0.35f

        // Screen off near the ear, only for earpiece calls
        val nearEar = st == Call.STATE_ACTIVE && (a == null || a.route == CallAudioState.ROUTE_EARPIECE)
        if (nearEar) prox?.acquire(3 * 3600_000L) else prox?.release()
        renderStatus()
    }

    /** Smart caller info: how often you talk, last call, and the contact's note. */
    private fun loadFacts(num: String) {
        factsTv.text = ""; noteTv.visibility = View.GONE; warnTv.visibility = View.GONE
        if (num.isBlank()) { warnTv.text = getString(R.string.warn_hidden); warnTv.visibility = View.VISIBLE; return }
        Thread {
            val f = Facts.of(this, num)
            val warn = try { Warning.of(this, num, Lookup.caller(this, num).name, f) } catch (e: Exception) { null }
            runOnUiThread {
                if (shownNumber != num) return@runOnUiThread
                if (warn != null) {
                    warnTv.text = warn.first
                    warnTv.background = round(if (warn.second) 0xCCB3261E.toInt() else 0x26FFFFFF, 16f)
                    warnTv.visibility = View.VISIBLE
                }
                factsTv.text = if (f.count == 0) getString(R.string.first_call)
                    else getString(R.string.call_facts, f.count, relDay(f.last))
                val note = f.note
                if (!note.isNullOrBlank()) { noteTv.text = note; noteTv.visibility = View.VISIBLE }
            }
        }.start()
    }

    private fun relDay(t: Long): String {
        val d = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> d.format(DateTimeFormatter.ofPattern("d MMM", resources.configuration.locales[0]))
        }
    }

    private fun renderStatus() {
        val call = CallManager.primary() ?: return
        statusTv.text = when (call.st()) {
            Call.STATE_RINGING -> getString(R.string.incoming)
            // Android has no "other side is ringing" state; after a few seconds of dialing the call is ringing
            Call.STATE_DIALING -> {
                if (dialingSince == 0L || dialingCall !== call) { dialingSince = System.currentTimeMillis(); dialingCall = call }
                getString(if (System.currentTimeMillis() - dialingSince > 3000) R.string.ringing else R.string.calling)
            }
            Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> getString(R.string.connecting)
            Call.STATE_HOLDING -> getString(R.string.on_hold)
            Call.STATE_ACTIVE -> {
                val start = call.details.connectTimeMillis
                val s = if (start > 0) ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0) else 0
                if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
            }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> getString(R.string.ended)
            else -> ""
        }
    }

    private fun chooseSim(call: Call) {
        @Suppress("DEPRECATION")
        val list: List<PhoneAccountHandle> = call.details.intentExtras
            ?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS) ?: emptyList()
        if (list.isEmpty()) return
        val tm = getSystemService(TelecomManager::class.java)
        val labels = list.map { h -> try { tm.getPhoneAccount(h)?.label?.toString() } catch (e: Exception) { null } ?: h.id }
        AlertDialog.Builder(this).setTitle(R.string.choose_sim)
            .setItems(labels.toTypedArray()) { _, i -> call.phoneAccountSelected(list[i], false) }
            .setOnCancelListener { call.disconnect() }
            .show()
    }
}
