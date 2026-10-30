package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsStatusTrackerTest {
  private val s = 1_000_000_000L

  @Test
  fun firstFix_movesFromWaitingToFixed_once() {
    val t = GpsStatusTracker()
    assertEquals(GpsState.WAITING, t.start(true, true, true))
    assertEquals(GpsState.FIXED, t.onFix(1 * s))
    assertNull("repeated fixes do not re-report", t.onFix(2 * s))
    assertEquals(GpsState.FIXED, t.state)
  }

  @Test
  fun fixesStopping_becomesStale_andRecoversOnNextFix() {
    val t = GpsStatusTracker()
    t.start(true, true, true)
    t.onFix(1 * s)
    assertNull(t.check(6 * s)) // exactly 5 s: not yet stale
    assertEquals(GpsState.STALE, t.check(6 * s + 1))
    assertNull(t.check(10 * s))
    assertEquals(GpsState.FIXED, t.onFix(11 * s))
  }

  @Test
  fun noFixYet_staysWaiting_notStale() {
    val t = GpsStatusTracker()
    t.start(true, true, true)
    assertNull(t.check(100 * s))
    assertEquals(GpsState.WAITING, t.state)
  }

  @Test
  fun initialStates_areDistinguished() {
    assertEquals(GpsState.PERMISSION_DENIED, GpsStatusTracker().start(false, true, true))
    assertEquals(GpsState.UNAVAILABLE, GpsStatusTracker().start(true, false, true))
    assertEquals(GpsState.DISABLED, GpsStatusTracker().start(true, true, false))
  }

  @Test
  fun providerOffAndOn_goesThroughDisabledAndWaiting() {
    val t = GpsStatusTracker()
    t.start(true, true, true)
    t.onFix(1 * s)
    assertEquals(GpsState.DISABLED, t.onProviderDisabled())
    assertNull("disabled is not reported as stale", t.check(20 * s))
    assertEquals(GpsState.WAITING, t.onProviderEnabled())
    assertEquals(GpsState.FIXED, t.onFix(21 * s))
  }

  @Test
  fun restart_clearsPreviousFix() {
    val t = GpsStatusTracker()
    t.start(true, true, true)
    t.onFix(1 * s)
    t.start(true, true, true)
    assertEquals(GpsState.WAITING, t.state)
    assertNull(t.check(100 * s))
  }
}
