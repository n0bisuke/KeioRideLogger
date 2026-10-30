package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedClassifierTest {
  private val s = 1_000_000_000L
  private fun mps(kmh: Double) = kmh / 3.6

  @Test
  fun bandBoundaries_areLowerInclusive() {
    val cases =
      listOf(
        0.0 to SpeedBand.STOPPED,
        0.999 to SpeedBand.STOPPED,
        1.0 to SpeedBand.SLOW_WALK,
        5.99 to SpeedBand.SLOW_WALK,
        6.0 to SpeedBand.RUN,
        14.99 to SpeedBand.RUN,
        15.0 to SpeedBand.BICYCLE,
        29.99 to SpeedBand.BICYCLE,
        30.0 to SpeedBand.CAR,
        69.99 to SpeedBand.CAR,
        70.0 to SpeedBand.LINER_FAST,
        89.99 to SpeedBand.LINER_FAST,
        90.0 to SpeedBand.FAST,
        109.99 to SpeedBand.FAST,
        110.0 to SpeedBand.VERY_FAST,
        300.0 to SpeedBand.VERY_FAST,
      )
    for ((kmh, band) in cases) assertEquals("at $kmh", band, SpeedBand.of(kmh))
  }

  @Test
  fun bands_matchSpriteOrderAndLabels() {
    assertEquals((0..7).toList(), SpeedBand.entries.map { it.frameIndex })
    assertEquals(
      listOf("停止", "ゆっくり歩き", "走る", "自転車くらい", "車くらい", "京王ライナー高速域", "高速域", "超高速域"),
      SpeedBand.entries.map { it.label },
    )
    assertEquals(listOf(0.0, 1.0, 6.0, 15.0, 30.0, 70.0, 90.0, 110.0), SpeedBand.entries.map { it.minKmh })
  }

  @Test
  fun hysteresis_atEveryBoundary() {
    for (upper in SpeedBand.entries.drop(1)) {
      val lower = SpeedBand.entries[upper.ordinal - 1]
      val b = upper.minKmh
      val margin = maxOf(0.5, 0.1 * b)
      val c = SpeedClassifier(windowNs = 0)
      var t = 0L
      fun at(kmh: Double): SpeedBand? {
        t += s
        c.onFix(t, mps(kmh), 5.0)
        return c.display(t).band
      }
      assertEquals("below $b", lower, at(b - 0.01))
      assertEquals("rising to $b + 0.9 margin", lower, at(b + 0.9 * margin))
      assertEquals("rising past $b + margin", upper, at(b + margin + 0.01))
      assertEquals("falling to $b - 0.9 margin", upper, at(b - 0.9 * margin))
      assertEquals("falling past $b - margin", lower, at(b - margin - 0.01))
    }
  }

  /** Runs the series shared with the Web viewer (src/test/resources/speed_band_vectors.txt). */
  @Test
  fun sharedVectors_matchExpectedBands() {
    val text = javaClass.classLoader!!.getResource("speed_band_vectors.txt")!!.readText()
    val c = SpeedClassifier()
    var checks = 0
    for ((lineNo, line) in text.lines().withIndex()) {
      val f = line.trim().split(' ')
      if (f[0].isEmpty() || f[0].startsWith("#")) continue
      val ns = f[1].toLong() * 1_000_000L
      when (f[0]) {
        "fix" -> {
          c.onFix(ns, f[2].toDoubleOrNull()?.let(::mps), f[3].toDoubleOrNull())
          c.display(ns)
        }
        "check" -> {
          val d = c.display(ns)
          val where = "line ${lineNo + 1}"
          if (f[2] == "-") {
            assertNull(where, d.band)
            assertEquals(where, f[3], d.unknownReason?.name)
          } else {
            assertEquals(where, f[2].toInt(), d.band?.frameIndex)
            assertEquals(where, f[3], d.band?.label)
          }
          checks++
        }
        else -> error("bad line ${lineNo + 1}: $line")
      }
    }
    assertEquals(19, checks)
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
    assertEquals(SpeedBand.FAST, c.display(2 * s).band)
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
    assertEquals(SpeedBand.FAST, c.display(1 * s).band)
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
