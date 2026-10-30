package com.example.helloandroid.data

/**
 * One CSV row. [timeUtcMs] is the acquisition time (UTC epoch millis); [elapsedRealtimeNs] is the
 * monotonic timestamp the platform attached to the sample, when it has one.
 * [values] keys must be names from [CsvFormat.COLUMNS]; missing keys are written as empty cells.
 */
data class LogEvent(
  val timeUtcMs: Long,
  val elapsedRealtimeNs: Long?,
  val event: String,
  val values: Map<String, String> = emptyMap(),
)
