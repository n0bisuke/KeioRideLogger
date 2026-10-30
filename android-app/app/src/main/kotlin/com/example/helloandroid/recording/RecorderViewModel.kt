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
  /** OpenStreetMap background behind the live track (tile requests only while shown). */
  val mapEnabled: Boolean = true,
  /** User switch for the BLE live share; it runs only while recording. */
  val bleEnabled: Boolean = false,
  val ble: BleStatus = BleStatus(),
  val network: NetworkState? = null,
  /** Set once the network callback has reported at least once this session. */
  val networkReported: Boolean = false,
  val lastProbe: ProbeResult? = null,
  val probeCount: Int = 0,
  val probeOkCount: Int = 0,
  val live: LiveSnapshot = LiveSnapshot(),
  val speed: SpeedDisplay = SpeedDisplay(null, null, SpeedUnknownReason.NO_FIX),
  /** Write the session to CSV; chosen before start, fixed while recording. */
  val csvEnabled: Boolean = true,
  /** The running (or last) session is writing a CSV. */
  val sessionHasCsv: Boolean = true,
  /** App-private session CSVs, newest first. */
  val sessionFiles: List<SessionFileInfo> = emptyList(),
  /** True while a stopped session is still being flushed and closed. */
  val closingCsv: Boolean = false,
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
  // Recording state is separate from [writer]: with CSV off a session runs without a writer.
  private var active = false
  private val sessionStore = SessionStore(File(app.filesDir, "sessions"))
  private var lastFile: File? = null
  private var startedElapsedNs = 0L
  private var tickerJob: Job? = null

  // Not cancelled by onCleared, so a session stopped during teardown still finishes writing.
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  // Latest values written from callback threads, read by the UI ticker.
  @Volatile private var latestSensors: Map<MotionSensor, MotionSample> = emptyMap()
  @Volatile private var latestFix: Location? = null
  @Volatile private var latestCell: CellSnapshot? = null

  // Bounded UI copies for the live view; the CSV stays the complete record.
  private val liveHistory = LiveHistory()

  // Fed and read on the main thread only (GPS callbacks use the main looper).
  private val speedClassifier = SpeedClassifier()

  // Kept here (not in the composable) so cached tiles survive recomposition and rotation.
  val mapTiles = OsmTileLoader(app)

  private val ble = BleShareController(app) { status -> _state.update { it.copy(ble = status) } }
  private var bleTick = 0

  private val sink: (LogEvent) -> Unit = { event -> writer?.write(event) }

  private val motion =
    MotionSensorCollector(app, sink) { kind, sample -> latestSensors = latestSensors + (kind to sample) }

  private val gps =
    GpsCollector(
      app,
      sink,
      onFix = { loc ->
        latestFix = loc
        val speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null
        val accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        liveHistory.addFix(
          TrackPoint(loc.elapsedRealtimeNanos, loc.latitude, loc.longitude, accuracyM, speedMps?.let { it * 3.6 })
        )
        speedClassifier.onFix(loc.elapsedRealtimeNanos, speedMps, accuracyM)
      },
      onStatus = { gpsState -> _state.update { it.copy(gpsStatus = gpsState.label) } },
    )

  private val cell =
    CellCollector(
      app,
      sink,
      onSnapshot = { snap ->
        latestCell = snap
        snap.serving?.let { liveHistory.addSignal(SignalPoint(it.elapsedNs, it.rat, it.rsrpDbm, it.metrics)) }
        snap.switchHistory.forEach { liveHistory.addSwitch(it.to.elapsedNs) }
      },
      onDisplayInfo = { label -> _state.update { it.copy(displayInfo = label) } },
    )

  private val network =
    NetworkCollector(
      app,
      sink,
      onNetwork = { net -> _state.update { it.copy(network = net, networkReported = true) } },
      onProbe = { result ->
        liveHistory.addProbe(ProbePoint(result.elapsedNs, result.outcome, result.durationMs))
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
    refreshSessionFiles()
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

  /** Turns the BLE share on or off; when recording, applies immediately. */
  fun setBleEnabled(enabled: Boolean) {
    _state.update { it.copy(bleEnabled = enabled) }
    if (!active) return
    if (enabled) ble.start() else ble.stop()
  }

  fun setMapEnabled(enabled: Boolean) {
    _state.update { it.copy(mapEnabled = enabled) }
  }

  fun setProbeEnabled(enabled: Boolean) {
    if (!active) _state.update { it.copy(probeEnabled = enabled) }
  }

  /** CSV recording on/off; only before a session starts. */
  fun setCsvEnabled(enabled: Boolean) {
    if (!active) _state.update { it.copy(csvEnabled = enabled) }
  }

  /** Re-reads the app-private session CSVs on the IO dispatcher. */
  fun refreshSessionFiles() {
    ioScope.launch {
      val files = sessionStore.info()
      _state.update { it.copy(sessionFiles = files) }
    }
  }

  /**
   * Deletes every app-private session CSV (never SAF copies or other files). Refused while a
   * session runs or a stopped session is still being flushed and closed.
   */
  fun deleteAllSessionFiles() {
    val current = _state.value
    if (active || current.closingCsv) {
      _state.update { it.copy(message = "計測中・書き込み中は削除できません。") }
      return
    }
    ioScope.launch {
      val result = sessionStore.deleteAll()
      val remaining = sessionStore.info()
      val lastGone = lastFile?.exists() == false
      if (lastGone) lastFile = null
      _state.update {
        it.copy(
          sessionFiles = remaining,
          savableFileName = if (lastGone) null else it.savableFileName,
          saved = if (lastGone) false else it.saved,
          message =
            if (result.failed == 0) "アプリ内の計測 CSV を ${result.deleted} 件削除しました。"
            else "${result.deleted} 件削除しました。${result.failed} 件は削除できませんでした。",
        )
      }
    }
  }

  fun start() {
    if (active) return
    refreshPermissions()
    val recordCsv = _state.value.csvEnabled
    val file =
      try {
        sessionStore.newSessionFile(recordCsv, LocalDateTime.now())
      } catch (e: IOException) {
        _state.update { it.copy(message = "記録ファイルを作成できませんでした: ${e.message}") }
        return
      }
    // With CSV off nothing is written to disk: no file, no writer, and no stale file to save.
    writer = file?.let { SessionWriter(it, ioScope) }
    lastFile = file
    active = true
    latestSensors = emptyMap()
    latestFix = null
    latestCell = null
    liveHistory.clear()
    speedClassifier.reset()
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
        sessionHasCsv = recordCsv,
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
    if (current.bleEnabled) ble.start()
    tickerJob = viewModelScope.launch {
      while (isActive) {
        gps.checkStale(Clock.nowElapsedNs())
        publish()
        if (++bleTick % 2 == 0) ble.publish(_state.value, Clock.nowUtcMs())
        delay(UI_REFRESH_MS)
      }
    }
  }

  /** [reason] is recorded in the CSV, e.g. "user" or "background". */
  fun stop(reason: String = "user") {
    if (!active) return
    active = false
    val sessionWriter = writer
    // Stop every source and wait for their threads first, so nothing lands after session_stop.
    motion.stop()
    gps.stop()
    cell.stop()
    network.stop()
    ble.stop()
    tickerJob?.cancel()
    tickerJob = null
    publish()
    writer = null
    if (sessionWriter == null) {
      _state.update {
        it.copy(
          recording = false,
          frozen = true,
          gpsStatus = "停止",
          message = "計測を停止しました（CSV 記録オフのため、保存されたデータはありません）。",
        )
      }
      return
    }
    motion.rateSummary().forEach(sessionWriter::write)
    sessionWriter.write(
      LogEvent(Clock.nowUtcMs(), Clock.nowElapsedNs(), "session_stop", mapOf("note" to reason))
    )
    _state.update { it.copy(recording = false, frozen = true, gpsStatus = "停止", closingCsv = true) }
    ioScope.launch {
      sessionWriter.close()
      val error = sessionWriter.error
      _state.update {
        it.copy(
          eventCount = sessionWriter.count,
          closingCsv = false,
          savableFileName = sessionWriter.file.name,
          message =
            when {
              error != null -> "書き込みエラー: $error"
              reason == "background" -> "画面を離れたため計測を停止しました。CSV を保存してください。"
              else -> "計測を停止しました。CSV を保存してください。"
            },
        )
      }
      refreshSessionFiles()
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
        live = liveHistory.snapshot(now),
        speed = speedClassifier.display(now),
        cell =
          cellSnapshot?.let { snap ->
            CellReading(snap, snap.serving?.let { s -> (now - s.elapsedNs) / 1_000_000 })
          },
      )
    }
  }

  override fun onCleared() {
    mapTiles.shutdown()
    ble.stop()
    stop("cleared")
  }

  private companion object {
    const val UI_REFRESH_MS = 500L
  }
}
