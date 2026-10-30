package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
  private val ms = 1_000_000L

  /** Feeds samples every [inputMs] for [seconds] and returns the accepted rate in Hz. */
  private fun outputHz(targetMs: Long, inputMs: Long, seconds: Int = 60): Double {
    val limiter = RateLimiter(targetMs * ms)
    var accepted = 0
    var t = 0L
    while (t < seconds * 1000L * ms) {
      if (limiter.accept(t)) accepted++
      t += inputMs * ms
    }
    return accepted / seconds.toDouble()
  }

  @Test
  fun slightlyFasterInput_isThinnedToTarget_notHalved() {
    // 25 Hz input with a 20 Hz target: the old gap-based filter produced 12.5 Hz here.
    assertEquals(20.0, outputHz(targetMs = 50, inputMs = 40), 0.5)
  }

  @Test
  fun muchFasterInput_isThinnedToTarget() {
    assertEquals(20.0, outputHz(targetMs = 50, inputMs = 5), 0.5)
  }

  @Test
  fun inputAtTarget_keepsEverySample() {
    assertEquals(20.0, outputHz(targetMs = 50, inputMs = 50), 0.1)
  }

  @Test
  fun slowerInput_keepsEverySample() {
    assertEquals(12.5, outputHz(targetMs = 50, inputMs = 80), 0.1)
  }

  @Test
  fun jitterAroundTarget_keepsEverySample() {
    val limiter = RateLimiter(50 * ms)
    var t = 0L
    var accepted = 0
    repeat(1000) { i ->
      t += (if (i % 2 == 0) 47 else 53) * ms
      if (limiter.accept(t)) accepted++
    }
    assertEquals(1000, accepted)
  }

  @Test
  fun afterGap_doesNotBurst() {
    val limiter = RateLimiter(50 * ms)
    assertTrue(limiter.accept(0))
    // 1 s with no samples, then a fast stream resumes.
    var accepted = 0
    var t = 1000 * ms
    while (t < 2000 * ms) {
      if (limiter.accept(t)) accepted++
      t += 10 * ms
    }
    assertEquals(20, accepted)
  }
}
