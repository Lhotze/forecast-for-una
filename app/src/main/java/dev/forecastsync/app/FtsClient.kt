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
 * see una-sdk/Docs/BLE-File-Transfer-Service.md. It can do two things only:
 *  - write ONE data file of a glance app and verify it ([writeFile]), and
 *  - for the Find my Phone request: list one app folder, read the small `ring.txt` and delete that
 *    one file ([withConnection] with [listDir], [readSmallFile], [deleteFile]).
 * No move, no directory writes, no other file is ever read or deleted.
 */
@SuppressLint("MissingPermission")
class FtsClient(private val ctx: Context) {

    companion object {
        val FTS_SERVICE: UUID = UUID.fromString("0000FEBB-0000-1000-8000-00805F9B34FB")
        val FTS_VERSION: UUID = UUID.fromString("ADAF0001-4669-6C65-5472-616E73666572")
        val FTS_RAW: UUID = UUID.fromString("ADAF0002-4669-6C65-5472-616E73666572")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_READ = 0x10
        const val CMD_READ_DATA = 0x11
        const val CMD_READ_PACING = 0x12
        const val CMD_DELETE = 0x30
        const val CMD_DELETE_STATUS = 0x31
        const val CMD_LISTDIR = 0x50
        const val CMD_LISTDIR_ENTRY = 0x51
        const val CMD_WRITE = 0x20
        const val CMD_WRITE_PACING = 0x21
        const val CMD_WRITE_DATA = 0x22
        const val CMD_DIGEST = 0x70
        const val CMD_DIGEST_STATUS = 0x71
        const val STATUS_OK = 0x01
        const val STATUS_NO_FILE = 0x03
        /** The request file is tiny ("ts=1760000000\n"); anything bigger is not ours. */
        const val MAX_SMALL_FILE = 64

        /** Never write anywhere else than a watch app's own data file (the Forecast weather file or the Agenda calendar file). */
        fun isAllowedPath(path: String) =
            Regex("^/Apps/[0-9A-Fa-f]{16}/(weather|calendar)\\.json$").matches(path)

        /** The only file that may be read or deleted: the Find my Phone request. */
        fun isAllowedRingPath(path: String) =
            Regex("^/Apps/[0-9A-Fa-f]{16}/ring\\.txt$").matches(path)

        /** The only folder that may be listed: a watch app's own folder. */
        fun isAllowedAppDir(path: String) =
            Regex("^/Apps/[0-9A-Fa-f]{16}$").matches(path)
    }

    /** One entry of a directory listing. [mtimeNs] is the file's modification time, ns since 1970. */
    class Entry(val name: String, val isDir: Boolean, val size: Int, val mtimeNs: Long)

    /** Message layouts of the FTS commands used for the Find my Phone request (see BLE-File-Transfer-Service.md). */
    object Wire {
        private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        /** `{cmd, reserved, pathLength}` + path: LISTDIR and DELETE. */
        fun pathRequest(cmd: Int, path: String): ByteArray {
            val p = path.toByteArray(Charsets.UTF_8)
            return le(4 + p.size).put(cmd.toByte()).put(0).putShort(p.size.toShort()).put(p).array()
        }

        fun readRequest(path: String, size: Int): ByteArray {
            val p = path.toByteArray(Charsets.UTF_8)
            return le(12 + p.size).put(CMD_READ.toByte()).put(0).putShort(p.size.toShort())
                .putInt(0).putInt(size).put(p).array()
        }

        fun readPacing(offset: Int, size: Int): ByteArray =
            le(12).put(CMD_READ_PACING.toByte()).put(STATUS_OK.toByte()).putShort(0).putInt(offset).putInt(size).array()

        class ListReply(val status: Int, val terminal: Boolean, val entry: Entry?)

        /** One LISTDIR notification: an entry, or the terminating one (entryNumber == totalEntries, no name). */
        fun parseListReply(m: ByteArray): ListReply {
            if (m.size < 28) throw IOException("Short listing entry")
            val bb = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
            val status = m[1].toInt() and 0xFF
            val nameLen = bb.getShort(2).toInt() and 0xFFFF
            val number = bb.getInt(4)
            val total = bb.getInt(8)
            if (status != STATUS_OK) return ListReply(status, false, null)
            if (nameLen == 0 && number >= total) return ListReply(status, true, null)
            if (m.size < 28 + nameLen) throw IOException("Short listing entry")
            return ListReply(status, false, Entry(
                name = String(m, 28, nameLen, Charsets.UTF_8),
                isDir = (bb.getInt(12) and 1) != 0,
                size = bb.getInt(24),
                mtimeNs = bb.getLong(16),
            ))
        }

