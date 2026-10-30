package com.example.helloandroid.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Event-style CSV: every row shares the same columns, and each event type fills only the columns
 * it owns. Empty cell = not available for this row (never 0).
 */
object CsvFormat {
  val COLUMNS =
    listOf(
      "time_utc",
      "elapsed_realtime_ns",
      "event",
      "status",
      "lat",
      "lon",
      "speed_mps",
      "altitude_m",
      "h_accuracy_m",
      "provider",
      "x",
      "y",
      "z",
      "accuracy",
      "pressure_hpa",
      "rat",
      "override_rat",
      "cell_id",
      "pci",
      "tac",
      "arfcn",
      "bands",
      "rsrp_dbm",
      "registered",
      "age_ms",
      "note",
    )

  private val COLUMN_SET = COLUMNS.toSet()
  private val FIXED = setOf("time_utc", "elapsed_realtime_ns", "event")

  private val UTC_MILLIS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  fun header(): String = COLUMNS.joinToString(",")

  fun formatUtc(epochMs: Long): String = UTC_MILLIS.format(Instant.ofEpochMilli(epochMs))

  fun escape(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
      "\"" + value.replace("\"", "\"\"") + "\""
    } else {
      value
    }

  fun row(event: LogEvent): String {
    val unknown = event.values.keys.filter { it !in COLUMN_SET || it in FIXED }
    require(unknown.isEmpty()) { "Unknown or fixed CSV columns: $unknown" }
    return COLUMNS.joinToString(",") { column ->
      val raw =
        when (column) {
          "time_utc" -> formatUtc(event.timeUtcMs)
          "elapsed_realtime_ns" -> event.elapsedRealtimeNs?.toString() ?: ""
          "event" -> event.event
          else -> event.values[column] ?: ""
        }
      escape(raw)
    }
  }
}
