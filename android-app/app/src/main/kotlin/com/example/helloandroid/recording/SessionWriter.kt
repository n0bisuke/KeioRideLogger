package com.example.helloandroid.recording

import com.example.helloandroid.data.CsvFormat
import com.example.helloandroid.data.LogEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Appends events to a CSV file on a background coroutine. [write] is safe to call from any thread
 * (sensor, location and telephony callbacks) and never blocks.
 */
class SessionWriter(val file: File, scope: CoroutineScope) {
  // [scope] must outlive the screen (not viewModelScope) so queued rows reach disk after onCleared.
  private val channel = Channel<LogEvent>(Channel.UNLIMITED)
  private val written = AtomicLong(0)

  @Volatile
  var error: String? = null
    private set

  val count: Long
    get() = written.get()

  private val job =
    scope.launch(Dispatchers.IO) {
      try {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
          writer.write(CsvFormat.header())
          writer.write("\n")
          var lastFlushNs = System.nanoTime()
          for (event in channel) {
            writer.write(CsvFormat.row(event))
            writer.write("\n")
            written.incrementAndGet()
            val now = System.nanoTime()
            if (now - lastFlushNs > FLUSH_INTERVAL_NS) {
              writer.flush()
              lastFlushNs = now
            }
          }
        }
      } catch (e: IOException) {
        error = e.message ?: e.javaClass.simpleName
        // Drain so senders never pile up memory after a failure.
        for (ignored in channel) Unit
      }
    }

  fun write(event: LogEvent) {
    channel.trySend(event)
  }

  /** Stops accepting events and waits until everything queued is on disk. */
  suspend fun close() {
    channel.close()
    job.join()
  }

  private companion object {
    const val FLUSH_INTERVAL_NS = 1_000_000_000L
  }
}
