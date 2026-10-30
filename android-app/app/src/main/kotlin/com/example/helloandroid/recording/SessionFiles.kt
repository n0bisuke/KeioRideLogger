package com.example.helloandroid.recording

import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Allocates a session CSV path that never reuses an existing file. */
object SessionFiles {
  private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

  /** Atomically creates and returns a new empty file in [dir]. */
  fun create(dir: File, now: LocalDateTime): File {
    dir.mkdirs()
    val base = "keio-ride-" + now.format(FILE_TIME)
    for (attempt in 1..1000) {
      val name = if (attempt == 1) "$base.csv" else "$base-$attempt.csv"
      val file = File(dir, name)
      if (file.createNewFile()) return file
    }
    throw IOException("Could not allocate a session file for $base")
  }
}
