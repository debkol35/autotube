package com.autotube.car

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** YouTube ReVanced (or any app) > Share > "Play in car (AutoTube)" puts the video on the car screen. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val url = YouTubeLinks.fromText(text)
        val msg = when {
            url == null -> "Nothing to play in that"
            Bridge.send(this, url) -> "Playing on the car screen"
            else -> "Saved. It plays when you open AutoTube on the car screen"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
