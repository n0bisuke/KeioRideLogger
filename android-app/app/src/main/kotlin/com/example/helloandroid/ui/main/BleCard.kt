package com.example.helloandroid.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.helloandroid.recording.BleState
import com.example.helloandroid.recording.RecorderUiState

/** Seconds without a completed frame after which the share is shown as stale while sending. */
private const val BLE_STALE_NS = 5_000_000_000L

/** Opt-in BLE live share to a Web Bluetooth page; states what is sent and to whom. */
@Composable
fun BleCard(state: RecorderUiState, onBleEnabledChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  val ble = state.ble
  Card(modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = state.bleEnabled, onCheckedChange = onBleEnabledChange)
        Text("BLE でブラウザへ共有（デモ）", fontWeight = FontWeight.Bold)
      }
      val status =
        when {
          !state.bleEnabled -> "オフ"
          !state.recording -> "計測を開始すると共有を始めます"
          ble.state == BleState.SENDING &&
            ble.lastSentNs != null &&
            state.live.windowEndNs - ble.lastSentNs > BLE_STALE_NS -> "送信が止まっています（更新なし）"
          else -> ble.state.label + (ble.detail?.let { "：$it" } ?: "")
        }
      Text("状態: $status", style = MaterialTheme.typography.bodyMedium)
      if (state.bleEnabled && state.recording && ble.state == BleState.SENDING) {
        Text(
          "送信 ${ble.framesSent} 回（1 秒ごと）" + (ble.mtu?.let { "・MTU $it" } ?: ""),
          style = MaterialTheme.typography.bodySmall,
        )
      }
      if (ble.state == BleState.PERMISSION_DENIED) {
        Text(
          "「付近のデバイス」の権限が必要です。端末の設定 > アプリ > Keio Ride Logger > 権限 から許可できます。",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Text(
        "オンにすると、計測中だけ、接続したブラウザ 1 台へ GPS の位置・速度・精度、在圏セルの方式・Cell ID・PCI・電波の値、" +
          "セル変更候補、HTTP 応答時間を BLE で 1 秒ごとに送ります。インターネットは使いません。" +
          "近くの端末がサービスを見つけて接続できるため、使わないときはオフにしてください。共有がうまくいかなくても計測と CSV 記録は続きます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
