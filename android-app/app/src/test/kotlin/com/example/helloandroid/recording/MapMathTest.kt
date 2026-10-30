package com.example.helloandroid.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {
  @Test
  fun mercator_matchesKnownTileIndices() {
    // Standard slippy-map tile math: lon 0 / lat 0 is the centre of the world.
    assertEquals(128.0, WebMercator.x0(0.0), 1e-9)
    assertEquals(128.0, WebMercator.y0(0.0), 1e-9)
    // At zoom 16, lon 20.0 / lat 10.0 fall in tile x=36408, y=30938.
    val n = 1 shl 16
    assertEquals(36408, (WebMercator.x0(20.0) * n / 256).toInt())
    assertEquals(30938, (WebMercator.y0(10.0) * n / 256).toInt())
  }

  @Test
  fun fit_keepsPointsInsideCanvasWithPadding() {
    val pts = listOf(10.0 to 20.0, 10.01 to 20.02, 10.005 to 20.01)
    val v = MapView.fit(pts, 1000.0, 600.0, paddingPx = 24.0)!!
    for ((lat, lon) in pts) {
      assertTrue(v.toCanvasX(lon) in 23.9..976.1)
      assertTrue(v.toCanvasY(lat) in 23.9..576.1)
    }
    assertTrue("tile stretch within [1, 2)", v.tileScale >= 1.0 && v.tileScale < 2.0)
  }

  @Test
  fun stationaryTrack_isWidenedToMinimumSpan_notMaxZoom() {
    val v = MapView.fit(listOf(35.0 to 139.0), 600.0, 600.0, paddingPx = 0.0, minSpanM = 120.0)!!
    val px = v.metersToPx(120.0, 35.0)
    assertEquals(600.0, px, 1.0)
    assertTrue(v.zoom <= 19)
  }

  @Test
  fun emptyOrTinyCanvas_givesNoView() {
    assertNull(MapView.fit(emptyList(), 100.0, 100.0))
    assertNull(MapView.fit(listOf(1.0 to 1.0), 40.0, 40.0, paddingPx = 24.0))
  }

  @Test
  fun visibleTiles_coverTheCanvas_andWrapLongitude() {
    val v = MapView.fit(listOf(10.0 to 20.0, 10.01 to 20.02), 800.0, 400.0)!!
    val tiles = v.visibleTiles()
    assertTrue(tiles.isNotEmpty() && tiles.size <= 40)
    val size = v.tileSizePx
    // Every canvas pixel corner is covered by some tile.
    for ((cx, cy) in listOf(0.0 to 0.0, 799.0 to 0.0, 0.0 to 399.0, 799.0 to 399.0, 400.0 to 200.0)) {
      assertTrue(tiles.any { (_, l, t) -> cx >= l && cx < l + size && cy >= t && cy < t + size })
    }
    // Across the antimeridian x wraps into 0 until 2^z.
    val w = MapView.fit(listOf(0.0 to 179.999, 0.0 to -179.999), 400.0, 400.0)!!
    val n = 1 shl w.zoom
    assertTrue(w.visibleTiles().all { it.first.x in 0 until n })
  }

  @Test
  fun metersToPx_isConsistentWithZoom() {
    val v = MapView.fit(listOf(35.0 to 139.0, 35.01 to 139.01), 1000.0, 1000.0)!!
    val metersPerCanvasPx = WebMercator.metersPerPx0(35.0) / v.scale
    assertEquals(10.0 / metersPerCanvasPx, v.metersToPx(10.0, 35.0), 1e-6)
  }
}
