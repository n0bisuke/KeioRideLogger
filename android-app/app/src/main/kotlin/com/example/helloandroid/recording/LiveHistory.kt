package com.example.helloandroid.recording

/** One GPS fix kept for the live track. Times are elapsedRealtime nanoseconds. */
data class TrackPoint(
  val elapsedNs: Long,
  val lat: Double,
  val lon: Double,
  val accuracyM: Double?,
  val speedKmh: Double?,
)

/** One registered-cell signal sample. Metrics keep the CSV column names (lte_rsrq_db, ...). */
data class SignalPoint(
  val elapsedNs: Long,
  val rat: String,
  val rsrpDbm: Int?,
  val metrics: Map<String, Int>,
)

data class ProbePoint(val elapsedNs: Long, val outcome: ProbeOutcome, val durationMs: Long)

/** Immutable copy of the live histories for one UI frame. */
data class LiveSnapshot(
  val track: List<TrackPoint> = emptyList(),
  val signals: List<SignalPoint> = emptyList(),
  val probes: List<ProbePoint> = emptyList(),
  /** Times of observed serving-cell switch candidates (baseline excluded). */
  val cellSwitchNs: List<Long> = emptyList(),
  /** Right edge of the time-series window: now while recording, the stop time afterwards. */
  val windowEndNs: Long = 0,
  /** True when [track] reached its cap and the oldest points were dropped. */
  val trackTruncated: Boolean = false,
)

/**
 * Bounded histories for the live visualisation. These are UI copies of values the collectors
 * already deliver; the CSV is the complete record. Thread-safe: appended from sensor, telephony
 * and probe threads, read by the UI ticker.
 */
class LiveHistory(
  private val maxTrackPoints: Int = 1_800,
  private val seriesWindowNs: Long = 60_000_000_000L,
  private val maxSeriesPoints: Int = 600,
) {
  private val track = ArrayDeque<TrackPoint>()
  private val signals = ArrayDeque<SignalPoint>()
  private val probes = ArrayDeque<ProbePoint>()
  private val switches = ArrayDeque<Long>()
  private var truncated = false

  @Synchronized
  fun clear() {
    track.clear()
    signals.clear()
    probes.clear()
    switches.clear()
    truncated = false
  }

  @Synchronized
  fun addFix(point: TrackPoint) {
    // Locations without coordinates never get here; (0, 0) is a real place and is kept as is.
    track.addLast(point)
    while (track.size > maxTrackPoints) {
      track.removeFirst()
      truncated = true
    }
  }

  /** Adds a serving-cell sample unless it is not newer than the last one (repeat or older path). */
  @Synchronized
  fun addSignal(point: SignalPoint) {
    val last = signals.lastOrNull()
    if (last != null && point.elapsedNs <= last.elapsedNs) return
    signals.addLast(point)
    trim(signals, point.elapsedNs) { it.elapsedNs }
  }

  @Synchronized
  fun addProbe(point: ProbePoint) {
    probes.addLast(point)
    trim(probes, point.elapsedNs) { it.elapsedNs }
  }

  @Synchronized
  fun addSwitch(elapsedNs: Long) {
    if (elapsedNs in switches) return
    switches.addLast(elapsedNs)
    trim(switches, elapsedNs) { it }
  }

  /** Copies the histories; time series are limited to the window ending at [windowEndNs]. */
  @Synchronized
  fun snapshot(windowEndNs: Long): LiveSnapshot {
    val from = windowEndNs - seriesWindowNs
    return LiveSnapshot(
      track = track.toList(),
      signals = signals.filter { it.elapsedNs in from..windowEndNs },
      probes = probes.filter { it.elapsedNs in from..windowEndNs },
      cellSwitchNs = switches.filter { it in from..windowEndNs },
      windowEndNs = windowEndNs,
      trackTruncated = truncated,
    )
  }

  private fun <T> trim(queue: ArrayDeque<T>, newestNs: Long, timeOf: (T) -> Long) {
    // Keep a little more than one window so a stopped session still shows its last minute.
    while (queue.isNotEmpty() && newestNs - timeOf(queue.first()) > seriesWindowNs * 2) queue.removeFirst()
    while (queue.size > maxSeriesPoints) queue.removeFirst()
  }
}

/**
 * Splits time-ordered samples into runs whose neighbours are at most [maxGapNs] apart. Charts and
 * the track draw lines only inside a run, so missing data is never bridged by interpolation.
 */
fun <T> splitAtGaps(samples: List<T>, maxGapNs: Long, timeOf: (T) -> Long): List<List<T>> {
  val runs = mutableListOf<MutableList<T>>()
  for (sample in samples) {
    val run = runs.lastOrNull()
    if (run == null || timeOf(sample) - timeOf(run.last()) > maxGapNs) runs += mutableListOf(sample)
    else run += sample
  }
  return runs
}
