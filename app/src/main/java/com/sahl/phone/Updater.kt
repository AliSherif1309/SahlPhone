package com.sahl.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK and installs it. */
object Updater {
    private const val API = "https://api.github.com/repos/AliSherif1309/SahlPhone/releases/latest"
    data class Release(val code: Int, val name: String, val apkUrl: String, val notes: String)

    /** Blocking — call off the main thread. Returns a release only if it is newer. */
    fun fetchNewer(): Release? = try {
        val con = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name")                       // e.g. v1.7
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = j.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (code > BuildConfig.VERSION_CODE && url.isNotEmpty())
            Release(code, tag, url, j.optString("body").lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "") else null
    } catch (e: Exception) { null }

    /** Once a day from the background: post a notification if an update exists. */
    fun dailyCheck(ctx: Context) {
        val p = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastUpdateCheck", 0) < 20 * 3600_000L) return
        p.edit().putLong("lastUpdateCheck", now).apply()
        Thread {
            val r = fetchNewer() ?: return@Thread
            val c = Sahl.wrap(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("updates", c.getString(R.string.check_updates), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(500, Notification.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_call)
                .setContentTitle(c.getString(R.string.update_available, r.name))
                .setContentText(c.getString(R.string.install_update))
                .setContentIntent(open).setAutoCancel(true).build())
        }.start()
    }

    /** Opens the APK link in the browser, which downloads it; tap the download to install. */
    fun downloadAndInstall(ctx: Context, r: Release) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
