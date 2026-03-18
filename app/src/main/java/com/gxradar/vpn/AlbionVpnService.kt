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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG = "AlbionVpnService"
        const val ACTION_START = "com.gxradar.vpn.START"
        const val ACTION_STOP = "com.gxradar.vpn.STOP"

        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_ROUTE = "0.0.0.0"
        private const val MTU = 1500
        private const val TARGET_PORT = 5056
        private const val TARGET_PACKAGE = "com.albiononline"
        private const val DNS_SERVER = "8.8.8.8"
        private const val NOTIFICATION_CHANNEL_ID = "gxradar_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "GXRadar:VpnWake"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isRunning = false

    private lateinit var photonParser: PhotonParser
    private lateinit var eventDispatcher: EventDispatcher
    private var udpChannel: DatagramChannel? = null
    private val tcpChannels = ConcurrentHashMap<Int, SocketChannel>()
    private var selector: Selector? = null
    private var serverAddress: InetAddress? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
        photonParser = PhotonParser()
        eventDispatcher = EventDispatcher(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN Service destroyed")
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        Log.i(TAG, "Starting VPN...")

        try {
            acquireWakeLock()

            val builder = Builder()
                .setSession("GX Radar")
                .addAddress(TUN_ADDRESS, 32)
                .addRoute(TUN_ROUTE, 0)
                .setMtu(MTU)
                .addDnsServer(DNS_SERVER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    builder.addAllowedApplication(TARGET_PACKAGE)
                    Log.i(TAG, "Added allowed application: $TARGET_PACKAGE")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add allowed application: ${e.message}")
                }
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

            initUdpChannel()
            selector = Selector.open()
            isRunning = true

            startForeground(NOTIFICATION_ID, createNotification())
            MainApplication.getInstance().setVpnRunning(true)

            serviceScope.launch { runVpnLoop() }
            serviceScope.launch { runResponseListener() }

            Log.i(TAG, "VPN started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun initUdpChannel() {
        try {
            udpChannel = DatagramChannel.open()
            udpChannel?.configureBlocking(false)

            val socket = udpChannel?.socket()
            if (socket != null && !protect(socket)) {
                Log.e(TAG, "Failed to protect UDP socket!")
                throw IllegalStateException("Socket protection failed")
            }

            Log.i(TAG, "UDP channel initialized and protected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UDP channel: ${e.message}")
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN...")
        isRunning = false
        releaseWakeLock()

        try { udpChannel?.close() } catch (e: Exception) { }
        udpChannel = null

        tcpChannels.values.forEach { try { it.close() } catch (e: Exception) { } }
        tcpChannels.clear()

        try { selector?.close() } catch (e: Exception) { }
        selector = null

        try {
            vpnInputStream?.close()
            vpnOutputStream?.close()
            vpnInterface?.close()
        } catch (e: Exception) { }

        vpnInputStream = null
        vpnOutputStream = null
        vpnInterface = null

        MainApplication.getInstance().setVpnRunning(false)
        Log.i(TAG, "VPN stopped")
    }

    private suspend fun runVpnLoop() {
        Log.i(TAG, "VPN loop started")

        val buffer = ByteBuffer.allocate(MTU)
        buffer.order(ByteOrder.BIG_ENDIAN)

        while (isRunning && vpnInputStream != null) {
            try {
                val length = vpnInputStream!!.read(buffer.array())
                if (length <= 0) continue

                buffer.limit(length)
                buffer.position(0)

                val ipVersion = (buffer.get().toInt() shr 4) and 0x0F
                if (ipVersion != 4) continue

                buffer.position(0)
                val ihl = (buffer.get().toInt() and 0x0F) * 4

                buffer.position(9)
                val protocol = buffer.get().toInt() and 0xFF

                buffer.position(12)
                val srcIp = ByteArray(4)
                val dstIp = ByteArray(4)
                buffer.get(srcIp)
                buffer.get(dstIp)

                buffer.position(ihl)
                val srcPort = buffer.short.toInt() and 0xFFFF
                val dstPort = buffer.short.toInt() and 0xFFFF

                if (srcPort != TARGET_PORT && dstPort != TARGET_PORT) continue

                when (protocol) {
                    17 -> handleUdpPacket(buffer, ihl, srcIp, srcPort, dstIp, dstPort)
                    6 -> handleTcpPacket(buffer, ihl, srcIp, srcPort, dstIp, dstPort)
                }

            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Error in VPN loop: ${e.message}")
            }
        }

        Log.i(TAG, "VPN loop exited")
    }

    private fun handleUdpPacket(
        buffer: ByteBuffer,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ) {
        try {
            val udpLength = buffer.short.toInt() and 0xFFFF
            buffer.short // Skip checksum

            val payloadLength = udpLength - 8
            if (payloadLength <= 0) return

            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            val isOutbound = dstPort == TARGET_PORT

            if (isOutbound) {
                forwardUdpToServer(dstIp, dstPort, payload)
            } else {
                parsePhotonPacket(payload)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error handling UDP packet: ${e.message}")
        }
    }

    private fun forwardUdpToServer(dstIp: ByteArray, dstPort: Int, payload: ByteArray) {
        try {
            val channel = udpChannel ?: return
            val address = InetAddress.getByAddress(dstIp)
            serverAddress = address

            channel.send(ByteBuffer.wrap(payload), InetSocketAddress(address, dstPort))
            Log.d(TAG, "Forwarded ${payload.size} bytes to ${address.hostAddress}:$dstPort")

        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding UDP: ${e.message}")
        }
    }

    private suspend fun runResponseListener() {
        Log.i(TAG, "Response listener started")

        val responseBuffer = ByteBuffer.allocate(MTU)

        while (isRunning && udpChannel != null) {
            try {
                responseBuffer.clear()
                val sourceAddr = udpChannel?.receive(responseBuffer)

                if (sourceAddr != null) {
                    responseBuffer.flip()
                    val response = ByteArray(responseBuffer.remaining())
                    responseBuffer.get(response)

                    injectUdpResponse(response, sourceAddr as InetSocketAddress)
                    parsePhotonPacket(response)
                }

                delay(1)

            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Error in response listener: ${e.message}")
            }
        }

        Log.i(TAG, "Response listener stopped")
    }

    private fun injectUdpResponse(payload: ByteArray, sourceAddr: InetSocketAddress) {
        try {
            val packetLen = 20 + 8 + payload.size
            val packet = ByteBuffer.allocate(packetLen)
            packet.order(ByteOrder.BIG_ENDIAN)

            packet.put(0x45.toByte())
            packet.put(0)
            packet.putShort(packetLen.toShort())
            packet.putShort(0)
            packet.putShort(0)
            packet.put(64.toByte())
            packet.put(17.toByte())
            packet.putShort(0)

            val serverIp = sourceAddr.address.address
            packet.put(serverIp)
            packet.put(byteArrayOf(10, 0, 0, 2))

            packet.putShort(sourceAddr.port.toShort())
            packet.putShort(0)
            packet.putShort((8 + payload.size).toShort())
            packet.putShort(0)

            packet.put(payload)

            vpnOutputStream?.write(packet.array())
            Log.d(TAG, "Injected ${payload.size} byte response from server")

        } catch (e: Exception) {
            Log.w(TAG, "Error injecting UDP response: ${e.message}")
        }
    }

    private fun handleTcpPacket(
        buffer: ByteBuffer,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ) {
        try {
            val tcpHeaderStart = ipHeaderLen
            buffer.position(tcpHeaderStart + 12)
            val tcpHeaderLen = ((buffer.get().toInt() and 0xF0) shr 4) * 4

            val payloadStart = tcpHeaderStart + tcpHeaderLen
            if (payloadStart >= buffer.limit()) return

            buffer.position(payloadStart)
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)

            Log.d(TAG, "TCP packet: $srcPort -> $dstPort, ${payload.size} bytes")

        } catch (e: Exception) {
            Log.w(TAG, "Error handling TCP packet: ${e.message}")
        }
    }

    private fun parsePhotonPacket(payload: ByteArray) {
        try {
            if (payload.isNotEmpty()) {
                Log.d(TAG, "Parsing packet: ${payload.size} bytes, first bytes: ${
                    payload.take(16).joinToString(" ") { String.format("%02X", it) }
                }")
            }

            val events = photonParser.parse(payload)

            if (events.isNotEmpty()) {
                Log.i(TAG, "Parsed ${events.size} Photon messages")
            }

            events.forEach { event ->
                eventDispatcher.dispatchEvent(event)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Photon packet: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GX Radar VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for packet capture"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("GX Radar")
                .setContentText("Monitoring Albion Online traffic")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GX Radar")
                .setContentText("Monitoring Albion Online traffic")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
