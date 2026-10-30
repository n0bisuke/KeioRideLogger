package com.example.helloandroid.recording

import android.os.SystemClock

/** Converts platform elapsedRealtime timestamps (sensor events, cell info) to UTC epoch millis. */
object Clock {
  fun nowUtcMs(): Long = System.currentTimeMillis()

  fun nowElapsedNs(): Long = SystemClock.elapsedRealtimeNanos()

  fun elapsedNsToUtcMs(elapsedNs: Long): Long =
    System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - elapsedNs) / 1_000_000
}
