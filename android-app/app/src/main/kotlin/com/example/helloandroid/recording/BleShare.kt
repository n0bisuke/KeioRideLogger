package com.example.helloandroid.recording

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/** What the BLE share is doing, shown on screen. */
enum class BleState(val label: String) {
  OFF("オフ"),
  NO_BLE("この端末は BLE に非対応"),
  UNSUPPORTED("この端末は BLE 広告（ペリフェラル）に非対応"),
  BLUETOOTH_OFF("Bluetooth がオフ"),
  PERMISSION_DENIED("Bluetooth の権限なし"),
  STARTING("開始中"),
  ADVERTISING("接続待ち（広告中）"),
  CONNECTED("接続中（通知の開始待ち）"),
  SENDING("送信中"),
  ERROR("エラー"),
}

data class BleStatus(
  val state: BleState = BleState.OFF,
  val detail: String? = null,
  val mtu: Int? = null,
  val framesSent: Long = 0,
  /** elapsedRealtime of the last completely sent frame. */
  val lastSentNs: Long? = null,
)

/**
 * BLE peripheral + GATT server that notifies the latest live values ([BleProtocol]) to one Web
 * Bluetooth client. Runs only while recording with sharing switched on; never in the background.
 *
 * - The advertisement carries only the service UUID (no device name, which may contain a personal
 *   name). Advertising stops while a client is connected, and a second client is disconnected.
 * - Only the latest frame is kept; while one is being sent a newer frame replaces the rest of it,
 *   so nothing queues up when the link is slow or gone.
 * - Failures only change [BleStatus]; recording and the CSV are never blocked by BLE.
 */
