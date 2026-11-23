package com.autotube.car

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Phone screen: gets AutoTube onto the Android Auto launcher and holds its few settings. */
class MainActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private val night by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val fg by lazy { if (night) Color.WHITE else 0xFF15191B.toInt() }
    private val muted by lazy { if (night) 0xFFA3AAAE.toInt() else 0xFF5C6469.toInt() }
    private val good = 0xFF2BB3A3.toInt()
    private val bad = 0xFFE5735A.toInt()

    private lateinit var installStatus: TextView
    private lateinit var installDetail: TextView
    private lateinit var installButton: Button
    private lateinit var locationStatus: TextView
    private lateinit var locationButton: Button
    private lateinit var carStatus: TextView
    private lateinit var sendBox: EditText
    private lateinit var pickButton: Button
    private lateinit var changeAppButton: Button
    private lateinit var pickTip: TextView
    private lateinit var adsSwitch: Switch
    private lateinit var shizukuButton: Button
    private lateinit var shizukuHint: TextView
    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_SHIZUKU) runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) runShizukuInstall() else refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply { addView(col); isFillViewport = true })

        col.addView(text("AutoTube", 30f, bold = true))
        col.addView(text("YouTube on your Android Auto screen, when the car is parked", 16f, color = muted)
            .apply { setPadding(0, dp(2), 0, dp(14)) })

        // 1. Install record
        col.addView(card("1 · Let Android Auto show it").apply {
            installStatus = text("", 16f, bold = true)
            addView(installStatus)
            addView(text("Android Auto only lists full-screen apps that its Play Store check accepts. AutoTube " +
                "reinstalls itself through the phone's own installer with Play Store recorded. Newer Android " +
                "only allows that with \"shell\" rights, so use one of these (and again after every update):\n" +
                "• No computer: the free Shizuku app (needs Wi-Fi for Wireless debugging). The button below " +
                "walks you through it.\n" +
                "• Computer: the AutoTube installer from the release zip, with this phone plugged in.",
                14f, color = muted).pad())
            shizukuButton = button("Set up without a computer") { shizukuSetup() }
            addView(shizukuButton)
            shizukuHint = text("", 13f, color = muted).pad()
            addView(shizukuHint)
            installButton = button("Try it from the phone (Android 13 and older)", primary = false) { reinstallSelf() }
            addView(installButton)
            installDetail = text("", 12f, color = muted).pad()
            addView(installDetail)
        })

        // 2. Launcher
        col.addView(card("2 · Switch it on in Android Auto").apply {
            addView(text("Phone Settings → Connected devices → Android Auto → Customize launcher → " +
                "turn on AutoTube. (The menu path differs a little between phones.)", 15f))
            addView(text("Then on the car screen open the Android Auto app grid and tap AutoTube. " +
                "If it's missing, force-stop Android Auto, reconnect, and check step 1.", 14f, color = muted).pad())
            addView(button("Open Android Auto settings", primary = false) { openAndroidAuto() })
        })

        // 3. Driving safety (always on)
        col.addView(card("3 · Location for the driving lock").apply {
            addView(text("Video is shown only while the phone's GPS says the car is parked. While driving the " +
                "picture is covered and the sound keeps playing; it comes back a few seconds after you stop. " +
                "Without location, the picture stays covered.", 14f, color = muted))
            locationStatus = text("", 14f, bold = true).pad()
            addView(locationStatus)
            locationButton = button("Allow location") { askLocation() }
            addView(locationButton)
        })

        // 4. Send to car
        col.addView(card("Send to the car").apply {
            sendBox = EditText(this@MainActivity).apply {
                hint = "Paste a YouTube link or type a search"
                setTextColor(fg)
                setHintTextColor(muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_SEND
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            }
            addView(sendBox, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(button("Send to car") { send() })
            pickButton = button("", primary = false) { pickInApp() }
            addView(pickButton)
            changeAppButton = button("", primary = false) { chooseApp() }
            addView(changeAppButton)
            pickTip = text("", 14f, color = muted).pad()
            addView(pickTip)
            carStatus = text("", 13f, color = muted).pad()
            addView(carStatus)
        })

        // 5. Other services
        col.addView(card("Netflix and Prime Video (optional)").apply {
            addView(text("Adds an Apps button on the car screen to switch between YouTube, Netflix and Prime " +
                "Video. Sign in here on the phone first. These services protect their videos (DRM), so on " +
                "some phones or cars they may refuse to play, play in lower quality, or show a black picture.",
                14f, color = muted))
            for (s in listOf(Service.NETFLIX, Service.PRIME)) {
                addView(Switch(this@MainActivity).apply {
                    text = "Show ${s.label} on the car screen"
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    isChecked = prefs.serviceOn(s)
                    setOnCheckedChangeListener { _, on -> prefs.setServiceOn(s, on) }
                    setPadding(0, dp(10), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(button("Sign in to ${s.label}", primary = false) { signIn(s) })
            }
            addView(button("Sign in to YouTube (optional, e.g. for Premium)", primary = false) { signIn(Service.YOUTUBE) })
            addView(text("Google may refuse YouTube sign-in inside apps; YouTube works without signing in.",
                12f, color = muted).pad())
        })

        // 6. Settings
        col.addView(card("Settings").apply {
            adsSwitch = Switch(this@MainActivity).apply {
                text = "Skip YouTube ads on the car screen"
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                isChecked = prefs.skipAds
                setOnCheckedChangeListener { _, on -> prefs.skipAds = on }
            }
            addView(adsSwitch, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(text("Video ads are muted, fast-forwarded and skipped, and ad cards are hidden (like " +
                "YouTube ReVanced). Turn off to see YouTube's ads as normal.", 14f, color = muted).pad())
            addView(button("Share car log (for problem reports)", primary = false) { shareLog() })
        })

        // 6. Preview
        col.addView(card("Try it on this phone").apply {
            addView(text("Opens the same car screen here, sideways, so you can check it before driving.", 14f, color = muted))
            addView(button("Open the car screen here", primary = false) {
                startActivity(Intent(this@MainActivity, PreviewActivity::class.java))
            })
        })

        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        col.addView(text("AutoTube $v · unofficial; not made by or affiliated with Google or YouTube. " +
            "The car screen shows YouTube's mobile website.", 12f, color = muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })

        if (!prefs.noticeAccepted) showNotice()
        try { rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (e: Throwable) {}
    }

    override fun onDestroy() {
        try { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (e: Throwable) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showNotice() {
        AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage(
                "• AutoTube is an unofficial app. It is not made by, endorsed by or affiliated with Google, " +
                    "YouTube or Android Auto.\n\n" +
                    "• Video shows on the car screen only while the phone's GPS says the car is parked. " +
                    "Never watch or operate it while driving, and follow your local laws.\n\n" +
                    "• It shows YouTube's mobile website and, if you leave it on, skips ads. YouTube's terms " +
                    "apply to your use of YouTube.\n\n" +
                    "• Use at your own risk; it comes with no warranty."
            )
            .setCancelable(false)
            .setPositiveButton("I understand") { _, _ -> prefs.noticeAccepted = true; refresh() }
            .setNegativeButton("Quit") { _, _ -> finish() }
            .show()
    }

    private fun refresh() {
        val s = Installer.source(this)
        if (s.ok) {
            installStatus.text = "✓ Ready: Android Auto sees AutoTube as a Play Store app"
            installStatus.setTextColor(good)
            installButton.visibility = View.GONE
            shizukuButton.text = "Redo setup without a computer"
            shizukuButton.visibility = View.VISIBLE
        } else {
            installStatus.text = "✗ Not yet: Android Auto will hide AutoTube"
            installStatus.setTextColor(bad)
            installButton.visibility = View.VISIBLE
            shizukuButton.text = "Set up without a computer"
        }
        shizukuHint.text = when (ShizukuSetup.step(this)) {
            ShizukuSetup.Step.NOT_INSTALLED -> "Next: install Shizuku (the button opens Play Store)."
            ShizukuSetup.Step.NOT_RUNNING -> "Next: start Shizuku (open it > \"Start via Wireless debugging\"; " +
                "it shows how to pair once). Then come back here and tap the button."
            ShizukuSetup.Step.NEEDS_PERMISSION -> "Next: tap the button and allow AutoTube in Shizuku's prompt."
            ShizukuSetup.Step.READY -> "Shizuku is running: tap the button, then \"Update\" on the next screen."
        }
        installDetail.text = "Installer: ${s.installer ?: "unknown"}" +
            (if (s.initiator != null) " · made by: ${s.initiator}" else "") +
            when {
                s.ok -> ""
                s.initiator == Installer.SYSTEM_INSTALLER ->
                    "\nThis phone's installer ignored the Play Store request (Android 14 and newer do). " +
                        "Use the installer script on a computer."
                else -> "\nRun the installer script on a computer with this phone plugged in."
            }

        val fine = DriveGuard.hasLocation(this)
        val always = DriveGuard.hasBackgroundLocation(this)
        when {
            fine && always -> {
                locationStatus.text = "✓ Location: allowed all the time"
                locationStatus.setTextColor(good)
                locationButton.visibility = View.GONE
            }
            fine -> {
                locationStatus.text = "Set location to \"Allow all the time\": on the car screen the phone counts " +
                    "AutoTube as in the background, and without it the picture stays covered"
                locationStatus.setTextColor(bad)
                locationButton.text = "Set \"Allow all the time\""
                locationButton.visibility = View.VISIBLE
            }
            else -> {
                locationStatus.text = "✗ Location not allowed: the car screen will keep the picture covered"
                locationStatus.setTextColor(bad)
                locationButton.text = "Allow location"
                locationButton.visibility = View.VISIBLE
            }
        }

        val apps = YouTubeApp.all(this)
        val yt = YouTubeApp.find(this)
        if (yt != null) {
            val name = YouTubeApp.label(this, yt)
            pickButton.text = "Pick a video in $name"
            pickButton.visibility = View.VISIBLE
            pickTip.text = "In $name tap Share → \"Play in car (AutoTube)\". It plays on the car screen " +
                "(or waits there if the car screen is closed)."
        } else {
            pickButton.visibility = View.GONE
            pickTip.text = "Tip: in the YouTube app or YouTube ReVanced tap Share → \"Play in car (AutoTube)\"."
        }
        changeAppButton.text = "Use a different app (${apps.size} installed)"
        changeAppButton.visibility = if (apps.size > 1) View.VISIBLE else View.GONE
        adsSwitch.isChecked = prefs.skipAds

        carStatus.text = if (Bridge.carScreenOpen) "Car screen is open: links play right away"
        else "Car screen is closed: a sent link waits and plays when you open AutoTube in the car"
    }

    // ---------------------------------------------------------------- actions

    private fun reinstallSelf() {
        if (!Installer.canInstall(this)) {
            toast("Turn on \"Allow from this source\" for AutoTube, then come back and tap again")
            Installer.openInstallPermission(this)
            return
        }
        toast("Tap Install / Update. AutoTube closes while it reinstalls; open it again afterwards")
        Installer.install(this, Installer.selfApk(this))
    }

    private fun shizukuSetup() {
        when (ShizukuSetup.step(this)) {
            ShizukuSetup.Step.NOT_INSTALLED -> {
                toast("Install Shizuku, start it with Wireless debugging, then come back here")
                openStore(ShizukuSetup.PKG)
            }
            ShizukuSetup.Step.NOT_RUNNING -> {
                toast("In Shizuku tap \"Start\" under Wireless debugging, then come back here")
                packageManager.getLaunchIntentForPackage(ShizukuSetup.PKG)?.let { startActivity(it) }
            }
            ShizukuSetup.Step.NEEDS_PERMISSION -> ShizukuSetup.requestPermission(REQ_SHIZUKU)
            ShizukuSetup.Step.READY -> runShizukuInstall()
        }
    }

    private fun runShizukuInstall() {
        val cmd = Installer.shellCommand(this)
        Thread {
            val (code, out) = try { ShizukuSetup.run(cmd) } catch (e: Throwable) { -1 to (e.cause ?: e).toString() }
            CarLog.add(this, "shizuku setup: exit=$code ${out.take(300)}")
            runOnUiThread {
                if (code == 0) toast("Tap \"Update\" on the next screen. If Play Protect asks: More details > Install without scanning")
                else toast("Shizuku couldn't open the installer: $out")
            }
        }.start()
    }

    private fun openStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    private fun signIn(s: Service) {
        startActivity(Intent(this, SignInActivity::class.java).putExtra(SignInActivity.EXTRA_SERVICE, s.id))
    }

    private fun shareLog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val log = CarLog.read(this).ifBlank { "No car events recorded yet.\n" }
        val text = "AutoTube $v car log (${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE})\n\n" +
            log.takeLast(60_000)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share car log"))
    }

    private fun openAndroidAuto() {
        val pkg = "com.google.android.projection.gearhead"
        val candidates = listOf(
            Intent("android.intent.action.APPLICATION_PREFERENCES").setPackage(pkg),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            Intent(Intent.ACTION_MAIN).setPackage(pkg),
        )
        for (c in candidates) {
            val ri = packageManager.resolveActivity(c, 0) ?: continue
            try {
                startActivity(Intent(c).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { /* try the next one */ }
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            toast("Android Auto isn't installed on this phone")
        }
    }

    private fun askLocation() {
        when {
            !DriveGuard.hasLocation(this) -> requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            !DriveGuard.hasBackgroundLocation(this) -> {
                toast("Choose \"Allow all the time\"")
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG_LOC)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_LOC && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED &&
            !DriveGuard.hasBackgroundLocation(this)) {
            askLocation() // second step: "all the time"
        }
        refresh()
    }

    private fun pickInApp() {
        val yt = YouTubeApp.find(this)
        if (yt == null || !YouTubeApp.open(this, yt)) {
            toast("No YouTube app found (or it's disabled)")
            refresh()
            return
        }
        toast("Find the video, then Share → \"Play in car (AutoTube)\"")
    }

    private fun chooseApp() {
        val apps = YouTubeApp.all(this)
        if (apps.size < 2) return refresh()
        val names = apps.map { YouTubeApp.label(this, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pick videos in")
            .setSingleChoiceItems(names, apps.indexOf(YouTubeApp.find(this)).coerceAtLeast(0)) { d, i ->
                prefs.youtubeApp = apps[i]
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun send() {
        val url = YouTubeLinks.fromText(sendBox.text.toString())
        if (url == null) {
            toast("Type a search or paste a link first")
            return
        }
        toast(if (Bridge.send(this, url)) "Playing on the car screen" else "Saved: it plays when you open AutoTube in the car")
        sendBox.setText("")
        refresh()
    }

    // ---------------------------------------------------------------- view helpers

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun text(s: String, sp: Float, bold: Boolean = false, color: Int = fg) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun TextView.pad() = apply { setPadding(0, dp(6), 0, dp(4)) }

    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(if (night) 0xFF1D2124.toInt() else 0xFFF1F4F5.toInt())
            cornerRadius = dp(18).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(6), 0, dp(8)) }
        addView(text(title, 18f, bold = true).apply { setPadding(0, 0, 0, dp(6)) })
    }

    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (primary) Color.WHITE else fg)
        background = pressable(
            if (primary) 0xFF1F8F83.toInt() else if (night) 0xFF2C3236.toInt() else 0xFFDDE3E5.toInt(),
            if (primary) 0xFF2BB3A3.toInt() else if (night) 0xFF3B4247.toInt() else 0xFFC9D1D4.toInt(),
            14,
        )
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(50)).apply { setMargins(0, dp(8), 0, 0) }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REQ_LOC = 2
        private const val REQ_BG_LOC = 3
        private const val REQ_SHIZUKU = 4
    }
}
