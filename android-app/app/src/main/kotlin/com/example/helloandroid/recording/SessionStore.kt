package com.example.helloandroid.recording

import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/** One session CSV kept in the app's private sessions directory. */
data class SessionFileInfo(val name: String, val sizeBytes: Long, val lastModifiedMs: Long)

data class DeleteResult(val deleted: Int, val failed: Int, val bytesDeleted: Long)

/**
 * Lists and deletes the app's own session CSVs. Only regular files named like
 * `keio-ride-*.csv` directly inside [dir] are touched; anything else in the directory, other app
 * files and copies exported through SAF (which live at other URIs) are never listed or deleted.
 */
class SessionStore(
  private val dir: File,
  private val deleter: (File) -> Boolean = { it.delete() },
) {
  private fun canonicalDir(): File? = runCatching { dir.canonicalFile }.getOrNull()

  /** Session files, newest first. Symlinks or entries resolving outside [dir] are ignored. */
  fun list(): List<File> {
    val root = canonicalDir() ?: return emptyList()
    return (dir.listFiles() ?: return emptyList())
      .filter { f ->
        f.isFile &&
          NAME.matches(f.name) &&
          runCatching { f.canonicalFile.parentFile == root }.getOrDefault(false)
      }
      .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
  }

  fun info(): List<SessionFileInfo> = list().map { SessionFileInfo(it.name, it.length(), it.lastModified()) }

  /** Deletes every listed session file. Callers must make sure no session is being written. */
  fun deleteAll(): DeleteResult {
    var deleted = 0
    var failed = 0
    var bytes = 0L
    for (f in list()) {
      val size = f.length()
      if (runCatching { deleter(f) }.getOrDefault(false)) {
        deleted++
        bytes += size
      } else {
        failed++
      }
    }
    return DeleteResult(deleted, failed, bytes)
  }

  /**
   * File for a new session, or null when CSV recording is off; in that case nothing is created
   * on disk (not even the directory).
   */
  fun newSessionFile(recordCsv: Boolean, now: LocalDateTime): File? =
    if (recordCsv) SessionFiles.create(dir, now) else null

  companion object {
    /** Matches what [SessionFiles.create] produces, including the "-2" style suffixes. */
    val NAME = Regex("""keio-ride-\d{8}-\d{6}-\d{3}(-\d+)?\.csv""")
  }
}

/** Thrown when an operation needs the session directory to be idle. */
class SessionBusyException : IOException("計測中または書き込み中のため操作できません")
