package com.example.helloandroid.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.helloandroid.recording.RecorderUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val FILES_SHOWN = 10

/** App-private session CSVs with an all-delete action that only targets those files. */
@Composable
fun SessionFilesCard(state: RecorderUiState, onDeleteAll: () -> Unit, modifier: Modifier = Modifier) {
  val files = state.sessionFiles
  val totalBytes = files.sumOf { it.sizeBytes }
  var confirming by rememberSaveable { mutableStateOf(false) }
  val busy = state.recording || state.closingCsv
  Card(modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("アプリ内の計測 CSV", fontWeight = FontWeight.Bold)
      if (files.isEmpty()) {
        Text("アプリ内に保存された計測 CSV はありません。", style = MaterialTheme.typography.bodyMedium)
      } else {
        Text("${files.size} 件・合計 ${formatBytes(totalBytes)}", style = MaterialTheme.typography.bodyMedium)
        for (f in files.take(FILES_SHOWN)) {
          Text(
            "${formatTime(f.lastModifiedMs)}  ${formatBytes(f.sizeBytes)}  ${f.name}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        if (files.size > FILES_SHOWN) {
          Text("ほか ${files.size - FILES_SHOWN} 件", style = MaterialTheme.typography.bodySmall)
        }
      }
      OutlinedButton(
        onClick = { confirming = true },
        enabled = files.isNotEmpty() && !busy,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) {
        Text("アプリ内の計測 CSV をすべて削除")
      }
      Text(
        (if (busy) "計測中・書き込み中は削除できません。" else "") +
          "この操作はアプリ内の CSV だけを削除します。「CSV を保存」で保存先へ書き出したコピーは残ります。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text("計測 CSV をすべて削除しますか？") },
      text = {
        Text(
          "アプリ内の計測 CSV ${files.size} 件（合計 ${formatBytes(totalBytes)}）を削除します。この操作は取り消せません。" +
            "保存先へ書き出したコピーは削除されません。"
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirming = false
            onDeleteAll()
          },
          enabled = !busy,
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text("削除する")
        }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("キャンセル") } },
    )
  }
}

internal fun formatBytes(bytes: Long): String =
  when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
  }

private fun formatTime(epochMs: Long): String =
  Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
