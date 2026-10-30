package com.example.helloandroid.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.helloandroid.recording.CellStatus
import com.example.helloandroid.recording.LiveSnapshot
import com.example.helloandroid.recording.MapTiles
import com.example.helloandroid.recording.MapView
import com.example.helloandroid.recording.OsmTileLoader
import com.example.helloandroid.recording.ProbeOutcome
import com.example.helloandroid.recording.RecorderUiState
import com.example.helloandroid.recording.TrackPoint
import com.example.helloandroid.recording.splitAtGaps
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

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
fun LiveSection(
  state: RecorderUiState,
  mapTiles: MapTiles?,
  onMapEnabledChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
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
      TrackMap(state, mapTiles, onMapEnabledChange)
      SpeedChart(state.live)
      SignalCharts(state)
      ProbeChart(state)
    }
  }
}

// ---------------------------------------------------------------------------------------------
// GPS track on an OpenStreetMap background (or a plain background when the map is off/failing)

@Composable
private fun TrackMap(state: RecorderUiState, mapTiles: MapTiles?, onMapEnabledChange: (Boolean) -> Unit) {
  val track = state.live.track
  Text("GPS測位軌跡（概略）", fontWeight = FontWeight.Bold)
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Switch(checked = state.mapEnabled, onCheckedChange = onMapEnabledChange, enabled = mapTiles != null)
    Text(
      if (mapTiles == null) "背景地図: 利用できません" else "背景地図（OpenStreetMap）",
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  if (state.mapEnabled && mapTiles != null) {
    Text(
      "地図の表示のため、表示範囲のタイル座標と端末の IP アドレスが OpenStreetMap に伝わります。" +
        "測位点・CSV・セル情報は送りません。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  val status =
    when {
      !state.locationGranted -> "位置情報の権限がないため測位していません"
      track.isEmpty() -> if (state.frozen) "測位なし（このセッションで GPS を受信できませんでした）" else "GPS受信待ち（測位なし）"
      else -> {
        val age = state.gps?.ageMs
        when {
          state.frozen -> "計測停止時点の軌跡（${track.size} 点）"
          age == null -> "測位 ${track.size} 点"
          age > GPS_STALE_MS -> "更新なし: 最終測位 ${age / 1000} 秒前（現在位置は不明。灰色の印は最後の位置）"
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
  val plainBackground = MaterialTheme.colorScheme.surfaceVariant
  // Fixed colours (not theme): must contrast with OSM tiles, whose roads are orange/yellow/white.
  val trackColor = Color(0xFFD0156C)
  val trackEdge = Color(0xFF3A0020)
  val stale = state.frozen || (state.gps?.ageMs ?: 0) > GPS_STALE_MS
  val measurer = rememberTextMeasurer()
  val labelStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF222222))

  var sizePx by remember { mutableStateOf(IntSize.Zero) }
  val view =
    remember(track, sizePx) {
      MapView.fit(track.map { it.lat to it.lon }, sizePx.width.toDouble(), sizePx.height.toDouble())
    }
  val showMap = state.mapEnabled && mapTiles != null && view != null
  // Read the version so a finished tile download triggers a redraw.
  val tileVersion = mapTiles?.version?.collectAsState()?.value
  val tiles =
    remember(view, showMap, tileVersion) {
      if (!showMap) emptyList()
      else view!!.visibleTiles().map { (key, left, top) -> Triple(key, Offset(left.toFloat(), top.toFloat()), mapTiles!!.tile(key)) }
    }
  val tilesFailed = showMap && tiles.isNotEmpty() && mapTiles!!.anyFailed(tiles.map { it.first })

  Box(Modifier.fillMaxWidth().height(240.dp).onSizeChanged { sizePx = it }) {
    Canvas(
      // clipToBounds: edge tiles extend past the canvas and must not cover the text around it.
      Modifier.matchParentSize().clipToBounds().semantics { contentDescription = "GPS測位軌跡の概略図" }
    ) {
      val v = view ?: return@Canvas
      drawRect(plainBackground)
      val tilePx = v.tileSizePx.toFloat()
      for ((_, offset, image) in tiles) {
        image ?: continue
        drawImage(
          image,
          dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
          // +1 px hides hairline seams between stretched tiles.
          dstSize = IntSize(tilePx.roundToInt() + 1, tilePx.roundToInt() + 1),
        )
      }
      fun at(p: TrackPoint) = Offset(v.toCanvasX(p.lon).toFloat(), v.toCanvasY(p.lat).toFloat())

      // Accuracy circles (only where they are visible but not larger than the view).
      for (p in track) {
        val r = v.metersToPx(p.accuracyM ?: continue, p.lat).toFloat()
        if (r in 1.5f..size.maxDimension) {
          drawCircle(primary.copy(alpha = 0.12f), r, at(p))
          drawCircle(primary.copy(alpha = 0.35f), r, at(p), style = Stroke(width = 1.5f))
        }
      }
      // Lines only within runs without GPS gaps.
      for (run in splitAtGaps(track, GPS_GAP_NS) { it.elapsedNs }) {
        // Dark outer edge + white casing + colour: readable on light and dark map areas alike.
        for (i in 1 until run.size) drawLine(trackEdge, at(run[i - 1]), at(run[i]), strokeWidth = 13f, cap = StrokeCap.Round)
        for (i in 1 until run.size) drawLine(Color.White, at(run[i - 1]), at(run[i]), strokeWidth = 10f, cap = StrokeCap.Round)
        for (i in 1 until run.size) drawLine(trackColor, at(run[i - 1]), at(run[i]), strokeWidth = 6f, cap = StrokeCap.Round)
      }
      // Points: filled = good accuracy, hollow = low accuracy (shape, not only colour).
      for (p in track) {
        val low = p.accuracyM == null || p.accuracyM > LOW_ACCURACY_M
        if (low) {
          drawCircle(Color.White, 6f, at(p))
          drawCircle(warn, 6f, at(p), style = Stroke(width = 3f))
        } else {
          drawCircle(Color.White, 6f, at(p))
          drawCircle(trackColor, 4f, at(p))
        }
      }
      // Current (or last) position.
      val last = at(track.last())
      val color = if (stale) outline else current
      drawCircle(Color.White, 15f, last)
      drawCircle(color, 14f, last, style = Stroke(width = 4f))
      drawCircle(color, 6f, last)
      // Scale bar (top-left so it never collides with the attribution).
      val nice = listOf(5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000)
      val pxPerMeter = v.metersToPx(1.0, track.last().lat).toFloat()
      val meters = nice.firstOrNull { it * pxPerMeter >= 60f } ?: nice.last()
      val label = measurer.measure(if (meters >= 1000) "${meters / 1000} km" else "$meters m", labelStyle)
      drawRect(Color.White.copy(alpha = 0.8f), Offset(4f, 4f), Size(meters * pxPerMeter + 12f, label.size.height + 14f))
      drawText(label, topLeft = Offset(10f, 6f))
      val y = label.size.height + 12f
      drawLine(Color(0xFF222222), Offset(10f, y), Offset(10f + meters * pxPerMeter, y), strokeWidth = 3f)
    }
    if (showMap) {
      // Required attribution: always visible on the map while it is shown.
      Text(
        OsmTileLoader.ATTRIBUTION,
        style = TextStyle(fontSize = 10.sp, color = Color(0xFF222222)),
        modifier =
          Modifier.align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(Color.White.copy(alpha = 0.85f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
      )
    }
    if (tilesFailed) {
      Text(
        "地図タイルを取得できません（圏外・オフラインなど）。軌跡は表示を続けます。",
        style = TextStyle(fontSize = 11.sp, color = Color.White),
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(4.dp)
            .background(Color(0xCC1D232A))
            .padding(horizontal = 6.dp, vertical = 2.dp),
      )
    }
  }
  Text(
    "● 精度 ${LOW_ACCURACY_M.toInt()} m 以内　○ 低精度　◎ 最新位置（灰色は更新なし）　薄い円は水平精度。" +
      "5 秒を超えて測位が途切れた区間は線でつなぎません。GPS の座標をそのまま描いたもので、道路や線路には合わせていません。" +
      if (state.live.trackTruncated) "（古い点は省略し、直近の点だけを表示）" else "",
    style = MaterialTheme.typography.bodySmall,
  )
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
  Canvas(Modifier.fillMaxWidth().height(124.dp).semantics { contentDescription = "$title のグラフ" }) {
    // Margins come from the measured label size, so tick labels fit at any screen density.
    val tickSample = measurer.measure("-60s", labelStyle)
    val labelH = tickSample.size.height.toFloat()
    val left = max(measurer.measure("-000.0", labelStyle).size.width.toFloat() + 6f, 40f)
    val right = tickSample.size.width / 2f + 4f
    val top = labelH / 2 + 2f
    val bottom = labelH + 8f
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
    drawText(measurer, formatAxis(yMax), Offset(2f, top - labelH / 2), labelStyle)
    drawText(measurer, formatAxis(yMin), Offset(2f, top + h - labelH / 2), labelStyle)
    for (sec in listOf(60, 45, 30, 15, 0)) {
      val tx = x(windowEndNs - sec * 1_000_000_000L)
      drawLine(grid, Offset(tx, top), Offset(tx, top + h))
      val tick = measurer.measure(if (sec == 0) "0s" else "-${sec}s", labelStyle)
      val tickX = (tx - tick.size.width / 2f).coerceIn(0f, size.width - tick.size.width)
      drawText(tick, topLeft = Offset(tickX, top + h + 4f))
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