        class ReadReply(val status: Int, val offset: Int, val total: Int, val data: ByteArray)

        /** One READ_DATA notification; [data] holds only the bytes that really arrived. */
        fun parseReadReply(m: ByteArray): ReadReply {
            if (m.size < 16) throw IOException("Short read reply")
            val bb = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
            val len = minOf(bb.getInt(12), m.size - 16).coerceAtLeast(0)
            return ReadReply(m[1].toInt() and 0xFF, bb.getInt(4), bb.getInt(8), m.copyOfRange(16, 16 + len))
        }
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

    /** [what] names the step in the error, so a silent watch can be told apart from a refusing one. */
    private suspend fun awaitReply(expectedCmd: Int, what: String, timeoutMs: Long = 6_000): ByteArray {
        try {
            return withTimeout(timeoutMs) {
                while (true) {
                    val m = notifications.receive()
                    if ((m[0].toInt() and 0xFF) == expectedCmd) return@withTimeout m
                }
                @Suppress("UNREACHABLE_CODE") ByteArray(0)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("No answer from the watch to $what within ${timeoutMs / 1000} s")
        }
    }

    private fun drain() {
        while (notifications.tryReceive().isSuccess) { /* discard stale notifications */ }
    }

    /** Connects, runs [block] on the open link and disconnects, whatever happens. */
    suspend fun <T> withConnection(address: String, block: suspend FtsClient.() -> T): T {
        try {
            connect(address)
            drain()
            return block()
        } finally {
            close()
        }
    }

    /** Lists the files in [dir] (LISTDIR). Needs [withConnection]. */
    suspend fun listDir(dir: String): List<Entry> {
        require(isAllowedAppDir(dir)) { "Folder not allowed: $dir" }
        drain()
        writeRaw(Wire.pathRequest(CMD_LISTDIR, dir))
        val out = ArrayList<Entry>()
        while (out.size < 256) {
            val m = awaitReply(CMD_LISTDIR_ENTRY, "the folder listing of $dir (does the folder exist on the watch?)")
            val r = Wire.parseListReply(m)
            if (r.status != STATUS_OK) throw IOException("Watch cannot list $dir (status ${r.status})")
            if (r.terminal) break
            r.entry?.let { out += it }
        }
        return out
    }

    /** Reads a whole file of at most [MAX_SMALL_FILE] bytes whose exact [size] is known from [listDir]. Needs [withConnection]. */
    suspend fun readSmallFile(path: String, size: Int): ByteArray {
        require(isAllowedRingPath(path)) { "Path not allowed: $path" }
        require(size in 1..MAX_SMALL_FILE) { "Unexpected file size $size" }
        drain()
        writeRaw(Wire.readRequest(path, size))
        val data = ByteArray(size)
        var have = 0
        while (have < size) {
            val r = Wire.parseReadReply(awaitReply(CMD_READ_DATA, "the read of $path"))
            if (r.status == STATUS_NO_FILE) throw java.io.FileNotFoundException("File vanished")
            if (r.status != STATUS_OK) throw IOException("Watch refuses the read (status ${r.status})")
            if (r.offset < 0 || r.offset + r.data.size > size) throw IOException("Unexpected read data")
            System.arraycopy(r.data, 0, data, r.offset, r.data.size)
            have = maxOf(have, r.offset + r.data.size)
            if (have < size) writeRaw(Wire.readPacing(have, size - have))
        }
        return data
    }

    /** Deletes [path] (DELETE). A file that is already gone counts as deleted. Needs [withConnection]. */
    suspend fun deleteFile(path: String) {
        require(isAllowedRingPath(path)) { "Path not allowed: $path" }
        drain()
        writeRaw(Wire.pathRequest(CMD_DELETE, path))
        val r = awaitReply(CMD_DELETE_STATUS, "the delete of $path")
        val status = r[1].toInt() and 0xFF
        if (status != STATUS_OK && status != STATUS_NO_FILE) throw IOException("Watch refuses the delete (status $status)")
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
            val first = awaitReply(CMD_WRITE_PACING, "the write request for $path (does that folder exist on the watch?)")
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
                val ack = awaitReply(CMD_WRITE_PACING, "data at offset ${off - n}")
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
                val r = awaitReply(CMD_DIGEST_STATUS, "the checksum request")
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
