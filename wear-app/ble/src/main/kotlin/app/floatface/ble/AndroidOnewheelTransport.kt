package app.floatface.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import app.floatface.core.GattOp
import app.floatface.core.GattStatus
import app.floatface.core.Logger
import app.floatface.core.OnewheelTransport
import app.floatface.core.OwCharacteristic
import app.floatface.core.TransportEvent
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The `:ble` GATT adapter (SPEC §4.5.4 / §3.5 / §6.1). Implements
 * [OnewheelTransport] over `android.bluetooth`, translating
 * `BluetoothGattCallback`/`ScanCallback` deliveries into Android-free
 * [TransportEvent]s and routing every discover/notify/read/write through a
 * single [GattOperationQueue] so at most one GATT operation is ever
 * outstanding at a time.
 *
 * **Read-only guarantee (SPEC §1.2.1):** [writeUnlock] is the only write
 * entry point on this class and it hard-codes the queued write's target to
 * [OwCharacteristic.UART_SERIAL_WRITE] — there is no characteristic parameter,
 * and no other call site in this file ever constructs a [GattQueueOp.Write].
 * No code path exists to write any other characteristic.
 *
 * [gattOpsFactory] defaults to wrapping the real, just-connected
 * `BluetoothGatt` in [RealGattOps]; tests substitute a fake to exercise the
 * write-target guarantee and queueing behavior without a functioning radio.
 * [scope] should be long-lived (its only job is hosting each queued op's
 * timeout timer) — the caller (`:app`'s composition root) owns its lifecycle.
 */
class AndroidOnewheelTransport(
    private val context: Context,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val opTimeoutMs: Long = 8000L,
    private val gattOpsFactory: (BluetoothGatt) -> GattOps = { RealGattOps(it) },
) : OnewheelTransport {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    // internal (not private) so tests in this module can inject a fake queue/state
    // directly, per the GattOps-abstraction test seam called for in the task.
    internal var gatt: BluetoothGatt? = null
    internal var queue: GattOperationQueue? = null
    internal var servicesDiscovered: Boolean = false

    /** Exposed so Robolectric tests can drive Android→core mappings directly. */
    internal val callback: BluetoothGattCallback = TransportGattCallback()

    private var scanCallback: ScanCallback? = null

    private fun emit(event: TransportEvent) {
        _events.tryEmit(event)
    }

    override fun startScan() {
        val scanner = safeScanner()
        if (scanner == null) {
            emit(TransportEvent.ScanFailed("BLE scanner unavailable"))
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                emit(
                    TransportEvent.DeviceFound(
                        deviceId = result.device.address,
                        name = record?.deviceName ?: safeDeviceName(result.device),
                        serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList(),
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                emit(TransportEvent.ScanFailed("scan failed, code=$errorCode"))
            }
        }
        scanCallback = cb
        try {
            scanner.startScan(cb)
        } catch (e: SecurityException) {
            logger.e(TAG, "startScan denied", e)
            scanCallback = null
            emit(TransportEvent.ScanFailed("permission denied"))
        }
    }

    override fun stopScan() {
        val scanner = safeScanner()
        val cb = scanCallback
        if (scanner != null && cb != null) {
            try {
                scanner.stopScan(cb)
            } catch (e: SecurityException) {
                logger.e(TAG, "stopScan denied", e)
            }
        }
        scanCallback = null
        emit(TransportEvent.ScanStopped)
    }

    override fun connect(deviceId: String) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            emit(TransportEvent.OperationFailed(GattOp.CONNECT, GattStatus(STATUS_NO_ADAPTER)))
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(deviceId)
        } catch (e: IllegalArgumentException) {
            logger.e(TAG, "invalid device id $deviceId", e)
            emit(TransportEvent.OperationFailed(GattOp.CONNECT, GattStatus(STATUS_NO_ADAPTER)))
            return
        }
        servicesDiscovered = false
        val newGatt = try {
            // autoConnect=false: a direct, immediate connect attempt (SPEC §3.5 step 1).
            device.connectGatt(context, false, callback)
        } catch (e: SecurityException) {
            logger.e(TAG, "connectGatt denied", e)
            emit(TransportEvent.OperationFailed(GattOp.CONNECT, GattStatus(STATUS_NO_ADAPTER)))
            return
        }
        gatt = newGatt
        queue = GattOperationQueue(
            ops = gattOpsFactory(newGatt),
            scope = scope,
            timeoutMs = opTimeoutMs,
            onTimeout = { op -> emit(TransportEvent.OperationFailed(opKindOf(op), GattStatus(STATUS_TIMEOUT))) },
        )
    }

    override fun closeGatt() {
        gatt?.close()
        gatt = null
        queue = null
        servicesDiscovered = false
    }

    override fun discoverServices() {
        val q = queue ?: run { logger.w(TAG, "discoverServices with no active connection"); return }
        q.enqueue(GattQueueOp.Discover)
    }

    override fun enableNotifications(char: OwCharacteristic) {
        val q = queue ?: run { logger.w(TAG, "enableNotifications($char) with no active connection"); return }
        q.enqueue(GattQueueOp.EnableNotify(char))
    }

    override fun read(char: OwCharacteristic) {
        val q = queue ?: run { logger.w(TAG, "read($char) with no active connection"); return }
        q.enqueue(GattQueueOp.Read(char))
    }

    /**
     * The sole write path (SPEC §1.2.1, §3.5 step 5). Hard-codes the queued
     * write's target to [OwCharacteristic.UART_SERIAL_WRITE]; there is no
     * characteristic parameter, so no caller can direct a write anywhere else.
     * Guards against writing before service discovery has completed, per
     * SPEC §3.5 step 2 (`getService()` returns null before then).
     */
    override fun writeUnlock(value: ByteArray) {
        if (!servicesDiscovered) {
            logger.w(TAG, "writeUnlock called before services discovered; ignoring")
            return
        }
        val q = queue ?: run { logger.w(TAG, "writeUnlock called with no active GATT connection; ignoring"); return }
        q.enqueue(GattQueueOp.Write(OwCharacteristic.UART_SERIAL_WRITE, value))
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    private fun safeScanner() = try {
        bluetoothManager?.adapter?.bluetoothLeScanner
    } catch (e: SecurityException) {
        null
    }

    private fun safeDeviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    private fun opKindOf(op: GattQueueOp): GattOp = when (op) {
        is GattQueueOp.Discover -> GattOp.DISCOVER
        is GattQueueOp.EnableNotify -> GattOp.ENABLE_NOTIFY
        is GattQueueOp.Read -> GattOp.READ
        is GattQueueOp.Write -> GattOp.WRITE
    }

    /**
     * Maps `BluetoothGattCallback` deliveries onto [TransportEvent]s (SPEC
     * §4.5.2) and acknowledges the [GattOperationQueue] so it can advance.
     * Both the pre- and post-API-33 `onCharacteristicRead`/`onCharacteristicChanged`
     * overloads are implemented for correctness across `minSdk` (30) devices;
     * only one pair fires per platform version, guarded by [Build.VERSION].
     */
    private inner class TransportGattCallback : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> emit(TransportEvent.Connected)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    servicesDiscovered = false
                    emit(TransportEvent.Disconnected(GattStatus(status)))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            servicesDiscovered = success
            val available = if (success) {
                gatt.getService(SERVICE_UUID)?.characteristics
                    ?.mapNotNull { OwCharacteristic.fromUuid(it.uuid.toString()) }
                    ?.toSet() ?: emptySet()
            } else {
                emptySet()
            }
            queue?.completeCurrent(GattStatus(status))
            emit(TransportEvent.ServicesDiscovered(GattStatus(status), available))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val char = OwCharacteristic.fromUuid(descriptor.characteristic.uuid.toString())
            queue?.completeCurrent(GattStatus(status))
            if (char != null) emit(TransportEvent.NotificationsEnabled(char, GattStatus(status)))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic, value, status)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleRead(characteristic, characteristic.value ?: ByteArray(0), status)
        }

        private fun handleRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val char = OwCharacteristic.fromUuid(characteristic.uuid.toString())
            queue?.completeCurrent(GattStatus(status))
            if (char != null) emit(TransportEvent.ReadComplete(char, value, GattStatus(status)))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val char = OwCharacteristic.fromUuid(characteristic.uuid.toString())
            queue?.completeCurrent(GattStatus(status))
            if (char != null) emit(TransportEvent.WriteComplete(char, GattStatus(status)))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleChanged(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleChanged(characteristic, characteristic.value ?: ByteArray(0))
        }

        private fun handleChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Notification delivery, not a read reply — never acknowledges the queue.
            val char = OwCharacteristic.fromUuid(characteristic.uuid.toString())
            if (char != null) emit(TransportEvent.CharacteristicChanged(char, value))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // Never requested by this adapter (SPEC instruction); handled defensively
            // in case the platform/remote side triggers one unsolicited.
            emit(TransportEvent.MtuChanged(mtu, GattStatus(status)))
        }
    }

    companion object {
        private const val TAG = "AndroidOnewheelTransport"
        private const val STATUS_NO_ADAPTER = -1
        private const val STATUS_TIMEOUT = -2
        private val SERVICE_UUID: UUID = UUID.fromString(OwCharacteristic.SERVICE_UUID)
    }
}

/**
 * Real [GattOps] wrapping a connected [BluetoothGatt] (SPEC §4.5.4). Every
 * method resolves the target `BluetoothGattCharacteristic` off the single
 * Onewheel service; a missing service/characteristic is logged and the call
 * is dropped (the queue's per-op timeout then reports the failure).
 */
class RealGattOps(private val gatt: BluetoothGatt) : GattOps {

    override fun discover() {
        gatt.discoverServices()
    }

    override fun enableNotify(c: OwCharacteristic) {
        val characteristic = resolve(c) ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
    }

    override fun read(c: OwCharacteristic) {
        val characteristic = resolve(c) ?: return
        gatt.readCharacteristic(characteristic)
    }

    /** Write-with-response (SPEC §3.5 step 5); only ever invoked for [OwCharacteristic.UART_SERIAL_WRITE]. */
    override fun write(c: OwCharacteristic, bytes: ByteArray) {
        val characteristic = resolve(c) ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = bytes
        gatt.writeCharacteristic(characteristic)
    }

    private fun resolve(c: OwCharacteristic): BluetoothGattCharacteristic? =
        gatt.getService(SERVICE_UUID)?.getCharacteristic(UUID.fromString(c.uuid))

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString(OwCharacteristic.SERVICE_UUID)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
