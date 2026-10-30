package com.example.helloandroid.recording

import java.util.Locale

/**
 * Keio Ride Logger live BLE protocol, version 1. The Android app is the GATT server; a Web
 * Bluetooth page (web/csv-visualizer/) subscribes to one notify characteristic.
 *
 * Service  20896d82-0d9e-4791-8522-22bc245b46bb  "Keio Ride Logger live values, protocol v1"
 * Notify   b58ccb41-6443-4ee3-9d29-0d7e7a56cbe8  one frame per update, split into fragments
 *
 * Fragment layout (little endian):
 *   byte 0      protocol version (1)
 *   bytes 1..4  frame sequence number (uint32, increases by 1 per frame)
 *   byte 5      fragment index (0-based)
 *   byte 6      fragment count (1..255)
 *   bytes 7..   slice of the frame body
 * Frame body: UTF-8 JSON object (see [BlePayload]), at most [MAX_FRAME_BYTES] bytes.
 * A frame is shown only when all fragments of one sequence number arrived; incomplete, duplicate,
 * out-of-date or wrong-version frames are dropped by the receiver.
 *
 * The same constants live in web/csv-visualizer/index.html; change both together.
 */
object BleProtocol {
  const val SERVICE_UUID = "20896d82-0d9e-4791-8522-22bc245b46bb"
  const val CHARACTERISTIC_UUID = "b58ccb41-6443-4ee3-9d29-0d7e7a56cbe8"
  const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
  const val VERSION = 1
  const val HEADER_BYTES = 7
  const val MAX_FRAME_BYTES = 2048
  /** ATT notifications carry MTU - 3 bytes of value. */
  const val ATT_OVERHEAD = 3
  const val DEFAULT_MTU = 23

  /** Splits [body] into fragments that fit [mtu]. Throws if the frame is too large. */
  fun fragment(seq: Long, body: ByteArray, mtu: Int): List<ByteArray> {
    require(body.size <= MAX_FRAME_BYTES) { "frame too large: ${body.size}" }
    val chunk = (mtu - ATT_OVERHEAD - HEADER_BYTES).coerceAtLeast(1)
    val count = maxOf(1, (body.size + chunk - 1) / chunk)
    require(count <= 255) { "too many fragments: $count" }
    return (0 until count).map { i ->
      val from = i * chunk
      val to = minOf(body.size, from + chunk)
      val out = ByteArray(HEADER_BYTES + (to - from))
      out[0] = VERSION.toByte()
      for (b in 0 until 4) out[1 + b] = ((seq ushr (8 * b)) and 0xFF).toByte()
      out[5] = i.toByte()
      out[6] = count.toByte()
      System.arraycopy(body, from, out, HEADER_BYTES, to - from)
      out
    }
  }
}

/**
 * Receiver-side reassembly (mirrors the browser code; used by tests to pin the format).
 * Returns the complete frame body, or null while incomplete or when the fragment is rejected.
 */
class BleReassembler {
  private var currentSeq = -1L
  private var parts: Array<ByteArray?> = emptyArray()
  /** Sequence of the last frame handed out; older or equal frames are ignored. */
  var lastCompleteSeq = -1L
    private set

  fun accept(fragment: ByteArray): ByteArray? {
    if (fragment.size < BleProtocol.HEADER_BYTES) return null
    if (fragment[0].toInt() != BleProtocol.VERSION) return null
    var seq = 0L
    for (b in 0 until 4) seq = seq or ((fragment[1 + b].toLong() and 0xFF) shl (8 * b))
    val index = fragment[5].toInt() and 0xFF
    val count = fragment[6].toInt() and 0xFF
    if (count == 0 || index >= count || seq <= lastCompleteSeq) return null
    if (seq != currentSeq) {
      if (seq < currentSeq) return null // a fragment of an older, abandoned frame
      currentSeq = seq
      parts = arrayOfNulls(count)
    }
    if (parts.size != count) return null
    parts[index] = fragment.copyOfRange(BleProtocol.HEADER_BYTES, fragment.size)
    if (parts.any { it == null }) return null
    val body = parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
    if (body.size > BleProtocol.MAX_FRAME_BYTES) return null
    lastCompleteSeq = seq
    currentSeq = -1
    parts = emptyArray()
    return body
  }
}