class BleShareController(
  private val context: Context,
  private val onStatus: (BleStatus) -> Unit,
) {
  private val manager = context.getSystemService(BluetoothManager::class.java)
  private val serviceUuid = UUID.fromString(BleProtocol.SERVICE_UUID)
  private val charUuid = UUID.fromString(BleProtocol.CHARACTERISTIC_UUID)
  private val cccdUuid = UUID.fromString(BleProtocol.CCCD_UUID)

  // Everything below is touched only on [handler]'s thread.
  private var thread: HandlerThread? = null
  private var handler: Handler? = null
  private var server: BluetoothGattServer? = null
  private var advertiser: BluetoothLeAdvertiser? = null
  private var characteristic: BluetoothGattCharacteristic? = null
  private var client: BluetoothDevice? = null
  private var subscribed = false
  private var mtu = BleProtocol.DEFAULT_MTU
  private var seq = 0L
  private val pending = ArrayDeque<ByteArray>()
  private var inFlightSinceNs: Long? = null
  private var status = BleStatus()

  private fun update(transform: (BleStatus) -> BleStatus) {
    status = transform(status)
    onStatus(status)
  }

  private fun hasPermissions(): Boolean =
    requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

  /** Why sharing cannot start right now, or null if it can. */
  fun blocker(): BleState? {
    val adapter = manager?.adapter
    return when {
      adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> BleState.NO_BLE
      !hasPermissions() -> BleState.PERMISSION_DENIED
      !adapter.isEnabled -> BleState.BLUETOOTH_OFF
      !adapter.isMultipleAdvertisementSupported || adapter.bluetoothLeAdvertiser == null -> BleState.UNSUPPORTED
      else -> null
    }
  }

  @SuppressLint("MissingPermission") // blocker() checked the runtime permissions.
  fun start() {
    if (thread != null) return
    blocker()?.let { reason ->
      update { BleStatus(reason) }
      return
    }
    val t = HandlerThread("ble-share").also { it.start() }
    thread = t
    handler = Handler(t.looper)
    update { BleStatus(BleState.STARTING) }
    handler?.post {
      try {
        val gattServer = manager!!.openGattServer(context, callback)
        if (gattServer == null) {
          update { BleStatus(BleState.ERROR, "GATT サーバーを開けません") }
          return@post
        }
        server = gattServer
        val chr =
          BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
          )
        chr.addDescriptor(
          BluetoothGattDescriptor(
            cccdUuid,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
          )
        )
        characteristic = chr
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(chr)
        // Advertising starts in onServiceAdded, once the service is really available.
        if (!gattServer.addService(service)) update { BleStatus(BleState.ERROR, "サービスを登録できません") }
      } catch (e: SecurityException) {
        update { BleStatus(BleState.PERMISSION_DENIED) }
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun startAdvertising() {
    val adv = manager?.adapter?.bluetoothLeAdvertiser
    if (adv == null) {
      update { BleStatus(BleState.BLUETOOTH_OFF) }
      return
    }
    advertiser = adv
    val settings =
      AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .setTimeout(0)
        .build()
    val data =
      AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(serviceUuid)).build()
    try {
      adv.startAdvertising(settings, data, advertiseCallback)
    } catch (e: SecurityException) {
      update { BleStatus(BleState.PERMISSION_DENIED) }
    }
  }

  @SuppressLint("MissingPermission")
  private fun stopAdvertising() {
    try {
      advertiser?.stopAdvertising(advertiseCallback)
    } catch (e: SecurityException) {
      // Permission revoked while running; the advertiser stops with the app anyway.
    } catch (e: IllegalStateException) {
      // Bluetooth turned off.
    }
  }

  private val advertiseCallback =
    object : AdvertiseCallback() {
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
        handler?.post { if (client == null) update { it.copy(state = BleState.ADVERTISING, detail = null) } }
      }

      override fun onStartFailure(errorCode: Int) {
        handler?.post {
          update {
            when (errorCode) {
              ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> it.copy(state = BleState.UNSUPPORTED, detail = null)
              ADVERTISE_FAILED_ALREADY_STARTED -> it.copy(state = BleState.ADVERTISING)
              else -> it.copy(state = BleState.ERROR, detail = "広告を開始できません（コード $errorCode）")
            }
          }
        }
      }
    }

  private val callback =
    object : BluetoothGattServerCallback() {
      override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        handler?.post {
          if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
          else update { BleStatus(BleState.ERROR, "サービスの登録に失敗（$status）") }
        }
      }

      @SuppressLint("MissingPermission")
      override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        handler?.post {
          if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (client != null && client != device) {
              // One client at a time: refuse others while the chosen browser is connected.
              try {
                server?.cancelConnection(device)
              } catch (e: SecurityException) {
                // Nothing else to do; the client stays unsubscribed.
              }
              return@post
            }
            client = device
            subscribed = false
            mtu = BleProtocol.DEFAULT_MTU
            pending.clear()
            inFlightSinceNs = null
            stopAdvertising()
            update { it.copy(state = BleState.CONNECTED, detail = null, mtu = mtu) }
          } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device == client) {
            client = null
            subscribed = false
            pending.clear()
            inFlightSinceNs = null
            update { it.copy(state = BleState.ADVERTISING, detail = "切断されました。ブラウザから再接続できます", mtu = null) }
            startAdvertising()
          }
        }
      }

      override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        handler?.post {
          if (device == client) {
            this@BleShareController.mtu = mtu
            update { it.copy(mtu = mtu) }
          }
        }
      }

      @SuppressLint("MissingPermission")
      override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
      ) {
        handler?.post {
          val ok = device == client && descriptor.uuid == cccdUuid
          if (ok) {
            subscribed = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) == 0x01
            update { it.copy(state = if (subscribed) BleState.SENDING else BleState.CONNECTED) }
          }
          if (responseNeeded) {
            try {
              server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            } catch (e: SecurityException) {
              update { BleStatus(BleState.PERMISSION_DENIED) }
            }
          }
        }
      }

      @SuppressLint("MissingPermission")
      override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
        handler?.post {
          val value =
            if (device == client && subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
          try {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
          } catch (e: SecurityException) {
            update { BleStatus(BleState.PERMISSION_DENIED) }
          }
        }
      }

      @SuppressLint("MissingPermission")
      override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
      ) {
        // Values are delivered by notification only; a read returns an empty value.
        handler?.post {
          try {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
          } catch (e: SecurityException) {
            update { BleStatus(BleState.PERMISSION_DENIED) }
          }
        }
      }

      override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        handler?.post {
          inFlightSinceNs = null
          if (status != BluetoothGatt.GATT_SUCCESS) {
            pending.clear()
            return@post
          }
          if (pending.isEmpty()) {
            update { it.copy(framesSent = it.framesSent + 1, lastSentNs = SystemClock.elapsedRealtimeNanos()) }
          } else {
            sendNext()
          }
        }
      }
    }

  /** Queues the latest values; replaces whatever part of an older frame is still unsent. */
  fun publish(state: RecorderUiState, nowUtcMs: Long) {
    val h = handler ?: return
    h.post {
      if (client == null || !subscribed) return@post
      if (manager?.adapter?.isEnabled != true) {
        update { BleStatus(BleState.BLUETOOTH_OFF) }
        return@post
      }
      val since = inFlightSinceNs
      if (since != null && SystemClock.elapsedRealtimeNanos() - since > IN_FLIGHT_TIMEOUT_NS) inFlightSinceNs = null
      seq += 1
      val body = BlePayload.build(state, seq, nowUtcMs)
      val frags =
        try {
          BleProtocol.fragment(seq, body, mtu)
        } catch (e: IllegalArgumentException) {
          update { it.copy(detail = "送信データが大きすぎるため、このフレームを送りませんでした") }
          return@post
        }
      pending.clear()
      pending.addAll(frags)
      if (inFlightSinceNs == null) sendNext()
    }
  }

  @SuppressLint("MissingPermission")
  private fun sendNext() {
    val device = client ?: return
    val chr = characteristic ?: return
    val gattServer = server ?: return
    val fragment = pending.removeFirstOrNull() ?: return
    val ok =
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          gattServer.notifyCharacteristicChanged(device, chr, false, fragment) == BluetoothStatusCodes.SUCCESS
        } else {
          @Suppress("DEPRECATION")
          chr.value = fragment
          @Suppress("DEPRECATION")
          gattServer.notifyCharacteristicChanged(device, chr, false)
        }
      } catch (e: SecurityException) {
        update { BleStatus(BleState.PERMISSION_DENIED) }
        false
      }
    if (ok) inFlightSinceNs = SystemClock.elapsedRealtimeNanos() else pending.clear()
  }

  /** Stops advertising, disconnects and closes the GATT server; safe to call repeatedly. */
  @SuppressLint("MissingPermission")
  fun stop() {
    val h = handler ?: run {
      if (status.state != BleState.OFF) update { BleStatus(BleState.OFF) }
      return
    }
    h.post {
      stopAdvertising()
      try {
        client?.let { server?.cancelConnection(it) }
        server?.close()
      } catch (e: SecurityException) {
        // Permission revoked; closing the process releases the server.
      }
      server = null
      client = null
      subscribed = false
      pending.clear()
      inFlightSinceNs = null
      update { BleStatus(BleState.OFF) }
    }
    thread?.quitSafely()
    thread?.join(STOP_TIMEOUT_MS)
    thread = null
    handler = null
  }

  companion object {
    private const val IN_FLIGHT_TIMEOUT_NS = 2_000_000_000L
    private const val STOP_TIMEOUT_MS = 1_000L

    /** Runtime permissions needed for advertising and the GATT server on this API level. */
    fun requiredPermissions(): List<String> =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
      } else {
        emptyList() // BLUETOOTH / BLUETOOTH_ADMIN are install-time permissions up to API 30.
      }
  }
}
