package com.example.helloandroid.recording

/**
 * Speed bands for the glider character, in sprite-sheet order (left to right, top to bottom).
 * Thresholds are display values for the character, not vehicle detection: GPS speed is not the
 * train's speedometer, and the 90/110 km/h bands do not prove which train is being ridden.
 */
enum class SpeedBand(val label: String, val minKmh: Double) {
  STOPPED("停止", 0.0),
  SLOW_WALK("ゆっくり歩き", 1.0),
  RUN("走る", 6.0),
  BICYCLE("自転車くらい", 15.0),
  CAR("車くらい", 30.0),
  LINER_FAST("京王ライナー高速域", 70.0),
  FAST("高速域", 90.0),
  VERY_FAST("超高速域", 110.0);

  /** Exclusive upper bound; the last band is open-ended. */
  val maxKmh: Double
    get() = entries.getOrNull(ordinal + 1)?.minKmh ?: Double.POSITIVE_INFINITY

  /** Index of this band's frame in the 4x2 sprite sheet (all 8 cells are used). */
  val frameIndex: Int
    get() = ordinal

  companion object {
    /** Band for a valid speed; callers must reject NaN/negative values first. */
    fun of(kmh: Double): SpeedBand = entries.last { kmh >= it.minKmh }
  }
}

/** Why no speed band is shown. 0 km/h is a band (STOPPED); these are "unknown". */
enum class SpeedUnknownReason(val label: String) {
  NO_FIX("GPS待ち"),
  STALE("GPS更新なし"),
  LOW_ACCURACY("精度不足"),
  NO_SPEED("速度不明"),
}

data class SpeedDisplay(
  val band: SpeedBand?,
  /** Smoothed speed used for the band; null when unknown. */
  val smoothedKmh: Double?,
  val unknownReason: SpeedUnknownReason?,
)

/**
 * Turns GPS fixes into a stable speed band.
 *
 * Smoothing: the median of valid speeds from fixes in the last [windowNs] (GPS time base). A
 * median ignores single-fix spikes that GPS jitter produces while standing still.
 *
 * Hysteresis: the band changes only when the smoothed speed leaves the current band by a margin of
 * max([minMarginKmh], [relativeMargin] x the crossed boundary). Speeds wobbling around a boundary
 * therefore keep the current band instead of flickering.
 *
 * Unknown: no fix yet, the newest fix older than [staleNs], horizontal accuracy worse than
 * [maxAccuracyM] or missing, or no valid speed (missing, NaN, negative). The band is cleared so
 * the character does not keep showing an old speed.
 */
class SpeedClassifier(
  private val windowNs: Long = 5_000_000_000L,
  private val staleNs: Long = 5_000_000_000L,
  private val maxAccuracyM: Double = 30.0,
  private val minMarginKmh: Double = 0.5,
  private val relativeMargin: Double = 0.1,
) {
  private data class Sample(val elapsedNs: Long, val kmh: Double)

  private val samples = ArrayDeque<Sample>()
  private var lastFixNs: Long? = null
  private var lastFixReason: SpeedUnknownReason? = SpeedUnknownReason.NO_FIX
  private var band: SpeedBand? = null

  fun reset() {
    samples.clear()
    lastFixNs = null
    lastFixReason = SpeedUnknownReason.NO_FIX
    band = null
  }

  /** Feeds one GPS fix. [speedMps] null = the fix had no speed; [accuracyM] null = unknown. */
  fun onFix(elapsedNs: Long, speedMps: Double?, accuracyM: Double?) {
    lastFixNs = elapsedNs
    lastFixReason =
      when {
        accuracyM == null || accuracyM.isNaN() || accuracyM > maxAccuracyM -> SpeedUnknownReason.LOW_ACCURACY
        speedMps == null || speedMps.isNaN() || speedMps < 0 -> SpeedUnknownReason.NO_SPEED
        else -> null
      }
    if (lastFixReason == null) samples.addLast(Sample(elapsedNs, speedMps!! * 3.6))
    while (samples.isNotEmpty() && elapsedNs - samples.first().elapsedNs > windowNs) samples.removeFirst()
  }

  /** Current display at [nowNs] (elapsedRealtime base). */
  fun display(nowNs: Long): SpeedDisplay {
    val fixNs = lastFixNs
    val reason =
      when {
        fixNs == null -> SpeedUnknownReason.NO_FIX
        nowNs - fixNs > staleNs -> SpeedUnknownReason.STALE
        else -> lastFixReason
      }
    val valid = samples.filter { fixNs != null && fixNs - it.elapsedNs <= windowNs }
    if (reason != null || valid.isEmpty()) {
      band = null
      return SpeedDisplay(null, null, reason ?: SpeedUnknownReason.NO_SPEED)
    }
    val smoothed = median(valid.map { it.kmh })
    band = nextBand(band, smoothed)
    return SpeedDisplay(band, smoothed, null)
  }

  private fun nextBand(current: SpeedBand?, kmh: Double): SpeedBand {
    val target = SpeedBand.of(kmh)
    if (current == null || target == current) return target
    val boundary = if (target > current) current.maxKmh else current.minKmh
    val margin = maxOf(minMarginKmh, relativeMargin * boundary)
    val beyond = if (target > current) kmh >= boundary + margin else kmh < boundary - margin
    return if (beyond) target else current
  }

  private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
  }
}
