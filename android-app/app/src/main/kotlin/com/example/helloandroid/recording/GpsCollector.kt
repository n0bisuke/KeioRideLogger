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

/** GNSS fixes from the platform GPS provider at ~1 s. Uses no network location services. */
class GpsCollector(
  private val context: Context,
  private val sink: (LogEvent) -> Unit,
  private val onFix: (Location) -> Unit,
  private val onStatus: (String) -> Unit,
) {
  private val locationManager = context.getSystemService(LocationManager::class.java)

  private val listener =
    object : LocationListener {
      override fun onLocationChanged(location: Location) {
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

      override fun onProviderEnabled(provider: String) = status("provider_enabled", "受信待ち")

      override fun onProviderDisabled(provider: String) = status("provider_disabled", "位置情報がオフ")
    }

  private fun status(status: String, label: String) {
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        Clock.nowElapsedNs(),
        "gps_status",
        mapOf("status" to status, "provider" to LocationManager.GPS_PROVIDER),
      )
    )
    onStatus(label)
  }

  @SuppressLint("MissingPermission") // Checked explicitly below.
  fun start(looper: Looper) {
    val granted =
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    when {
      !granted -> status("permission_denied", "権限なし")
      locationManager == null ||
        LocationManager.GPS_PROVIDER !in locationManager.allProviders ->
        status("unavailable", "GPS なし")
      else -> {
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
          status("waiting", "受信待ち")
        } else {
          status("provider_disabled", "位置情報がオフ")
        }
        try {
          locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L,
            0f,
            listener,
            looper,
          )
        } catch (e: SecurityException) {
          status("permission_denied", "権限なし")
        }
      }
    }
  }

  fun stop() {
    locationManager?.removeUpdates(listener)
  }
}
