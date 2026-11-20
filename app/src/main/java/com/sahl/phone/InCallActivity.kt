package com.sahl.phone

import android.app.AlertDialog
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

class InCallActivity : SahlActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nameTv: TextView
    private lateinit var numberTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var avatarLetter: TextView
    private lateinit var avatarImg: ImageView
    private lateinit var avatarBox: FrameLayout
    private lateinit var ringBox: LinearLayout
    private lateinit var activeBox: LinearLayout
    private lateinit var padBox: LinearLayout
    private lateinit var dtmfTv: TextView
    private val toggles = HashMap<String, TextView>()
    private var shownNumber: String? = null
    private var askedSim: Call? = null
    private var prox: PowerManager.WakeLock? = null

    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable { override fun run() { renderStatus(); main.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = col(R.color.w_right); window.navigationBarColor = col(R.color.w_right)

        val white = 0xFFFFFFFF.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(col(R.color.w_right)); setPadding(px(24), px(56), px(24), px(40))
        }
        val (f, iv, letter) = avatar(112)
        avatarBox = f; avatarImg = iv; avatarLetter = letter
        root.addView(f, LinearLayout.LayoutParams(px(112), px(112)))
        nameTv = text("", 28f, white, true).apply { gravity = Gravity.CENTER; setPadding(0, px(18), 0, 0) }
        numberTv = text("", 16f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        statusTv = text("", 15f, col(R.color.w_gold)).apply { gravity = Gravity.CENTER; setPadding(0, px(8), 0, 0) }
        root.addView(nameTv); root.addView(numberTv); root.addView(statusTv)

        // DTMF keypad (hidden until asked)
        dtmfTv = text("", 22f, white).apply { gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR }
        padBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        padBox.addView(dtmfTv)
        for (row in listOf("123", "456", "789", "*0#")) {
            val r = LinearLayout(this)
            for (ch in row) r.addView(text(ch.toString(), 26f, white).apply {
                gravity = Gravity.CENTER; setPadding(0, px(10), 0, px(10))
                setOnClickListener {
                    CallManager.primary()?.let { c -> c.playDtmfTone(ch); main.postDelayed({ c.stopDtmfTone() }, 150) }
                    dtmfTv.append(ch.toString())
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            padBox.addView(r)
        }
        root.addView(padBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Active-call controls
        activeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val grid = LinearLayout(this)
        fun toggle(key: String, icon: String, label: Int, go: () -> Unit) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(8), 0, px(8))
                setOnClickListener { go() }
            }
            val ic = text(icon, 24f, white).apply {
                gravity = Gravity.CENTER; background = oval(0x22FFFFFF)
            }
            b.addView(ic, LinearLayout.LayoutParams(px(58), px(58)))
            b.addView(text(getString(label), 12f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0) })
            toggles[key] = ic
            grid.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        toggle("mute", "🎙", R.string.mute) {
            val muted = CallManager.audio?.isMuted == true
            CallManager.service?.setMuted(!muted)
        }
        toggle("speaker", "🔊", R.string.speaker) {
            val route = CallManager.audio?.route ?: CallAudioState.ROUTE_EARPIECE
            CallManager.service?.setAudioRoute(if (route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        }
        toggle("bt", "ᛒ", R.string.bluetooth) {
            val route = CallManager.audio?.route ?: 0
            CallManager.service?.setAudioRoute(if (route == CallAudioState.ROUTE_BLUETOOTH) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_BLUETOOTH)
        }
        toggle("hold", "⏸", R.string.hold) {
            val c = CallManager.primary() ?: return@toggle
            if (c.st() == Call.STATE_HOLDING) c.unhold() else c.hold()
        }
        toggle("pad", "⌨", R.string.keypad) { padBox.visibility = if (padBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        activeBox.addView(grid, LinearLayout.LayoutParams(-1, -2))
        activeBox.addView(roundButton("✆", 0xFFD64545.toInt(), R.string.end) { CallManager.primary()?.disconnect() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(24) })
        root.addView(activeBox, LinearLayout.LayoutParams(-1, -2))

        // Ringing controls
        ringBox = LinearLayout(this).apply { gravity = Gravity.CENTER }
        ringBox.addView(roundButton("✆", 0xFFD64545.toInt(), R.string.decline) { CallManager.primary()?.reject(false, null) },
            LinearLayout.LayoutParams(0, -2, 1f))
        ringBox.addView(roundButton("✆", 0xFF1E9E63.toInt(), R.string.answer) {
            CallManager.primary()?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(ringBox, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "sahl:prox").apply { setReferenceCounted(false) }
    }

    private fun roundButton(icon: String, color: Int, label: Int, go: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(text(icon, 30f, 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER; background = oval(color); setOnClickListener { go() }
        }, LinearLayout.LayoutParams(px(72), px(72)))
        addView(text(getString(label), 13f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, px(8), 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        CallManager.listeners.add(listener)
        main.post(tick)
        render()
    }

    override fun onPause() {
        super.onPause()
        CallManager.listeners.remove(listener)
        main.removeCallbacks(tick)
    }

    override fun onDestroy() { super.onDestroy(); prox?.release() }

    private fun render() {
        val call = CallManager.primary()
        if (call == null) {
            statusTv.text = getString(R.string.ended)
            prox?.release()
            main.postDelayed({ if (CallManager.primary() == null) finishAndRemoveTask() }, 900)
            return
        }
        val num = call.number()
        if (num != shownNumber) {
            shownNumber = num
            val who = Lookup.caller(this, num)
            nameTv.text = who.name ?: num.ifBlank { getString(R.string.private_number) }
            numberTv.text = if (who.name != null) num else ""
            avatarBox.background = oval(avatarColor(nameTv.text.toString()))
            avatarLetter.text = Norm.section(nameTv.text.toString()).let { if (it == "#") "👤" else it }
            Thumbs.load(this, who.photo, avatarImg, avatarLetter)
        }
        val st = call.st()
        val ringing = st == Call.STATE_RINGING
        ringBox.visibility = if (ringing) View.VISIBLE else View.GONE
        activeBox.visibility = if (ringing) View.GONE else View.VISIBLE
        if (st == Call.STATE_SELECT_PHONE_ACCOUNT && askedSim !== call) { askedSim = call; chooseSim(call) }

        val a = CallManager.audio
        fun on(key: String, active: Boolean) { toggles[key]?.background = oval(if (active) col(R.color.w_gold) and 0xCCFFFFFF.toInt() else 0x22FFFFFF) }
        on("mute", a?.isMuted == true)
        on("speaker", a?.route == CallAudioState.ROUTE_SPEAKER)
        on("bt", a?.route == CallAudioState.ROUTE_BLUETOOTH)
        on("hold", st == Call.STATE_HOLDING)
        toggles["bt"]?.alpha = if (a != null && (a.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0) 1f else 0.35f

        // Screen off near the ear, only for earpiece calls
        val nearEar = st == Call.STATE_ACTIVE && (a == null || a.route == CallAudioState.ROUTE_EARPIECE)
        if (nearEar) prox?.acquire(3 * 3600_000L) else prox?.release()
        renderStatus()
    }

    private fun renderStatus() {
        val call = CallManager.primary() ?: return
        statusTv.text = when (call.st()) {
            Call.STATE_RINGING -> getString(R.string.incoming)
            Call.STATE_DIALING -> getString(R.string.calling)
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
