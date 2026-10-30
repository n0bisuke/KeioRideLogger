package com.example.helloandroid.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.helloandroid.R
import com.example.helloandroid.recording.SpeedBand
import com.example.helloandroid.recording.SpeedDisplay
import java.util.Locale

private const val SHEET_COLUMNS = 4
private const val SHEET_ROWS = 2

/** Source rectangle of sprite [index] (row-major) using integer cell edges, so frames never overlap. */
internal fun spriteFrame(sheetWidth: Int, sheetHeight: Int, index: Int): Pair<IntOffset, IntSize> {
  val col = index % SHEET_COLUMNS
  val row = index / SHEET_COLUMNS
  val x0 = col * sheetWidth / SHEET_COLUMNS
  val x1 = (col + 1) * sheetWidth / SHEET_COLUMNS
  val y0 = row * sheetHeight / SHEET_ROWS
  val y1 = (row + 1) * sheetHeight / SHEET_ROWS
  return IntOffset(x0, y0) to IntSize(x1 - x0, y1 - y0)
}

/**
 * Glider character whose pose follows the smoothed GPS speed band. Shows a dimmed placeholder and
 * the reason when the speed is unknown, so the last band is never shown as if it were current.
 */
@Composable
fun SpeedCharacter(speed: SpeedDisplay, recording: Boolean, frozen: Boolean, modifier: Modifier = Modifier) {
  val resources = LocalResources.current
  // A failed decode must not crash the screen; fall back to text only.
  val sheet: ImageBitmap? = remember(resources) { runCatching { ImageBitmap.imageResource(resources, R.drawable.glider_speed_sheet) }.getOrNull() }
  val band = if (recording) speed.band else null
  val caption =
    when {
      !recording && !frozen -> "計測を開始すると、GPS速度に合わせて姿が変わります"
      frozen -> "計測停止中"
      band == null -> speed.unknownReason?.label ?: "速度不明"
      else -> band.label
    }
  Card(modifier = modifier.fillMaxWidth()) {
    Row(
      Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        if (sheet == null) {
          Text(caption, style = MaterialTheme.typography.bodySmall)
        } else {
          Canvas(Modifier.size(88.dp).semantics { contentDescription = "速度キャラクター: $caption" }) {
            val (src, srcSize) = spriteFrame(sheet.width, sheet.height, (band ?: SpeedBand.STOPPED).frameIndex)
            // Keep the cell's aspect ratio and draw with nearest-neighbour sampling for crisp pixels.
            val scale = minOf(size.width / srcSize.width, size.height / srcSize.height)
            val dst = IntSize((srcSize.width * scale).toInt(), (srcSize.height * scale).toInt())
            val offset = IntOffset(((size.width - dst.width) / 2).toInt(), ((size.height - dst.height) / 2).toInt())
            drawImage(
              sheet,
              srcOffset = src,
              srcSize = srcSize,
              dstOffset = offset,
              dstSize = dst,
              alpha = if (band == null) 0.25f else 1f,
              filterQuality = FilterQuality.None,
            )
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(caption, fontWeight = FontWeight.Bold)
        val kmh = speed.smoothedKmh
        if (band != null && kmh != null) {
          Text(String.format(Locale.US, "GPS速度 %.1f km/h（直近5秒の中央値）", kmh), style = MaterialTheme.typography.bodyMedium)
        } else if (recording) {
          Text("GPS速度 不明（0 km/h ではありません）", style = MaterialTheme.typography.bodyMedium)
        }
        if (band == SpeedBand.LINER_FAST) {
          Text(
            "演出上の速度帯です。GPS速度は車両の速度計ではありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
