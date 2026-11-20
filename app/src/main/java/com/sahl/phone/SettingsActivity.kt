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
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 18f)
            setPadding(px(16), px(12), px(16), px(12))
        }
        c.addView(text(getString(title), 13f, col(R.color.gold), true))
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
