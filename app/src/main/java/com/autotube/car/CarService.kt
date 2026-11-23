package com.autotube.car

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/** Android Auto binds this and shows [YouTubeCarActivity] on the car display. */
class CarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = YouTubeCarActivity::class.java
}

class YouTubeCarActivity : CarActivity() {
    private var screen: CarScreen? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        installCrashLog()
        val c = resources.configuration
        CarLog.add(this, "car onCreate ${c.screenWidthDp}x${c.screenHeightDp}dp " +
            "density=${resources.displayMetrics.densityDpi} restored=${state != null}")
        // Handle day/night, size and density changes ourselves instead of being recreated
        // (recreating would restart the video).
        setIgnoreConfigChanges(-1)
        try {
            carUiController.statusBarController.hideAppHeader()
            carUiController.menuController.hideMenuButton()
        } catch (e: Throwable) {
            Log.w(TAG, "car UI controller", e)
        }
        val s = CarScreen(ContextThemeWrapper(this, R.style.CarTheme), inCar = true)
        setContentView(s)
        screen = s
        s.start()
    }

    override fun onStart() {
        super.onStart()
        CarLog.add(this, "car onStart")
    }

    override fun onStop() {
        CarLog.add(this, "car onStop")
        super.onStop()
    }

    override fun onConfigurationChanged(c: Configuration) {
        CarLog.add(this, "car config changed ${c.screenWidthDp}x${c.screenHeightDp}dp " +
            "orientation=${c.orientation} night=${c.uiMode and Configuration.UI_MODE_NIGHT_MASK}")
        super.onConfigurationChanged(c)
    }

    override fun onBackPressed() {
        if (screen?.handleBack() != true) {
            CarLog.add(this, "car back pressed on the first page: leaving")
            super.onBackPressed()
        }
    }

    // onPause/onStop are left alone on purpose: switching to Maps on the car screen keeps the
    // sound playing, like a music app. Disconnecting the car destroys us and stops it.
    override fun onDestroy() {
        CarLog.add(this, "car onDestroy")
        screen?.destroy()
        screen = null
        super.onDestroy()
    }

    /** Writes any crash to the car log before Android's own handler ends the app. */
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(applicationContext, prev))
    }

    private class CrashLogger(
        private val ctx: android.content.Context,
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            CarLog.add(ctx, "CRASH in ${t.name}: " + Log.getStackTraceString(e).take(3000))
            next?.uncaughtException(t, e)
        }
    }

    companion object { private const val TAG = "YouTubeCarActivity" }
}
