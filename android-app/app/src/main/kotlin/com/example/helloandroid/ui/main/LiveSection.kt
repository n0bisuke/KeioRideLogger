package com.example.helloandroid.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.helloandroid.recording.CellStatus
import com.example.helloandroid.recording.LiveSnapshot
import com.example.helloandroid.recording.ProbeOutcome
import com.example.helloandroid.recording.RecorderUiState
import com.example.helloandroid.recording.TrackPoint
import com.example.helloandroid.recording.splitAtGaps
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private const val WINDOW_NS = 60_000_000_000L
private const val GPS_GAP_NS = 5_000_000_000L
private const val SIGNAL_GAP_NS = 10_000_000_000L
private const val LOW_ACCURACY_M = 30.0
private const val GPS_STALE_MS = 5_000L
/** Probes run every 10 s; no result for 2.5 intervals means the display is out of date. */
private const val PROBE_STALE_NS = 25_000_000_000L

/**
 * Collapsible live view of the running session: the GPS track sketch and the last 60 s of speed,
 * serving-cell signal and HTTP probe time. Draws only from bounded UI histories; collection and the
 * CSV are untouched.
 */
@Composable
fun LiveSection(state: RecorderUiState, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  Card(modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("ライブ可視化（デバッグ表示）", fontWeight = FontWeight.Bold)
        Text(if (expanded) "▲ 閉じる" else "▼ 開く", color = MaterialTheme.colorScheme.primary)
      }
      if (!expanded) return@Column
      if (!state.recording && !state.frozen) {
        Text("計測を開始すると表示されます。", style = MaterialTheme.typography.bodyMedium)
        return@Column
      }
      if (state.frozen) {
        Text(
          "計測停止中: 停止した時点までの値です（以後は更新されません）。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TrackSketch(state)
      SpeedChart(state.live)
      SignalCharts(state)
      ProbeChart(state)
    }
  }
}

// ---------------------------------------------------------------------------------------------
// GPS track sketch

@Composable
private fun TrackSketch(state: RecorderUiState) {
  val track = state.live.track
  Text("GPS軌跡（概略）", fontWeight = FontWeight.Bold)
  val status =
    when {
      !state.locationGranted -> "位置情報の権限がないため測位していません"
      track.isEmpty() -> if (state.frozen) "測位なし（このセッションで GPS を受信できませんでした）" else "GPS受信待ち（測位なし）"
      else -> {
        val age = state.gps?.ageMs
        when {
          state.frozen -> "計測停止時点の軌跡（${track.size} 点）"
          age == null -> "測位 ${track.size} 点"
          age > GPS_STALE_MS -> "更新なし: 最終測位 ${age / 1000} 秒前（現在位置は不明。灰色の点は最後の位置）"
          else -> "最新: ${age / 1000} 秒前の測位（${track.size} 点）"
        }
      }
    }
  Text(status, style = MaterialTheme.typography.bodySmall)
  if (track.isEmpty()) return

  val primary = MaterialTheme.colorScheme.primary
  val warn = MaterialTheme.colorScheme.tertiary
  val outline = MaterialTheme.colorScheme.outline
  val current = MaterialTheme.colorScheme.error
  val stale = state.frozen || (state.gps?.ageMs ?: 0) > GPS_STALE_MS
  val measurer = rememberTextMeasurer()
  val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

  Canvas(
    Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = "GPS軌跡の概略図" }
  ) {
    val projected = project(track)
    val xs = projected.map { it.second.x }
    val ys = projected.map { it.second.y }
    val spanX = max(xs.max() - xs.min(), 40f)
    val spanY = max(ys.max() - ys.min(), 40f)
    val pad = 16f
    val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
    val cx = (xs.max() + xs.min()) / 2
    val cy = (ys.max() + ys.min()) / 2
    fun screen(o: Offset) = Offset(size.width / 2 + (o.x - cx) * scale, size.height / 2 - (o.y - cy) * scale)

    // Accuracy circles (only where they are visible but not larger than the view).
    for ((p, o) in projected) {
      val r = (p.accuracyM ?: continue).toFloat() * scale
      if (r in 1.5f..size.maxDimension) {
        drawCircle(primary.copy(alpha = 0.07f), r, screen(o))
      }
    }
    // Lines only within runs without GPS gaps.
    for (run in splitAtGaps(projected, GPS_GAP_NS) { it.first.elapsedNs }) {
      for (i in 1 until run.size) {
        drawLine(primary, screen(run[i - 1].second), screen(run[i].second), strokeWidth = 4f)
      }
    }
    // Points: filled = good accuracy, hollow = low accuracy (shape, not only colour).
    for ((p, o) in projected) {
      val low = p.accuracyM == null || p.accuracyM > LOW_ACCURACY_M
      if (low) drawCircle(warn, 6f, screen(o), style = Stroke(width = 3f))
      else drawCircle(primary, 4f, screen(o))
    }
    // Current (or last) position.
    val last = screen(projected.last().second)
    val color = if (stale) outline else current
    drawCircle(color, 14f, last, style = Stroke(width = 4f))
    drawCircle(color, 6f, last)
    // Scale bar.
    val nice = listOf(5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000)
    val meters = nice.firstOrNull { it * scale >= 60f } ?: nice.last()
    val y = size.height - 8f
    drawLine(labelStyle.color, Offset(8f, y), Offset(8f + meters * scale, y), strokeWidth = 3f)
    drawText(measurer, if (meters >= 1000) "${meters / 1000} km" else "$meters m", Offset(8f, y - 28f), labelStyle)
  }
  Text(
    "● 精度 ${LOW_ACCURACY_M.toInt()} m 以内　○ 低精度　◎ 最新位置（灰色は更新なし）　薄い円は水平精度。" +
      "5 秒を超えて測位が途切れた区間は線でつなぎません。座標をそのまま描いた図で、地図・道路・駅には合わせていません。" +
      if (state.live.trackTruncated) "（古い点は省略し、直近の点だけを表示）" else "",
    style = MaterialTheme.typography.bodySmall,
  )
}

