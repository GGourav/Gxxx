package com.gxradar.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import com.gxradar.MainApplication
import com.gxradar.R
import com.gxradar.parser.EventDispatcher
import com.gxradar.parser.PhotonParser
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN Service for Albion Online packet capture.
 *
 * KEY: addAllowedApplication("com.albiononline")
 * - Only Albion traffic enters TUN
 * - All other apps bypass VPN
 *
 * Architecture:
 * - TUN read loop → parse IP packets from Albion
 * - UDP port 5056 → NIO DatagramChannel (protected) + Photon parser
 * - TCP → NIO SocketChannel (protected) relay
 * - Selector loop → incoming responses → write back to TUN
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG = "AlbionVpnService"
        const val ACTION_START = "com.gxradar.vpn.START"
        const val ACTION_STOP = "com.gxradar.vpn.STOP"
        private const val ALBION_PACKAGE = "com.albiononline"
        private const val ALBION_PORT = 5056
        private const val NOTIF_ID = 1001
        private const val MTU = 32767
        private const val TUN_IP = "10.8.0.2"
        private const val TUN_PREFIX = 32
        val packetCount = AtomicLong(0)
        val albionCount = AtomicLong(0)
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var selector: Selector? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val udpMap = ConcurrentHashMap<Int, UdpEntry>()
    private val tcpMap = ConcurrentHashMap<Int, TcpEntry>()
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var dispatcher: EventDispatcher

    override fun onCreate() {
        super.onCreate()
        dispatcher = EventDispatcher(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, createNotification("Starting..."))
        scope.launch { runVpn() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { selector?.close() }
        udpMap.values.forEach { runCatching { it.channel.close() } }; udpMap.clear()
        tcpMap.values.forEach { it.close() }; tcpMap.clear()
        runCatching { tunPfd?.close() }; tunPfd = null
        releaseWakeLock()
        packetCount.set(0); albionCount.set(0)
        super.onDestroy()
    }

    // ─── TUN Setup ────────────────────────────────────────────────────────────

    private suspend fun runVpn() {
        acquireWakeLock()

        val pfd = withContext(Dispatchers.IO) {
            runCatching {
                Builder()
                    .setSession("GX Radar")
                    .addAddress(TUN_IP, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAllowedApplication(ALBION_PACKAGE) // ★ KEY: Only Albion goes through VPN
                    .establish()
            }.getOrNull()
        }

        if (pfd == null) {
            Log.e(TAG, "Failed to establish VPN")
            stopSelf()
            return
        }

        tunPfd = pfd
        tunOut = FileOutputStream(pfd.fileDescriptor)
        selector = Selector.open()
        updateNotification("Capturing Albion port $ALBION_PORT")

        scope.launch(Dispatchers.IO) { runSelectorLoop() }
        runTunReadLoop(pfd)
    }

    // ─── TUN Read Loop ────────────────────────────────────────────────────────

    private suspend fun runTunReadLoop(pfd: ParcelFileDescriptor) =
        withContext(Dispatchers.IO) {
            val input = FileInputStream(pfd.fileDescriptor)
            val buf = ByteArray(MTU)
            try {
                while (isActive) {
                    val len = input.read(buf).takeIf { it > 20 } ?: continue
                    packetCount.incrementAndGet()

                    // IPv4 only
                    if ((buf[0].toInt() and 0xF0) != 0x40) continue

                    val ihl = (buf[0].toInt() and 0x0F) * 4
                    val proto = buf[9].toInt() and 0xFF
                    if (len < ihl + 8) continue

                    val srcIp = buf.copyOfRange(12, 16)
                    val dstIp = buf.copyOfRange(16, 20)

                    when (proto) {
                        17 -> handleUdp(buf, len, ihl, srcIp, dstIp)
                        6 -> handleTcp(buf, len, ihl, srcIp, dstIp)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "TUN read cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TUN read error", e)
            }
        }

    // ─── UDP Handling ─────────────────────────────────────────────────────────

    private fun handleUdp(buf: ByteArray, len: Int, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        val payOff = ihl + 8
        val payLen = len - payOff
        if (payLen <= 0) return

        // Parse Photon on Albion UDP traffic
        if (dstPort == ALBION_PORT || srcPort == ALBION_PORT) {
            albionCount.incrementAndGet()
            parsePhoton(buf, payOff, payLen)
            updateNotification()
        }

        // Create or reuse protected DatagramChannel
        val entry = udpMap.getOrPut(srcPort) {
            runCatching {
                val ch = DatagramChannel.open()
                protect(ch.socket()) // ★ MUST protect before connect
                ch.configureBlocking(false)
                ch.connect(InetSocketAddress(
                    java.net.InetAddress.getByAddress(dstIp), dstPort
                ))
                val e = UdpEntry(ch, srcIp.copyOf(), srcPort, dstPort)
                selector?.wakeup()
                ch.register(selector, SelectionKey.OP_READ, e)
                e
            }.getOrElse { return }
        }

        // Forward payload to real server
        runCatching {
            entry.channel.write(ByteBuffer.wrap(buf, payOff, payLen))
        }.onFailure {
            udpMap.remove(srcPort)?.channel?.close()
        }
    }

    // ─── TCP Handling ─────────────────────────────────────────────────────────

    private fun handleTcp(buf: ByteArray, len: Int, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (len < ihl + 20) return
        val srcPort = u16(buf, ihl)
        val dstPort = u16(buf, ihl + 2)
        val tcpOff = ((buf[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val flags = buf[ihl + 13].toInt() and 0xFF
        val isSyn = flags and 0x02 != 0
        val isFin = flags and 0x01 != 0
        val isRst = flags and 0x04 != 0
        val payOff = ihl + tcpOff
        val payLen = (len - payOff).coerceAtLeast(0)

        when {
            isRst || isFin -> tcpMap.remove(srcPort)?.close()
            isSyn -> scope.launch(Dispatchers.IO) {
                openTcpChannel(srcIp, srcPort, dstIp, dstPort)
            }
            payLen > 0 -> {
                val entry = tcpMap[srcPort] ?: return
                runCatching {
                    val d = ByteBuffer.wrap(buf, payOff, payLen)
                    while (d.hasRemaining()) entry.channel.write(d)
                }.onFailure { tcpMap.remove(srcPort)?.close() }
            }
        }
    }

    private fun openTcpChannel(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int) {
        runCatching {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            protect(ch.socket()) // ★ MUST protect before connect
            val entry = TcpEntry(ch, srcIp.copyOf(), srcPort, dstPort)
            tcpMap[srcPort] = entry
            ch.connect(InetSocketAddress(
                java.net.InetAddress.getByAddress(dstIp), dstPort
            ))
            selector?.wakeup()
            ch.register(selector, SelectionKey.OP_CONNECT, entry)
        }.onFailure {
            tcpMap.remove(srcPort)
        }
    }

    // ─── NIO Selector Loop ────────────────────────────────────────────────────

    private fun runSelectorLoop() {
        val sel = selector ?: return
        val buf = ByteBuffer.allocate(MTU)
        try {
            while (scope.isActive) {
                if (sel.select(500L) == 0) continue
                val keys = sel.selectedKeys().toSet()
                sel.selectedKeys().clear()
                for (key in keys) {
                    if (!key.isValid) continue
                    when {
                        key.isReadable -> onReadable(key, buf)
                        key.isConnectable -> onConnectable(key)
                    }
                }
            }
        } catch (e: ClosedSelectorException) {
            Log.d(TAG, "Selector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Selector error", e)
        }
    }

    private fun onReadable(key: SelectionKey, buf: ByteBuffer) {
        when (val att = key.attachment()) {
            is UdpEntry -> readUdp(att, buf)
            is TcpEntry -> readTcp(att, buf)
        }
    }

    private fun onConnectable(key: SelectionKey) {
        val entry = key.attachment() as? TcpEntry ?: return
        try {
            if (entry.channel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ)
            } else {
                tcpMap.remove(entry.srcPort)?.close()
            }
        } catch (e: Exception) {
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    private fun readUdp(entry: UdpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)
            if (n <= 0) return
            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }

            // Parse server→client Photon
            if (entry.dstPort == ALBION_PORT && n >= 12) {
                albionCount.incrementAndGet()
                parsePhoton(payload, 0, n)
                updateNotification()
            }

            // Write response back to TUN
            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return
            val pkt = buildUdpPacket(serverIp, entry.srcIp, entry.dstPort, entry.srcPort, payload)
            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            Log.v(TAG, "UDP read: ${e.message}")
        }
    }

    private fun readTcp(entry: TcpEntry, buf: ByteBuffer) {
        try {
            buf.clear()
            val n = entry.channel.read(buf)
            if (n < 0) {
                tcpMap.remove(entry.srcPort)?.close()
                return
            }
            if (n == 0) return
            buf.flip()
            val payload = ByteArray(n).also { buf.get(it) }
            val serverIp = (entry.channel.remoteAddress as? InetSocketAddress)
                ?.address?.address ?: return
            val pkt = buildTcpPacket(serverIp, entry.srcIp, entry.dstPort, entry.srcPort, payload)
            synchronized(this) { tunOut?.write(pkt) }
        } catch (e: Exception) {
            tcpMap.remove(entry.srcPort)?.close()
        }
    }

    // ─── Packet Builders ──────────────────────────────────────────────────────

    private fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen = 20 + udpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        b.put(0x45.toByte()); b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(17)
        val csumPos = b.position(); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        val arr = b.array()
        val cs = ipChecksum(arr, 0, 20)
        arr[csumPos] = (cs shr 8).toByte(); arr[csumPos + 1] = cs.toByte()

        // UDP header
        b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
        b.putShort(udpLen.toShort()); b.putShort(0)
        b.put(payload)
        return arr
    }

    private fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val ipLen = 20 + tcpLen
        val b = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        b.put(0x45.toByte()); b.put(0)
        b.putShort(ipLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64); b.put(6)
        val ipCsumPos = b.position(); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        val arr = b.array()
        val ipCs = ipChecksum(arr, 0, 20)
        arr[ipCsumPos] = (ipCs shr 8).toByte(); arr[ipCsumPos + 1] = ipCs.toByte()

        // TCP header (PSH+ACK)
        b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
        b.putInt(1); b.putInt(1)
        b.put(0x50.toByte())
        b.put(0x18.toByte())
        b.putShort(65535.toShort())
        b.putShort(0); b.putShort(0)
        b.put(payload)
        return arr
    }

    private fun ipChecksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len % 2 != 0) sum += (data[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    // ─── Photon Parser ─────────────────────────────────────────────────────────

    private fun parsePhoton(buf: ByteArray, off: Int, len: Int) {
        try {
            val payload = if (off == 0 && len == buf.size) buf else buf.copyOfRange(off, off + len)
            val events = PhotonParser.parse(payload)
            events.forEach { dispatcher.dispatchEvent(it) }
        } catch (e: Exception) {
            Log.v(TAG, "Photon parse: ${e.message}")
        }
    }

    // ─── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GXRadar:VpnWake")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun u16(buf: ByteArray, off: Int) =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "gxradar_vpn", "GX Radar VPN", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "gxradar_vpn")
                .setContentTitle("GX Radar")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GX Radar")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String? = null) {
        val msg = text ?: "PKT ${packetCount.get()}  ALB ${albionCount.get()}"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, createNotification(msg))
    }

    // ─── Data Classes ─────────────────────────────────────────────────────────

    private data class UdpEntry(
        val channel: DatagramChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int
    )

    private inner class TcpEntry(
        val channel: SocketChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int
    ) {
        fun close() = runCatching { channel.close() }
    }
}
