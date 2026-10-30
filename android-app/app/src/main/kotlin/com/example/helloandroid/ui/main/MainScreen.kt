package com.example.helloandroid.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.helloandroid.recording.CellSnapshot
import com.example.helloandroid.recording.MapTiles
import com.example.helloandroid.recording.MotionSensor
import com.example.helloandroid.recording.ProbeOutcome
import com.example.helloandroid.recording.RecorderUiState
import com.example.helloandroid.recording.SensorRate
import com.example.helloandroid.recording.SensorReading
import com.example.helloandroid.ui.theme.HelloAndroidTheme
import java.util.Locale

/** Values older than this are shown as stale rather than current. */
private const val STALE_MS = 5_000L

@Composable
fun MainScreen(
  state: RecorderUiState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onSave: () -> Unit,
  onRequestPermissions: () -> Unit,
  onProbeEnabledChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  mapTiles: MapTiles? = null,
  onMapEnabledChange: (Boolean) -> Unit = {},
  onBleEnabledChange: (Boolean) -> Unit = {},
  onCsvEnabledChange: (Boolean) -> Unit = {},
  onDeleteAllSessionFiles: () -> Unit = {},
) {
  Column(
    modifier =
      modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Keio Ride Logger", style = MaterialTheme.typography.headlineSmall)

    StatusCard(state, onStart, onStop, onSave, onProbeEnabledChange, onCsvEnabledChange)

    if (!state.locationGranted || !state.phoneStateGranted) {
      PermissionCard(state, onRequestPermissions)
    }

    SpeedCharacter(state.speed, state.recording, state.frozen)
    LiveSection(state, mapTiles, onMapEnabledChange)
    BleCard(state, onBleEnabledChange)
    SessionFilesCard(state, onDeleteAllSessionFiles)

    CellCard(state)
    NetworkCard(state)
    GpsCard(state)
    for (kind in MotionSensor.entries) {
      SensorCard(kind, state.sensorAvailable[kind], state.sensors[kind], state.recording, state.frozen)
    }

    Text(
      "記録は端末内にだけ保存され、外部へは送信しません。応答時間の計測がオンのときだけ、" +
        "Google の接続確認用 URL（connectivitycheck.gstatic.com）へ中身のない GET を 10 秒ごとに送ります。" +
        "位置・セル情報・端末情報は送りません。計測は画面を表示している間だけ行います。",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun StatusCard(
  state: RecorderUiState,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onSave: () -> Unit,
  onProbeEnabledChange: (Boolean) -> Unit,
  onCsvEnabledChange: (Boolean) -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        if (state.recording) "● 記録中  ${formatDuration(state.elapsedMs)}" else "停止中",
        style = MaterialTheme.typography.titleLarge,
        color = if (state.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      if (state.recording && !state.sessionHasCsv) {
        Text(
          "CSV 記録オフ・データは保存されません",
          color = MaterialTheme.colorScheme.error,
          fontWeight = FontWeight.Bold,
        )
      } else if (state.recording || state.sessionHasCsv) {
        Text("CSV に記録したイベント数: ${state.eventCount}")
      } else {
        Text("前回の計測: CSV 記録なし", style = MaterialTheme.typography.bodyMedium)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.recording) {
          Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          ) {
            Text("計測停止")
          }
        } else {
          Button(onClick = onStart) { Text("計測開始") }
          // Hidden (not just disabled) when there is nothing to save, e.g. with CSV off.
          if (state.savableFileName != null) {
            OutlinedButton(onClick = onSave, enabled = !state.closingCsv) { Text("CSV を保存") }
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = state.csvEnabled, onCheckedChange = onCsvEnabledChange, enabled = !state.recording)
        Text(
          if (state.csvEnabled) "CSV に記録する" else "CSV に記録しない（画面表示と BLE 共有だけ）",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
          checked = state.probeEnabled,
          onCheckedChange = onProbeEnabledChange,
          enabled = !state.recording,
        )
        Text("応答時間を計測（10 秒ごとに HTTPS で接続確認）", style = MaterialTheme.typography.bodyMedium)
      }
      if (!state.recording && state.savableFileName != null && !state.saved) {
        Text(
          "未保存の記録: ${state.savableFileName}（次の計測開始後も端末内には残ります）",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
  }
}

@Composable
private fun PermissionCard(state: RecorderUiState, onRequestPermissions: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("権限について", fontWeight = FontWeight.Bold)
      if (!state.locationGranted) {
        Text(
          "・位置情報: GPS の緯度・経度・速度・高度の記録に使います。" +
            "Android の仕様で、携帯基地局の情報を読むにもこの権限が必要です。"
        )
      }
      if (!state.phoneStateGranted) {
        Text("・電話の状態: LTE / 5G（NR）の表示種別の記録に使います。通話内容や電話番号は読みません。")
      }
      Text("許可しなくても、加速度・ジャイロ・気圧・磁気の計測は行えます。")
      OutlinedButton(onClick = onRequestPermissions) { Text("権限を許可する") }
      Text(
        "ダイアログが出ない場合は、端末の設定 > アプリ > Keio Ride Logger > 権限 から許可できます。",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun GpsCard(state: RecorderUiState) {
  ValueCard("GPS", state.gpsStatus) {
    val gps = state.gps
    if (gps == null) {
      Text(if (state.recording) "測位値なし" else "—")
    } else {
      AgeLine(gps.ageMs, state.frozen)
      Mono("緯度 ${fmt(gps.lat, 6)}  経度 ${fmt(gps.lon, 6)}")
      Mono(
        "速度 ${gps.speedMps?.let { "${fmt(it * 3.6, 1)} km/h" } ?: "不明"}  " +
          "高度 ${gps.altitudeM?.let { "${fmt(it, 1)} m" } ?: "不明"}  " +
          "精度 ${gps.accuracyM?.let { "±${fmt(it, 1)} m" } ?: "不明"}"
      )
    }
  }
}

@Composable
private fun CellCard(state: RecorderUiState) {
  val cell = state.cell
  val status =
    when {
      cell == null -> if (state.recording) "応答待ち" else "未開始"
      state.frozen -> "停止（停止時: ${cell.snapshot.status.label}）"
      else -> cell.snapshot.status.label + (cell.snapshot.detail?.let { " $it" } ?: "")
    }
  ValueCard("携帯網", status) {
    state.displayInfo?.let { Mono("表示種別 $it") }
    if (cell == null) {
      Text("—")
      return@ValueCard
    }
    val snapshot = cell.snapshot
    val serving = snapshot.serving
    if (serving == null) {
      Text(
        if (snapshot.observedCount > 0) {
          "在圏セルなし。周辺セルを ${snapshot.observedCount} 件観測（在圏セルとしては扱いません）"
        } else {
          "在圏セルなし"
        }
      )
    } else {
      cell.ageMs?.let { AgeLine(it, state.frozen) }
      Mono("在圏 ${serving.rat}  Cell ID ${serving.cellId ?: "不明"}  PCI ${serving.pci ?: "不明"}")
      Mono("ARFCN ${serving.arfcn ?: "不明"}  バンド ${serving.bands ?: "不明"}")
      Mono(signalLine(serving.rat, serving.rsrpDbm, serving.metrics))
      Text(
        "取得経路: ${snapshot.servingSource ?: "不明"} / 観測セル ${snapshot.observedCount} 件",
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (snapshot.connectionSignals.isNotEmpty()) {
      Text("接続中の信号（SignalStrength、セル識別なし）", style = MaterialTheme.typography.bodySmall)
      for (signal in snapshot.connectionSignals) {
        Mono(signalLine(signal.rat, signal.rsrpDbm, signal.metrics))
      }
    }
    SwitchSummary(snapshot)
  }
}

/** RAT-specific metric names: LTE RSRP/RSRQ/RSSNR, NR SS-RSRP/SS-RSRQ/SS-SINR. Missing = 不明. */
private fun signalLine(rat: String, rsrpDbm: Int?, metrics: Map<String, Int>): String {
  fun v(key: String, unit: String) = metrics[key]?.let { "$it $unit" } ?: "不明"
  val rsrp = rsrpDbm?.let { "$it dBm" } ?: "不明"
  return when (rat) {
    "LTE" -> "LTE RSRP $rsrp  RSRQ ${v("lte_rsrq_db", "dB")}  RSSNR ${v("lte_rssnr_db", "dB")}"
    "NR" -> "NR SS-RSRP $rsrp  SS-RSRQ ${v("nr_ss_rsrq_db", "dB")}  SS-SINR ${v("nr_ss_sinr_db", "dB")}"
    else -> "$rat RSRP $rsrp"
  }
}

@Composable
private fun SwitchSummary(snapshot: CellSnapshot) {
  when {
    !snapshot.servingEverSeen ->
      Text("在圏セルの切り替わり: 在圏セル情報を取得できず（判定できません）")
    snapshot.switchCount == 0 ->
      Text("在圏セルの切り替わり: 確認できた切り替わりなし（最初の在圏セルを基準にしています）")
    else -> {
      Text("在圏セルの切り替わり: ${snapshot.switchCount} 回（最初の在圏セルは数えません）")
      for (change in snapshot.switchHistory.takeLast(SWITCH_HISTORY_SHOWN).asReversed()) {
        Mono("${formatClock(change.timeUtcMs)}  ${change.from.label} → ${change.to.label}")
      }
    }
  }
  Text(
    "Android の更新間隔の制限により、短い切り替わりは見逃すことがあります。完全なハンドオーバー記録ではありません。",
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun NetworkCard(state: RecorderUiState) {
  val net = state.network
  val status =
    when {
      !state.recording && !state.frozen -> "未開始"
      state.frozen -> "停止"
      net == null -> if (state.networkReported) "既定ネットワークなし" else "確認中"
      else -> if (net.validated) "インターネット接続を確認済み" else "接続未確認"
    }
  ValueCard("通信", status) {
    if (net != null) {
      Mono("経路 ${transportLabel(net.transport)}  （ネットワーク #${net.index}）")
    }
    if (!state.probeEnabled) {
      Text("応答時間の計測: オフ")
      return@ValueCard
    }
    val probe = state.lastProbe
    if (probe == null) {
      Text(if (state.recording) "応答時間: 最初の計測待ち（10 秒ごと）" else "応答時間: —")
    } else {
      val result =
        when (probe.outcome) {
          ProbeOutcome.OK -> "${probe.durationMs} ms（HTTP ${probe.httpStatus}）"
          ProbeOutcome.HTTP_ERROR -> "HTTP ${probe.httpStatus}（${probe.durationMs} ms）"
          ProbeOutcome.TIMEOUT -> "タイムアウト（5 秒）"
          ProbeOutcome.ERROR -> "失敗（${probe.durationMs} ms）"
        }
      Mono("最新の応答時間 $result")
      Text(
        "成功 ${state.probeOkCount} / ${state.probeCount} 回" +
          (probe.network?.let { "  計測時の経路 ${transportLabel(it.transport)}" } ?: "  計測時の経路 なし"),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

private fun transportLabel(transport: String): String =
  transport.split("+").joinToString("+") {
    when (it) {
      "cellular" -> "携帯網"
      "wifi" -> "Wi-Fi"
      "vpn" -> "VPN"
      "ethernet" -> "有線"
      "bluetooth" -> "Bluetooth"
      else -> "その他"
    }
  }

private const val SWITCH_HISTORY_SHOWN = 5

private fun formatClock(epochMs: Long): String =
  java.time.Instant.ofEpochMilli(epochMs)
    .atZone(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable
private fun SensorCard(
  kind: MotionSensor,
  available: Boolean?,
  reading: SensorReading?,
  recording: Boolean,
  frozen: Boolean,
) {
  val status =
    when (available) {
      false -> "利用不可（未搭載）"
      true -> if (recording) "取得中" else "利用可"
      null -> "確認中"
    }
  ValueCard(kind.label, status) {
    when {
      available == false -> Unit
      reading == null -> Text(if (recording) "値なし" else "—")
      else -> {
        AgeLine(reading.ageMs, frozen)
        if (kind == MotionSensor.BAROMETER) {
          Mono("${fmt(reading.values[0], 2)} hPa")
        } else {
          Mono(reading.values.take(3).joinToString("  ") { fmt(it, 3) })
        }
        RateLine(kind, reading.rate)
      }
    }
  }
}

/** Distinguishes what the device delivered from what was recorded after thinning. */
@Composable
private fun RateLine(kind: MotionSensor, rate: SensorRate) {
  val recorded = rate.recordedHz?.let { "${fmt(it, 1)} Hz" } ?: "—"
  val received = rate.receivedHz?.let { "${fmt(it, 1)} Hz" } ?: "—"
  Text(
    "記録 $recorded / 端末からの配信 $received（目安 ${fmt(kind.targetHz, 0)} Hz）",
    style = MaterialTheme.typography.bodySmall,
  )
  val deliveredHz = rate.receivedHz
  if (deliveredHz != null && deliveredHz < kind.targetHz * 0.9) {
    Text(
      "端末の配信が目安を下回っています（記録は配信された分のみ）",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

@Composable
private fun ValueCard(title: String, status: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(status, style = MaterialTheme.typography.bodyMedium)
      }
      content()
    }
  }
}

/**
 * While recording, [ageMs] is live. After a stop ([frozen]) it is the age at the moment of the
 * stop, so it is labelled as such instead of looking like a fresh "0 秒前".
 */
@Composable
private fun AgeLine(ageMs: Long, frozen: Boolean) {
  val seconds = ageMs / 1000
  when {
    frozen ->
      Text(
        "計測停止時の最終値（停止時点で ${seconds} 秒前に取得・以後更新なし）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    ageMs > STALE_MS ->
      Text("最終取得 ${seconds} 秒前（更新なし）", color = MaterialTheme.colorScheme.error)
    else -> Text("最終取得 ${seconds} 秒前", style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun Mono(text: String) {
  Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
}

private fun fmt(value: Number, digits: Int): String =
  String.format(Locale.US, "%.${digits}f", value.toDouble())

private fun formatDuration(ms: Long): String {
  val total = ms / 1000
  return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
  HelloAndroidTheme {
    MainScreen(
      state = RecorderUiState(sensorAvailable = MotionSensor.entries.associateWith { it != MotionSensor.BAROMETER }),
      onStart = {},
      onStop = {},
      onSave = {},
      onRequestPermissions = {},
      onProbeEnabledChange = {},
    )
  }
}
