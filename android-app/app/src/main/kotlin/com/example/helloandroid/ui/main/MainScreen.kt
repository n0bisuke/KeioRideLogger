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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.helloandroid.recording.MotionSensor
import com.example.helloandroid.recording.RecorderUiState
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
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Keio Ride Logger", style = MaterialTheme.typography.headlineSmall)

    StatusCard(state, onStart, onStop, onSave)

    if (!state.locationGranted || !state.phoneStateGranted) {
      PermissionCard(state, onRequestPermissions)
    }

    GpsCard(state)
    CellCard(state)
    for (kind in MotionSensor.entries) {
      SensorCard(kind, state.sensorAvailable[kind], state.sensors[kind], state.recording)
    }

    Text(
      "記録は端末内にだけ保存され、外部へは送信しません。計測は画面を表示している間だけ行います。",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun StatusCard(state: RecorderUiState, onStart: () -> Unit, onStop: () -> Unit, onSave: () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        if (state.recording) "● 記録中  ${formatDuration(state.elapsedMs)}" else "停止中",
        style = MaterialTheme.typography.titleLarge,
        color = if (state.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      )
      Text("記録イベント数: ${state.eventCount}")
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
          OutlinedButton(onClick = onSave, enabled = state.savableFileName != null) {
            Text("CSV を保存")
          }
        }
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
      AgeLine(gps.ageMs)
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
  ValueCard("携帯網", state.cellStatus) {
    state.displayInfo?.let { Mono("表示種別 $it") }
    val cell = state.cell
    val serving = cell?.serving
    if (cell == null || serving == null) {
      Text(if (state.recording) "在圏セルの情報なし" else "—")
    } else {
      AgeLine(cell.ageMs)
      Mono("${serving.rat}  Cell ID ${serving.cellId ?: "不明"}  PCI ${serving.pci ?: "不明"}")
      Mono(
        "RSRP ${serving.rsrpDbm?.let { "$it dBm" } ?: "不明"}  " +
          "ARFCN ${serving.arfcn ?: "不明"}  バンド ${serving.bands ?: "不明"}"
      )
    }
  }
}

@Composable
private fun SensorCard(kind: MotionSensor, available: Boolean?, reading: SensorReading?, recording: Boolean) {
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
        AgeLine(reading.ageMs)
        if (kind == MotionSensor.BAROMETER) {
          Mono("${fmt(reading.values[0], 2)} hPa")
        } else {
          Mono(reading.values.take(3).joinToString("  ") { fmt(it, 3) })
        }
      }
    }
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

@Composable
private fun AgeLine(ageMs: Long) {
  val seconds = ageMs / 1000
  if (ageMs > STALE_MS) {
    Text("最終取得 ${seconds} 秒前（更新なし）", color = MaterialTheme.colorScheme.error)
  } else {
    Text("最終取得 ${seconds} 秒前", style = MaterialTheme.typography.bodySmall)
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
    )
  }
}
