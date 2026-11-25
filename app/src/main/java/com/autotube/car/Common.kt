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

    /**
     * Driving lock: hide the picture unless the phone's GPS says the car is parked. On by default;
     * the user can turn it off (passengers), after a warning.
     */
    var driveLock: Boolean
        get() = p.getBoolean("driveLock", true)
        set(v) = p.edit().putBoolean("driveLock", v).apply()

    /** Skip / hide YouTube ads in the car player. */
    var skipAds: Boolean
        get() = p.getBoolean("skipAds", true)
        set(v) = p.edit().putBoolean("skipAds", v).apply()

    /** Package of the YouTube app to pick videos in; null = automatic. */
    var youtubeApp: String?
        get() = p.getString("youtubeApp", null)
        set(v) = p.edit().putString("youtubeApp", v).apply()

    /** "Set up on this phone" was tried; if the record still isn't right, the phone refused it. */
    var directSetupTried: Boolean
        get() = p.getBoolean("directSetupTried", false)
        set(v) = p.edit().putBoolean("directSetupTried", v).apply()

    /** Services offered on the car screen's Apps button (YouTube always; the browser on by default). */
    fun serviceOn(s: Service): Boolean = s == Service.YOUTUBE || p.getBoolean("on_" + s.id, s == Service.WEB)

    fun setServiceOn(s: Service, on: Boolean) = p.edit().putBoolean("on_" + s.id, on).apply()

    val services: List<Service> get() = Service.values().filter { serviceOn(it) }

    /** Browser: block ads (requests to ad domains, ad boxes, pop-ups). */
    var adBlock: Boolean
        get() = p.getBoolean("adBlock", true)
        set(v) = p.edit().putBoolean("adBlock", v).apply()

    /** When the full ad block list was last downloaded (0 = never). */
    var adBlockUpdated: Long
        get() = p.getLong("adBlockUpdated", 0)
        set(v) = p.edit().putLong("adBlockUpdated", v).apply()

    /** Browser: ask sites for their desktop version. */
    var webDesktop: Boolean
        get() = p.getBoolean("webDesktop", false)
        set(v) = p.edit().putBoolean("webDesktop", v).apply()

    /** Browser bookmarks, shown on its start page: (title, url). */
    var bookmarks: List<Pair<String, String>>
        get() = try {
            val a = org.json.JSONArray(p.getString("bookmarks", "[]"))
            (0 until a.length()).map { a.getJSONArray(it).let { e -> e.getString(0) to e.getString(1) } }
        } catch (e: Exception) { emptyList() }
        set(v) = p.edit().putString("bookmarks",
            org.json.JSONArray(v.map { org.json.JSONArray(listOf(it.first, it.second)) }).toString()).apply()

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

/**
 * What the car screen can show. YouTube uses its mobile site; Prime Video only serves its web
 * player to desktop browsers, so it gets a desktop user agent and DRM (Widevine).
 * (Netflix is not offered: its licence check refuses in-app browsers, error M7111-1957-2050xx.)
 */
enum class Service(val id: String, val label: String, val home: String, val signIn: String, val desktop: Boolean) {
    YOUTUBE("youtube", "YouTube", "https://m.youtube.com/",
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F", false),
    PRIME("prime", "Prime Video", "https://www.primevideo.com/", "https://www.primevideo.com/", true),
    WEB("web", "Browser", "https://autotube.start/", "", false); // home = START_URL

    fun search(q: String): String {
        val e = URLEncoder.encode(q.trim(), "UTF-8")
        return when (this) {
            YOUTUBE -> YouTubeLinks.search(q)
            PRIME -> "https://www.primevideo.com/search/ref=atv_nb_sug?ie=UTF8&phrase=$e"
            WEB -> "https://www.google.com/search?q=$e"
        }
    }

    companion object {
        fun forUrl(url: String?): Service {
            val host = try { Uri.parse(url ?: "").host?.lowercase() ?: "" } catch (e: Exception) { "" }
            fun on(domain: String) = host == domain || host.endsWith(".$domain")
            return when {
                on("youtube.com") || on("youtu.be") || on("youtube-nocookie.com") -> YOUTUBE
                on("primevideo.com") || Regex("(^|\\.)amazon\\.[a-z.]+$").containsMatchIn(host) -> PRIME
                else -> WEB
            }
        }

        /** The browser's start page (rendered by AutoTube, never fetched). */
        const val START_URL = "https://autotube.start/"

        /** "example.com", "http://x.org/a", "192.168.1.1:8080" -> a URL; anything else -> null. */
        fun webAddress(text: String): String? {
            val t = text.trim()
            if (t.isEmpty() || ' ' in t) return null
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
            val host = t.substringBefore('/').substringBefore(':')
            val ok = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}$").matches(host) ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || host == "localhost"
            return if (ok) "https://$t" else null
        }

        /** A desktop Chrome (Linux) user agent with the same Chrome version as this WebView. */
        fun desktopUserAgent(webViewUa: String): String {
            val v = Regex("Chrome/([\\d.]+)").find(webViewUa)?.groupValues?.get(1) ?: "124.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
        }

        /** Plain Chrome-for-Android user agent (without the "wv" WebView markers). */
        fun mobileUserAgent(webViewUa: String): String = webViewUa
            .replace("; wv", "")
            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
            .replace(Regex(" Build/[^;)]+"), "")
    }
}

/**
 * Small persistent log of what Android Auto does with the car screen (start/stop, size changes,
 * crashes). Kept in the app's files (adb: /sdcard/Android/data/com.autotube.car/files/car-log.txt)
 * and shared from the phone screen, because the phone's own log is gone by the time you're home.
 */
object CarLog {
    private const val KEEP = 400
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    private fun file(ctx: Context) = java.io.File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "car-log.txt")

    @Synchronized
    fun add(ctx: Context, msg: String) {
        android.util.Log.i("CarLog", msg)
        try {
            val f = file(ctx)
            f.appendText(fmt.format(java.util.Date()) + "  " + msg + "\n")
            if (f.length() > 64_000) f.writeText(f.readLines().takeLast(KEEP).joinToString("\n", postfix = "\n"))
        } catch (e: Exception) { /* logging must never break the app */ }
    }

    @Synchronized
    fun read(ctx: Context): String = try { file(ctx).readText() } catch (e: Exception) { "" }
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
