package com.example.helloandroid.recording

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.helloandroid.data.LogEvent
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SensorReading(val values: List<Float>, val ageMs: Long, val rate: SensorRate)

data class GpsReading(
  val lat: Double,
  val lon: Double,
  val speedMps: Float?,
  val altitudeM: Double?,
  val accuracyM: Float?,
  val ageMs: Long,
)

/** [ageMs] is the age of the serving cell sample, when there is one. */
data class CellReading(val snapshot: CellSnapshot, val ageMs: Long?)

data class RecorderUiState(
  val recording: Boolean = false,
  /**
   * True after a session stopped: readings are the last values of that session and their ages are
   * as of the stop, not live.
   */
  val frozen: Boolean = false,
  val elapsedMs: Long = 0,
  val eventCount: Long = 0,
  val locationGranted: Boolean = false,
  val phoneStateGranted: Boolean = false,
  val sensorAvailable: Map<MotionSensor, Boolean> = emptyMap(),
  val sensors: Map<MotionSensor, SensorReading> = emptyMap(),
  val gpsStatus: String = "未開始",
  val gps: GpsReading? = null,
  val cell: CellReading? = null,
  val displayInfo: String? = null,
  val probeEnabled: Boolean = true,
  val network: NetworkState? = null,
  /** Set once the network callback has reported at least once this session. */
  val networkReported: Boolean = false,
  val lastProbe: ProbeResult? = null,
  val probeCount: Int = 0,
  val probeOkCount: Int = 0,
  val savableFileName: String? = null,
  val saved: Boolean = false,
  val message: String? = null,
)

/**
 * Owns one recording session. Survives configuration changes; the activity stops the session when
 * the screen goes to the background (MVP records only while the screen is shown).
 */
class RecorderViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application
  private val _state = MutableStateFlow(RecorderUiState())
  val state: StateFlow<RecorderUiState> = _state.asStateFlow()

  private var writer: SessionWriter? = null
  private var lastFile: File? = null
  private var startedElapsedNs = 0L
  private var tickerJob: Job? = null

  // Not cancelled by onCleared, so a session stopped during teardown still finishes writing.
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Latest values written from callback threads, read by the UI ticker.
  @Volatile private var latestSensors: Map<MotionSensor, MotionSample> = emptyMap()
  @Volatile private var latestFix: Location? = null
  @Volatile private var latestCell: CellSnapshot? = null

  private val sink: (LogEvent) -> Unit = { event -> writer?.write(event) }

  private val motion =
    MotionSensorCollector(app, sink) { kind, sample -> latestSensors = latestSensors + (kind to sample) }

  private val gps =
    GpsCollector(
      app,
      sink,
      onFix = { latestFix = it },
      onStatus = { label -> _state.update { it.copy(gpsStatus = label) } },
    )

  private val cell =
    CellCollector(
      app,
      sink,
      onSnapshot = { latestCell = it },
      onDisplayInfo = { label -> _state.update { it.copy(displayInfo = label) } },
    )

  private val network =
    NetworkCollector(
      app,
      sink,
      onNetwork = { net -> _state.update { it.copy(network = net, networkReported = true) } },
      onProbe = { result ->
        _state.update {
          it.copy(
            lastProbe = result,
            probeCount = it.probeCount + 1,
            probeOkCount = it.probeOkCount + if (result.outcome == ProbeOutcome.OK) 1 else 0,
          )
        }
      },
    )

  init {
    refreshPermissions()
    _state.update { it.copy(sensorAvailable = motion.availability()) }
  }

  private fun granted(permission: String) =
    app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  fun refreshPermissions() {
    _state.update {
      it.copy(
        locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        phoneStateGranted = granted(Manifest.permission.READ_PHONE_STATE),
      )
    }
  }

  fun setProbeEnabled(enabled: Boolean) {
    if (writer == null) _state.update { it.copy(probeEnabled = enabled) }
  }

  fun start() {
    if (writer != null) return
    refreshPermissions()
    val file =
      try {
        SessionFiles.create(File(app.filesDir, "sessions"), LocalDateTime.now())
      } catch (e: IOException) {
        _state.update { it.copy(message = "記録ファイルを作成できませんでした: ${e.message}") }
        return
      }
    val sessionWriter = SessionWriter(file, ioScope)
    writer = sessionWriter
    lastFile = file
    latestSensors = emptyMap()
    latestFix = null
    latestCell = null
    startedElapsedNs = Clock.nowElapsedNs()

    val current = _state.value
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        startedElapsedNs,
        "session_start",
        mapOf(
          "note" to
            "sdk=${Build.VERSION.SDK_INT};model=${Build.MODEL};" +
              "location=${current.locationGranted};phone_state=${current.phoneStateGranted};" +
              (if (current.probeEnabled) {
                "probe=${NetworkCollector.PROBE_URL};probe_interval_ms=${NetworkCollector.PROBE_INTERVAL_MS}"
              } else {
                "probe=off"
              })
        ),
      )
    )
    _state.update {
      it.copy(
        recording = true,
        frozen = false,
        elapsedMs = 0,
        eventCount = 0,
        sensors = emptyMap(),
        gps = null,
        cell = null,
        displayInfo = null,
        network = null,
        networkReported = false,
        lastProbe = null,
        probeCount = 0,
        probeOkCount = 0,
        savableFileName = null,
        saved = false,
        message = null,
      )
    }
    motion.start()
    gps.start(Looper.getMainLooper())
    cell.start()
    network.start(current.probeEnabled)
    tickerJob = viewModelScope.launch {
      while (isActive) {
        publish()
        delay(UI_REFRESH_MS)
      }
    }
  }

  /** [reason] is recorded in the CSV, e.g. "user" or "background". */
  fun stop(reason: String = "user") {
    val sessionWriter = writer ?: return
    // Stop every source and wait for their threads first, so nothing lands after session_stop.
    motion.stop()
    gps.stop()
    cell.stop()
    network.stop()
    tickerJob?.cancel()
    tickerJob = null
    publish()
    writer = null
    motion.rateSummary().forEach(sessionWriter::write)
    sessionWriter.write(
      LogEvent(Clock.nowUtcMs(), Clock.nowElapsedNs(), "session_stop", mapOf("note" to reason))
    )
    _state.update { it.copy(recording = false, frozen = true, gpsStatus = "停止") }
    ioScope.launch {
      sessionWriter.close()
      val error = sessionWriter.error
      _state.update {
        it.copy(
          eventCount = sessionWriter.count,
          savableFileName = sessionWriter.file.name,
          message =
            when {
              error != null -> "書き込みエラー: $error"
              reason == "background" -> "画面を離れたため計測を停止しました。CSV を保存してください。"
              else -> "計測を停止しました。CSV を保存してください。"
            },
        )
      }
    }
  }

  fun saveTo(uri: Uri) {
    val file = lastFile ?: return
    viewModelScope.launch {
      val result =
        withContext(Dispatchers.IO) {
          try {
            val out = app.contentResolver.openOutputStream(uri, "wt") ?: return@withContext "保存先を開けませんでした"
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            null
          } catch (e: IOException) {
            "保存に失敗しました: ${e.message ?: e.javaClass.simpleName}"
          } catch (e: SecurityException) {
            "保存先への書き込みが許可されませんでした"
          }
        }
      _state.update {
        if (result == null) it.copy(saved = true, message = "CSV を保存しました（${file.name}）")
        else it.copy(message = result)
      }
    }
  }

  private fun publish() {
    val now = Clock.nowElapsedNs()
    val fix = latestFix
    val cellSnapshot = latestCell
    val rates = motion.rates()
    _state.update {
      it.copy(
        elapsedMs = (now - startedElapsedNs) / 1_000_000,
        eventCount = writer?.count ?: it.eventCount,
        sensors =
          latestSensors.mapValues { (kind, s) ->
            SensorReading(
              s.values.toList(),
              (now - s.elapsedNs) / 1_000_000,
              rates[kind] ?: SensorRate(null, null),
            )
          },
        gps =
          fix?.let { loc ->
            GpsReading(
              lat = loc.latitude,
              lon = loc.longitude,
              speedMps = if (loc.hasSpeed()) loc.speed else null,
              altitudeM = if (loc.hasAltitude()) loc.altitude else null,
              accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
              ageMs = (now - loc.elapsedRealtimeNanos) / 1_000_000,
            )
          },
        cell =
          cellSnapshot?.let { snap ->
            CellReading(snap, snap.serving?.let { s -> (now - s.elapsedNs) / 1_000_000 })
          },
      )
    }
  }

  override fun onCleared() {
    stop("cleared")
  }

  private companion object {
    const val UI_REFRESH_MS = 500L
  }
}
