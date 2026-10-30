package com.example.helloandroid.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.example.helloandroid.data.LogEvent

enum class MotionSensor(val sensorType: Int, val event: String, val label: String, val periodUs: Int) {
  ACCELEROMETER(Sensor.TYPE_ACCELEROMETER, "accel", "加速度", 50_000),
  GYROSCOPE(Sensor.TYPE_GYROSCOPE, "gyro", "ジャイロ", 50_000),
  MAGNETOMETER(Sensor.TYPE_MAGNETIC_FIELD, "mag", "磁気", 200_000),
  BAROMETER(Sensor.TYPE_PRESSURE, "pressure", "気圧", 1_000_000),
}

class MotionSample(val values: FloatArray, val elapsedNs: Long, val accuracy: Int)

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
  private val lastAcceptedNs = LongArray(MotionSensor.entries.size) { Long.MIN_VALUE }
  private var thread: HandlerThread? = null

  fun availability(): Map<MotionSensor, Boolean> =
    MotionSensor.entries.associateWith { sensorManager?.getDefaultSensor(it.sensorType) != null }

  private val listener =
    object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        val kind = byType[event.sensor.type] ?: return
        // The requested rate is only a hint; drop samples arriving well ahead of the target period.
        val minGapNs = kind.periodUs * 900L
        val last = lastAcceptedNs[kind.ordinal]
        if (last != Long.MIN_VALUE && event.timestamp - last < minGapNs) return
        lastAcceptedNs[kind.ordinal] = event.timestamp

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
    lastAcceptedNs.fill(Long.MIN_VALUE)
    val handlerThread = HandlerThread("motion-sensors").also { it.start() }
    thread = handlerThread
    val handler = Handler(handlerThread.looper)
    for (kind in MotionSensor.entries) {
      val sensor = sensorManager?.getDefaultSensor(kind.sensorType)
      val registered =
        sensor != null && sensorManager.registerListener(listener, sensor, kind.periodUs, handler)
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
              "note" to kind.event + (sensor?.let { ";name=${it.name};vendor=${it.vendor}" } ?: ""),
            ),
        )
      )
    }
  }

  fun stop() {
    sensorManager?.unregisterListener(listener)
    thread?.quitSafely()
    thread = null
  }
}
