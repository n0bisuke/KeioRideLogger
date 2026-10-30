package com.example.helloandroid.recording

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Web Mercator helpers shared by the live track and its OpenStreetMap background. */
object WebMercator {
  const val TILE_PX = 256.0
  const val MAX_LAT = 85.05112878
  /** Ground metres per world pixel at zoom 0 on the equator. */
  const val EQUATOR_M_PER_PX = 156_543.03392

  /** World pixel x at zoom 0 (0..256). */
  fun x0(lon: Double): Double = (lon + 180.0) / 360.0 * TILE_PX

  /** World pixel y at zoom 0 (0..256), clamped to the Mercator limit. */
  fun y0(lat: Double): Double {
    val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
    return (1 - asinh(tan(clamped)) / PI) / 2 * TILE_PX
  }

  fun metersPerPx0(lat: Double): Double = EQUATOR_M_PER_PX * cos(lat * PI / 180.0)
}

data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * A view that fits a set of points into a canvas. [scale] is canvas pixels per zoom-0 world pixel;
 * tiles are drawn from integer zoom [zoom] stretched by [tileScale] (1 <= tileScale < 2, except at
 * the zoom limits).
 */
data class MapView(
  val zoom: Int,
  val scale: Double,
  val centerX0: Double,
  val centerY0: Double,
  val widthPx: Double,
  val heightPx: Double,
) {
  val tileScale: Double
    get() = scale / (1 shl zoom)

  fun toCanvasX(lon: Double): Double = widthPx / 2 + (WebMercator.x0(lon) - centerX0) * scale

  fun toCanvasY(lat: Double): Double = heightPx / 2 + (WebMercator.y0(lat) - centerY0) * scale

  /** Canvas pixels for [meters] of ground distance at latitude [lat]. */
  fun metersToPx(meters: Double, lat: Double): Double = meters / WebMercator.metersPerPx0(lat) * scale

  /** Tiles that cover the canvas, with their top-left canvas position and drawn size. */
  fun visibleTiles(): List<Triple<TileKey, Double, Double>> {
    val n = 1 shl zoom
    val tileCanvasPx = WebMercator.TILE_PX * tileScale
    val leftWorld = (centerX0 - widthPx / 2 / scale) * (1 shl zoom) / WebMercator.TILE_PX
    val topWorld = (centerY0 - heightPx / 2 / scale) * (1 shl zoom) / WebMercator.TILE_PX
    val cols = (widthPx / tileCanvasPx).toInt() + 2
    val rows = (heightPx / tileCanvasPx).toInt() + 2
    val firstX = floor(leftWorld).toInt()
    val firstY = floor(topWorld).toInt()
    val tiles = mutableListOf<Triple<TileKey, Double, Double>>()
    for (ty in firstY until firstY + rows) {
      if (ty < 0 || ty >= n) continue
      for (tx in firstX until firstX + cols) {
        val left = (tx - leftWorld) * tileCanvasPx
        val top = (ty - topWorld) * tileCanvasPx
        if (left >= widthPx || top >= heightPx || left + tileCanvasPx <= 0 || top + tileCanvasPx <= 0) continue
        tiles += Triple(TileKey(zoom, Math.floorMod(tx, n), ty), left, top)
      }
    }
    return tiles
  }

  val tileSizePx: Double
    get() = WebMercator.TILE_PX * tileScale

  companion object {
    /**
     * Fits [points] (lat to lon) with [paddingPx] margins. Very small extents are widened to
     * [minSpanM] so a stationary track is not zoomed past what the GPS error can support.
     */
    fun fit(
      points: List<Pair<Double, Double>>,
      widthPx: Double,
      heightPx: Double,
      paddingPx: Double = 24.0,
      minSpanM: Double = 120.0,
      maxZoom: Int = 19,
    ): MapView? {
      if (points.isEmpty() || widthPx <= 2 * paddingPx || heightPx <= 2 * paddingPx) return null
      val xs = points.map { WebMercator.x0(it.second) }
      val ys = points.map { WebMercator.y0(it.first) }
      val midLat = points.sumOf { it.first } / points.size
      val minSpan0 = minSpanM / WebMercator.metersPerPx0(midLat)
      val spanX = max(xs.max() - xs.min(), minSpan0)
      val spanY = max(ys.max() - ys.min(), minSpan0)
      var scale = min((widthPx - 2 * paddingPx) / spanX, (heightPx - 2 * paddingPx) / spanY)
      // Never stretch tiles more than 2x beyond the highest zoom the server provides.
      scale = min(scale, (1 shl maxZoom) * 2.0)
      val zoom = floor(ln(scale) / ln(2.0)).toInt().coerceIn(0, maxZoom)
      return MapView(zoom, scale, (xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, widthPx, heightPx)
    }
  }
}
