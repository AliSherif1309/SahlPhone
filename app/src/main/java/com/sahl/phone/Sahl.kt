package com.sahl.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sahl Core — shared by all Sahl apps (theme, language, union messaging).
 * Keep this file identical across Sahl apps so they behave as one family.
 */
object Sahl {
    /** Signature permission: only apps signed with the Sahl key hold it. */
    const val PERMISSION = "com.sahl.permission.UNION"
    /** Broadcast every Sahl app can listen to. Extras: event, from. */
    const val ACTION_EVENT = "com.sahl.action.EVENT"
    const val EXTRA_EVENT = "event"
    const val EXTRA_FROM = "from"
    const val EVENT_CONTACTS_CHANGED = "contacts_changed"

    fun prefs(c: Context) = c.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    /** Applies the user's language and theme choice to a context. */
    fun wrap(base: Context): Context {
        val p = prefs(base)
        val lang = p.getString("lang", "system") ?: "system"
        val theme = p.getString("theme", "system") ?: "system"
        val cfg = Configuration(base.resources.configuration)
        if (lang != "system") {
            val l = Locale(lang)
            Locale.setDefault(l)
            cfg.setLocale(l)
            cfg.setLayoutDirection(l)
        }
        val mask = Configuration.UI_MODE_NIGHT_MASK
        when (theme) {
            "light" -> cfg.uiMode = (cfg.uiMode and mask.inv()) or Configuration.UI_MODE_NIGHT_NO
            "dark" -> cfg.uiMode = (cfg.uiMode and mask.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        return base.createConfigurationContext(cfg)
    }

    /** Tell every other Sahl app that something happened. */
    fun announce(c: Context, event: String, extras: Bundle? = null) {
        val i = Intent(ACTION_EVENT).putExtra(EXTRA_EVENT, event).putExtra(EXTRA_FROM, c.packageName)
        if (extras != null) i.putExtras(extras)
        c.sendBroadcast(i, PERMISSION)
    }

    fun countryCode(c: Context) = prefs(c).getString("cc", "974") ?: "974"

    /** Saves any crash so the app can show it on the next start (shared Sahl debug helper). */
    fun catchCrashes(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is SahlCrash) return
        Thread.setDefaultUncaughtExceptionHandler(SahlCrash(app, prev))
    }

    fun lastCrash(c: Context): String? = prefs(c).getString("crash", null)
    fun clearCrash(c: Context) = prefs(c).edit().remove("crash").apply()
}

class SahlCrash(private val app: Context, private val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val v = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (x: Exception) { "?" }
            Sahl.prefs(app).edit().putString("crash", "v$v\n" + android.util.Log.getStackTraceString(e).take(6000)).commit()
        } catch (x: Exception) { }
        prev?.uncaughtException(t, e)
    }
}

/** Arabic-aware text and phone-number normalisation. */
object Norm {
    private val marks = Regex("[ً-ْٰـ]")

    fun text(s: String): String {
        val t = marks.replace(s, "").lowercase()
        val sb = StringBuilder(t.length)
        for (ch in t) sb.append(
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                else -> ch
            }
        )
        return sb.toString()
    }

    fun digits(s: String) = text(s).filter { it in '0'..'9' }

    /** Same number written as +974 5555 1234, 0097455551234 or 55551234 gives the same key. */
    fun phoneKey(s: String): String {
        val d = digits(s)
        return if (d.length > 8) d.takeLast(8) else d
    }

    /** International digits for WhatsApp links. */
    fun international(s: String, cc: String): String {
        var d = digits(s)
        val plus = text(s).trim().startsWith("+")
        if (d.startsWith("00")) return d.drop(2)
        if (plus) return d
        if (d.startsWith("0")) d = d.drop(1)
        return if (d.length <= 9) cc + d else d
    }

    fun section(name: String): String {
        val ch = text(name).trimStart().firstOrNull() ?: return "#"
        return when {
            ch in 'a'..'z' -> ch.uppercaseChar().toString()
            ch in 'ء'..'ي' -> ch.toString()
            else -> "#"
        }
    }

    fun group(section: String) = when {
        section[0] in 'A'..'Z' -> 0
        section == "#" -> 2
        else -> 1
    }
}

