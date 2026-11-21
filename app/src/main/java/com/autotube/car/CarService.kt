package com.autotube.car

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

    override fun onBackPressed() {
        if (screen?.handleBack() != true) super.onBackPressed()
    }

    // onPause/onStop are left alone on purpose: switching to Maps on the car screen keeps the
    // sound playing, like a music app. Disconnecting the car destroys us and stops it.
    override fun onDestroy() {
        screen?.destroy()
        screen = null
        super.onDestroy()
    }

    companion object { private const val TAG = "YouTubeCarActivity" }
}
