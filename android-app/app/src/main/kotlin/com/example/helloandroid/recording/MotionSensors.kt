package com.example.helloandroid.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.example.helloandroid.data.LogEvent
import java.util.Locale

enum class MotionSensor(val sensorType: Int, val event: String, val label: String, val periodUs: Int) {
  ACCELEROMETER(Sensor.TYPE_ACCELEROMETER, "accel", "加速度", 50_000),
  GYROSCOPE(Sensor.TYPE_GYROSCOPE, "gyro", "ジャイロ", 50_000),
  MAGNETOMETER(Sensor.TYPE_MAGNETIC_FIELD, "mag", "磁気", 200_000),
  BAROMETER(Sensor.TYPE_PRESSURE, "pressure", "気圧", 1_000_000);

  val targetHz: Double
    get() = 1_000_000.0 / periodUs
}

class MotionSample(val values: FloatArray, val elapsedNs: Long, val accuracy: Int)

/**
 * Rates measured from SensorEvent.timestamp: [receivedHz] is what the device delivered,
 * [recordedHz] is what was written after thinning to the target. Null until two samples exist.
 */
data class SensorRate(val receivedHz: Double?, val recordedHz: Double?)

/** Counts samples and spans their timestamps; touched only on the sensor thread. */
private class RateCounter {
  var count = 0L
  var firstNs = 0L
  var lastNs = 0L

  fun add(timestampNs: Long) {
    if (count == 0L) firstNs = timestampNs
    lastNs = timestampNs
    count++
  }

  fun hz(): Double? =
    if (count < 2 || lastNs <= firstNs) null else (count - 1) * 1e9 / (lastNs - firstNs)
}

/**
 * Registers the motion/environment sensors on a background thread. Sensors that the device lacks
 * are reported as unavailable instead of failing the session.
 */
class MotionSensorCollector(
  context: Context,
  private val sink: (LogEvent) -> Unit,
  private val onSample: (MotionSensor, MotionSample) -> Unit,
) {
  private val sensorManager = context.getSystemService(SensorManager::class.java)
  private val byType = MotionSensor.entries.associateBy { it.sensorType }
  private val limiters = MotionSensor.entries.map { RateLimiter(it.periodUs * 1_000L) }
  private val received = MotionSensor.entries.map { RateCounter() }
  private val recorded = MotionSensor.entries.map { RateCounter() }
  private var thread: HandlerThread? = null

  fun availability(): Map<MotionSensor, Boolean> =
    MotionSensor.entries.associateWith { sensorManager?.getDefaultSensor(it.sensorType) != null }

  /** Snapshot for the UI; values may lag the sensor thread slightly. */
  fun rates(): Map<MotionSensor, SensorRate> =
    MotionSensor.entries.associateWith {
      SensorRate(received[it.ordinal].hz(), recorded[it.ordinal].hz())
    }

  private val listener =
    object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        val kind = byType[event.sensor.type] ?: return
        received[kind.ordinal].add(event.timestamp)
        // The requested rate is only a hint; thin faster streams to the target grid.
        if (!limiters[kind.ordinal].accept(event.timestamp)) return
        recorded[kind.ordinal].add(event.timestamp)

        val values =
          if (kind == MotionSensor.BAROMETER) {
            mapOf("pressure_hpa" to event.values[0].toString())
          } else {
            mapOf(
              "x" to event.values[0].toString(),
              "y" to event.values[1].toString(),
              "z" to event.values[2].toString(),
            )
          }
        sink(
          LogEvent(
            timeUtcMs = Clock.elapsedNsToUtcMs(event.timestamp),
            elapsedRealtimeNs = event.timestamp,
            event = kind.event,
            values = values + ("accuracy" to event.accuracy.toString()),
          )
        )
        onSample(kind, MotionSample(event.values.copyOf(), event.timestamp, event.accuracy))
      }

      override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

  fun start() {
    for (kind in MotionSensor.entries) {
      limiters[kind.ordinal].reset()
      received[kind.ordinal].count = 0
      recorded[kind.ordinal].count = 0
    }
    val handlerThread = HandlerThread("motion-sensors").also { it.start() }
    thread = handlerThread
    val handler = Handler(handlerThread.looper)
    for (kind in MotionSensor.entries) {
      val sensor = sensorManager?.getDefaultSensor(kind.sensorType)
      val registered =
        sensor != null && sensorManager.registerListener(listener, sensor, kind.periodUs, handler)
      // min_delay_us: fastest period the device advertises (0 = on-change sensor).
      val capability =
        sensor?.let {
          ";name=${it.name};vendor=${it.vendor};requested_period_us=${kind.periodUs}" +
            ";min_delay_us=${it.minDelay};max_delay_us=${it.maxDelay};fifo_max=${it.fifoMaxEventCount}"
        } ?: ""
      sink(
        LogEvent(
          timeUtcMs = Clock.nowUtcMs(),
          elapsedRealtimeNs = Clock.nowElapsedNs(),
          event = "sensor_status",
          values =
            mapOf(
              "status" to
                when {
                  sensor == null -> "unavailable"
                  registered -> "registered"
                  else -> "register_failed"
                },
              "note" to kind.event + capability,
            ),
        )
      )
    }
  }

  /** Unregisters and waits for the sensor thread, so no sample is delivered after this returns. */
  fun stop() {
    sensorManager?.unregisterListener(listener)
    thread?.let {
      it.quitSafely()
      it.join(THREAD_JOIN_TIMEOUT_MS)
    }
    thread = null
  }

  /** One `sensor_rate` row per available sensor; call after [stop]. */
  fun rateSummary(): List<LogEvent> =
    MotionSensor.entries
      .filter { received[it.ordinal].count > 0 }
      .map { kind ->
        val rate = SensorRate(received[kind.ordinal].hz(), recorded[kind.ordinal].hz())
        LogEvent(
          Clock.nowUtcMs(),
          Clock.nowElapsedNs(),
          "sensor_rate",
          mapOf(
            "status" to "summary",
            "note" to
              "sensor=${kind.event};target_hz=${hz(kind.targetHz)}" +
                ";received=${received[kind.ordinal].count};received_hz=${hz(rate.receivedHz)}" +
                ";recorded=${recorded[kind.ordinal].count};recorded_hz=${hz(rate.recordedHz)}",
          ),
        )
      }

  private fun hz(value: Double?) = value?.let { String.format(Locale.US, "%.2f", it) } ?: ""

  private companion object {
    const val THREAD_JOIN_TIMEOUT_MS = 500L
  }
}
