package com.example.helloandroid.recording

import com.example.helloandroid.data.LogEvent
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionFilesTest {
  @get:Rule val tmp = TemporaryFolder()

  private val sameInstant = LocalDateTime.of(2026, 10, 30, 20, 8, 57, 228_000_000)

  @Test
  fun name_includesMilliseconds() {
    val file = SessionFiles.create(tmp.root, sameInstant)
    assertEquals("keio-ride-20261030-200857-228.csv", file.name)
  }

  @Test
  fun sameInstantTwice_givesDistinctFiles_andKeepsTheFirst() {
    val first = SessionFiles.create(tmp.root, sameInstant)
    first.writeText("previous session\n")
    val second = SessionFiles.create(tmp.root, sameInstant)
    assertNotEquals(first, second)
    assertEquals("previous session\n", first.readText())
  }

  @Test
  fun twoSessionsInSameInstant_eachKeepStartAndStop() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val files =
      (1..2).map { n ->
        val writer = SessionWriter(SessionFiles.create(tmp.root, sameInstant), scope)
        writer.write(LogEvent(0, 1, "session_start", mapOf("note" to "session$n")))
        writer.write(LogEvent(0, 2, "session_stop", mapOf("note" to "user")))
        writer.close()
        writer.file
      }
    assertEquals(2, files.toSet().size)
    files.forEachIndexed { i, file ->
      val lines = file.readLines()
      assertEquals(3, lines.size)
      assertTrue(lines[1].contains("session_start") && lines[1].endsWith("session${i + 1}"))
      assertTrue(lines[2].contains("session_stop"))
    }
  }
}
