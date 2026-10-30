package com.example.helloandroid.recording

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import com.example.helloandroid.data.LogEvent
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** The app's default network as last reported by ConnectivityManager. */
data class NetworkState(
  /** Session-local index of the Network object (1, 2, ...); changes when the default network changes. */
  val index: Int?,
  val transport: String,
  val validated: Boolean,
)

enum class ProbeOutcome(val csv: String) {
  OK("ok"),
  HTTP_ERROR("http_error"),
  TIMEOUT("timeout"),
  ERROR("error"),
}

data class ProbeResult(
  /** elapsedRealtime when the probe started. */
  val elapsedNs: Long,
  val outcome: ProbeOutcome,
  val httpStatus: Int?,
  val durationMs: Long,
  val network: NetworkState?,
)

/**
 * Records default-network changes (transport, validation) and probes HTTP(S) responsiveness.
 *
 * Probe spec: an empty GET to [PROBE_URL] every [PROBE_INTERVAL_MS], a fresh connection each time
 * (`Connection: close`, no cache, no redirects), so the duration includes DNS, TCP and TLS. The
 * whole probe is bounded by [PROBE_TIMEOUT_MS]. Nothing measured (location, cell, device data) is
 * sent; the server only sees the request itself.
 */
class NetworkCollector(
  context: Context,
  private val sink: (LogEvent) -> Unit,
  private val onNetwork: (NetworkState?) -> Unit,
  private val onProbe: (ProbeResult) -> Unit,
) {
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private var callbackThread: HandlerThread? = null
  private var scheduler: ScheduledExecutorService? = null
  private var worker: ExecutorService? = null
  private val current = AtomicReference<NetworkState?>(null)
  private val networkIndex = HashMap<Network, Int>()
  private var lastLogged: NetworkState? = null

  private val callback =
    object : ConnectivityManager.NetworkCallback() {
      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        update("capabilities", network, caps)
      }

      override fun onLost(network: Network) {
        current.set(null)
        lastLogged = null
        log("lost", indexOf(network), null)
        onNetwork(null)
      }

      override fun onUnavailable() {
        log("unavailable", null, null)
        onNetwork(null)
      }
    }

  private fun indexOf(network: Network): Int =
    networkIndex.getOrPut(network) { networkIndex.size + 1 }

  private fun update(reason: String, network: Network, caps: NetworkCapabilities) {
    val state =
      NetworkState(
        index = indexOf(network),
        transport = transportName(caps),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
      )
    current.set(state)
    // Capabilities callbacks repeat for bandwidth changes; log only when what we record changes.
    if (state != lastLogged) {
      log(if (lastLogged?.index == state.index) reason else "default_changed", state.index, state)
      lastLogged = state
    }
    onNetwork(state)
  }

  private fun log(status: String, index: Int?, state: NetworkState?) {
    sink(
      LogEvent(
        Clock.nowUtcMs(),
        Clock.nowElapsedNs(),
        "network",
        buildMap {
          put("status", status)
          state?.let {
            put("transport", it.transport)
            put("validated", it.validated.toString())
          }
          index?.let { put("note", "net=$it") }
        },
      )
    )
  }

  fun start(probeEnabled: Boolean) {
    networkIndex.clear()
    lastLogged = null
    current.set(null)
    val cm = connectivity
    if (cm == null) {
      log("unavailable", null, null)
    } else {
      val thread = HandlerThread("network-callback").also { it.start() }
      callbackThread = thread
      try {
        cm.registerDefaultNetworkCallback(callback, Handler(thread.looper))
      } catch (e: SecurityException) {
        log("permission_denied", null, null)
      } catch (e: RuntimeException) {
        // TooManyRequestsException and similar: keep the rest of the session running.
        log("register_failed", null, null)
      }
    }
    if (probeEnabled) {
      worker = Executors.newSingleThreadExecutor()
      scheduler =
        Executors.newSingleThreadScheduledExecutor().also {
          it.scheduleWithFixedDelay(::probe, PROBE_INTERVAL_MS, PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }
  }

  /** Unregisters and waits for in-flight work, so nothing is recorded after this returns. */
  fun stop() {
    try {
      connectivity?.unregisterNetworkCallback(callback)
    } catch (e: IllegalArgumentException) {
      // Not registered (registration failed); nothing to undo.
    }
    callbackThread?.let {
      it.quitSafely()
      it.join(STOP_TIMEOUT_MS)
    }
    callbackThread = null
    scheduler?.let {
      it.shutdownNow()
      it.awaitTermination(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
    scheduler = null
    worker?.shutdownNow()
    worker = null
  }

  private fun probe() {
    val exec = worker ?: return
    val network = current.get()
    val startUtcMs = Clock.nowUtcMs()
    val startNs = Clock.nowElapsedNs()
    val connection = AtomicReference<HttpURLConnection?>(null)
    val future =
      exec.submit<Int> {
        val conn = URL(PROBE_URL).openConnection() as HttpURLConnection
        connection.set(conn)
        conn.connectTimeout = PROBE_TIMEOUT_MS.toInt()
        conn.readTimeout = PROBE_TIMEOUT_MS.toInt()
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Connection", "close")
        try {
          val code = conn.responseCode
          (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
          code
        } finally {
          conn.disconnect()
        }
      }
    var errorKind: String? = null
    val (outcome, status) =
      try {
        val code = future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        (if (code in 200..399) ProbeOutcome.OK else ProbeOutcome.HTTP_ERROR) to code
      } catch (e: TimeoutException) {
        future.cancel(true)
        connection.get()?.disconnect()
        ProbeOutcome.TIMEOUT to null
      } catch (e: ExecutionException) {
        errorKind = e.cause?.javaClass?.simpleName
        (if (e.cause is SocketTimeoutException) ProbeOutcome.TIMEOUT else ProbeOutcome.ERROR) to null
      } catch (e: InterruptedException) {
        // Session stopping; do not record a half-finished probe.
        Thread.currentThread().interrupt()
        return
      }
    val durationMs = (Clock.nowElapsedNs() - startNs) / 1_000_000
    sink(
      LogEvent(
        startUtcMs,
        startNs,
        "http_probe",
        buildMap {
          put("status", outcome.csv)
          status?.let { put("http_status", it.toString()) }
          put("duration_ms", durationMs.toString())
          network?.let {
            put("transport", it.transport)
            put("validated", it.validated.toString())
          }
          put(
            "note",
            "url=$PROBE_URL;timeout_ms=$PROBE_TIMEOUT_MS" +
              (network?.index?.let { ";net=$it" } ?: ";net=none") +
              (errorKind?.let { ";error=$it" } ?: ""),
          )
        },
      )
    )
    onProbe(ProbeResult(startNs, outcome, status, durationMs, network))
  }

  companion object {
    const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
    const val PROBE_INTERVAL_MS = 10_000L
    const val PROBE_TIMEOUT_MS = 5_000L
    private const val STOP_TIMEOUT_MS = 500L

    fun transportName(caps: NetworkCapabilities): String {
      val names =
        listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
          )
          .filter { (transport, _) -> caps.hasTransport(transport) }
          .map { it.second }
      return names.ifEmpty { listOf("other") }.joinToString("+")
    }
  }
}
