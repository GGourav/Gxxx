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

/**
 * VPN Service for capturing Albion Online UDP traffic.
 *
 * This service:
 * - Creates a TUN interface to intercept network traffic
 * - Filters traffic to com.albiononline on port 5056
 * - Parses Photon Protocol 16 packets
 * - Dispatches events to the event system
 *
 * CRITICAL: WakeLock is required to prevent Android from throttling
 * the TUN read thread during gameplay.
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
        private const val MTU = 32767
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

            isRunning = true

            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification())

            // Update state
            MainApplication.getInstance().setVpnRunning(true)

            // Start packet capture loop
            serviceScope.launch {
                runVpnLoop()
            }

            Log.i(TAG, "VPN started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            stopVpn()
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
     * Reads IP packets from the TUN interface and processes UDP packets
     * destined for port 5056 (Albion Online Photon protocol).
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

                // Check protocol (UDP = 17)
                buffer.position(9)
                val protocol = buffer.get().toInt() and 0xFF
                if (protocol != 17) {
                    continue
                }

                // Get source and destination ports
                buffer.position(ihl)
                val srcPort = buffer.short.toInt() and 0xFFFF
                val dstPort = buffer.short.toInt() and 0xFFFF

                // Check if this is Albion traffic (port 5056)
                if (srcPort != TARGET_PORT && dstPort != TARGET_PORT) {
                    continue
                }

                // Get UDP payload length and skip UDP header
                val udpLength = buffer.short.toInt() and 0xFFFF
                buffer.short // Skip checksum

                // Extract UDP payload
                val payloadLength = udpLength - 8 // UDP header is 8 bytes
                if (payloadLength <= 0) {
                    continue
                }

                val payload = ByteArray(payloadLength)
                buffer.get(payload)

                // Parse Photon packet
                parsePhotonPacket(payload)

            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Error in VPN loop: ${e.message}")
                }
            }
        }

        Log.i(TAG, "VPN loop exited")
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
     *
     * CRITICAL: Without this WakeLock, Android will throttle or terminate
     * the TUN read thread during gameplay, causing the radar to stop.
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
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // Max 10 hours, will be released in stopVpn
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
