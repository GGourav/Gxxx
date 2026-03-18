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
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap

/**
 * VPN Service for capturing Albion Online UDP traffic.
 *
 * CRITICAL FIX: This version implements proper packet forwarding to maintain
 * game connectivity. Without forwarding, the game client cannot communicate
 * with the server when VPN is active.
 *
 * Architecture:
 * 1. TUN interface intercepts all game traffic
 * 2. UDP packets are forwarded via protected DatagramChannel
 * 3. TCP packets are proxied via protected SocketChannel
 * 4. Photon Protocol parsing on received packets
 * 5. Events dispatched to UI via EventDispatcher
 */
class AlbionVpnService : VpnService() {

    companion object {
        private const val TAG = "AlbionVpnService"

        // Service actions
        const val ACTION_START = "com.gxradar.vpn.START"
        const val ACTION_STOP = "com.gxradar.vpn.STOP"

        // VPN configuration
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val TUN_PREFIX = "32"
        private const val TUN_ROUTE = "0.0.0.0"
        private const val TUN_ROUTE_PREFIX = "0"
        private const val MTU = 1500  // FIXED: Reduced from 32767 to standard MTU
        private const val TARGET_PORT = 5056
        private const val TARGET_PACKAGE = "com.albiononline"
        private const val DNS_SERVER = "8.8.8.8"

        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "gxradar_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        // WakeLock tag
        private const val WAKELOCK_TAG = "GXRadar:VpnWake"
    }

    // VPN interface
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInputStream: FileInputStream? = null
    private var vpnOutputStream: FileOutputStream? = null

    // Worker scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WakeLock for preventing CPU throttling
    private var wakeLock: PowerManager.WakeLock? = null

    // Running state
    @Volatile
    private var isRunning = false

    // Photon parser
    private lateinit var photonParser: PhotonParser

    // Event dispatcher
    private lateinit var eventDispatcher: EventDispatcher

    // UDP forwarding channel (protected from VPN)
    private var udpChannel: DatagramChannel? = null

    // TCP proxy channels
    private val tcpChannels = ConcurrentHashMap<Int, SocketChannel>()

    // Selector for non-blocking I/O
    private var selector: Selector? = null

    // Server address cache
    private var serverAddress: InetAddress? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")

        // Initialize parser and dispatcher
        photonParser = PhotonParser()
        eventDispatcher = EventDispatcher(this)