/** Small async thumbnail loader with a memory cache. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(150)
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(c: Context, uri: String?, iv: ImageView, letter: TextView) {
        iv.tag = uri
        if (uri == null) { iv.setImageDrawable(null); letter.visibility = View.VISIBLE; return }
        val hit = cache.get(uri)
        if (hit != null) { iv.setImageBitmap(hit); letter.visibility = View.GONE; return }
        iv.setImageDrawable(null); letter.visibility = View.VISIBLE
        val app = c.applicationContext
        pool.execute {
            val b = try {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { null }
            if (b != null) {
                cache.put(uri, b)
                main.post { if (iv.tag == uri) { iv.setImageBitmap(b); letter.visibility = View.GONE } }
            }
        }
    }

    fun clear() = cache.evictAll()
}

/** Base for every screen: Sahl theme, font and small view helpers. */
abstract class SahlActivity : Activity() {
    val dp get() = resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()
    fun col(id: Int) = getColor(id)
    val font: Typeface? by lazy { try { resources.getFont(R.font.thmanyah) } catch (e: Exception) { null } }
    val rtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** True while the crash report is on screen — screens wait before loading data. */
    var crashOpen = false
    open fun onCrashClosed() {}

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Sahl.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Sahl.catchCrashes(this)
        super.onCreate(savedInstanceState)
        Sahl.lastCrash(this)?.let { log ->
            Sahl.clearCrash(this)
            crashOpen = true
            android.app.AlertDialog.Builder(this).setTitle("Sahl crash report")
                .setMessage(log.take(1500))
                .setPositiveButton("Copy") { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                }
                .setNegativeButton("Close", null)
                .setOnDismissListener { crashOpen = false; onCrashClosed() }
                .show()
        }
        window.statusBarColor = col(R.color.emerald_dark)
        window.navigationBarColor = col(R.color.bg)
    }

    fun text(s: CharSequence, size: Float = 15f, color: Int = col(R.color.text), bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = Typeface.create(font, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    fun round(color: Int, radius: Float = 16f, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * dp
        if (stroke != 0) setStroke(px(1), stroke)
    }

    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Calm avatar colour picked from the name. */
    fun avatarColor(name: String): Int {
        val palette = intArrayOf(0xFF2F6F5E.toInt(), 0xFF5B7C6F.toInt(), 0xFF7A6A43.toInt(),
            0xFF4E6A80.toInt(), 0xFF6D5A73.toInt(), 0xFF3F7A7A.toInt())
        return palette[(name.hashCode() and 0x7fffffff) % palette.size]
    }

    /** Round avatar: photo if any, otherwise the first letter. */
    fun avatar(sizeDp: Int): Triple<FrameLayout, ImageView, TextView> {
        val f = FrameLayout(this)
        val letter = text("", sizeDp * 0.4f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(letter, FrameLayout.LayoutParams(-1, -1))
        f.addView(iv, FrameLayout.LayoutParams(-1, -1))
        f.clipToOutline = true
        f.layoutParams = ViewGroup.LayoutParams(px(sizeDp), px(sizeDp))
        return Triple(f, iv, letter)
    }

    /** Top bar with back arrow, title and text actions. */
    fun header(title: String, back: Boolean, actions: List<Pair<String, () -> Unit>> = emptyList()): LinearLayout {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(col(R.color.emerald_dark))
            setPadding(px(8), px(10), px(8), px(10))
        }
        if (back) bar.addView(text(if (rtl) "→" else "←", 22f, 0xFFFFFFFF.toInt()).apply {
            setPadding(px(10), 0, px(10), 0); setOnClickListener { finish() }
        })
        bar.addView(text(title, 19f, col(R.color.gold), true).apply {
            setPadding(px(8), 0, 0, 0); maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        for ((label, act) in actions) bar.addView(text(label, 21f, 0xFFFFFFFF.toInt()).apply {
            setPadding(px(12), 0, px(12), 0); setOnClickListener { act() }
        })
        return bar
    }

    fun toast(id: Int) = android.widget.Toast.makeText(this, id, android.widget.Toast.LENGTH_SHORT).show()
}
