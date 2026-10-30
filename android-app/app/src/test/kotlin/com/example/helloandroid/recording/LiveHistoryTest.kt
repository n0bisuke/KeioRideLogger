package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHistoryTest {
  private val s = 1_000_000_000L
  private fun fix(t: Long) = TrackPoint(t, 1.0, 2.0, 5.0, 0.0)

  @Test
  fun track_isCapped_andReportsTruncation() {
    val h = LiveHistory(maxTrackPoints = 3)
    for (i in 1..5) h.addFix(fix(i * s))
    val snap = h.snapshot(5 * s)
    assertEquals(listOf(3 * s, 4 * s, 5 * s), snap.track.map { it.elapsedNs })
    assertTrue(snap.trackTruncated)
  }

  @Test
  fun series_areLimitedToWindow() {
    val h = LiveHistory(seriesWindowNs = 60 * s)
    for (t in listOf(0L, 30L, 70L, 100L)) h.addProbe(ProbePoint(t * s, ProbeOutcome.OK, 100))
    assertEquals(listOf(70 * s, 100 * s), h.snapshot(100 * s).probes.map { it.elapsedNs })
  }

  @Test
  fun duplicateSignalSample_isNotRepeated() {
    val h = LiveHistory()
    val p = SignalPoint(1 * s, "LTE", -80, emptyMap())
    h.addSignal(p)
    h.addSignal(p)
    assertEquals(1, h.snapshot(1 * s).signals.size)
  }

  @Test
  fun clear_resetsEverything() {
    val h = LiveHistory(maxTrackPoints = 1)
    h.addFix(fix(1)); h.addFix(fix(2)); h.addSwitch(2)
    h.clear()
    val snap = h.snapshot(2)
    assertTrue(snap.track.isEmpty() && snap.cellSwitchNs.isEmpty())
    assertFalse(snap.trackTruncated)
  }

  @Test
  fun splitAtGaps_breaksRunsInsteadOfBridging() {
    val times = listOf(0L, 1L, 2L, 10L, 11L, 30L)
    val runs = splitAtGaps(times, maxGapNs = 5) { it }
    assertEquals(listOf(listOf(0L, 1L, 2L), listOf(10L, 11L), listOf(30L)), runs)
    assertTrue(splitAtGaps(emptyList<Long>(), 5) { it }.isEmpty())
  }
}
