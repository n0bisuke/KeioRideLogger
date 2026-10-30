package com.example.helloandroid.recording

/** GPS state shown on the GPS card and recorded as `gps_status` rows on every change. */
enum class GpsState(val label: String, val csv: String) {
  WAITING("受信待ち", "waiting"),
  FIXED("測位中", "fix_acquired"),
  STALE("更新停止", "stale"),
  DISABLED("位置情報がオフ", "provider_disabled"),
  PERMISSION_DENIED("権限なし", "permission_denied"),
  UNAVAILABLE("GPS なし", "unavailable"),
}

/**
 * GPS status state machine, separated from LocationManager so it can be unit tested.
 * "Waiting" means no fix yet; "fixed" means fixes are arriving; "stale" means fixes stopped for
 * more than [staleNs], so the last position must not be shown as current. Each method returns the
 * new state when it changed, or null.
 */
class GpsStatusTracker(private val staleNs: Long = 5_000_000_000L) {
  var state: GpsState = GpsState.WAITING
    private set

  private var lastFixNs: Long? = null

  private fun moveTo(next: GpsState): GpsState? = if (next == state) null else next.also { state = it }

  /** Initial state; always reported so the session starts with one `gps_status` row. */
  fun start(permissionGranted: Boolean, gpsAvailable: Boolean, providerEnabled: Boolean): GpsState {
    lastFixNs = null
    state =
      when {
        !permissionGranted -> GpsState.PERMISSION_DENIED
        !gpsAvailable -> GpsState.UNAVAILABLE
        !providerEnabled -> GpsState.DISABLED
        else -> GpsState.WAITING
      }
    return state
  }

  fun onFix(elapsedNs: Long): GpsState? {
    lastFixNs = elapsedNs
    return moveTo(GpsState.FIXED)
  }

  fun onProviderDisabled(): GpsState? = moveTo(GpsState.DISABLED)

  /** Location turned back on: waiting until the next fix arrives. */
  fun onProviderEnabled(): GpsState? = if (state == GpsState.DISABLED) moveTo(GpsState.WAITING) else null

  /** Called periodically; marks fixes that stopped arriving as stale. */
  fun check(nowNs: Long): GpsState? {
    val last = lastFixNs ?: return null
    return if (state == GpsState.FIXED && nowNs - last > staleNs) moveTo(GpsState.STALE) else null
  }
}
