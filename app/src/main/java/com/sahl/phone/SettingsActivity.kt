package com.sahl.phone

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.*

class SettingsActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.settings), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val p = Sahl.prefs(this)

        choice(R.string.language, listOf("system" to getString(R.string.system), "ar" to "العربية", "en" to "English"), "lang")
        choice(R.string.theme, listOf("system" to getString(R.string.system), "light" to getString(R.string.light), "dark" to getString(R.string.dark)), "theme")

        val dcard = card(R.string.default_phone)
        if (Dialer.isDefault(this)) dcard.addView(text(getString(R.string.is_default), 14f, col(R.color.emerald)))
        else {
            dcard.addView(text(getString(R.string.make_default), 14f, col(R.color.sub)))
            dcard.addView(Button(this).apply {
                text = getString(R.string.set_default); typeface = font
                setOnClickListener { finish(); startActivity(Intent(this@SettingsActivity, MainActivity::class.java)) }
            })
        }

        // Prayer-time silence (prayer times come from Sahl Adhan through Sahl Union)
        val pr = card(R.string.prayer_silence)
        val adhanOk = PrayerSilence.available(this)
        pr.addView(text(getString(if (adhanOk) R.string.adhan_ok else R.string.adhan_missing), 13f,
            if (adhanOk) col(R.color.emerald) else col(R.color.sub)).apply { setPadding(0, px(2), 0, px(6)) })
        fun sw(label: Int, key: String, def: Boolean) = pr.addView(Switch(this).apply {
            text = getString(label); typeface = font; setTextColor(col(R.color.text)); textSize = 15f
            isChecked = p.getBoolean(key, def); isEnabled = adhanOk
            setPadding(0, px(6), 0, px(6))
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply() }
        })
        sw(R.string.prayer_silence_on, "prayer_silence", false)
        pr.addView(text(getString(R.string.prayer_window), 14f, col(R.color.sub)).apply { setPadding(0, px(8), 0, 0) })
        val mins = intArrayOf(10, 15, 20, 30, 45)
        pr.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, mins.map { it.toString() })
            setSelection(mins.indexOf(PrayerSilence.minutes(this@SettingsActivity)).coerceAtLeast(0))
            isEnabled = adhanOk
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { p.edit().putInt("prayer_minutes", mins[pos]).apply() }
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
        })
        sw(R.string.prayer_allow_fav, "prayer_allow_fav", true)
        sw(R.string.prayer_auto_reply, "prayer_auto_reply", false)

        // Call Guard (in Sahl Tools) and call notes
        val gd = card(R.string.call_guard)
        val gOn = Guard.installed(this) && Guard.active(this)
        gd.addView(text(getString(when { !Guard.installed(this) -> R.string.guard_missing; gOn -> R.string.guard_on; else -> R.string.guard_off }), 13f,
            if (gOn) col(R.color.emerald) else col(R.color.sub)).apply { setPadding(0, px(2), 0, px(6)) })
        if (Guard.installed(this)) gd.addView(Button(this).apply {
            text = getString(R.string.guard_settings); typeface = font
            setOnClickListener { Guard.openSettings(this@SettingsActivity) }
        })
        gd.addView(Switch(this).apply {
            text = getString(R.string.note_prompt_setting); typeface = font; setTextColor(col(R.color.text)); textSize = 15f
            isChecked = p.getBoolean("note_prompt", true); setPadding(0, px(8), 0, px(4))
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("note_prompt", on).apply() }
        })

        // Quick replies
        val qr = card(R.string.quick_replies)
        val qrText = text(QuickReplies.list(this).joinToString("\n") { "• $it" }, 14f, col(R.color.sub))
        qr.addView(qrText)
        qr.addView(Button(this).apply {
            text = getString(R.string.edit_replies); typeface = font
            setOnClickListener {
                val et = EditText(this@SettingsActivity).apply {
                    setText(QuickReplies.list(this@SettingsActivity).joinToString("\n")); typeface = font; minLines = 4
                }
                android.app.AlertDialog.Builder(this@SettingsActivity).setTitle(R.string.quick_replies).setView(et)
                    .setPositiveButton(R.string.save) { _, _ ->
                        QuickReplies.save(this@SettingsActivity, et.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() })
                        qrText.text = QuickReplies.list(this@SettingsActivity).joinToString("\n") { "• $it" }
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        })

        // Country code (for showing where unknown callers are from)
        val cc = card(R.string.country_code)
        cc.addView(EditText(this).apply {
            setText(Sahl.countryCode(this@SettingsActivity)); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 16f; typeface = font; setTextColor(col(R.color.text))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = Norm.digits(s?.toString() ?: ""); if (v.isNotEmpty()) p.edit().putString("cc", v).apply()
                }
            })
        })

        val up = card(R.string.check_updates)
        up.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 14f, col(R.color.sub)))
        up.addView(Button(this).apply {
            text = getString(R.string.check_updates); typeface = font
            setOnClickListener { checkUpdates(this) }
        })

        val un = card(R.string.union)
        un.addView(text(getString(R.string.union_desc), 14f, col(R.color.sub)))
    }

    private fun card(title: Int): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 22f)
            setPadding(px(16), px(12), px(16), px(12))
        }
        c.addView(text(getString(title), 13f, col(R.color.emerald), true))
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        return c
    }

    private fun choice(title: Int, options: List<Pair<String, String>>, key: String) {
        val c = card(title)
        val p = Sahl.prefs(this)
        val cur = p.getString(key, "system")
        val group = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i; text = label; typeface = font; setTextColor(col(R.color.text))
                isChecked = value == cur
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val v = options[checkedId - 1000].first
            if (v == p.getString(key, "system")) return@setOnCheckedChangeListener
            p.edit().putString(key, v).commit()
            // Restart so the new language / theme applies everywhere
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
        c.addView(group)
    }

    private fun checkUpdates(btn: Button) {
        btn.isEnabled = false; btn.text = getString(R.string.checking)
        Thread {
            val r = Updater.fetchNewer()
            runOnUiThread {
                btn.isEnabled = true; btn.text = getString(R.string.check_updates)
                if (r == null) { toast(R.string.up_to_date); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_available, r.name))
                    .setMessage(r.notes)
                    .setPositiveButton(R.string.install) { _, _ -> Updater.downloadAndInstall(this, r) }
                    .setNegativeButton(R.string.later, null).show()
            }
        }.start()
    }
}
