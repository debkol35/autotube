package com.autotube.car

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/** The car screen on the phone itself, to try things out without the car. */
class PreviewActivity : Activity() {
    private lateinit var screen: CarScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen = CarScreen(this, inCar = false)
        setContentView(screen)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        screen.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!screen.handleBack()) super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        screen.pause()
    }

    override fun onResume() {
        super.onResume()
        screen.resume()
    }

    override fun onDestroy() {
        screen.destroy()
        super.onDestroy()
    }
}
