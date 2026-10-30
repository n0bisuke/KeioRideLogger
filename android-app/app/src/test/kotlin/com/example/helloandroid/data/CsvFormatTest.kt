package com.example.helloandroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvFormatTest {
  private fun cells(row: String) = row.split(",")

  @Test
  fun header_hasUniqueColumnsAndStartsWithTime() {
    val header = cells(CsvFormat.header())
    assertEquals(CsvFormat.COLUMNS, header)
    assertEquals(header.size, header.toSet().size)
    assertEquals(listOf("time_utc", "elapsed_realtime_ns", "event"), header.take(3))
  }

  @Test
  fun formatUtc_isIso8601WithMillisInUtc() {
    assertEquals("1970-01-01T00:00:00.000Z", CsvFormat.formatUtc(0))
    assertEquals("2026-10-30T11:16:47.330Z", CsvFormat.formatUtc(1_793_359_007_330))
  }

  @Test
  fun row_placesValuesInColumnOrderAndLeavesMissingEmpty() {
    val row =
      CsvFormat.row(
        LogEvent(0, 42, "gps", mapOf("lon" to "139.5", "lat" to "35.6", "speed_mps" to "0.0"))
      )
    val cells = cells(row)
    assertEquals(CsvFormat.COLUMNS.size, cells.size)
    fun at(column: String) = cells[CsvFormat.COLUMNS.indexOf(column)]
    assertEquals("42", at("elapsed_realtime_ns"))
    assertEquals("gps", at("event"))
    assertEquals("35.6", at("lat"))
    assertEquals("139.5", at("lon"))
    // A real zero stays "0.0"; an unavailable value stays empty.
    assertEquals("0.0", at("speed_mps"))
    assertEquals("", at("altitude_m"))
  }

  @Test
  fun row_withoutElapsedTime_leavesItEmpty() {
    val cells = cells(CsvFormat.row(LogEvent(0, null, "session_stop")))
    assertEquals("", cells[1])
  }

  @Test
  fun escape_quotesCommasQuotesAndNewlines() {
    assertEquals("plain", CsvFormat.escape("plain"))
    assertEquals("\"a,b\"", CsvFormat.escape("a,b"))
    assertEquals("\"say \"\"hi\"\"\"", CsvFormat.escape("say \"hi\""))
    assertEquals("\"line1\nline2\"", CsvFormat.escape("line1\nline2"))
    assertEquals("\"cr\r\"", CsvFormat.escape("cr\r"))
  }

  @Test
  fun row_escapesNoteField() {
    val row = CsvFormat.row(LogEvent(0, 1, "sensor_status", mapOf("note" to "name=Foo, Inc.")))
    assert(row.endsWith(",\"name=Foo, Inc.\""))
  }

  @Test
  fun row_rejectsUnknownOrFixedColumns() {
    assertThrows(IllegalArgumentException::class.java) {
      CsvFormat.row(LogEvent(0, 1, "x", mapOf("latitude" to "1")))
    }
    assertThrows(IllegalArgumentException::class.java) {
      CsvFormat.row(LogEvent(0, 1, "x", mapOf("event" to "other")))
    }
  }
}
