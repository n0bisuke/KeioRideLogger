package com.example.helloandroid.recording

/**
 * Thins a sensor stream to roughly one sample per [periodNs] on a fixed time grid.
 *
 * Comparing only against the previously accepted sample would halve a stream that arrives a bit
 * faster than the target (e.g. 25 Hz input -> 12.5 Hz output for a 20 Hz target); advancing a due
 * time by [periodNs] keeps the long-run output at the target rate whenever the input is faster.
 */
class RateLimiter(private val periodNs: Long) {
  private val toleranceNs = periodNs / 10
  private var dueNs = Long.MIN_VALUE

  fun reset() {
    dueNs = Long.MIN_VALUE
  }

  fun accept(timestampNs: Long): Boolean {
    if (dueNs != Long.MIN_VALUE && timestampNs < dueNs - toleranceNs) return false
    // Restart the grid after a gap (or on the first sample) instead of bursting to catch up.
    dueNs =
      if (dueNs == Long.MIN_VALUE || timestampNs - dueNs > periodNs) timestampNs + periodNs
      else dueNs + periodNs
    return true
  }
}
