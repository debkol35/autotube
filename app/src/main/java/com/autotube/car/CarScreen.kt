package com.autotube.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The car screen: YouTube's mobile site (or Prime Video, if turned on) in a WebView, a
 * rail of big buttons on the left (made for wide car displays), our own keyboard, a full-screen
 * video mode and the driving cover. Used by both the Android Auto CarActivity and the phone preview.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class CarScreen(ctx: Context, private val inCar: Boolean) : FrameLayout(ctx) {

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val rail = LinearLayout(ctx)
    private val content = FrameLayout(ctx)
    private var web: CarWebView = CarWebView(ctx)
    private var baseUa = ""
    private val fullscreenBox = FrameLayout(ctx)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val keyboard: CarKeyboard
    private val driveCover: LinearLayout
    private val coverTitle = TextView(ctx)
    private val coverText = TextView(ctx)
    private val toastView = TextView(ctx)
    private val playBtn: ImageButton
    private val fullBtn: ImageButton
    private val appsBtn: ImageButton
    private val appsPanel = LinearLayout(ctx)
    private val fullTouch = View(ctx)
    private val fullBar = LinearLayout(ctx)
    private lateinit var fullPlayBtn: ImageButton
    private lateinit var muteBtn: ImageButton
    private val seek = SeekBar(ctx)
    private val elapsed = TextView(ctx)
    private val total = TextView(ctx)
    private var seeking = false
    private var fullMode = false
    // Browser: address bar above the page, ad blocking.
    private val webColumn = LinearLayout(ctx)
    private val addrBar = LinearLayout(ctx)
    private val addrText = TextView(ctx)
    private val shieldText = TextView(ctx)
    private lateinit var desktopBtn: ImageButton
    private lateinit var starBtn: ImageButton
    private val blocked = AtomicInteger()
    @Volatile private var pageIsWeb = false
    @Volatile private var adBlockOn = prefs.adBlock
    private var playing = false
    private var destroyed = false
    private val guard = DriveGuard(ctx) { onDriveState(it) }
    private val bridgeListener: (String) -> Unit = { open(it) }
    private val hideToast = Runnable { toastView.visibility = GONE }
    private val hideFullBar = Runnable { if (!seeking) fullBar.visibility = GONE }
    private val fullTick: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || !fullMode || fullBar.visibility != VISIBLE) return
            refreshMediaState()
            main.postDelayed(this, 500)
        }
    }

    /**
     * The picture (and browsing) is allowed only once the phone's GPS says the car is parked,
     * unless the user turned the driving lock off on the phone.
     */
    private val locked get() =
        !prefs.noticeAccepted || (prefs.driveLock && guard.state != DriveGuard.State.PARKED)

    /** The service of the page on screen (decides home, search and user agent). */
    private val service get() = Service.forUrl(web.url ?: prefs.lastUrl)

    init {
        setBackgroundColor(BG)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        addView(row, LayoutParams(MATCH, MATCH))

        rail.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(RAIL)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        row.addView(rail, LinearLayout.LayoutParams(dp(76), MATCH))
        row.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))

        // The page, with the browser's address bar above it (shown only for web pages).
        webColumn.orientation = LinearLayout.VERTICAL
        content.addView(webColumn, LayoutParams(MATCH, MATCH))
        buildAddressBar()
        webColumn.addView(addrBar, LinearLayout.LayoutParams(MATCH, dp(56)))
        webColumn.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Full screen: the page shows only the video; a tap anywhere brings up the control bar.
        fullTouch.apply {
            visibility = GONE
            isClickable = true
            setOnClickListener { showFullBar() }
        }
        content.addView(fullTouch, LayoutParams(MATCH, MATCH))
        buildFullBar()
        content.addView(fullBar, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(14), 0, dp(14), dp(14))
        })

        appsPanel.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            isClickable = true
            setBackgroundColor(0xF0101214.toInt())
            setOnClickListener { visibility = GONE }
        }
        content.addView(appsPanel, LayoutParams(MATCH, MATCH))

        keyboard = CarKeyboard(ctx, ::onKeyboardDone, ::closeKeyboard).apply { visibility = GONE }
        content.addView(keyboard, LayoutParams(MATCH, MATCH))

        driveCover = buildDriveCover().apply { visibility = GONE }
        content.addView(driveCover, LayoutParams(MATCH, MATCH))

        // Real fullscreen (the site's own fullscreen button) covers the rail too.
        fullscreenBox.setBackgroundColor(Color.BLACK)
        fullscreenBox.visibility = GONE
        addView(fullscreenBox, LayoutParams(MATCH, MATCH))

        toastView.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = context.pressable(0xE6303437.toInt(), 0xE6303437.toInt(), 20)
            visibility = GONE
        }
        addView(toastView, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })

        railButton(R.drawable.ic_back, "Back") { handleBack() }
        railButton(R.drawable.ic_home, "Home") { if (!blockedWhileLocked()) load(service.home) }
        railButton(R.drawable.ic_search, "Search") { if (!blockedWhileLocked()) openSearch() }
        playBtn = railButton(R.drawable.ic_play, "Play or pause") { togglePlay() }
        fullBtn = railButton(R.drawable.ic_fit, "Full screen video") { if (!blockedWhileLocked()) setFull(true) }
        appsBtn = railButton(R.drawable.ic_apps, "YouTube, Prime Video, Browser") { if (!blockedWhileLocked()) showApps() }
        updateAppsButton()

        setupWeb(web)
        Thread { AdBlock.ensureLoaded(context.applicationContext) }.start()
    }

    // ---------------------------------------------------------------- full-screen controls

    /** [exit] [-10 s] [play] [+10 s] 0:42 ━━━━●━━━━ 3:15 [mute] */
    private fun buildFullBar() {
        fullBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            isClickable = true // taps on the bar's gaps don't fall through to the video
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = context.pressable(0xE6181A1B.toInt(), 0xE6181A1B.toInt(), 22)
            addView(barButton(R.drawable.ic_fit_exit, "Exit full screen") { setFull(false) })
            addView(barButton(R.drawable.ic_rewind, "Back 10 seconds") { mediaCmd("skip", -10.0) })
            fullPlayBtn = barButton(R.drawable.ic_play, "Play or pause") { togglePlay(); showFullBar() }
            addView(fullPlayBtn)
            addView(barButton(R.drawable.ic_forward, "Forward 10 seconds") { mediaCmd("skip", 10.0) })
            for (t in listOf(elapsed, total)) t.apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                text = "0:00"
            }
            addView(elapsed, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(10), 0, dp(4), 0) })
            seek.apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF2BB3A3.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFF2BB3A3.toInt())
                setPadding(dp(18), dp(14), dp(18), dp(14))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) elapsed.text = clock(p / 1000.0)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {
                        seeking = true
                        main.removeCallbacks(hideFullBar)
                    }
                    override fun onStopTrackingTouch(s: SeekBar) {
                        seeking = false
                        mediaCmd("seek", s.progress / 1000.0)
                    }
                })
            }
            addView(seek, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(total, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, dp(10), 0) })
            muteBtn = barButton(R.drawable.ic_volume_up, "Mute or unmute") { mediaCmd("mute", 0.0) }
            addView(muteBtn)
        }
    }

    /** Reads the video's time, length, play and mute state into the bar. */
    private fun refreshMediaState() {
        web.evaluateJavascript(JS_MEDIA_STATE) { r ->
            if (destroyed || r == null || r == "null") return@evaluateJavascript
            try {
                val o = JSONObject(r)
                val t = o.optDouble("t", 0.0)
                val d = o.optDouble("d", -1.0)
                setPlaying(!o.optBoolean("p", true))
                muteBtn.setImageResource(if (o.optBoolean("m")) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                if (d > 0) {
                    seek.isEnabled = true
                    seek.max = (d * 1000).toInt().coerceAtLeast(1)
                    if (!seeking) { seek.progress = (t * 1000).toInt(); elapsed.text = clock(t) }
                    total.text = clock(d)
                } else { // live stream: no length to seek in
                    seek.isEnabled = false
                    seek.progress = 0
                    elapsed.text = clock(t)
                    total.text = "LIVE"
                }
            } catch (e: Exception) { Log.d(TAG, "media state: $e") }
        }
    }

    private fun mediaCmd(action: String, value: Double) {
        web.evaluateJavascript("$JS_MEDIA_CMD('$action', $value)") { main.postDelayed({ refreshMediaState() }, 150) }
        showFullBar()
    }

    private fun clock(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- browser

    /** [shield 12] [address ........] [desktop] [bookmark] [reload] */
    private fun buildAddressBar() {
        addrBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(RAIL)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            visibility = GONE
        }
        addrBar.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_shield)
            setColorFilter(0xFF2BB3A3.toInt())
            setPadding(dp(8), dp(10), dp(2), dp(10))
        }, LinearLayout.LayoutParams(dp(34), MATCH))
        shieldText.apply {
            setTextColor(0xFF9FD8D1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
        }
        addrBar.addView(shieldText, LinearLayout.LayoutParams(WRAP, MATCH).apply { setMargins(0, 0, dp(8), 0) })
        addrText.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = context.pressable(0xFF26292B.toInt(), 0xFF34393C.toInt(), 12)
            setOnClickListener { if (!blockedWhileLocked()) openAddress(web.url?.takeIf { it != Service.START_URL } ?: "") }
        }
        addrBar.addView(addrText, LinearLayout.LayoutParams(0, MATCH, 1f))
        desktopBtn = barButton(R.drawable.ic_desktop, "Desktop site") {
            prefs.webDesktop = !prefs.webDesktop
            toast(if (prefs.webDesktop) "Desktop site" else "Mobile site")
            web.url?.let { if (it != Service.START_URL) load(it) }
            updateAddressBar()
        }
        starBtn = barButton(R.drawable.ic_star_border, "Bookmark") { toggleBookmark() }
        for (b in listOf(desktopBtn, starBtn, barButton(R.drawable.ic_refresh, "Reload") { web.reload() })) {
            addrBar.addView(b, LinearLayout.LayoutParams(dp(60), MATCH).apply { setMargins(dp(4), 0, 0, 0) })
        }
    }

    private fun updateAddressBar() {
        val url = web.url
        val isWeb = Service.forUrl(url) == Service.WEB
        addrBar.visibility = if (isWeb && !fullMode) VISIBLE else GONE
        if (!isWeb) return
        addrText.text = if (url == null || url == Service.START_URL) "Search or type a web address"
        else url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        shieldText.text = if (adBlockOn) blocked.get().toString() else "off"
        desktopBtn.alpha = if (prefs.webDesktop) 1f else 0.45f
        starBtn.setImageResource(if (prefs.bookmarks.any { it.second == url }) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun openAddress(initial: String) {
        keyboard.open(initial, true, "the web or type an address", suggest = false)
    }

    private fun toggleBookmark() {
        val url = web.url ?: return
        if (url == Service.START_URL) return toast("Open a site first, then tap the star to keep it")
        val list = prefs.bookmarks
        prefs.bookmarks = if (list.any { it.second == url }) {
            toast("Bookmark removed")
            list.filter { it.second != url }
        } else {
            toast("Bookmarked: it's on the browser's start page")
            list + ((Uri.parse(url).host?.removePrefix("www.") ?: url) to url) // short tile name: the site
        }
        updateAddressBar()
    }

    /** The browser's start page: a search box and big tiles (bookmarks first, then suggestions). */
    private fun showStartPage() {
        fun esc(s: String) = TextUtils.htmlEncode(s)
        val tiles = StringBuilder()
        prefs.bookmarks.forEach { (title, url) ->
            tiles.append("<div class='t'><a href='${esc(url)}'><b>${esc(title.take(1).uppercase())}</b>${esc(title.take(40))}</a>" +
                "<span class='x' onclick=\"AutoTube.removeBookmark(${esc(JSONObject.quote(url))})\">×</span></div>")
        }
        DEFAULT_SITES.forEach { (title, url) ->
            tiles.append("<div class='t'><a href='${esc(url)}'><b>${esc(title.take(1))}</b>${esc(title)}</a></div>")
        }
        val html = """<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>
            <style>
            body{background:#0f0f0f;color:#fff;font-family:sans-serif;margin:0;padding:22px}
            .s{background:#26292b;border-radius:16px;padding:22px 26px;font-size:24px;color:#9aa0a4;margin-bottom:22px}
            .g{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:16px}
            .t{position:relative}
            .t a{display:flex;flex-direction:column;align-items:center;gap:10px;background:#1c1f21;border-radius:16px;
              padding:20px 8px;color:#fff;text-decoration:none;font-size:18px;text-align:center}
            .t b{display:flex;width:58px;height:58px;border-radius:29px;background:#1f8f83;align-items:center;
              justify-content:center;font-size:28px}
            .x{position:absolute;top:4px;right:10px;font-size:26px;color:#9aa0a4;padding:4px 8px}
            </style></head><body>
            <div class='s' onclick='AutoTube.openAddress()'>🔍&nbsp; Search or type a web address</div>
            <div class='g'>$tiles</div></body></html>"""
        web.loadDataWithBaseURL(Service.START_URL, html, "text/html", "utf-8", Service.START_URL)
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        Bridge.attach(bridgeListener)
        guard.start()
        onDriveState(guard.state)
        main.postDelayed(recheck, RECHECK_MS)
        val pending = prefs.takePending()
        val last = prefs.lastUrl
        val url = when {
            pending != null -> pending
            last != null && YouTubeLinks.isWatchPage(last) && prefs.lastTime > 10 ->
                YouTubeLinks.watch(YouTubeLinks.videoId(last)!!, "${prefs.lastTime}s")
            last != null -> last
            else -> YouTubeLinks.HOME
        }
        load(url)
    }

    /** Phone preview only: pause when the phone app goes to the background. */
    fun pause() { web.onPause() }
    fun resume() { web.onResume() }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        Bridge.detach(bridgeListener)
        guard.stop()
        keyboard.shutdown()
        main.removeCallbacksAndMessages(null)
        exitFullscreen()
        web.stopLoading()
        web.loadUrl("about:blank")
        webColumn.removeView(web)
        web.destroy()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (inCar) CarLog.add(context, "car screen size ${oldw}x$oldh -> ${w}x$h")
    }

    /** @return true if Back was used up here (keyboard, apps, fullscreen, page history). */
    fun handleBack(): Boolean {
        when {
            keyboard.visibility == VISIBLE -> closeKeyboard()
            appsPanel.visibility == VISIBLE -> appsPanel.visibility = GONE
            customView != null -> exitFullscreen()
            fullMode -> setFull(false)
            web.canGoBack() -> web.goBack()
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- actions

    fun open(url: String) {
        if (destroyed) return
        if (keyboard.visibility == VISIBLE) closeKeyboard()
        appsPanel.visibility = GONE
        exitFullscreen()
        load(url)
    }

    /** Loads [url] with the user agent its site needs (desktop for Prime Video, browser's choice for web). */
    private fun load(url: String) {
        pageIsWeb = Service.forUrl(url) == Service.WEB
        setPlaying(false) // the new page reports its own video state
        if (url == Service.START_URL) return showStartPage()
        val ua = userAgentFor(url)
        if (web.settings.userAgentString != ua) web.settings.userAgentString = ua
        web.loadUrl(url)
    }

    private fun userAgentFor(url: String?): String {
        val s = Service.forUrl(url)
        val desktop = s.desktop || (s == Service.WEB && prefs.webDesktop)
        return if (desktop) Service.desktopUserAgent(baseUa) else Service.mobileUserAgent(baseUa)
    }

    private fun togglePlay() {
        web.evaluateJavascript(JS_TOGGLE_PLAY) { r ->
            Log.d(TAG, "toggle play -> $r")
            when (r?.trim('"')) {
                "none" -> toast("No video on this page")
                "play" -> setPlaying(true)
                "pause" -> setPlaying(false)
            }
        }
    }

    private fun setPlaying(p: Boolean) {
        if (p != playing) Log.d(TAG, "playing=$p")
        playing = p
        val icon = if (p) R.drawable.ic_pause else R.drawable.ic_play
        playBtn.setImageResource(icon)
        fullPlayBtn.setImageResource(icon)
    }

    /** Full screen: hides our rail and everything on the page except the video. */
    private fun setFull(on: Boolean) {
        if (on) {
            web.evaluateJavascript("document.getElementsByTagName('video').length") { n ->
                if ((n?.toIntOrNull() ?: 0) == 0) {
                    toast("Open a video first")
                } else if (!destroyed) {
                    applyFull(true)
                    showFullBar()
                    toast("Tap the screen for controls. Back leaves full screen")
                }
            }
        } else {
            applyFull(false)
        }
    }

    private fun applyFull(on: Boolean) {
        fullMode = on
        rail.visibility = if (on) GONE else VISIBLE
        fullTouch.visibility = if (on) VISIBLE else GONE
        if (!on) {
            main.removeCallbacks(hideFullBar)
            fullBar.visibility = GONE
        }
        web.evaluateJavascript("$JS_FIT($on)", null)
        updateAddressBar()
        if (inCar) CarLog.add(context, "full screen $on")
    }

    private fun showFullBar() {
        if (!fullMode) return
        val wasHidden = fullBar.visibility != VISIBLE
        fullBar.visibility = VISIBLE
        fullBar.bringToFront()
        main.removeCallbacks(hideFullBar)
        main.postDelayed(hideFullBar, 5000)
        if (wasHidden) { main.removeCallbacks(fullTick); main.post(fullTick) }
    }

    private fun openSearch() {
        val s = service
        if (s == Service.WEB) openAddress("") else keyboard.open("", true, s.label, suggest = s == Service.YOUTUBE)
    }

    // ---------------------------------------------------------------- services

    private fun updateAppsButton() {
        appsBtn.visibility = if (prefs.services.size > 1) VISIBLE else GONE
    }

    private fun showApps() {
        appsPanel.removeAllViews()
        prefs.services.forEach { s ->
            appsPanel.addView(TextView(context).apply {
                text = s.label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
                val c = when (s) {
                    Service.YOUTUBE -> 0xFFB3261E.toInt()
                    Service.PRIME -> 0xFF1A6FB0.toInt()
                    Service.WEB -> 0xFF1F8F83.toInt()
                }
                background = context.pressable(c, 0xFF3A3E41.toInt(), 18)
                setOnClickListener {
                    appsPanel.visibility = GONE
                    if (fullMode) applyFull(false)
                    load(s.home)
                }
            }, LinearLayout.LayoutParams(dp(200), dp(130)).apply { setMargins(dp(12), 0, dp(12), 0) })
        }
        appsPanel.visibility = VISIBLE
    }

    private fun blockedWhileLocked(): Boolean {
        if (locked) toast(if (guard.state == DriveGuard.State.MOVING) "Not while the car is moving" else coverTitle.text.toString())
        return locked
    }

    // ---------------------------------------------------------------- keyboard

    private fun onKeyboardDone(text: String, search: Boolean) {
        closeKeyboard()
        if (search) {
            if (text.isBlank()) return
            val s = service
            // Browser: a web address opens directly; anything else is a Google search.
            load(if (s == Service.WEB) Service.webAddress(text) ?: s.search(text) else s.search(text))
        } else {
            web.evaluateJavascript("$JS_SET_INPUT(${JSONObject.quote(text)})", null)
        }
    }

    private fun closeKeyboard() {
        keyboard.visibility = GONE
        web.evaluateJavascript(JS_BLUR, null)
    }

    // ---------------------------------------------------------------- driving lock

    private var lastDriveState = guard.state

    /** Picks up setup done on the phone while this screen is open (notice, location, services). */
    private val recheck: Runnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (guard.state == DriveGuard.State.NO_PERMISSION && DriveGuard.hasLocation(context)) guard.start()
            onDriveState(guard.state)
            updateAppsButton()
            adBlockOn = prefs.adBlock
            main.postDelayed(this, RECHECK_MS)
        }
    }

    private fun onDriveState(s: DriveGuard.State) {
        if (destroyed) return
        val wasMoving = lastDriveState == DriveGuard.State.MOVING
        lastDriveState = s
        if (locked) {
            setCoverText(s)
            keyboard.visibility = GONE
            appsPanel.visibility = GONE
            exitFullscreen()
            driveCover.visibility = VISIBLE
        } else if (driveCover.visibility == VISIBLE) {
            driveCover.visibility = GONE
            if (wasMoving) toast("Parked: video is back")
        }
    }

    private fun setCoverText(s: DriveGuard.State) {
        val (title, text) = when {
            !prefs.noticeAccepted -> "Finish setup on your phone" to
                "Open AutoTube on the phone once and read the short notice."
            s == DriveGuard.State.MOVING -> "Video hidden while the car is moving" to
                "Sound keeps playing. The picture comes back when you stop."
            s == DriveGuard.State.NO_PERMISSION -> "Location permission needed" to
                "Video is shown only when the phone's GPS says the car is parked. Open AutoTube on the " +
                "phone and allow location (\"Allow all the time\"). Sound keeps playing."
            s == DriveGuard.State.NO_GPS -> "Turn on the phone's location" to
                "Video is shown only when the phone's GPS says the car is parked. Sound keeps playing."
            inCar && !DriveGuard.hasBackgroundLocation(context) -> "Checking that the car is parked…" to
                "No GPS reading yet. If this stays, open AutoTube on the phone and set location to " +
                "\"Allow all the time\". Sound keeps playing."
            else -> "Checking that the car is parked…" to
                "Waiting for the phone's GPS (a few seconds; slower indoors). Sound keeps playing."
        }
        coverTitle.text = title
        coverText.text = text
    }

    private fun buildDriveCover() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        isClickable = true
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(coverTitle.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        addView(coverText.apply {
            setTextColor(0xFFA0A6AA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = context.pressable(0xFF1F8F83.toInt(), 0xFF2BB3A3.toInt(), 40)
            contentDescription = "Play or pause"
            setOnClickListener { togglePlay() }
        }, LinearLayout.LayoutParams(dp(80), dp(80)))
    }

    // ---------------------------------------------------------------- site fullscreen

    private fun exitFullscreen() {
        val v = customView ?: return
        customView = null
        fullscreenBox.removeView(v)
        fullscreenBox.visibility = GONE
        val cb = customCallback
        customCallback = null
        try { cb?.onCustomViewHidden() } catch (e: Exception) { Log.w(TAG, "onCustomViewHidden", e) }
    }

    // ---------------------------------------------------------------- web view

    private fun setupWeb(w: CarWebView) {
        // Debug builds only: lets chrome://inspect (DevTools over adb) look inside the page.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        w.setBackgroundColor(BG)
        w.isLongClickable = false
        w.setOnLongClickListener { true }
        w.isHapticFeedbackEnabled = false
        w.settings.apply {
            if (baseUa.isEmpty()) baseUa = userAgentString
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            // New windows: links that open a new tab (with a tap) load here; script pop-ups are dropped.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = Service.mobileUserAgent(baseUa)
            if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        w.addJavascriptInterface(JsApi(), "AutoTube")
        w.webViewClient = Client()
        w.webChromeClient = Chrome()
    }

    /** The WebView renderer can be killed under memory pressure; rebuild instead of crashing. */
    private fun recreateWeb() {
        if (destroyed) return
        exitFullscreen()
        if (fullMode) applyFull(false)
        val old = web
        webColumn.removeView(old)
        try { old.destroy() } catch (e: Exception) { Log.w(TAG, "destroy", e) }
        web = CarWebView(context)
        setupWeb(web)
        webColumn.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        load(prefs.lastUrl ?: YouTubeLinks.HOME)
    }

    private fun remember(url: String?) {
        if (url == null || !url.startsWith("http") || url == Service.START_URL) return
        if (url != prefs.lastUrl) {
            prefs.lastUrl = url
            prefs.lastTime = 0
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url
            when (u.scheme?.lowercase()) {
                "http", "https" -> {
                    if (!req.isForMainFrame) return false
                    // Browser: don't follow redirects / taps to ad and pop-up domains.
                    if (adBlockOn && pageIsWeb && AdBlock.isBlocked(u.host)) {
                        blocked.incrementAndGet()
                        main.post { toast("Blocked an ad page"); updateAddressBar() }
                        return true
                    }
                    pageIsWeb = Service.forUrl(u.toString()) == Service.WEB
                    // Crossing between YouTube / Prime Video / web needs the other user agent.
                    if (userAgentFor(u.toString()) == view.settings.userAgentString) return false
                    main.post { load(u.toString()) }
                }
                "vnd.youtube" -> {
                    val id = u.schemeSpecificPart?.substringBefore('?')?.trim('/')
                    if (id != null && id.length == 11) load(YouTubeLinks.watch(id))
                }
                "intent" -> try {
                    Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        .getStringExtra("browser_fallback_url")
                        ?.takeIf { it.startsWith("http") }
                        ?.let { load(it) }
                } catch (e: Exception) { Log.d(TAG, "intent url: $e") }
            }
            return true // never leave for other apps: nothing else can show on the car screen
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageIsWeb = Service.forUrl(url) == Service.WEB
            blocked.set(0)
            updateAddressBar()
        }

        // Browser ad blocking: requests to listed ad / tracker domains get an empty answer.
        // (Off on YouTube / Prime Video pages; runs on a WebView background thread.)
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (!adBlockOn || !pageIsWeb || req.isForMainFrame) return null
            if (!AdBlock.isBlocked(req.url.host)) return null
            if (blocked.incrementAndGet() % 5 == 1) main.post { updateAddressBar() }
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null, ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(JS_INIT, null)
            if (fullMode) view.evaluateJavascript("$JS_FIT(true)", null)
            if (adBlockOn && pageIsWeb) view.evaluateJavascript("$JS_STYLE('autotube-adcss', ${JSONObject.quote(AdBlock.CSS)})", null)
            remember(url)
            updateAddressBar()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // YouTube is a single-page app: most navigation never fires onPageFinished.
            view.evaluateJavascript(JS_INIT, null)
            if (fullMode) view.evaluateJavascript("$JS_FIT(true)", null)
            remember(url)
            updateAddressBar()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone, crashed=${detail.didCrash()}")
            if (inCar) CarLog.add(context, "web renderer gone, crashed=${detail.didCrash()}")
            main.post { recreateWeb() }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null || locked) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            fullscreenBox.addView(view, LayoutParams(MATCH, MATCH))
            fullscreenBox.visibility = VISIBLE
        }

        override fun onHideCustomView() = exitFullscreen()

        // Without this, a grey "play" placeholder flashes before every video.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // No dialogs can be shown on the car display: answer them quietly.
        override fun onJsAlert(v: WebView, url: String?, msg: String?, r: JsResult): Boolean { r.confirm(); return true }
        override fun onJsConfirm(v: WebView, url: String?, msg: String?, r: JsResult): Boolean { r.confirm(); return true }
        override fun onJsBeforeUnload(v: WebView, url: String?, msg: String?, r: JsResult): Boolean { r.confirm(); return true }

        // Protected music / video (Widevine DRM) is allowed for the services and the browser, like a
        // desktop browser does by default; camera, microphone etc. are never granted.
        override fun onPermissionRequest(request: PermissionRequest) {
            val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
            val s = Service.forUrl(request.origin?.toString())
            if (drm in request.resources && prefs.serviceOn(s)) request.grant(arrayOf(drm)) else request.deny()
        }

        override fun onReceivedTitle(view: WebView, title: String?) = updateAddressBar()

        // A link that opens a new tab: load it here (if it was a tap and isn't an ad); else drop it.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) {
                blocked.incrementAndGet()
                updateAddressBar()
                return false
            }
            val catcher = WebView(view.context)
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val target = r.url.toString()
                    v.destroy()
                    if (adBlockOn && AdBlock.isBlocked(r.url.host)) { blocked.incrementAndGet(); toast("Blocked a pop-up") }
                    else main.post { load(target) }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, cb: GeolocationPermissions.Callback) =
            cb.invoke(origin, false, false)
    }

    /** Called by the page (on a WebView thread). */
    private inner class JsApi {
        @JavascriptInterface
        fun onInputFocus(value: String?, isSearch: Boolean, isPassword: Boolean) = main.post {
            if (destroyed || locked) return@post
            val s = service
            val label = if (s == Service.WEB) "this site" else s.label
            keyboard.open(value ?: "", isSearch, label, suggest = s == Service.YOUTUBE, secret = isPassword)
        }.let { }

        @JavascriptInterface
        fun onPlayState(isPlaying: Boolean) = main.post { if (!destroyed) setPlaying(isPlaying) }.let { }

        @JavascriptInterface
        fun skipAds(): Boolean = prefs.skipAds

        @JavascriptInterface
        fun onAd(showing: Boolean) = Log.d(TAG, if (showing) "ad: skipping" else "ad: done, video resumes").let { }

        @JavascriptInterface
        fun onTime(seconds: Int) = main.post {
            if (!destroyed && YouTubeLinks.isWatchPage(prefs.lastUrl)) prefs.lastTime = seconds
        }.let { }

        /** Start page: its search box. */
        @JavascriptInterface
        fun openAddress() = main.post { if (!destroyed && !locked) openAddress("") }.let { }

        /** Start page: the × on a bookmark tile. */
        @JavascriptInterface
        fun removeBookmark(url: String) = main.post {
            if (destroyed) return@post
            prefs.bookmarks = prefs.bookmarks.filter { it.second != url }
            if (web.url == Service.START_URL) showStartPage()
        }.let { }
    }

    // ---------------------------------------------------------------- small views

    private fun railButton(icon: Int, label: String, onClick: () -> Unit): ImageButton {
        val b = ImageButton(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = label
            background = context.pressable(0x00000000, 0xFF34393C.toInt(), 14)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }
        rail.addView(b, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(0, dp(2), 0, dp(2)) })
        return b
    }

    private fun barButton(icon: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = label
        background = context.pressable(0x00000000, 0xFF34393C.toInt(), 14)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(76), dp(68)).apply { setMargins(dp(4), 0, dp(4), 0) }
    }

    private fun toast(msg: String, ms: Long = 2500) {
        toastView.text = msg
        toastView.visibility = VISIBLE
        toastView.bringToFront()
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, ms)
    }

    companion object {
        private const val TAG = "CarScreen"
        private const val RECHECK_MS = 3000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val BG = 0xFF0F0F0F.toInt()
        private const val RAIL = 0xFF181A1B.toInt()

        // Hooks text boxes (to open our keyboard) and video play/pause; safe to run many times.
        private val JS_INIT = """
            (function(){
              if (window.__autotube) return; window.__autotube = true;
              // No ads, like YouTube ReVanced: ad cards are hidden; a video ad is muted, run at 16x and
              // its Skip button pressed, then the video's sound and speed come back. The ad is never
              // seeked to its end: when the real video takes over, the player's "ad" mark can lag
              // behind, and a seek then would jump the real video to its end (blank screen).
              // (This can run on a page that has no <head> yet: touch the DOM only inside the timers.)
              var NOADS = 'ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,' +
                'ytm-promoted-sparkles-text-search-renderer,ytm-companion-ad-renderer,ytm-companion-slot,' +
                'ytm-display-ad-renderer,ytm-in-feed-ad-layout-renderer,ytm-mealbar-promo-renderer,' +
                'ytm-rich-item-renderer:has(ad-slot-renderer),ytm-item-section-renderer:has(ytm-promoted-sparkles-web-renderer),' +
                '.ytp-ad-overlay-container,.ytp-featured-product{display:none!important}';
              function unmute(v){
                if (window.__atUserMuted) return; // muted with AutoTube's mute button: leave it
                var mp = document.getElementById('movie_player');
                if (mp && mp.unMute) { try { mp.unMute(); } catch (x) {} }
                else { var b = document.querySelector('.ytp-unmute, .ytp-unmute-button'); if (b) b.click(); }
                setTimeout(function(){ if (v.muted) v.muted = false; }, 400);
              }
              function adOver(v){
                if (v.__atAdOver) return;
                v.__atAdOver = true;
                try { v.playbackRate = 1; } catch (x) {}
                v.__atTicks = 0; // the video after the ad gets its own unmute window
                unmute(v);
              }
              setInterval(function(){
                var skip = AutoTube.skipAds(), st = document.getElementById('autotube-noads');
                if (skip && !st && document.head) {
                  st = document.createElement('style'); st.id = 'autotube-noads'; st.textContent = NOADS;
                  document.head.appendChild(st);
                } else if (!skip && st) st.remove();
                var p = document.querySelector('.html5-video-player'), v = p && p.querySelector('video');
                if (!v) return;
                if (skip && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'))) {
                  if (!v.__atAd) {
                    v.__atAd = true; v.__atAdOver = false;
                    v.__atAdSrc = v.currentSrc; v.__atAdDur = v.duration;
                    AutoTube.onAd(true);
                  }
                  if (!isFinite(v.__atAdDur) && isFinite(v.duration)) v.__atAdDur = v.duration;
                  // Same element, but is it still the ad? A new source or a different length means
                  // the real video has started: stop speeding it up.
                  var changed = v.currentSrc !== v.__atAdSrc ||
                    (isFinite(v.__atAdDur) && isFinite(v.duration) && Math.abs(v.duration - v.__atAdDur) > 1);
                  if (!changed && !v.__atAdOver) {
                    if (!v.muted) v.muted = true;
                    if (v.playbackRate != 16) try { v.playbackRate = 16; } catch (x) {}
                  } else adOver(v);
                  var s = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
                  if (s) s.click();
                } else if (v.__atAd) {
                  v.__atAd = false; AutoTube.onAd(false);
                  adOver(v);
                }
              }, 250);
              function editable(t){
                if (!t || !t.tagName) return false;
                var n = t.tagName.toLowerCase();
                if (n == 'textarea') return true;
                if (n == 'input') {
                  var ty = (t.getAttribute('type') || 'text').toLowerCase();
                  return ['text','search','email','url','tel','number','password'].indexOf(ty) >= 0;
                }
                return !!t.isContentEditable;
              }
              function opened(t){
                if (!editable(t)) return;
                window.__atEl = t;
                var ty = (t.getAttribute && t.getAttribute('type') || '').toLowerCase();
                var hint = [t.name, t.id, t.className, ty, t.getAttribute('aria-label'), t.placeholder].join(' ');
                AutoTube.onInputFocus(t.isContentEditable ? t.innerText : (t.value || ''), /search/i.test(hint), ty == 'password');
              }
              document.addEventListener('focusin', function(e){ opened(e.target); }, true);
              document.addEventListener('click', function(e){ if (e.target === document.activeElement) opened(e.target); }, true);
              var tick = 0;
              setInterval(function(){
                var es = document.querySelectorAll('input,textarea');
                for (var i = 0; i < es.length; i++) if (es[i].getAttribute('inputmode') != 'none') es[i].setAttribute('inputmode', 'none');
                var vs = document.getElementsByTagName('video');
                for (var j = 0; j < vs.length; j++) {
                  var v = vs[j];
                  if (!v.__at) {
                    v.__at = true;
                    v.addEventListener('play', function(){ AutoTube.onPlayState(true); });
                    v.addEventListener('playing', function(){ AutoTube.onPlayState(true); });
                    v.addEventListener('pause', function(){ AutoTube.onPlayState(false); });
                  }
                  // Sites start muted when a page opens without a tap (resume, sent from phone), and
                  // may mute again while they set up. Unmute during the first 10 s of playing of each
                  // video, so a deliberate mute later is left alone.
                  if (v.__atSrc !== v.currentSrc) { v.__atSrc = v.currentSrc; v.__atTicks = 0; }
                  if (!v.paused && v.__atTicks < 10) { v.__atTicks++; if (!v.__atAd && v.muted) unmute(v); }
                }
                if (++tick % 5 == 0 && vs.length && !vs[0].paused && location.pathname.indexOf('/watch') == 0)
                  AutoTube.onTime(Math.floor(vs[0].currentTime));
              }, 1000);
            })();
        """.trimIndent()

        private val JS_TOGGLE_PLAY = """
            (function(){
              var vs = document.getElementsByTagName('video'), v = null;
              for (var i = 0; i < vs.length; i++) if (vs[i].currentSrc || vs[i].src) { v = vs[i]; break; }
              if (!v) return 'none';
              if (v.paused) { v.play(); return 'play'; }
              v.pause(); return 'pause';
            })()
        """.trimIndent()

        // Shows only the <video> element, pinned over the whole page: the picture fills the car
        // screen. Everything else is made invisible (and so untappable) rather than moved.
        private val JS_FIT = """
            (function(on){
              var id = 'autotube-fit', s = document.getElementById(id);
              if (!on) { if (s) s.remove(); return; }
              if (!s) { s = document.createElement('style'); s.id = id; (document.head || document.documentElement).appendChild(s); }
              s.textContent =
                'html,body{overflow:hidden!important;background:#000!important}' +
                'body *{visibility:hidden!important}' +
                'video{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;' +
                'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
                'z-index:2147483647!important;background:#000!important;object-fit:contain!important;margin:0!important;transform:none!important}' +
                '*:has(video){transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;overflow:visible!important}';
            })
        """.trimIndent()

        private val JS_SET_INPUT = """
            (function(v){
              var e = window.__atEl || document.activeElement;
              if (!e) return;
              try { e.focus(); } catch (x) {}
              if (e.isContentEditable) e.innerText = v;
              else {
                var proto = e.tagName == 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, v);
              }
              e.dispatchEvent(new InputEvent('input', {bubbles: true, data: v, inputType: 'insertText'}));
              e.dispatchEvent(new Event('change', {bubbles: true}));
              var o = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true};
              var go = e.dispatchEvent(new KeyboardEvent('keydown', o));
              e.dispatchEvent(new KeyboardEvent('keypress', o));
              e.dispatchEvent(new KeyboardEvent('keyup', o));
              if (go && e.form && e.tagName != 'TEXTAREA') {
                try { e.form.requestSubmit ? e.form.requestSubmit() : e.form.submit(); } catch (x) {}
              }
            })
        """.trimIndent()

        private const val JS_BLUR = "(function(){ var e = window.__atEl; if (e && e.blur) e.blur(); })()"

        // The page's main video: the biggest <video> that has something loaded.
        // (Uses only __-prefixed names: it is pasted into functions whose parameters must not be shadowed.)
        private const val JS_MAIN_VIDEO = "var __vs=document.getElementsByTagName('video'),v=null,__best=-1;" +
            "for(var __i=0;__i<__vs.length;__i++){var __e=__vs[__i];if(!(__e.currentSrc||__e.src))continue;" +
            "var __r=__e.getBoundingClientRect(),__s=__r.width*__r.height;if(__s>__best){__best=__s;v=__e;}}"

        /** {t: seconds, d: length (-1 = live), p: paused, m: muted} of the main video, or null. */
        private const val JS_MEDIA_STATE = "(function(){$JS_MAIN_VIDEO if(!v)return null;" +
            "var mp=document.getElementById('movie_player');" +
            "var m=v.muted||!!(mp&&mp.isMuted&&mp.isMuted());" +
            "return {t:v.currentTime,d:isFinite(v.duration)?v.duration:-1,p:v.paused,m:m};})()"

        /** seek (to seconds) / skip (by seconds) / mute (toggle) on the main video; YouTube via its player. */
        private const val JS_MEDIA_CMD = "(function(a,x){$JS_MAIN_VIDEO if(!v)return;" +
            "var mp=document.getElementById('movie_player'),yt=!!(mp&&mp.seekTo);" +
            "var d=isFinite(v.duration)?v.duration:1e9;" +
            "if(a=='seek'||a=='skip'){var t=a=='seek'?x:v.currentTime+x;t=Math.max(0,Math.min(d-0.5,t));" +
            "if(yt)mp.seekTo(t,true);else v.currentTime=t;}" +
            "else if(a=='mute'){var m=v.muted||!!(mp&&mp.isMuted&&mp.isMuted());" +
            // The user decided: no automatic unmuting while their mute is on (this page, next videos too).
            "v.__atTicks=99;window.__atUserMuted=!m;" +
            "if(mp&&mp.mute){if(m)mp.unMute();else mp.mute();}v.muted=!m;}})"

        /** Adds (or replaces) a <style id=...> on the page. */
        private const val JS_STYLE = "(function(id,css){var s=document.getElementById(id);" +
            "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=css;})"

        /** Suggested sites on the browser's start page (after the user's bookmarks). */
        private val DEFAULT_SITES = listOf(
            "YouTube Music" to "https://music.youtube.com/",
            "JioSaavn" to "https://www.jiosaavn.com/",
            "Gaana" to "https://gaana.com/",
            "Spotify" to "https://open.spotify.com/",
            "SoundCloud" to "https://m.soundcloud.com/",
            "Internet radio" to "https://radio.garden/",
            "Wikipedia" to "https://www.wikipedia.org/",
            "Google" to "https://www.google.com/",
        )
    }
}

/**
 * WebView tuned for the car display: never asks Android for its (phone-only) keyboard, and keeps
 * rendering even though Android thinks the phone screen is what's visible.
 */
class CarWebView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : WebView(ctx, attrs) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null

    override fun onCheckIsTextEditor(): Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        // On the projected car display the window can report INVISIBLE while it is on screen,
        // which would pause the video. Only a real GONE is passed on.
        super.onWindowVisibilityChanged(if (visibility == View.GONE) View.GONE else View.VISIBLE)
    }
}
