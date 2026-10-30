package com.example.helloandroid.recording

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.example.helloandroid.data.LogEvent
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


/** What the cell APIs have told us recently, so the UI can tell "no data" from "no serving cell". */
enum class CellStatus(val label: String) {
  WAITING("応答待ち"),
  SERVING("在圏セルあり"),
  NEIGHBORS_ONLY("観測データあり（在圏セルなし）"),
  EMPTY("観測データなし（空の応答）"),
  NO_RESPONSE("応答なし"),
  ERROR("エラー"),
  PERMISSION_DENIED("権限なし（位置情報）"),
  UNAVAILABLE("通信機能なし"),
}

data class CellSnapshot(
  val status: CellStatus,
  val detail: String? = null,
  val serving: CellObservation? = null,
  /** API path the serving cell came from: request / cached / listener. */
  val servingSource: String? = null,
  /** Cells in the freshest response, registered or not. */
  val observedCount: Int = 0,
  /** True once any registered cell was seen this session (the switch baseline exists). */
  val servingEverSeen: Boolean = false,
  val switchCount: Int = 0,
  val switchHistory: List<CellSwitch> = emptyList(),
  /** Signal of the current connection from SignalStrength (includes the NR leg of NSA). */
  val connectionSignals: List<SignalReading> = emptyList(),
)

/** One entry of TelephonyManager SignalStrength: no cell identity, only the connection's signal. */
data class SignalReading(val rat: String, val rsrpDbm: Int?, val metrics: Map<String, Int>)

/**
 * Collects cell info from three API paths and records which of them report a registered cell:
 * - `request`: requestCellInfoUpdate() on a TelephonyManager bound to the default data subscription
 *   (that call only queries the modem of the bound subscription),
 * - `cached`: allCellInfo on the default TelephonyManager (all modems, possibly stale),
 * - `listener`: TelephonyCallback.CellInfoListener (API 31+, fires on platform updates).
 *
 * Per-cell `cell` rows come from the request path, plus registered cells from the other paths.
 * Every response also yields a `cell_poll` row with counts only (no identifiers). What the modem
 * reports differs per device, SIM and carrier, and the platform rate-limits fresh requests, so a
 * poll is not guaranteed to return new data.
 */