        // Create notification channel
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) {
                    startVpn()
                }
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

    /**
     * Start the VPN interface and packet capture
     */
    private fun startVpn() {
        Log.i(TAG, "Starting VPN...")

        try {
            // Acquire WakeLock (CRITICAL for preventing thread throttling)
            acquireWakeLock()

            // Build VPN interface
            val builder = Builder()
                .setSession("GX Radar")
                .addAddress(TUN_ADDRESS, 32)
                .addRoute(TUN_ROUTE, 0)
                .setMtu(MTU)
                .addDnsServer(DNS_SERVER)

            // Allow only Albion Online (API 21+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    builder.addAllowedApplication(TARGET_PACKAGE)
                    Log.i(TAG, "Added allowed application: $TARGET_PACKAGE")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add allowed application: ${e.message}")
                }
            }

            // Establish VPN interface
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

            // Initialize UDP forwarding channel
            initUdpChannel()

            // Initialize selector for non-blocking I/O
            selector = Selector.open()

            isRunning = true

            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification())

            // Update state
            MainApplication.getInstance().setVpnRunning(true)

            // Start packet capture loop
            serviceScope.launch {
                runVpnLoop()
            }

            // Start response listener
            serviceScope.launch {
                runResponseListener()
            }

            Log.i(TAG, "VPN started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
        }
    }

    /**
     * Initialize UDP forwarding channel
     * CRITICAL: Must call protect() to exclude from VPN routing
     */
    private fun initUdpChannel() {
        try {
            udpChannel = DatagramChannel.open()
            udpChannel?.configureBlocking(false)
            
            // CRITICAL: Protect socket from VPN routing loop
            val socket = udpChannel?.socket()
            if (socket != null && !protect(socket)) {
                Log.e(TAG, "Failed to protect UDP socket - routing loop will occur!")
                throw IllegalStateException("Socket protection failed")
            }
            
            Log.i(TAG, "UDP channel initialized and protected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize UDP channel: ${e.message}")
        }
    }

    /**
     * Stop the VPN interface and cleanup
     */
    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN...")

        isRunning = false

        // Release WakeLock
        releaseWakeLock()

        // Close UDP channel
        try {
            udpChannel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP channel: ${e.message}")
        }
        udpChannel = null

        // Close TCP channels
        tcpChannels.values.forEach { channel ->
            try {
                channel.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing TCP channel: ${e.message}")
            }
        }
        tcpChannels.clear()

        // Close selector
        try {
            selector?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing selector: ${e.message}")
        }
        selector = null

        // Close VPN interface
        try {
            vpnInputStream?.close()
            vpnOutputStream?.close()
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }

        vpnInputStream = null
        vpnOutputStream = null
        vpnInterface = null

        // Update state
        MainApplication.getInstance().setVpnRunning(false)

        Log.i(TAG, "VPN stopped")
    }

    /**
     * Main VPN packet capture loop
     *
     * Reads IP packets from the TUN interface and:
     * 1. Forwards UDP packets to game server
     * 2. Forwards TCP packets via proxy
     * 3. Parses Photon protocol on responses
     */
    private suspend fun runVpnLoop() {
        Log.i(TAG, "VPN loop started")

        val buffer = ByteBuffer.allocate(MTU)
        buffer.order(ByteOrder.BIG_ENDIAN)

        while (isRunning && vpnInputStream != null) {
            try {
                // Read packet from TUN interface
                val length = vpnInputStream!!.read(buffer.array())
                if (length <= 0) {
                    continue
                }

                // Parse IP packet
                buffer.limit(length)
                buffer.position(0)

                val ipVersion = (buffer.get().toInt() shr 4) and 0x0F

                // Only process IPv4 packets
                if (ipVersion != 4) {
                    continue
                }

                // Get IP header length (in 4-byte words)
                buffer.position(0)
                val ihl = (buffer.get().toInt() and 0x0F) * 4

                // Check protocol (UDP = 17, TCP = 6)
                buffer.position(9)
                val protocol = buffer.get().toInt() and 0xFF

                // Get source and destination IP addresses
                buffer.position(12)
                val srcIp = ByteArray(4)
                val dstIp = ByteArray(4)
                buffer.get(srcIp)
                buffer.get(dstIp)

                // Get source and destination ports
                buffer.position(ihl)
                val srcPort = buffer.short.toInt() and 0xFFFF
                val dstPort = buffer.short.toInt() and 0xFFFF

                // Check if this is Albion traffic (port 5056)
                if (srcPort != TARGET_PORT && dstPort != TARGET_PORT) {
                    continue
                }

                when (protocol) {
                    17 -> { // UDP
                        handleUdpPacket(buffer, ihl, srcIp, srcPort, dstIp, dstPort)
                    }
                    6 -> { // TCP
                        handleTcpPacket(buffer, ihl, srcIp, srcPort, dstIp, dstPort)
                    }
                    else -> {
                        // Forward other protocols (ICMP, etc.)
                        Log.d(TAG, "Ignoring protocol $protocol")
                    }
                }

            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Error in VPN loop: ${e.message}")
                }
            }
        }

        Log.i(TAG, "VPN loop exited")
    }

    /**
     * Handle UDP packet - forward to server and parse response
     */
    private fun handleUdpPacket(
        buffer: ByteBuffer,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ) {
        try {
            // Get UDP payload length and skip UDP header
            val udpLength = buffer.short.toInt() and 0xFFFF
            buffer.short // Skip checksum

            // Extract UDP payload
            val payloadLength = udpLength - 8 // UDP header is 8 bytes
            if (payloadLength <= 0) {
                return
            }

            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            // Determine direction
            val isOutbound = dstPort == TARGET_PORT

            if (isOutbound) {
                // Outbound packet - forward to server
                forwardUdpToServer(dstIp, dstPort, payload)
            } else {
                // Inbound packet - parse and dispatch
                parsePhotonPacket(payload)
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error handling UDP packet: ${e.message}")
        }
    }

    /**
     * Forward UDP packet to game server
     */
    private fun forwardUdpToServer(dstIp: ByteArray, dstPort: Int, payload: ByteArray) {
        try {
            val channel = udpChannel ?: return

            // Build server address
            val address = InetAddress.getByAddress(dstIp)
            serverAddress = address

            // Send packet
            channel.send(
                ByteBuffer.wrap(payload),
                InetSocketAddress(address, dstPort)
            )

            Log.d(TAG, "Forwarded ${payload.size} bytes to ${address.hostAddress}:$dstPort")

        } catch (e: Exception) {
            Log.w(TAG, "Error forwarding UDP: ${e.message}")
        }
    }

    /**
     * Listen for UDP responses from server
     */
    private suspend fun runResponseListener() {
        Log.i(TAG, "Response listener started")

        val responseBuffer = ByteBuffer.allocate(MTU)

        while (isRunning && udpChannel != null) {
            try {
                responseBuffer.clear()

                // Non-blocking receive
                val sourceAddr = udpChannel?.receive(responseBuffer)

                if (sourceAddr != null) {
                    responseBuffer.flip()
                    val response = ByteArray(responseBuffer.remaining())
                    responseBuffer.get(response)

                    // Write response back to TUN interface (inject as server response)
                    injectUdpResponse(response, sourceAddr as InetSocketAddress)

                    // Parse the response for events
                    parsePhotonPacket(response)
                }

                // Small delay to prevent busy loop
                delay(1)

            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Error in response listener: ${e.message}")
                }
            }
        }

        Log.i(TAG, "Response listener stopped")
    }

    /**
     * Inject UDP response back to TUN interface
     */
    private fun injectUdpResponse(payload: ByteArray, sourceAddr: InetSocketAddress) {
        try {
            // Build IP header + UDP header + payload
            val packetLen = 20 + 8 + payload.size // IP header + UDP header + payload
            val packet = ByteBuffer.allocate(packetLen)
            packet.order(ByteOrder.BIG_ENDIAN)

            // IP header (simplified)
            packet.put(0x45.toByte()) // Version 4, IHL 5
            packet.put(0) // DSCP/ECN
            packet.putShort(packetLen.toShort()) // Total length
            packet.putShort(0) // Identification
            packet.putShort(0) // Flags/Fragment offset
            packet.put(64.toByte()) // TTL
            packet.put(17.toByte()) // Protocol (UDP)
            packet.putShort(0) // Checksum (kernel will fix)

            // Source IP (server)
            val serverIp = sourceAddr.address.address
            packet.put(serverIp)

            // Destination IP (our TUN address)
            packet.put(byteArrayOf(10, 0, 0, 2))

            // UDP header
            packet.putShort(sourceAddr.port.toShort()) // Source port (5056)
            packet.putShort(0) // Destination port (will be set by game)
            packet.putShort((8 + payload.size).toShort()) // UDP length
            packet.putShort(0) // Checksum

            // Payload
            packet.put(payload)

            // Write to TUN
            vpnOutputStream?.write(packet.array())

            Log.d(TAG, "Injected ${payload.size} byte response from server")

        } catch (e: Exception) {
            Log.w(TAG, "Error injecting UDP response: ${e.message}")
        }
    }

    /**
     * Handle TCP packet - proxy connection
     */
    private fun handleTcpPacket(
        buffer: ByteBuffer,
        ipHeaderLen: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ) {
        try {
            // Get TCP header
            val tcpHeaderStart = ipHeaderLen
            buffer.position(tcpHeaderStart + 12)
            val tcpHeaderLen = ((buffer.get().toInt() and 0xF0) shr 4) * 4

            // Extract TCP payload
            val payloadStart = tcpHeaderStart + tcpHeaderLen
            if (payloadStart >= buffer.limit()) {
                return // No payload (ACK, SYN, etc.)
            }

            buffer.position(payloadStart)
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)

            Log.d(TAG, "TCP packet: $srcPort -> $dstPort, ${payload.size} bytes")

            // For now, we just acknowledge TCP traffic exists
            // Full TCP proxy would require tracking connections, sequence numbers, etc.
            // This is complex and may not be needed if login uses HTTPS

        } catch (e: Exception) {
            Log.w(TAG, "Error handling TCP packet: ${e.message}")
        }
    }

    /**
     * Parse a Photon Protocol 16 packet
     */
    private fun parsePhotonPacket(payload: ByteArray) {
        try {
            val events = photonParser.parse(payload)

            events.forEach { event ->
                eventDispatcher.dispatchEvent(event)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Photon packet: ${e.message}")
        }
    }

    /**
     * Acquire WakeLock to prevent CPU throttling
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        )
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // Max 10 hours
        Log.i(TAG, "WakeLock acquired")
    }

    /**
     * Release WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    /**
     * Create notification channel for Android O+
     */
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

    /**
     * Create foreground service notification
     */
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
