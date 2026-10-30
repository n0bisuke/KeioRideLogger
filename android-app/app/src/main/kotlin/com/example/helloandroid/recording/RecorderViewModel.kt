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
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
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

data class SensorReading(val values: List<Float>, val ageMs: Long)

data class GpsReading(
  val lat: Double,
  val lon: Double,
  val speedMps: Float?,
  val altitudeM: Double?,
  val accuracyM: Float?,
  val ageMs: Long,
)

data class CellReading(val serving: CellObservation?, val ageMs: Long)

data class RecorderUiState(
  val recording: Boolean = false,
  val elapsedMs: Long = 0,
  val eventCount: Long = 0,
  val locationGranted: Boolean = false,
  val phoneStateGranted: Boolean = false,
  val sensorAvailable: Map<MotionSensor, Boolean> = emptyMap(),
  val sensors: Map<MotionSensor, SensorReading> = emptyMap(),
  val gpsStatus: String = "未開始",
  val gps: GpsReading? = null,
  val cellStatus: String = "未開始",
  val cell: CellReading? = null,
  val displayInfo: String? = null,
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
  @Volatile private var latestServing: CellObservation? = null
  @Volatile private var servingSeenNs: Long? = null

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
      onServing = {
        latestServing = it
        servingSeenNs = it?.elapsedNs
      },
      onDisplayInfo = { label -> _state.update { it.copy(displayInfo = label) } },
      onStatus = { label -> _state.update { it.copy(cellStatus = label) } },
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

  fun start() {
    if (writer != null) return
    refreshPermissions()
    val dir = File(app.filesDir, "sessions").apply { mkdirs() }
    val name = "keio-ride-" + LocalDateTime.now().format(FILE_TIME) + ".csv"
    val file = File(dir, name)
    val sessionWriter = SessionWriter(file, ioScope)
    writer = sessionWriter
    lastFile = file
    latestSensors = emptyMap()
    latestFix = null
    latestServing = null
    servingSeenNs = null
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
              "location=${current.locationGranted};phone_state=${current.phoneStateGranted}"
        ),
      )
    )
    _state.update {
      it.copy(
        recording = true,
        elapsedMs = 0,
        eventCount = 0,
        sensors = emptyMap(),
        gps = null,
        cell = null,
        displayInfo = null,
        savableFileName = null,
        saved = false,
        message = null,
      )
    }
    motion.start()
    gps.start(Looper.getMainLooper())
    cell.start()
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
    motion.stop()
    gps.stop()
    cell.stop()
    tickerJob?.cancel()
    tickerJob = null
    sink(LogEvent(Clock.nowUtcMs(), Clock.nowElapsedNs(), "session_stop", mapOf("note" to reason)))
    writer = null
    ioScope.launch {
      sessionWriter.close()
      val error = sessionWriter.error
      _state.update {
        it.copy(
          recording = false,
          eventCount = sessionWriter.count,
          savableFileName = sessionWriter.file.name,
          gpsStatus = "停止",
          cellStatus = "停止",
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
    val serving = latestServing
    val seen = servingSeenNs
    _state.update {
      it.copy(
        elapsedMs = (now - startedElapsedNs) / 1_000_000,
        eventCount = writer?.count ?: it.eventCount,
        sensors =
          latestSensors.mapValues { (_, s) ->
            SensorReading(s.values.toList(), (now - s.elapsedNs) / 1_000_000)
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
        cell = seen?.let { CellReading(serving, (now - it) / 1_000_000) },
      )
    }
  }

  override fun onCleared() {
    stop("cleared")
  }

  private companion object {
    const val UI_REFRESH_MS = 500L
    val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}
