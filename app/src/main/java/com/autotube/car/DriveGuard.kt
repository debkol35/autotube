package com.autotube.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Decides "moving" vs "parked" from the phone's GPS speed. The video picture is shown only in
 * [State.PARKED]; every other state (no permission, GPS off, no fix yet, moving) hides it and the
 * sound keeps playing, like Android Auto itself does for video apps. It fails closed: if the
 * phone can't prove the car is parked, the picture stays hidden.
 */
class DriveGuard(private val ctx: Context, private val onChange: (State) -> Unit) : LocationListener {

    enum class State { NO_PERMISSION, NO_GPS, WAITING, PARKED, MOVING }

    var state = State.WAITING
        private set

    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var last: Location? = null
    private var fastFixes = 0
    private var slowSince = 0L
    private var running = false

    fun start() {
        stop()
        if (!hasLocation(ctx)) return set(State.NO_PERMISSION)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            running = true
            set(if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) State.WAITING else State.NO_GPS)
        } catch (e: SecurityException) {
            set(State.NO_PERMISSION)
        } catch (e: IllegalArgumentException) {
            set(State.NO_GPS) // phone without a GPS provider
        }
    }

    fun stop() {
        if (running) try { lm.removeUpdates(this) } catch (e: Exception) { Log.w(TAG, "removeUpdates", e) }
        running = false
        last = null
        fastFixes = 0
        slowSince = 0
    }

    override fun onLocationChanged(loc: Location) {
        val prev = last
        last = loc
        val speed: Float = when {
            loc.hasSpeed() -> loc.speed
            prev != null -> {
                val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9f
                if (dt > 0.5f) prev.distanceTo(loc) / dt else return
            }
            else -> return
        }
        val now = SystemClock.elapsedRealtime()
        if (speed >= MOVING_MPS) {
            slowSince = 0
            // Two fast fixes in a row, so a single GPS jump while parked doesn't blank the video.
            // Before the first "parked" fix the picture is hidden anyway, so one is enough there.
            if (++fastFixes >= 2 || state != State.PARKED) set(State.MOVING)
        } else {
            fastFixes = 0
            if (speed < STOPPED_MPS) {
                if (slowSince == 0L) slowSince = now
                if (state != State.MOVING || now - slowSince >= STOP_HOLD_MS) set(State.PARKED)
            }
        }
    }

    // Abstract before API 30: must exist or Android 10 throws AbstractMethodError.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (state == State.NO_GPS) set(State.WAITING)
    }

    override fun onProviderDisabled(provider: String) {
        last = null
        fastFixes = 0
        slowSince = 0
        set(State.NO_GPS)
    }

    private fun set(s: State) {
        if (s == state) return
        state = s
        onChange(s)
    }

    companion object {
        private const val TAG = "DriveGuard"
        const val MOVING_MPS = 3.0f   // ~11 km/h
        const val STOPPED_MPS = 1.5f  // ~5 km/h
        const val STOP_HOLD_MS = 5000L

        fun hasLocation(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Needed in the car: there the app is not "on screen" as far as the phone is concerned. */
        fun hasBackgroundLocation(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
