package com.example.helloandroid.recording

import android.content.Context
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Source of background map tiles for the live track; null tiles mean "not available (yet)". */
interface MapTiles {
  /** Bumped whenever a tile finishes loading or fails, so the UI can redraw. */
  val version: StateFlow<Int>

  /** Cached tile, or null while it is loading / after it failed. Requests it if needed. */
  fun tile(key: TileKey): ImageBitmap?

  /** True if any of [keys] failed recently (offline, tunnel, server refusal). */
  fun anyFailed(keys: Collection<TileKey>): Boolean
}

/**
 * OpenStreetMap standard tiles, following the tile usage policy
 * (https://operations.osmfoundation.org/policies/tiles/):
 * - an identifying User-Agent,
 * - HTTP caching honoured through an installed [HttpResponseCache] plus an in-memory cache, so
 *   recompositions and screen rotations do not refetch tiles,
 * - only tiles for the visible view are requested, at most [MAX_PARALLEL] at a time; no bulk or
 *   offline download,
 * - the attribution is shown on the map by the UI.
 * Requests carry only the tile URL (zoom/x/y) and the User-Agent; no measured data is sent.
 */
class OsmTileLoader(context: Context) : MapTiles {
  private val userAgent: String
  private val memory = LruCache<TileKey, ImageBitmap>(MEMORY_TILES)
  private val inFlight = ConcurrentHashMap.newKeySet<TileKey>()
  private val failedUntil = ConcurrentHashMap<TileKey, Long>()
  private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL)
  private val _version = MutableStateFlow(0)
  override val version: StateFlow<Int> = _version.asStateFlow()

  init {
    val versionName =
      runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    userAgent = "KeioRideLogger/$versionName (Android; +https://github.com/n0bisuke/KeioRideLogger)"
    if (HttpResponseCache.getInstalled() == null) {
      try {
        HttpResponseCache.install(File(context.cacheDir, "http-tiles"), DISK_CACHE_BYTES)
      } catch (e: IOException) {
        // No disk cache: tiles still load, the in-memory cache avoids immediate refetches.
      }
    }
  }

  override fun tile(key: TileKey): ImageBitmap? {
    memory.get(key)?.let { return it }
    val retryAt = failedUntil[key]
    if (retryAt != null && SystemClock.elapsedRealtime() < retryAt) return null
    if (inFlight.add(key)) executor.execute { fetch(key) }
    return null
  }

  override fun anyFailed(keys: Collection<TileKey>): Boolean {
    val now = SystemClock.elapsedRealtime()
    return keys.any { key -> failedUntil[key]?.let { now < it } == true }
  }

  private fun fetch(key: TileKey) {
    try {
      val conn = URL("$TILE_URL/${key.z}/${key.x}/${key.y}.png").openConnection() as HttpURLConnection
      conn.setRequestProperty("User-Agent", userAgent)
      conn.connectTimeout = TIMEOUT_MS
      conn.readTimeout = TIMEOUT_MS
      conn.useCaches = true
      try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
        val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) } ?: throw IOException("decode")
        memory.put(key, bitmap.asImageBitmap())
        failedUntil.remove(key)
      } finally {
        conn.disconnect()
      }
    } catch (e: IOException) {
      failedUntil[key] = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
    } catch (e: SecurityException) {
      failedUntil[key] = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
    } finally {
      inFlight.remove(key)
      _version.update { it + 1 }
    }
  }

  fun shutdown() {
    executor.shutdownNow()
    HttpResponseCache.getInstalled()?.flush()
  }

  companion object {
    const val TILE_URL = "https://tile.openstreetmap.org"
    const val ATTRIBUTION = "© OpenStreetMap contributors"
    private const val MAX_PARALLEL = 2
    private const val MEMORY_TILES = 64
    private const val DISK_CACHE_BYTES = 50L * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    /** Failed tiles are not retried for this long, so offline periods do not hammer the server. */
    private const val RETRY_AFTER_MS = 30_000L
  }
}
