package dev.forecastsync.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Minimal client for the UNA Watch BLE File Transfer Service (service 0xFEBB),
 * see una-sdk/Docs/BLE-File-Transfer-Service.md. It can do exactly one thing:
 * write ONE file and verify it. No delete, no move, no directory writes.
 */
@SuppressLint("MissingPermission")
class FtsClient(private val ctx: Context) {

    companion object {
        val FTS_SERVICE: UUID = UUID.fromString("0000FEBB-0000-1000-8000-00805F9B34FB")
        val FTS_VERSION: UUID = UUID.fromString("ADAF0001-4669-6C65-5472-616E73666572")
        val FTS_RAW: UUID = UUID.fromString("ADAF0002-4669-6C65-5472-616E73666572")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_WRITE = 0x20
        const val CMD_WRITE_PACING = 0x21
        const val CMD_WRITE_DATA = 0x22
        const val CMD_DIGEST = 0x70
        const val CMD_DIGEST_STATUS = 0x71
        const val STATUS_OK = 0x01

        /** Never write anywhere else than the app's own data file. */
        fun isAllowedPath(path: String) =
            Regex("^/Apps/[0-9A-Fa-f]{16}/weather\\.json$").matches(path)
    }

    private var gatt: BluetoothGatt? = null
    private var rawChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var protocolVersion = 0

    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private var connectedDef = CompletableDeferred<Unit>()
    private var mtuDef = CompletableDeferred<Int>()
    private var servicesDef = CompletableDeferred<Boolean>()
    private var descDef = CompletableDeferred<Boolean>()
    private var readDef = CompletableDeferred<ByteArray?>()
    private var writeDef = CompletableDeferred<Boolean>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedDef.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val err = IOException("Connection lost (status $status)")
                connectedDef.completeExceptionally(err)
                mtuDef.completeExceptionally(err)
                servicesDef.completeExceptionally(err)
                descDef.completeExceptionally(err)
                readDef.completeExceptionally(err)
                writeDef.completeExceptionally(err)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtuDef.complete(if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDef.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descDef.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33, still delivered on all versions")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readDef.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDef.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in API 33, still delivered on all versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == FTS_RAW) {
                notifications.trySend(c.value.copyOf())
            }
        }
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private suspend fun connect(address: String) {
        val adapter: BluetoothAdapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IOException("No Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)

        connectedDef = CompletableDeferred()
        gatt = device.connectGatt(ctx, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt failed")
        withTimeout(20_000) { connectedDef.await() }

        mtuDef = CompletableDeferred()
        mtu = if (gatt!!.requestMtu(247)) {
            try { withTimeout(5_000) { mtuDef.await() } } catch (e: Exception) { 23 }
        } else 23

        servicesDef = CompletableDeferred()
        if (!gatt!!.discoverServices()) throw IOException("Service discovery failed")
        if (!withTimeout(15_000) { servicesDef.await() }) throw IOException("Service discovery failed")

        val service = gatt!!.getService(FTS_SERVICE)
            ?: throw IOException("Watch has no File Transfer Service (0xFEBB) - is it paired?")
        val verChar = service.getCharacteristic(FTS_VERSION) ?: throw IOException("FTS version characteristic missing")
        rawChar = service.getCharacteristic(FTS_RAW) ?: throw IOException("FTS channel missing")

        readDef = CompletableDeferred()
        if (!gatt!!.readCharacteristic(verChar)) throw IOException("Reading FTS version failed")
        val ver = withTimeout(8_000) { readDef.await() } ?: throw IOException("Reading FTS version rejected (not paired?)")
        protocolVersion = ByteBuffer.wrap(ver).order(ByteOrder.LITTLE_ENDIAN).int

        gatt!!.setCharacteristicNotification(rawChar, true)
        val cccd = rawChar!!.getDescriptor(CCCD) ?: throw IOException("CCCD missing")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        descDef = CompletableDeferred()
        if (!gatt!!.writeDescriptor(cccd)) throw IOException("Enabling notifications failed")
        if (!withTimeout(8_000) { descDef.await() }) throw IOException("Notifications rejected")
    }

    private fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rawChar = null
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        val ch = rawChar ?: throw IOException("not connected")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = bytes
        writeDef = CompletableDeferred()
        if (!gatt!!.writeCharacteristic(ch)) throw IOException("Send refused")
        if (!withTimeout(5_000) { writeDef.await() }) throw IOException("Send failed")
    }

    private suspend fun awaitReply(expectedCmd: Int, timeoutMs: Long = 6_000): ByteArray {
        return withTimeout(timeoutMs) {
            while (true) {
                val m = notifications.receive()
                if ((m[0].toInt() and 0xFF) == expectedCmd) return@withTimeout m
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }
    }

    private fun drain() {
        while (notifications.tryReceive().isSuccess) { /* discard stale notifications */ }
    }

    /**
     * Connects, writes [data] to [path], verifies it with DIGEST (FTS v5+) and
     * disconnects. Throws IOException / TimeoutCancellationException on failure.
     * Returns a short description of what happened.
     */
    suspend fun writeFile(address: String, path: String, data: ByteArray): String {
        require(isAllowedPath(path)) { "Path not allowed: $path" }
        try {
            connect(address)
            drain()

            val p = path.toByteArray(Charsets.UTF_8)
            val head = le(20 + p.size)
                .put(CMD_WRITE.toByte()).put(0).putShort(p.size.toShort())
                .putInt(0).putLong(System.currentTimeMillis() * 1_000_000L).putInt(data.size)
                .put(p).array()
            writeRaw(head)
            val first = awaitReply(CMD_WRITE_PACING)
            if ((first[1].toInt() and 0xFF) != STATUS_OK) {
                throw IOException("Watch refuses the write (status ${first[1].toInt() and 0xFF}) - does the folder ${path.substringBeforeLast('/')} exist?")
            }

            val chunkMax = maxOf(20, mtu - 3 - 12)
            var off = 0
            while (off < data.size) {
                val n = minOf(chunkMax, data.size - off)
                val msg = le(12 + n)
                    .put(CMD_WRITE_DATA.toByte()).put(STATUS_OK.toByte()).putShort(0)
                    .putInt(off).putInt(n).put(data, off, n).array()
                writeRaw(msg)
                off += n
                val ack = awaitReply(CMD_WRITE_PACING)
                if ((ack[1].toInt() and 0xFF) != STATUS_OK) {
                    throw IOException("Watch rejects data (status ${ack[1].toInt() and 0xFF})")
                }
                val free = ByteBuffer.wrap(ack, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (free == 0) break
            }

            if (protocolVersion >= 5) {
                drain()
                val req = le(4 + p.size).put(CMD_DIGEST.toByte()).put(0).putShort(p.size.toShort()).put(p).array()
                writeRaw(req)
                val r = awaitReply(CMD_DIGEST_STATUS)
                val bb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
                val size = bb.getInt(4)
                val crc = bb.getInt(8).toLong() and 0xFFFFFFFFL
                val mine = CRC32().apply { update(data) }.value
                if ((r[1].toInt() and 0xFF) != STATUS_OK || size != data.size || crc != mine) {
                    throw IOException("Checksum mismatch (watch: $size B / ${java.lang.Long.toHexString(crc)}, expected ${data.size} B / ${java.lang.Long.toHexString(mine)})")
                }
                return "${data.size} B written and verified by CRC32 (FTS v$protocolVersion)"
            }
            return "${data.size} B written (FTS v$protocolVersion, no checksum)"
        } finally {
            close()
        }
    }
}