/** Local equirectangular projection in metres around the track's mean position. */
private fun project(track: List<TrackPoint>): List<Pair<TrackPoint, Offset>> {
  val lat0 = track.sumOf { it.lat } / track.size
  val lon0 = track.sumOf { it.lon } / track.size
  val k = Math.PI / 180
  val r = 6_371_008.8
  val cosLat = cos(lat0 * k)
  return track.map { p ->
    p to Offset(((p.lon - lon0) * k * r * cosLat).toFloat(), ((p.lat - lat0) * k * r).toFloat())
  }
}

// ---------------------------------------------------------------------------------------------
// Time-series mini charts (last 60 s, x = seconds before the window end)

private enum class Mark { DOT, HOLLOW, TRIANGLE, CROSS }

private data class ChartPoint(val elapsedNs: Long, val value: Double, val mark: Mark = Mark.DOT)

private data class Series(
  val points: List<ChartPoint>,
  val color: Color,
  val dashed: Boolean = false,
  /** Join neighbours closer than this; null = points only (never join). */
  val joinGapNs: Long? = null,
)

@Composable
private fun MiniChart(
  title: String,
  unit: String,
  series: List<Series>,
  windowEndNs: Long,
  emptyText: String,
  switchMarksNs: List<Long> = emptyList(),
  legend: String? = null,
) {
  Text("$title [$unit]", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
  val values = series.flatMap { s -> s.points.map { it.value } }
  if (values.isEmpty()) {
    Text(emptyText, style = MaterialTheme.typography.bodySmall)
    return
  }
  val measurer = rememberTextMeasurer()
  val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
  val grid = MaterialTheme.colorScheme.outlineVariant
  val markColor = MaterialTheme.colorScheme.secondary
  Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "$title のグラフ" }) {
    val left = 44f
    val right = 8f
    val top = 8f
    val bottom = 22f
    var yMin = values.min()
    var yMax = values.max()
    if (yMax - yMin < 1e-6) { yMin -= 1; yMax += 1 }
    val padY = (yMax - yMin) * 0.1
    yMin -= padY; yMax += padY
    val w = size.width - left - right
    val h = size.height - top - bottom
    val startNs = windowEndNs - WINDOW_NS
    fun x(t: Long) = left + ((t - startNs).toDouble() / WINDOW_NS * w).toFloat()
    fun y(v: Double) = top + ((1 - (v - yMin) / (yMax - yMin)) * h).toFloat()

    // Axes: min/max labels and 15 s ticks.
    drawLine(grid, Offset(left, top), Offset(left + w, top))
    drawLine(grid, Offset(left, top + h), Offset(left + w, top + h))
    drawText(measurer, formatAxis(yMax), Offset(2f, top - 2f), labelStyle)
    drawText(measurer, formatAxis(yMin), Offset(2f, top + h - 14f), labelStyle)
    for (sec in listOf(60, 45, 30, 15, 0)) {
      val tx = x(windowEndNs - sec * 1_000_000_000L)
      drawLine(grid, Offset(tx, top), Offset(tx, top + h))
      drawText(measurer, if (sec == 0) "0s" else "-${sec}s", Offset(tx - 12f, top + h + 4f), labelStyle)
    }
    // Cell switch candidates: dashed vertical lines.
    for (t in switchMarksNs) {
      drawLine(markColor, Offset(x(t), top), Offset(x(t), top + h), strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
    for (s in series) {
      val effect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null
      s.joinGapNs?.let { gap ->
        for (run in splitAtGaps(s.points, gap) { it.elapsedNs }) {
          for (i in 1 until run.size) {
            drawLine(s.color, Offset(x(run[i - 1].elapsedNs), y(run[i - 1].value)),
              Offset(x(run[i].elapsedNs), y(run[i].value)), strokeWidth = 3f, pathEffect = effect)
          }
        }
      }
      for (p in s.points) drawMark(p.mark, Offset(x(p.elapsedNs), y(p.value)), s.color)
    }
  }
  legend?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

private fun DrawScope.drawMark(mark: Mark, c: Offset, color: Color) {
  when (mark) {
    Mark.DOT -> drawCircle(color, 4f, c)
    Mark.HOLLOW -> drawCircle(color, 5f, c, style = Stroke(width = 2.5f))
    Mark.TRIANGLE -> {
      val path = Path().apply { moveTo(c.x, c.y - 7f); lineTo(c.x + 7f, c.y + 6f); lineTo(c.x - 7f, c.y + 6f); close() }
      drawPath(path, color)
    }
    Mark.CROSS -> {
      drawLine(color, Offset(c.x - 6f, c.y - 6f), Offset(c.x + 6f, c.y + 6f), strokeWidth = 3f)
      drawLine(color, Offset(c.x + 6f, c.y - 6f), Offset(c.x - 6f, c.y + 6f), strokeWidth = 3f)
    }
  }
}

private fun formatAxis(v: Double): String =
  String.format(Locale.US, if (kotlin.math.abs(v) >= 100) "%.0f" else "%.1f", v)

@Composable
private fun SpeedChart(live: LiveSnapshot) {
  val from = live.windowEndNs - WINDOW_NS
  val points =
    live.track
      .filter { it.elapsedNs >= from && it.speedKmh != null }
      .map { ChartPoint(it.elapsedNs, it.speedKmh!!) }
  MiniChart(
    title = "GPS速度",
    unit = "km/h",
    series = listOf(Series(points, MaterialTheme.colorScheme.primary, joinGapNs = GPS_GAP_NS)),
    windowEndNs = live.windowEndNs,
    emptyText = "直近 60 秒に速度の値がありません（測位なし、または速度が含まれない測位）。",
  )
}

@Composable
private fun SignalCharts(state: RecorderUiState) {
  val live = state.live
  val snapshot = state.cell?.snapshot
  val cellStatus =
    when {
      snapshot == null -> "携帯網: 応答待ち"
      snapshot.status == CellStatus.SERVING -> "携帯網: 在圏セルあり（下のグラフは在圏セルの値のみ）"
      snapshot.status == CellStatus.NEIGHBORS_ONLY -> "携帯網: 周辺セルのみ（在圏セル情報なし。周辺セルの値はグラフに含めません）"
      else -> "携帯網: ${snapshot.status.label}"
    }
  Text(cellStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
  val switches = live.cellSwitchNs
  val rsrp = live.signals.filter { it.rsrpDbm != null }.map { ChartPoint(it.elapsedNs, it.rsrpDbm!!.toDouble()) }
  MiniChart(
    title = "在圏セル RSRP",
    unit = "dBm",
    series = listOf(Series(rsrp, MaterialTheme.colorScheme.primary, joinGapNs = SIGNAL_GAP_NS)),
    windowEndNs = live.windowEndNs,
    emptyText = "直近 60 秒に在圏セルの RSRP がありません。",
    switchMarksNs = switches,
    legend = if (switches.isNotEmpty()) "縦の破線: 在圏セルの変更候補（${switches.size} 件、最初の在圏セルは含まない）" else null,
  )
  // RSRQ and RSSNR/SS-SINR share the dB unit; told apart by line style and marker shape.
  fun metric(vararg keys: String) =
    live.signals.mapNotNull { s -> keys.firstNotNullOfOrNull { s.metrics[it] }?.let { ChartPoint(s.elapsedNs, it.toDouble()) } }
  val rsrq = metric("lte_rsrq_db", "nr_ss_rsrq_db")
  val snr = metric("lte_rssnr_db", "nr_ss_sinr_db").map { it.copy(mark = Mark.HOLLOW) }
  MiniChart(
    title = "在圏セル 品質",
    unit = "dB",
    series = listOf(
      Series(rsrq, MaterialTheme.colorScheme.tertiary, joinGapNs = SIGNAL_GAP_NS),
      Series(snr, MaterialTheme.colorScheme.secondary, dashed = true, joinGapNs = SIGNAL_GAP_NS),
    ),
    windowEndNs = live.windowEndNs,
    emptyText = "直近 60 秒に RSRQ・RSSNR（SINR）の値がありません（端末が返していない可能性があります）。",
    switchMarksNs = switches,
    legend = "実線・●: RSRQ（NR は SS-RSRQ）　破線・○: RSSNR（NR は SS-SINR）。未取得の値は描きません。",
  )
}

@Composable
private fun ProbeChart(state: RecorderUiState) {
  val live = state.live
  val net = state.network
  val netText =
    when {
      net == null -> if (state.networkReported) "既定ネットワーク: なし（未接続）" else "既定ネットワーク: 確認中"
      else -> "既定ネットワーク: ${transportName(net.transport)}・" + if (net.validated) "接続確認済み（validated）" else "接続未確認"
    }
  Text(netText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
  if (!state.probeEnabled) {
    Text("HTTP 応答時間: 計測オフ", style = MaterialTheme.typography.bodySmall)
    return
  }
  val last = state.lastProbe
  val ageNs = last?.let { live.windowEndNs - it.elapsedNs }
  val freshness =
    when {
      last == null -> "HTTP 応答時間: 最初の結果待ち（10 秒ごと）"
      !state.frozen && ageNs != null && ageNs > PROBE_STALE_NS -> "HTTP 応答時間: 更新なし（最終結果 ${ageNs / 1_000_000_000} 秒前）"
      else -> "HTTP 応答時間: 最終結果 ${(ageNs ?: 0) / 1_000_000_000} 秒前"
    }
  Text(freshness, style = MaterialTheme.typography.bodySmall)
  val error = MaterialTheme.colorScheme.error
  val points =
    live.probes.map {
      ChartPoint(
        it.elapsedNs,
        it.durationMs.toDouble(),
        when (it.outcome) {
          ProbeOutcome.OK -> Mark.DOT
          ProbeOutcome.HTTP_ERROR -> Mark.TRIANGLE
          ProbeOutcome.TIMEOUT, ProbeOutcome.ERROR -> Mark.CROSS
        },
      )
    }
  val ok = points.filter { it.mark == Mark.DOT }
  val bad = points.filter { it.mark != Mark.DOT }
  MiniChart(
    title = "HTTP 応答時間",
    unit = "ms",
    // Probes are 10 s apart: draw points only, never lines between them.
    series = listOf(Series(ok, MaterialTheme.colorScheme.primary), Series(bad, error)),
    windowEndNs = live.windowEndNs,
    emptyText = "直近 60 秒に応答時間の結果がありません。",
    switchMarksNs = live.cellSwitchNs,
    legend = "● 成功　▲ HTTP エラー　× タイムアウト（5 秒）・接続失敗。点どうしは線で結びません。",
  )
}

private fun transportName(transport: String): String =
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
