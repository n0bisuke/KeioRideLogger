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
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.example.helloandroid.data.LogEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One observed cell. Null fields were not reported by the modem for this sample. */
data class CellObservation(
  val rat: String,
  val cellId: String?,
  val pci: Int?,
  val tac: Int?,
  val arfcn: Int?,
  val bands: String?,
  val rsrpDbm: Int?,
  val registered: Boolean,
  val elapsedNs: Long,
) {
  /** Identity used to detect an observed change of the serving cell. */
  val key: String
    get() = "$rat/${cellId ?: "?"}/${pci ?: "?"}/${arfcn ?: "?"}"
}

/**
 * Polls cell info every [POLL_INTERVAL_MS] and listens for the LTE/NR display type. What the modem
 * reports differs per device, SIM and carrier; empty results are a normal outcome.
 */
class CellCollector(
  private val context: Context,
  private val sink: (LogEvent) -> Unit,
  private val onServing: (CellObservation?) -> Unit,
  private val onDisplayInfo: (String) -> Unit,
  private val onStatus: (String) -> Unit,
) {
  private val telephony = context.getSystemService(TelephonyManager::class.java)
  private var executor: ExecutorService? = null
  private var displayCallback: Any? = null
  private var lastServingKey: String? = null

  private fun granted(permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  private fun status(status: String, label: String, note: String = "") {
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        Clock.nowElapsedNs(),
        "cell_status",
        mapOf("status" to status, "note" to note),
      )
    )
    onStatus(label)
  }

  fun start() {
    lastServingKey = null
    if (telephony == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
      status("unavailable", "通信機能なし")
      return
    }
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
      status("permission_denied", "権限なし（位置情報）")
      return
    }
    status("started", "取得中")
    val exec = Executors.newSingleThreadScheduledExecutor()
    executor = exec
    exec.scheduleWithFixedDelay(::poll, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    registerDisplayInfo(exec)
  }

  fun stop() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      (displayCallback as? TelephonyCallback)?.let { telephony?.unregisterTelephonyCallback(it) }
    }
    displayCallback = null
    executor?.shutdownNow()
    executor = null
  }

  @SuppressLint("MissingPermission") // Callers check ACCESS_FINE_LOCATION; SecurityException handled.
  private fun poll() {
    val tm = telephony ?: return
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val exec = executor ?: return
        tm.requestCellInfoUpdate(
          exec,
          object : TelephonyManager.CellInfoCallback() {
            override fun onCellInfo(cellInfo: MutableList<CellInfo>) = handle(cellInfo)

            override fun onError(errorCode: Int, detail: Throwable?) =
              status("update_error", "取得エラー", "code=$errorCode")
          },
        )
      } else {
        @Suppress("DEPRECATION") handle(tm.allCellInfo.orEmpty())
      }
    } catch (e: SecurityException) {
      status("permission_denied", "権限なし（位置情報）")
    } catch (e: IllegalStateException) {
      status("update_error", "取得エラー", e.javaClass.simpleName)
    }
  }

  private fun handle(cells: List<CellInfo>) {
    val now = Clock.nowElapsedNs()
    val observations = cells.mapNotNull(::observe)
    if (observations.isEmpty()) {
      sink(LogEvent(Clock.nowUtcMs(), now, "cell", mapOf("status" to "empty")))
    }
    for (obs in observations) {
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
            put("registered", obs.registered.toString())
            put("age_ms", ((now - obs.elapsedNs) / 1_000_000).toString())
          },
        )
      )
    }
    val serving = observations.firstOrNull { it.registered }
    if (serving != null && serving.key != lastServingKey) {
      sink(
        LogEvent(
          Clock.elapsedNsToUtcMs(serving.elapsedNs),
          serving.elapsedNs,
          "cell_change",
          mapOf(
            "rat" to serving.rat,
            "cell_id" to (serving.cellId ?: ""),
            "pci" to (serving.pci?.toString() ?: ""),
            "arfcn" to (serving.arfcn?.toString() ?: ""),
            "note" to "observed serving cell change; prev=${lastServingKey ?: "none"}",
          ),
        )
      )
      lastServingKey = serving.key
    }
    onServing(serving)
  }

  private fun registerDisplayInfo(exec: ExecutorService) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
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
    val callback =
      object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
          val rat = networkTypeName(info.networkType)
          val override = overrideName(info.overrideNetworkType)
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
      telephony?.registerTelephonyCallback(exec, callback)
      displayCallback = callback
    } catch (e: SecurityException) {
      onDisplayInfo("権限なし（電話の状態）")
    }
  }

  companion object {
    const val POLL_INTERVAL_MS = 2_000L
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