class CellCollector(
  private val context: Context,
  private val sink: (LogEvent) -> Unit,
  private val onSnapshot: (CellSnapshot) -> Unit,
  private val onDisplayInfo: (String) -> Unit,
) {
  private val defaultTelephony = context.getSystemService(TelephonyManager::class.java)
  private var boundTelephony: TelephonyManager? = null
  private var executor: ScheduledExecutorService? = null
  private val callbacks = mutableListOf<TelephonyCallback>()

  // Touched only on the single executor thread.
  private class SourceResult(val receivedNs: Long, val total: Int, val serving: CellObservation?)
  private val latest = HashMap<String, SourceResult>()
  private var requestPendingSinceNs: Long? = null
  private var lastProblem: Pair<CellStatus, String?>? = null
  private val tracker = ServingCellTracker()
  private val lastRegisteredNs = HashMap<String, Long>()
  @Volatile private var connectionSignals: List<SignalReading> = emptyList()
  @Volatile private var overrideRat: String? = null

  private fun granted(permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  private fun statusRow(status: String, note: String = "") {
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        Clock.nowElapsedNs(),
        "cell_status",
        mapOf("status" to status, "note" to note),
      )
    )
  }

  fun start() {
    latest.clear()
    requestPendingSinceNs = null
    lastProblem = null
    tracker.reset()
    lastRegisteredNs.clear()
    connectionSignals = emptyList()
    overrideRat = null
    val tm = defaultTelephony
    if (tm == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
      statusRow("unavailable")
      onSnapshot(CellSnapshot(CellStatus.UNAVAILABLE))
      return
    }
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
      statusRow("permission_denied")
      onSnapshot(CellSnapshot(CellStatus.PERMISSION_DENIED))
      return
    }
    val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
    boundTelephony =
      if (dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(dataSub)
      else tm
    statusRow("started", diagnostics(tm, dataSub))
    onSnapshot(CellSnapshot(CellStatus.WAITING))

    val exec = Executors.newSingleThreadScheduledExecutor()
    executor = exec
    exec.scheduleWithFixedDelay(::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    registerCallbacks(exec)
  }

  /** Unregisters callbacks and waits for in-flight work, so nothing is recorded after return. */
  fun stop() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      callbacks.forEach { boundTelephony?.unregisterTelephonyCallback(it) }
    }
    callbacks.clear()
    executor?.let {
      it.shutdownNow()
      it.awaitTermination(EXECUTOR_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    executor = null
    boundTelephony = null
  }

  /** Subscription/modem facts as booleans and counts only; no identifiers are written. */
  @SuppressLint("MissingPermission") // Guarded by permission checks; SecurityException handled.
  private fun diagnostics(tm: TelephonyManager, dataSub: Int): String {
    val parts = mutableListOf<String>()
    val defaultSub = SubscriptionManager.getDefaultSubscriptionId()
    parts += "data_sub_valid=${dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID}"
    parts += "default_sub_valid=${defaultSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID}"
    parts += "data_sub_is_default_sub=${dataSub == defaultSub}"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) parts += "active_modems=${tm.activeModemCount}"
    parts += "sim_state=${tm.simState}"
    if (granted(Manifest.permission.READ_PHONE_STATE)) {
      try {
        val subs = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        parts += "active_subs=${subs?.size ?: 0}"
        val bound = boundTelephony ?: tm
        parts += "data_network=${networkTypeName(bound.dataNetworkType)}"
        bound.serviceState?.let { parts += "service_state=${serviceStateName(it.state)}" }
      } catch (e: SecurityException) {
        parts += "phone_state_query=denied"
      }
    } else {
      parts += "phone_state_permission=false"
    }
    return parts.joinToString(";")
  }

  @SuppressLint("MissingPermission") // start() checked ACCESS_FINE_LOCATION; SecurityException handled.
  private fun poll() {
    val now = Clock.nowElapsedNs()
    val pending = requestPendingSinceNs
    if (pending != null && now - pending > NO_RESPONSE_NS) {
      pollRow("request", "no_response", "waited_ms=${(now - pending) / 1_000_000}")
      lastProblem = CellStatus.NO_RESPONSE to null
      requestPendingSinceNs = null
      publish()
    }
    try {
      // Platform cache across all modems; cheap, may be stale.
      @Suppress("DEPRECATION")
      defaultTelephony?.allCellInfo?.let { handle(it, "cached") }

      val bound = boundTelephony ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val exec = executor ?: return
        if (requestPendingSinceNs == null) requestPendingSinceNs = now
        bound.requestCellInfoUpdate(
          exec,
          object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
              requestPendingSinceNs = null
              handle(cellInfo, "request")
            }

            override fun onError(errorCode: Int, detail: Throwable?) {
              requestPendingSinceNs = null
              pollRow("request", "error", "code=$errorCode")
              lastProblem = CellStatus.ERROR to "code=$errorCode"
              publish()
            }
          },
        )
      }
    } catch (e: SecurityException) {
      statusRow("permission_denied")
      lastProblem = CellStatus.PERMISSION_DENIED to null
      publish()
    } catch (e: IllegalStateException) {
      pollRow("request", "error", e.javaClass.simpleName)
      lastProblem = CellStatus.ERROR to e.javaClass.simpleName
      publish()
    }
  }

  private fun pollRow(source: String, status: String, note: String) {
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        Clock.nowElapsedNs(),
        "cell_poll",
        mapOf("status" to status, "note" to "src=$source;$note"),
      )
    )
  }

  private fun handle(cells: List<CellInfo>, source: String) {
    val now = Clock.nowElapsedNs()
    val observations = cells.mapNotNull(::observe)
    val registered = observations.filter { it.registered }
    val counts = observations.groupingBy { it.rat }.eachCount().entries.joinToString(";") {
      "${it.key.lowercase()}=${it.value}"
    }
    pollRow(
      source,
      if (observations.isEmpty()) "empty" else "ok",
      "n=${observations.size};registered=${registered.size}" + (if (counts.isEmpty()) "" else ";$counts"),
    )

    for (obs in observations) {
      // Neighbour lists from the secondary paths would duplicate the request path; keep only
      // their registered cells, which are what the comparison is about.
      if (source != "request" && (!obs.registered || lastRegisteredNs[source] == obs.elapsedNs)) continue
      if (source != "request") lastRegisteredNs[source] = obs.elapsedNs
      sink(
        LogEvent(
          timeUtcMs = Clock.elapsedNsToUtcMs(obs.elapsedNs),
          elapsedRealtimeNs = obs.elapsedNs,
          event = "cell",
          values = buildMap {
            put("rat", obs.rat)
            obs.cellId?.let { put("cell_id", it) }
            obs.pci?.let { put("pci", it.toString()) }
            obs.tac?.let { put("tac", it.toString()) }
            obs.arfcn?.let { put("arfcn", it.toString()) }
            obs.bands?.let { put("bands", it) }
            obs.rsrpDbm?.let { put("rsrp_dbm", it.toString()) }
            obs.metrics.forEach { (column, value) -> put(column, value.toString()) }
            put("registered", obs.registered.toString())
            put("age_ms", ((now - obs.elapsedNs) / 1_000_000).toString())
            put("note", "src=$source")
          },
        )
      )
    }

    latest[source] = SourceResult(now, observations.size, registered.maxByOrNull { it.elapsedNs })
    if (source == "request") lastProblem = null
    publish()
  }

  /** Combines the fresh results of all paths into one status and records serving-cell changes. */
  private fun publish() {
    val now = Clock.nowElapsedNs()
    val fresh = latest.filterValues { now - it.receivedNs < FRESH_NS }
    val servingEntry =
      fresh.entries.filter { it.value.serving != null }.maxByOrNull { it.value.serving!!.elapsedNs }
    val serving = servingEntry?.value?.serving
    val observed = fresh["request"]?.total ?: fresh.values.maxOfOrNull { it.total } ?: 0

    if (serving != null) {
      val timeUtcMs = Clock.elapsedNsToUtcMs(serving.elapsedNs)
      val (status, previous) =
        when (val result = tracker.observe(serving, timeUtcMs)) {
          is ServingCellTracker.Result.Baseline -> "baseline" to null
          is ServingCellTracker.Result.Switched -> "switched" to result.change.from
          is ServingCellTracker.Result.Indeterminate -> "indeterminate" to tracker.current
          ServingCellTracker.Result.Same, ServingCellTracker.Result.NotRegistered -> null to null
        }
      if (status != null) {
        sink(
          LogEvent(
            timeUtcMs,
            serving.elapsedNs,
            "cell_change",
            buildMap {
              put("status", status)
              put("rat", serving.rat)
              serving.cellId?.let { put("cell_id", it) }
              serving.pci?.let { put("pci", it.toString()) }
              serving.arfcn?.let { put("arfcn", it.toString()) }
              put(
                "note",
                "src=${servingEntry.key};switch_count=${tracker.switchCount}" +
                  (previous?.let { p -> ";prev=${p.rat}/${p.cellId ?: "?"}/${p.pci ?: "?"}/${p.arfcn ?: "?"}" } ?: ""),
              )
            },
          )
        )
      }
    }

    val problem = lastProblem
    val base =
      when {
        serving != null ->
          CellSnapshot(CellStatus.SERVING, null, serving, servingEntry.key, observed)
        problem != null -> CellSnapshot(problem.first, problem.second, observedCount = observed)
        observed > 0 -> CellSnapshot(CellStatus.NEIGHBORS_ONLY, observedCount = observed)
        fresh.isNotEmpty() -> CellSnapshot(CellStatus.EMPTY)
        else -> CellSnapshot(CellStatus.WAITING)
      }
    onSnapshot(
      base.copy(
        servingEverSeen = tracker.current != null,
        switchCount = tracker.switchCount,
        switchHistory = tracker.history,
        connectionSignals = connectionSignals,
      )
    )
  }

  @SuppressLint("MissingPermission") // start() checked ACCESS_FINE_LOCATION; SecurityException handled.
  private fun registerCallbacks(exec: ScheduledExecutorService) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val tm = boundTelephony ?: return

    val cellInfoCallback =
      object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) = handle(cellInfo, "listener")
      }
    try {
      tm.registerTelephonyCallback(exec, cellInfoCallback)
      callbacks += cellInfoCallback
    } catch (e: SecurityException) {
      statusRow("listener_denied")
    }

    // SignalStrength describes the current connection, including the NR leg of NSA, which often
    // has no registered CellInfoNr. It carries no cell identity.
    val signalCallback =
      object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
          val now = Clock.nowElapsedNs()
          val readings = signalStrength.cellSignalStrengths.mapNotNull(::signalReading)
          connectionSignals = readings
          for (reading in readings) {
            sink(
              LogEvent(
                Clock.nowUtcMs(),
                now,
                "signal_strength",
                buildMap {
                  put("rat", reading.rat)
                  overrideRat?.let { put("override_rat", it) }
                  reading.rsrpDbm?.let { put("rsrp_dbm", it.toString()) }
                  reading.metrics.forEach { (column, value) -> put(column, value.toString()) }
                  put("note", "src=signal_strength")
                },
              )
            )
          }
          if (readings.isEmpty()) {
            sink(LogEvent(Clock.nowUtcMs(), now, "signal_strength", mapOf("status" to "empty")))
          }
          publish()
        }
      }
    try {
      tm.registerTelephonyCallback(exec, signalCallback)
      callbacks += signalCallback
    } catch (e: SecurityException) {
      statusRow("signal_listener_denied")
    }

    if (!granted(Manifest.permission.READ_PHONE_STATE)) {
      onDisplayInfo("権限なし（電話の状態）")
      sink(
        LogEvent(
          Clock.nowUtcMs(),
          Clock.nowElapsedNs(),
          "display_info",
          mapOf("status" to "permission_denied"),
        )
      )
      return
    }
    val displayCallback =
      object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
          val rat = networkTypeName(info.networkType)
          val override = overrideName(info.overrideNetworkType)
          overrideRat = override
          sink(
            LogEvent(
              Clock.nowUtcMs(),
              Clock.nowElapsedNs(),
              "display_info",
              mapOf("rat" to rat, "override_rat" to override),
            )
          )
          onDisplayInfo(if (override == "NONE") rat else "$rat ($override)")
        }
      }
    try {
      tm.registerTelephonyCallback(exec, displayCallback)
      callbacks += displayCallback
    } catch (e: SecurityException) {
      onDisplayInfo("権限なし（電話の状態）")
    }
  }

  companion object {
    const val POLL_INTERVAL_MS = 2_000L
    private const val NO_RESPONSE_NS = 5_000_000_000L
    private const val FRESH_NS = 10_000_000_000L
    private const val EXECUTOR_STOP_TIMEOUT_MS = 500L
    private const val UNAVAILABLE_INT = Int.MAX_VALUE
    private const val UNAVAILABLE_LONG = Long.MAX_VALUE

    private fun Int.orNull(): Int? = takeIf { it != UNAVAILABLE_INT }

    private fun timestampNs(info: CellInfo): Long =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        info.timestampMillis * 1_000_000
      } else {
        @Suppress("DEPRECATION") info.timeStamp
      }

    private fun bands(values: IntArray): String? =
      values.takeIf { it.isNotEmpty() }?.joinToString(";")

    /** LTE quality metrics with their own column names/units; unavailable values are omitted. */
    private fun lteMetrics(s: CellSignalStrengthLte): Map<String, Int> = buildMap {
      s.rsrq.orNull()?.let { put("lte_rsrq_db", it) }
      s.rssnr.orNull()?.let { put("lte_rssnr_db", it) }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.rssi.orNull()?.let { put("lte_rssi_dbm", it) }
      s.cqi.orNull()?.let { put("lte_cqi", it) }
    }

    /** NR quality metrics (SS- and CSI-based); unavailable values are omitted. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun nrMetrics(s: CellSignalStrengthNr): Map<String, Int> = buildMap {
      s.ssRsrq.orNull()?.let { put("nr_ss_rsrq_db", it) }
      s.ssSinr.orNull()?.let { put("nr_ss_sinr_db", it) }
      s.csiRsrp.orNull()?.let { put("nr_csi_rsrp_dbm", it) }
      s.csiRsrq.orNull()?.let { put("nr_csi_rsrq_db", it) }
      s.csiSinr.orNull()?.let { put("nr_csi_sinr_db", it) }
    }

    fun signalReading(s: CellSignalStrength): SignalReading? =
      when {
        s is CellSignalStrengthLte -> SignalReading("LTE", s.rsrp.orNull(), lteMetrics(s))
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && s is CellSignalStrengthNr ->
          SignalReading("NR", s.ssRsrp.orNull(), nrMetrics(s))
        else -> null
      }

    fun observe(info: CellInfo): CellObservation? {
      val ts = timestampNs(info)
      return when {
        info is CellInfoLte -> {
          val id = info.cellIdentity
          CellObservation(
            rat = "LTE",
            cellId = id.ci.orNull()?.toString(),
            pci = id.pci.orNull(),
            tac = id.tac.orNull(),
            arfcn = id.earfcn.orNull(),
            bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bands(id.bands) else null,
            rsrpDbm = info.cellSignalStrength.rsrp.orNull(),
            metrics = lteMetrics(info.cellSignalStrength),
            registered = info.isRegistered,
            elapsedNs = ts,
          )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr -> {
          val id = info.cellIdentity as CellIdentityNr
          val signal = info.cellSignalStrength as CellSignalStrengthNr
          CellObservation(
            rat = "NR",
            cellId = id.nci.takeIf { it != UNAVAILABLE_LONG }?.toString(),
            pci = id.pci.orNull(),
            tac = id.tac.orNull(),
            arfcn = id.nrarfcn.orNull(),
            bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bands(id.bands) else null,
            rsrpDbm = signal.ssRsrp.orNull(),
            metrics = nrMetrics(signal),
            registered = info.isRegistered,
            elapsedNs = ts,
          )
        }
        info is CellInfoWcdma ->
          CellObservation("WCDMA", info.cellIdentity.cid.orNull()?.toString(), null, null,
            info.cellIdentity.uarfcn.orNull(), null, null, info.isRegistered, ts)
        info is CellInfoGsm ->
          CellObservation("GSM", info.cellIdentity.cid.orNull()?.toString(), null, null,
            info.cellIdentity.arfcn.orNull(), null, null, info.isRegistered, ts)
        else -> null
      }
    }

    fun serviceStateName(state: Int): String =
      when (state) {
        ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
        ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
        ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
        ServiceState.STATE_POWER_OFF -> "POWER_OFF"
        else -> "STATE_$state"
      }

    fun networkTypeName(type: Int): String =
      when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "WCDMA"
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "GSM"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$type"
      }

    fun overrideName(type: Int): String =
      when (type) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "NR_ADVANCED"
        else -> "OVERRIDE_$type"
      }
  }
}
