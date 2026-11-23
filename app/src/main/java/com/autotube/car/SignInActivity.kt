package com.autotube.car

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Sign in to YouTube, Netflix or Prime Video here on the phone, with the phone's own keyboard.
 * The car screen runs in the same app, so it shares these cookies (and so the login).
 * Uses the same user agent as the car screen, so the site sees the same kind of browser.
 */
class SignInActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Service.values().firstOrNull { it.id == intent.getStringExtra(EXTRA_SERVICE) } ?: Service.YOUTUBE
        title = "Sign in to ${s.label}"
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = if (s.desktop) Service.desktopUserAgent(userAgentString) else Service.mobileUserAgent(userAgentString)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.webViewClient = object : WebViewClient() {
            // Stay in this page for web links; other schemes (app links) are ignored.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                r.url.scheme?.lowercase() !in setOf("http", "https")
        }
        setContentView(web)
        web.loadUrl(s.signIn)
        Toast.makeText(this, "Sign in, then press Back. The car screen uses this login.", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_SERVICE = "service" }
}
