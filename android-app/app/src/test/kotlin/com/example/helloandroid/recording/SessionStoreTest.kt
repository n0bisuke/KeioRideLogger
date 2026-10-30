package com.example.helloandroid.recording

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun session(dir: File, name: String, modified: Long, content: String = "x") =
    File(dir, name).apply { writeText(content); setLastModified(modified) }

  @Test
  fun list_isNewestFirst_andOnlySessionCsvs() {
    val dir = tmp.newFolder("sessions")
    session(dir, "keio-ride-20261030-100000-000.csv", 1_000_000)
    session(dir, "keio-ride-20261030-110000-000.csv", 3_000_000)
    session(dir, "keio-ride-20261030-110000-000-2.csv", 2_000_000)
    File(dir, "notes.txt").writeText("keep")
    File(dir, "other.csv").writeText("keep")
    File(dir, "keio-ride-20261030-120000-000.csv.bak").writeText("keep")
    File(dir, "keio-ride-20261030-130000-000.csv").mkdir() // a directory, not a file
    val names = SessionStore(dir).list().map { it.name }
    assertEquals(
      listOf("keio-ride-20261030-110000-000.csv", "keio-ride-20261030-110000-000-2.csv", "keio-ride-20261030-100000-000.csv"),
      names,
    )
  }

  @Test
  fun deleteAll_removesOnlySessionCsvs_andReportsBytes() {
    val dir = tmp.newFolder("sessions")
    session(dir, "keio-ride-20261030-100000-000.csv", 1, "12345")
    session(dir, "keio-ride-20261030-100001-000.csv", 2, "123")
    val keep = File(dir, "notes.txt").apply { writeText("keep") }
    val outside = tmp.newFile("keio-ride-20261030-100002-000.csv") // sibling of the dir, not inside
    val result = SessionStore(dir).deleteAll()
    assertEquals(DeleteResult(deleted = 2, failed = 0, bytesDeleted = 8), result)
    assertTrue(keep.exists())
    assertTrue(outside.exists())
    assertTrue(SessionStore(dir).list().isEmpty())
  }

  @Test
  fun deleteAll_onEmptyOrMissingDir_isNoop() {
    assertEquals(DeleteResult(0, 0, 0), SessionStore(tmp.newFolder("empty")).deleteAll())
    assertEquals(DeleteResult(0, 0, 0), SessionStore(File(tmp.root, "missing")).deleteAll())
  }

  @Test
  fun deleteAll_countsPartialFailures() {
    val dir = tmp.newFolder("sessions")
    session(dir, "keio-ride-20261030-100000-000.csv", 1)
    session(dir, "keio-ride-20261030-100001-000.csv", 2)
    val stubborn = "keio-ride-20261030-100001-000.csv"
    val result = SessionStore(dir) { f -> if (f.name == stubborn) false else f.delete() }.deleteAll()
    assertEquals(1, result.deleted)
    assertEquals(1, result.failed)
    assertEquals(listOf(stubborn), SessionStore(dir).list().map { it.name })
  }

  @Test
  fun symlinkPointingOutside_isNotListedOrDeleted() {
    val dir = tmp.newFolder("sessions")
    val target = tmp.newFile("precious.csv").apply { writeText("do not delete") }
    Files.createSymbolicLink(File(dir, "keio-ride-20261030-100000-000.csv").toPath(), target.toPath())
    assertTrue(SessionStore(dir).list().isEmpty())
    SessionStore(dir).deleteAll()
    assertTrue(target.exists())
  }

  @Test
  fun recordingOff_createsNoFileOrDirectory() {
    val dir = File(tmp.root, "sessions")
    assertNull(SessionStore(dir).newSessionFile(recordCsv = false, now = LocalDateTime.of(2026, 10, 30, 10, 0)))
    assertFalse(dir.exists())
    val file = SessionStore(dir).newSessionFile(recordCsv = true, now = LocalDateTime.of(2026, 10, 30, 10, 0))
    assertTrue(file!!.exists())
    assertTrue(SessionStore.NAME.matches(file.name))
  }
}
