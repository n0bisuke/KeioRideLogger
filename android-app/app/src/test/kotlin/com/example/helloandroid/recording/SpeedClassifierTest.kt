package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedClassifierTest {
  private val s = 1_000_000_000L
  private fun mps(kmh: Double) = kmh / 3.6

  @Test
  fun bandBoundaries_areLowerInclusive() {
    assertEquals(SpeedBand.STOPPED, SpeedBand.of(0.0))
    assertEquals(SpeedBand.STOPPED, SpeedBand.of(0.999))
    assertEquals(SpeedBand.SLOW_WALK, SpeedBand.of(1.0))
    assertEquals(SpeedBand.WALK, SpeedBand.of(3.0))
    assertEquals(SpeedBand.RUN, SpeedBand.of(6.0))
    assertEquals(SpeedBand.BICYCLE, SpeedBand.of(15.0))
    assertEquals(SpeedBand.CAR, SpeedBand.of(30.0))
    assertEquals(SpeedBand.CAR, SpeedBand.of(89.99))
    assertEquals(SpeedBand.LINER_FAST, SpeedBand.of(90.0))
    assertEquals(SpeedBand.LINER_FAST, SpeedBand.of(300.0))
  }

  @Test
  fun bands_matchSpriteOrder() {
    assertEquals((0..6).toList(), SpeedBand.entries.map { it.frameIndex })
  }

  @Test
  fun zeroSpeed_isStopped_notUnknown() {
    val c = SpeedClassifier()
    c.onFix(1 * s, 0.0, 5.0)
    val d = c.display(1 * s)
    assertEquals(SpeedBand.STOPPED, d.band)
    assertEquals(0.0, d.smoothedKmh!!, 1e-9)
    assertNull(d.unknownReason)
  }

  @Test
  fun noFix_isUnknown() {
    assertEquals(SpeedUnknownReason.NO_FIX, SpeedClassifier().display(0).unknownReason)
  }

  @Test
  fun missingNegativeOrNaNSpeed_isUnknown() {
    for (bad in listOf(null, -1.0, Double.NaN)) {
      val c = SpeedClassifier()
      c.onFix(1 * s, bad, 5.0)
      val d = c.display(1 * s)
      assertNull(d.band)
      assertEquals(SpeedUnknownReason.NO_SPEED, d.unknownReason)
    }
  }

  @Test
  fun lowOrMissingAccuracy_isUnknown() {
    for (acc in listOf(null, 31.0, Double.NaN)) {
      val c = SpeedClassifier()
      c.onFix(1 * s, mps(50.0), acc)
      assertEquals(SpeedUnknownReason.LOW_ACCURACY, c.display(1 * s).unknownReason)
    }
  }

  @Test
  fun staleFix_clearsBand_insteadOfKeepingOldSpeed() {
    val c = SpeedClassifier()
    c.onFix(1 * s, mps(100.0), 5.0)
    assertEquals(SpeedBand.LINER_FAST, c.display(2 * s).band)
    val stale = c.display(7 * s)
    assertNull(stale.band)
    assertEquals(SpeedUnknownReason.STALE, stale.unknownReason)
  }

  @Test
  fun median_ignoresSingleSpike() {
    val c = SpeedClassifier()
    listOf(0.2, 0.3, 40.0, 0.2, 0.3).forEachIndexed { i, kmh -> c.onFix(i * s, mps(kmh), 5.0) }
    assertEquals(SpeedBand.STOPPED, c.display(4 * s).band)
  }

  @Test
  fun hysteresis_keepsBandWhenWobblingAroundBoundary() {
    val c = SpeedClassifier(windowNs = 0) // no smoothing: test the band logic alone
    c.onFix(0, mps(25.0), 5.0)
    assertEquals(SpeedBand.BICYCLE, c.display(0).band)
    // 30 km/h boundary, margin max(0.5, 3.0) = 3.0: 31 and 32 stay BICYCLE.
    for ((i, kmh) in listOf(31.0, 29.5, 32.0, 30.5).withIndex()) {
      c.onFix((i + 1) * s, mps(kmh), 5.0)
      assertEquals("at $kmh", SpeedBand.BICYCLE, c.display((i + 1) * s).band)
    }
    c.onFix(10 * s, mps(33.5), 5.0)
    assertEquals(SpeedBand.CAR, c.display(10 * s).band)
    // Coming back down needs < 30 - 3 = 27.
    c.onFix(11 * s, mps(28.0), 5.0)
    assertEquals(SpeedBand.CAR, c.display(11 * s).band)
    c.onFix(12 * s, mps(26.0), 5.0)
    assertEquals(SpeedBand.BICYCLE, c.display(12 * s).band)
  }

  @Test
  fun bigJump_switchesDirectly() {
    val c = SpeedClassifier(windowNs = 0)
    c.onFix(0, 0.0, 5.0)
    c.display(0)
    c.onFix(1 * s, mps(95.0), 5.0)
    assertEquals(SpeedBand.LINER_FAST, c.display(1 * s).band)
  }

  @Test
  fun afterUnknown_bandStartsFresh() {
    val c = SpeedClassifier(windowNs = 0)
    c.onFix(0, mps(50.0), 5.0)
    assertEquals(SpeedBand.CAR, c.display(0).band)
    c.display(10 * s) // stale
    c.onFix(11 * s, mps(10.0), 5.0)
    assertEquals(SpeedBand.RUN, c.display(11 * s).band)
  }
}
