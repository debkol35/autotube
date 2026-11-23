package com.autotube.car

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * The car screen: YouTube's mobile site (or Netflix / Prime Video, if turned on) in a WebView, a
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
    private var fullMode = false
    private var playing = false
    private var destroyed = false
    private val guard = DriveGuard(ctx) { onDriveState(it) }
    private val bridgeListener: (String) -> Unit = { open(it) }
    private val hideToast = Runnable { toastView.visibility = GONE }
    private val hideFullBar = Runnable { fullBar.visibility = GONE }

    /** The picture (and browsing) is allowed only once the phone's GPS says the car is parked. */
    private val locked get() = !prefs.noticeAccepted || guard.state != DriveGuard.State.PARKED

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

        content.addView(web, LayoutParams(MATCH, MATCH))

        // Full screen: the page shows only the video; a tap anywhere brings up a small control bar.
        fullTouch.apply {
            visibility = GONE
            isClickable = true
            setOnClickListener { showFullBar() }
        }
        content.addView(fullTouch, LayoutParams(MATCH, MATCH))
        fullBar.apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = context.pressable(0xE0181A1B.toInt(), 0xE0181A1B.toInt(), 22)
            addView(barButton(R.drawable.ic_fit_exit, "Exit full screen") { setFull(false) })
            fullPlayBtn = barButton(R.drawable.ic_play, "Play or pause") { togglePlay(); showFullBar() }
            addView(fullPlayBtn)
        }
        content.addView(fullBar, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18)
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
        appsBtn = railButton(R.drawable.ic_apps, "YouTube, Netflix, Prime Video") { if (!blockedWhileLocked()) showApps() }
        updateAppsButton()

        setupWeb(web)
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
        content.removeView(web)
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

    /** Loads [url] with the user agent its site needs (desktop for Netflix / Prime Video). */
    private fun load(url: String) {
        val ua = userAgentFor(url)
        if (web.settings.userAgentString != ua) web.settings.userAgentString = ua
        setPlaying(false) // the new page reports its own video state
        web.loadUrl(url)
    }

    private fun userAgentFor(url: String?): String =
        if (Service.forUrl(url).desktop) Service.desktopUserAgent(baseUa) else Service.mobileUserAgent(baseUa)

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
        if (inCar) CarLog.add(context, "full screen $on")
    }

    private fun showFullBar() {
        if (!fullMode) return
        fullBar.visibility = VISIBLE
        fullBar.bringToFront()
        main.removeCallbacks(hideFullBar)
        main.postDelayed(hideFullBar, 4000)
    }

    private fun openSearch() {
        val s = service
        keyboard.open("", true, s.label, suggest = s == Service.YOUTUBE)
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
                    Service.NETFLIX -> 0xFF8E0A12.toInt()
                    Service.PRIME -> 0xFF1A6FB0.toInt()
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
            if (text.isNotBlank()) load(service.search(text))
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
            setSupportMultipleWindows(false)
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
        content.removeView(old)
        try { old.destroy() } catch (e: Exception) { Log.w(TAG, "destroy", e) }
        web = CarWebView(context)
        setupWeb(web)
        content.addView(web, 0, LayoutParams(MATCH, MATCH))
        load(prefs.lastUrl ?: YouTubeLinks.HOME)
    }

    private fun remember(url: String?) {
        if (url == null || !url.startsWith("http")) return
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
                    // Crossing between YouTube and Netflix / Prime Video needs the other user agent.
                    if (!req.isForMainFrame || userAgentFor(u.toString()) == view.settings.userAgentString) return false
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

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(JS_INIT, null)
            if (fullMode) view.evaluateJavascript("$JS_FIT(true)", null)
            remember(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // YouTube is a single-page app: most navigation never fires onPageFinished.
            view.evaluateJavascript(JS_INIT, null)
            if (fullMode) view.evaluateJavascript("$JS_FIT(true)", null)
            remember(url)
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

        // Netflix / Prime Video (and paid YouTube films) need DRM (Widevine); nothing else is granted.
        override fun onPermissionRequest(request: PermissionRequest) {
            val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
            val s = Service.forUrl(request.origin?.toString())
            val known = s != Service.YOUTUBE || (request.origin?.host ?: "").endsWith("youtube.com")
            if (drm in request.resources && known && prefs.serviceOn(s)) request.grant(arrayOf(drm)) else request.deny()
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
            keyboard.open(value ?: "", isSearch, s.label, suggest = s == Service.YOUTUBE, secret = isPassword)
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
