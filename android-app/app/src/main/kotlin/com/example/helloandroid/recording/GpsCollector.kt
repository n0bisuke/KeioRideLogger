package com.example.helloandroid.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.example.helloandroid.data.LogEvent

/**
 * GNSS fixes from the platform GPS provider at ~1 s. Uses no network location services.
 * All callbacks and [checkStale] run on the main thread.
 */
class GpsCollector(
  private val context: Context,
  private val sink: (LogEvent) -> Unit,
  private val onFix: (Location) -> Unit,
  private val onStatus: (GpsState) -> Unit,
) {
  private val locationManager = context.getSystemService(LocationManager::class.java)
  private val tracker = GpsStatusTracker()

  private val listener =
    object : LocationListener {
      override fun onLocationChanged(location: Location) {
        // State row first, so "fix_acquired" precedes the first gps row of a run.
        tracker.onFix(location.elapsedRealtimeNanos)?.let(::report)
        val values = buildMap {
          put("lat", location.latitude.toString())
          put("lon", location.longitude.toString())
          if (location.hasSpeed()) put("speed_mps", location.speed.toString())
          if (location.hasAltitude()) put("altitude_m", location.altitude.toString())
          if (location.hasAccuracy()) put("h_accuracy_m", location.accuracy.toString())
          put("provider", location.provider ?: "")
        }
        // location.time is the fix time (UTC) reported by the GNSS chipset.
        sink(LogEvent(location.time, location.elapsedRealtimeNanos, "gps", values))
        onFix(location)
      }

      override fun onProviderEnabled(provider: String) {
        tracker.onProviderEnabled()?.let(::report)
      }

      override fun onProviderDisabled(provider: String) {
        tracker.onProviderDisabled()?.let(::report)
      }
    }

  private fun report(state: GpsState) {
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        Clock.nowElapsedNs(),
        "gps_status",
        mapOf("status" to state.csv, "provider" to LocationManager.GPS_PROVIDER),
      )
    )
    onStatus(state)
  }

  /** Marks the status stale when fixes stopped arriving; call periodically while recording. */
  fun checkStale(nowNs: Long) {
    tracker.check(nowNs)?.let(::report)
  }

  @SuppressLint("MissingPermission") // Checked explicitly below.
  fun start(looper: Looper) {
    val granted =
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val available = locationManager != null && LocationManager.GPS_PROVIDER in locationManager.allProviders
    val enabled = available && locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val initial = tracker.start(granted, available, enabled)
    report(initial)
    if (initial == GpsState.PERMISSION_DENIED || initial == GpsState.UNAVAILABLE) return
    try {
      // Registered even when location is off, so turning it on resumes recording.
      locationManager!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, looper)
    } catch (e: SecurityException) {
      tracker.start(false, available, enabled)
      report(GpsState.PERMISSION_DENIED)
    }
  }

  fun stop() {
    locationManager?.removeUpdates(listener)
  }
}
