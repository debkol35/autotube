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
 * The car screen: YouTube's mobile site in a WebView, a rail of big buttons on the left
 * (made for wide car displays), our own keyboard, a "fit video" mode and the driving cover.
 * Used by both the Android Auto CarActivity and the phone preview.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class CarScreen(ctx: Context, private val inCar: Boolean) : FrameLayout(ctx) {

    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val content = FrameLayout(ctx)
    private var web: CarWebView = CarWebView(ctx)
    private val fullscreenBox = FrameLayout(ctx)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val keyboard: CarKeyboard
    private val driveCover: LinearLayout
    private val coverTitle = TextView(ctx)
    private val coverText = TextView(ctx)
    private val toastView = TextView(ctx)
    private val playBtn: ImageButton
    private val fitBtn: ImageButton
    private var fitMode = false
    private var playing = false
    private var destroyed = false
    private val guard = DriveGuard(ctx) { onDriveState(it) }
    private val bridgeListener: (String) -> Unit = { open(it) }
    private val hideToast = Runnable { toastView.visibility = GONE }

    /** The picture (and browsing) is allowed only once the phone's GPS says the car is parked. */
    private val locked get() = !prefs.noticeAccepted || guard.state != DriveGuard.State.PARKED

    init {
        setBackgroundColor(BG)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        addView(row, LayoutParams(MATCH, MATCH))

        val rail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(RAIL)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        row.addView(rail, LinearLayout.LayoutParams(dp(76), MATCH))
        row.addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))

        content.addView(web, LayoutParams(MATCH, MATCH))

        keyboard = CarKeyboard(ctx, ::onKeyboardDone, ::closeKeyboard).apply { visibility = GONE }
        content.addView(keyboard, LayoutParams(MATCH, MATCH))

        driveCover = buildDriveCover().apply { visibility = GONE }
        content.addView(driveCover, LayoutParams(MATCH, MATCH))

        // Real fullscreen (YouTube's own fullscreen button) covers the rail too.
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

        railButton(rail, R.drawable.ic_back, "Back") { handleBack() }
        railButton(rail, R.drawable.ic_home, "YouTube home") { if (!blockedWhileLocked()) open(YouTubeLinks.HOME) }
        railButton(rail, R.drawable.ic_search, "Search") { if (!blockedWhileLocked()) keyboard.open("", true) }
        playBtn = railButton(rail, R.drawable.ic_play, "Play or pause") { togglePlay() }
        fitBtn = railButton(rail, R.drawable.ic_fit, "Fit video to screen") { if (!blockedWhileLocked()) setFit(!fitMode) }

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
        web.loadUrl(url)
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

    /** @return true if Back was used up here (keyboard, fullscreen, fit mode, page history). */
    fun handleBack(): Boolean {
        when {
            keyboard.visibility == VISIBLE -> closeKeyboard()
            customView != null -> exitFullscreen()
            fitMode -> setFit(false)
            web.canGoBack() -> web.goBack()
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- actions

    fun open(url: String) {
        if (destroyed) return
        if (keyboard.visibility == VISIBLE) closeKeyboard()
        exitFullscreen()
        web.loadUrl(url)
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
        playBtn.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setFit(on: Boolean) {
        fitMode = on
        fitBtn.setImageResource(if (on) R.drawable.ic_fit_exit else R.drawable.ic_fit)
        web.evaluateJavascript("$JS_FIT($on)", null)
        if (on) toast("Video fitted to the screen. Back or the same button to undo")
    }

    private fun blockedWhileLocked(): Boolean {
        if (locked) toast(if (guard.state == DriveGuard.State.MOVING) "Not while the car is moving" else coverTitle.text.toString())
        return locked
    }

    // ---------------------------------------------------------------- keyboard

    private fun onKeyboardDone(text: String, search: Boolean) {
        closeKeyboard()
        if (search) {
            if (text.isNotBlank()) web.loadUrl(YouTubeLinks.search(text))
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

    /** Picks up setup done on the phone while this screen is open (notice accepted, location allowed). */
    private val recheck: Runnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (guard.state == DriveGuard.State.NO_PERMISSION && DriveGuard.hasLocation(context)) guard.start()
            onDriveState(guard.state)
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

    // ---------------------------------------------------------------- fullscreen

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
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = cleanUserAgent(userAgentString)
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
        val old = web
        content.removeView(old)
        try { old.destroy() } catch (e: Exception) { Log.w(TAG, "destroy", e) }
        web = CarWebView(context)
        setupWeb(web)
        content.addView(web, 0, LayoutParams(MATCH, MATCH))
        web.loadUrl(prefs.lastUrl ?: YouTubeLinks.HOME)
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
                "http", "https" -> return false
                "vnd.youtube" -> {
                    val id = u.schemeSpecificPart?.substringBefore('?')?.trim('/')
                    if (id != null && id.length == 11) view.loadUrl(YouTubeLinks.watch(id))
                }
                "intent" -> try {
                    Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                        .getStringExtra("browser_fallback_url")
                        ?.takeIf { it.startsWith("http") }
                        ?.let { view.loadUrl(it) }
                } catch (e: Exception) { Log.d(TAG, "intent url: $e") }
            }
            return true // never leave for other apps: nothing else can show on the car screen
        }

        override fun onPageFinished(view: WebView, url: String?) {
            view.evaluateJavascript(JS_INIT, null)
            if (fitMode) view.evaluateJavascript("$JS_FIT(true)", null)
            remember(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            // YouTube is a single-page app: most navigation never fires onPageFinished.
            view.evaluateJavascript(JS_INIT, null)
            if (fitMode) view.evaluateJavascript("$JS_FIT(true)", null)
            remember(url)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone, crashed=${detail.didCrash()}")
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
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, cb: GeolocationPermissions.Callback) =
            cb.invoke(origin, false, false)
    }

    /** Called by the page (on a WebView thread). */
    private inner class JsApi {
        @JavascriptInterface
        fun onInputFocus(value: String?, isSearch: Boolean) = main.post {
            if (destroyed || locked) return@post
            keyboard.open(value ?: "", isSearch)
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

    private fun railButton(rail: LinearLayout, icon: Int, label: String, onClick: () -> Unit): ImageButton {
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

    private fun toast(msg: String, ms: Long = 2500) {
        toastView.text = msg
        toastView.visibility = VISIBLE
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

        /** Plain Chrome-for-Android user agent (without the "wv" WebView markers). */
        fun cleanUserAgent(ua: String): String = ua
            .replace("; wv", "")
            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
            .replace(Regex(" Build/[^;)]+"), "")

        // Hooks text boxes (to open our keyboard) and video play/pause; safe to run many times.
        private val JS_INIT = """
            (function(){
              if (window.__autotube) return; window.__autotube = true;
              // No ads, like YouTube ReVanced: ad cards are hidden, video ads are muted, run at 16x and
              // skipped (jump to the end / Skip button), then the video's sound and speed come back.
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
              setInterval(function(){
                var skip = AutoTube.skipAds(), st = document.getElementById('autotube-noads');
                if (skip && !st && document.head) {
                  st = document.createElement('style'); st.id = 'autotube-noads'; st.textContent = NOADS;
                  document.head.appendChild(st);
                } else if (!skip && st) st.remove();
                var p = document.querySelector('.html5-video-player'), v = p && p.querySelector('video');
                if (!v) return;
                if (skip && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'))) {
                  if (!v.__atAd) { v.__atAd = true; AutoTube.onAd(true); }
                  if (!v.muted) v.muted = true;
                  if (v.playbackRate != 16) try { v.playbackRate = 16; } catch (x) {}
                  if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.5)
                    try { v.currentTime = v.duration - 0.1; } catch (x) {}
                  var s = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
                  if (s) s.click();
                } else if (v.__atAd) {
                  v.__atAd = false; AutoTube.onAd(false);
                  try { v.playbackRate = 1; } catch (x) {}
                  v.__atTicks = 0; // the video after the ad gets its own unmute window
                  unmute(v);
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
                var hint = [t.name, t.id, t.className, t.getAttribute('type'), t.getAttribute('aria-label'), t.placeholder].join(' ');
                AutoTube.onInputFocus(t.isContentEditable ? t.innerText : (t.value || ''), /search/i.test(hint));
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
                  // YouTube starts muted when a page opens without a tap (resume, sent from phone), and
                  // may mute again while it sets up. Unmute during the first 10 s of playing of each
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
