package com.autotube.car

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad blocking for the web browser: requests to known ad / tracker / pop-up domains are refused.
 * Starts with a small built-in list and can download the full StevenBlack hosts list
 * (MIT licence, ~100k domains) from GitHub. Not used on YouTube / Prime Video pages: YouTube has
 * its own ad skipper, and blocking there can trigger the sites' anti-ad-block walls.
 */
object AdBlock {
    const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val FILE = "adblock-domains.txt"
    private const val TAG = "AdBlock"

    @Volatile private var domains: Set<String> = emptySet() // set to BUILT_IN at the end (init order)
    @Volatile private var loaded = false

    val size get() = domains.size

    /** Loads the downloaded list (if any) once; cheap to call again. Call off the main thread. */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) {
                try {
                    val set = HashSet<String>(131072)
                    f.forEachLine { if (it.isNotBlank()) set.add(it) }
                    set.addAll(BUILT_IN)
                    domains = set
                } catch (e: Exception) { Log.w(TAG, "load", e) }
            }
            loaded = true
        }
    }

    /** True if [host] or any parent domain (ads.example.com -> example.com) is on the list. */
    fun isBlocked(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        val d = domains
        while (true) {
            if (h in d) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
            if ('.' !in h) return false // never match a bare TLD
        }
    }

    /** Downloads the full list. Call off the main thread. @return number of domains, or -1. */
    fun update(ctx: Context): Int {
        return try {
            val c = URL(LIST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val set = HashSet<String>(131072)
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("0.0.0.0 ")) {
                        val d = line.substring(8).substringBefore('#').trim().lowercase()
                        if (d.isNotEmpty() && d != "0.0.0.0" && '.' in d) set.add(d)
                    }
                }
            }
            c.disconnect()
            if (set.size < 1000) return -1 // not the list we expected
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w -> set.forEach { w.write(it); w.write("\n") } }
            tmp.renameTo(File(ctx.filesDir, FILE))
            set.addAll(BUILT_IN)
            domains = set
            loaded = true
            Prefs(ctx).adBlockUpdated = System.currentTimeMillis()
            set.size
        } catch (e: Exception) {
            Log.w(TAG, "update", e)
            -1
        }
    }

    /** Hides common ad boxes on web pages (cosmetic part; the network part is [isBlocked]). */
    const val CSS = "ins.adsbygoogle,.adsbygoogle,[id^='google_ads_iframe'],[id^='div-gpt-ad'],[id^='taboola-']," +
        "[class*='taboola'],.OUTBRAIN,[id^='outbrain'],.ad-slot,.ad-banner,.adsbox,.advertisement," +
        "iframe[src*='doubleclick.net'],iframe[src*='googlesyndication.com'],iframe[src*='adnxs.com']" +
        "{display:none!important}"

    /** The biggest ad, tracker and pop-up networks; enough to start with before the full download. */
    private val BUILT_IN: Set<String> = """
        doubleclick.net googlesyndication.com googleadservices.com adservice.google.com
        pagead2.googlesyndication.com tpc.googlesyndication.com googletagservices.com
        google-analytics.com analytics.google.com
        adnxs.com adsrvr.org amazon-adsystem.com criteo.com criteo.net taboola.com outbrain.com
        pubmatic.com rubiconproject.com openx.net casalemedia.com indexww.com contextweb.com
        33across.com teads.tv sharethrough.com smartadserver.com adform.net bidswitch.net
        yieldmo.com media.net moatads.com scorecardresearch.com quantserve.com quantcount.com
        zedo.com revcontent.com mgid.com adsterra.com popads.net popcash.net propellerads.com
        onclickads.net onclkds.com exoclick.com juicyads.com hilltopads.net trafficjunky.net
        adcash.com clickadu.com a-ads.com coinzilla.io bitmedia.io adskeeper.com adskeeper.co.uk
        spotxchange.com spotx.tv springserve.com adcolony.com inmobi.com vungle.com chartboost.com
        unityads.unity3d.com applovin.com mopub.com startappservice.com admob.com
        advertising.com adtechus.com yieldlab.net smaato.net adition.com mathtag.com
        rlcdn.com krxd.net bluekai.com demdex.net everesttech.net agkn.com adsymptotic.com
        lijit.com sovrn.com gumgum.com districtm.io emxdgt.com richaudience.com improvedigital.com
        undertone.com tribalfusion.com conversantmedia.com serving-sys.com sizmek.com flashtalking.com
        innovid.com tremorhub.com yieldoptimizer.com adhigh.net adlightning.com adsafeprotected.com
        doubleverify.com imrworldwide.com chartbeat.net hotjar.com mouseflow.com fullstory.com
        ads.twitter.com static.ads-twitter.com analytics.twitter.com
        ads.linkedin.com bat.bing.com ads.yahoo.com adtech.yahooinc.com
        zeroredirect1.com zeroredirect2.com popunder.net poptm.com popmyads.com ad-maven.com
        admaven.com adsco.re clickaine.com galaksion.com evadav.com richpush.co pushcrew.com
        onesignal.com pushwoosh.com notix.io realsrv.com tsyndicate.com magsrv.com
        adtng.com adxpansion.com plugrush.com ero-advertising.com
        vdo.ai vidazoo.com connatix.com primis.tech aniview.com jwpltx.com
        colombiaonline.com colombia.com izooto.com affinity.com adgebra.co.in
    """.trim().split(Regex("\\s+")).toSet()

    init { domains = BUILT_IN }
}
