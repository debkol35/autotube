package com.autotube.car

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import java.net.URLEncoder

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun View.dp(v: Number): Int = context.dp(v)

/** Rounded background that darkens while pressed (no androidx, no theme attrs needed). */
fun Context.pressable(normal: Int, pressed: Int, radiusDp: Int = 10): StateListDrawable {
    fun shape(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(radiusDp).toFloat() }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
        addState(intArrayOf(), shape(normal))
    }
}

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("autotube", Context.MODE_PRIVATE)

    /** The first-run notice (unofficial app, video only while parked) was accepted on the phone. */
    var noticeAccepted: Boolean
        get() = p.getBoolean("noticeAccepted", false)
        set(v) = p.edit().putBoolean("noticeAccepted", v).apply()

    /** Skip / hide YouTube ads in the car player. */
    var skipAds: Boolean
        get() = p.getBoolean("skipAds", true)
        set(v) = p.edit().putBoolean("skipAds", v).apply()

    /** Package of the YouTube app to pick videos in; null = automatic. */
    var youtubeApp: String?
        get() = p.getString("youtubeApp", null)
        set(v) = p.edit().putString("youtubeApp", v).apply()

    var lastUrl: String?
        get() = p.getString("lastUrl", null)
        set(v) = p.edit().putString("lastUrl", v).apply()

    /** Seconds into the video on [lastUrl], so a reconnect resumes where it stopped. */
    var lastTime: Int
        get() = p.getInt("lastTime", 0)
        set(v) = p.edit().putInt("lastTime", v).apply()

    /** Something sent from the phone while the car screen was closed. */
    var pendingUrl: String?
        get() = p.getString("pendingUrl", null)
        set(v) = p.edit().putString("pendingUrl", v).apply()

    fun takePending(): String? = pendingUrl?.also { pendingUrl = null }
}

/** Phone -> car screen hand-off (same process: Android Auto runs our CarActivity in this app). */
object Bridge {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((String) -> Unit)? = null

    val carScreenOpen: Boolean get() = listener != null

    fun attach(l: (String) -> Unit) { listener = l }

    fun detach(l: (String) -> Unit) { if (listener === l) listener = null }

    /** @return true if an open car screen got it now; false if it was saved for later. */
    fun send(ctx: Context, url: String): Boolean {
        val l = listener
        if (l != null) {
            main.post { l(url) }
            return true
        }
        Prefs(ctx).pendingUrl = url
        return false
    }
}

/**
 * The phone's YouTube apps for picking videos: the original YouTube app and YouTube ReVanced (or
 * any other patched YouTube). Disabled apps are never listed. The car screen itself never uses
 * any of them: it plays YouTube's website inside AutoTube, so both work the same way (Share →
 * "Play in car (AutoTube)").
 */
object YouTubeApp {
    const val GOOGLE = "com.google.android.youtube"
    const val REVANCED = "app.revanced.android.youtube"
    private val NOT_VIDEO = listOf("music", "kids", "creator", "unplugged", "studio")

    /** Installed, enabled YouTube apps: ReVanced first, other patched builds, then the original. */
    fun all(ctx: Context): List<String> = ctx.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { p -> p == GOOGLE || ("youtube" in p && NOT_VIDEO.none { it in p }) }
        .sortedBy { when (it) { REVANCED -> 0; GOOGLE -> 2; else -> 1 } }

    /** The app the user chose, if it's still there; otherwise the first of [all]. */
    fun find(ctx: Context): String? {
        val apps = all(ctx)
        return Prefs(ctx).youtubeApp?.takeIf { it in apps } ?: apps.firstOrNull()
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            .let { if (pkg != GOOGLE && "revanced" !in it.lowercase() && "revanced" in pkg) "$it ReVanced" else it }
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun open(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

object YouTubeLinks {
    const val HOME = "https://m.youtube.com/"
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL = Regex("https?://\\S+")

    fun search(q: String): String =
        "https://m.youtube.com/results?search_query=" + URLEncoder.encode(q.trim(), "UTF-8")

    fun watch(id: String, t: String? = null): String =
        "https://m.youtube.com/watch?v=$id" + (if (t.isNullOrBlank()) "" else "&t=$t")

    fun videoId(url: String): String? {
        val u = try { Uri.parse(url) } catch (e: Exception) { return null }
        val host = (u.host ?: "").lowercase()
            .removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val segs = u.pathSegments
        val id = when {
            host == "youtu.be" -> segs.firstOrNull()
            host == "youtube.com" || host == "youtube-nocookie.com" -> when {
                segs.firstOrNull() == "watch" -> u.getQueryParameter("v")
                segs.size >= 2 && segs[0] in setOf("shorts", "embed", "live", "v") -> segs[1]
                else -> null
            }
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    fun isWatchPage(url: String?): Boolean = url != null && url.contains("/watch") && videoId(url) != null

    /** Whatever was shared or typed -> something the car screen can open. */
    fun fromText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val url = URL.find(t)?.value?.trimEnd('.', ',', ')', '"', '\'')
        if (url != null) {
            val id = videoId(url)
            if (id != null) return watch(id, Uri.parse(url).getQueryParameter("t"))
            val host = Uri.parse(url).host?.lowercase() ?: ""
            if (host == "youtube.com" || host == "www.youtube.com") {
                return url.replaceFirst("://www.youtube.com", "://m.youtube.com")
                    .replaceFirst("://youtube.com", "://m.youtube.com")
            }
            return url // any other web link opens as is
        }
        return search(t)
    }
}