/** Minimal JSON writer: no Android or library dependency, explicit nulls for missing values. */
class JsonWriter {
  private val sb = StringBuilder("{")
  private var first = true

  private fun key(name: String) {
    if (!first) sb.append(',')
    first = false
    sb.append('"').append(name).append("\":")
  }

  fun num(name: String, value: Number?) = apply {
    key(name)
    when {
      value == null -> sb.append("null")
      value is Double && !value.isFinite() -> sb.append("null")
      value is Float && !value.isFinite() -> sb.append("null")
      value is Double || value is Float -> sb.append(String.format(Locale.US, "%.9g", value.toDouble()).trimDecimal())
      else -> sb.append(value.toString())
    }
  }

  fun str(name: String, value: String?) = apply {
    key(name)
    if (value == null) sb.append("null") else sb.append('"').append(escape(value)).append('"')
  }

  fun bool(name: String, value: Boolean?) = apply { key(name); sb.append(value?.toString() ?: "null") }

  fun obj(name: String, value: JsonWriter?) = apply { key(name); sb.append(value?.toString() ?: "null") }

  override fun toString(): String = "$sb}"

  private fun String.trimDecimal(): String =
    if (contains('e') || contains('E') || !contains('.')) this else trimEnd('0').trimEnd('.')

  private fun escape(s: String): String = buildString {
    for (c in s) when {
      c == '"' -> append("\\\"")
      c == '\\' -> append("\\\\")
      c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
      else -> append(c)
    }
  }
}

/**
 * Builds the frame body from the latest UI state. Times are UTC epoch ms; ages are ms before
 * [nowElapsedNs]. Missing values are null, never 0. Includes cell identity and position on
 * purpose (shown on both screens); no raw 20 Hz sensor data and no CSV rows.
 */
object BlePayload {
  fun build(state: RecorderUiState, seq: Long, nowUtcMs: Long): ByteArray {
    val gps = state.gps
    val cell = state.cell?.snapshot
    val serving = cell?.serving
    val probe = state.lastProbe
    val windowEnd = state.live.windowEndNs
    val lastSwitch = cell?.switchHistory?.lastOrNull()
    val json =
      JsonWriter()
        .num("v", BleProtocol.VERSION)
        .num("seq", seq)
        .num("t", nowUtcMs)
        .bool("recording", state.recording)
        .str("gpsStatus", state.gpsStatus)
        .obj(
          "gps",
          gps?.let {
            JsonWriter()
              .num("lat", it.lat)
              .num("lon", it.lon)
              .num("kmh", it.speedMps?.let { mps -> mps * 3.6 })
              .num("accM", it.accuracyM)
              .num("ageMs", it.ageMs)
          },
        )
        .str("cellStatus", cell?.status?.name)
        .obj(
          "cell",
          serving?.let {
            JsonWriter()
              .str("rat", it.rat)
              .str("id", it.cellId)
              .num("pci", it.pci)
              .num("arfcn", it.arfcn)
              .num("rsrp", it.rsrpDbm)
              .num("rsrq", it.metrics["lte_rsrq_db"] ?: it.metrics["nr_ss_rsrq_db"])
              .num("snr", it.metrics["lte_rssnr_db"] ?: it.metrics["nr_ss_sinr_db"])
              .str("src", cell.servingSource)
              .num("ageMs", state.cell?.ageMs)
          },
        )
        .num("neighbors", cell?.observedCount)
        .num("switches", cell?.switchCount)
        .obj(
          "lastSwitch",
          lastSwitch?.let {
            JsonWriter().num("t", it.timeUtcMs).str("from", it.from.label).str("to", it.to.label)
          },
        )
        .obj(
          "net",
          state.network?.let { JsonWriter().str("transport", it.transport).bool("validated", it.validated) },
        )
        .obj(
          "probe",
          probe?.let {
            JsonWriter()
              .str("status", it.outcome.csv)
              .num("ms", it.durationMs)
              .num("http", it.httpStatus)
              .num("ageMs", if (windowEnd > 0) (windowEnd - it.elapsedNs) / 1_000_000 else null)
          },
        )
    return json.toString().toByteArray(Charsets.UTF_8)
  }
}
