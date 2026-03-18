package com.gxradar.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.gxradar.MainApplication
import com.gxradar.R
import com.gxradar.model.RadarEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest

/**
 * Radar Overlay Service
 *
 * Manages the floating window overlay that displays the radar.
 * Uses WindowManager to create a transparent overlay on top of other apps.
 *
 * Features:
 * - Foreground service for background operation
 * - WindowManager overlay configuration
 * - Reactive entity updates from EventDispatcher
 * - 30 FPS render loop
 */
class RadarOverlayService : LifecycleService() {

    companion object {
        private const val TAG = "RadarOverlayService"

        // Service actions
        const val ACTION_START = "com.gxradar.overlay.START"
        const val ACTION_STOP = "com.gxradar.overlay.STOP"

        // Notification
        private const val NOTIFICATION_CHANNEL_ID = "gxradar_overlay_channel"
        private const val NOTIFICATION_ID = 1002
    }

    // Window manager
    private lateinit var windowManager: WindowManager

    // Overlay views
    private var overlayView: View? = null
    private var radarSurfaceView: RadarSurfaceView? = null

    // Layout params
    private lateinit var layoutParams: WindowManager.LayoutParams

    // Render job
    private var renderJob: Job? = null

    // Entity observation job
    private var entityObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Overlay service created")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (overlayView == null) {
                    startOverlay()
                }
            }
            ACTION_STOP -> {
                stopOverlay()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "Overlay service destroyed")
        stopOverlay()
        super.onDestroy()
    }

    /**
     * Start the overlay
     */
    private fun startOverlay() {
        Log.i(TAG, "Starting overlay...")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Create overlay view
        createOverlayView()

        // Start render loop
        startRenderLoop()

        // Start observing entities from EventDispatcher
        startEntityObserver()

        Log.i(TAG, "Overlay started")
    }

    /**
     * Stop the overlay
     */
    private fun stopOverlay() {
        Log.i(TAG, "Stopping overlay...")

        // Cancel jobs
        renderJob?.cancel()
        entityObserverJob?.cancel()
        renderJob = null
        entityObserverJob = null

        // Remove overlay view
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
        }
        overlayView = null
        radarSurfaceView = null

        Log.i(TAG, "Overlay stopped")
    }

    /**
     * Create the overlay view
     */
    private fun createOverlayView() {
        // Get radar size from preferences
        val prefs = MainApplication.getInstance().sharedPreferences
        val radarSize = prefs.getInt(MainApplication.KEY_RADAR_SIZE, MainApplication.DEFAULT_RADAR_SIZE)

        // Initialize layout params
        layoutParams = WindowManager.LayoutParams(
            radarSize,
            radarSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(MainApplication.KEY_RADAR_X, 100)
            y = prefs.getInt(MainApplication.KEY_RADAR_Y, 100)
        }

        // Create surface view for radar rendering
        radarSurfaceView = RadarSurfaceView(this)

        // Add view to window
        try {
            windowManager.addView(radarSurfaceView, layoutParams)
            overlayView = radarSurfaceView
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}")
        }
    }

    /**
     * Start the render loop (30 FPS)
     */
    private fun startRenderLoop() {
        renderJob = lifecycleScope.launch {
            while (isActive) {
                radarSurfaceView?.requestRender()
                delay(33) // ~30 FPS
            }
        }
    }

    /**
     * Start observing entities from EventDispatcher
     */
    private fun startEntityObserver() {
        entityObserverJob = lifecycleScope.launch {
            // Wait for EventDispatcher to be available
            while (!::MainApplication.getInstance().eventDispatcher.isInitialized) {
                delay(100)
            }

            val eventDispatcher = MainApplication.getInstance().eventDispatcher

            // Observe entity flow
            eventDispatcher.entities.collectLatest { entityMap ->
                withContext(Dispatchers.Main) {
                    // Update radar view with new entities
                    radarSurfaceView?.updateEntities(entityMap)

                    // Update local player position
                    val localX = MainApplication.getInstance().getLocalPlayerX()
                    val localY = MainApplication.getInstance().getLocalPlayerY()
                    radarSurfaceView?.updateLocalPosition(localX, localY)

                    // Log entity count periodically
                    if (entityMap.isNotEmpty() && entityMap.size % 10 == 0) {
                        Log.d(TAG, "Entities updated: ${entityMap.size}")
                    }
                }
            }
        }

        // Also start periodic position update (fallback)
        lifecycleScope.launch {
            while (isActive) {
                delay(100)
                updateLocalPosition()
            }
        }
    }

    /**
     * Update local player position from MainApplication
     */
    private suspend fun updateLocalPosition() {
        withContext(Dispatchers.Main) {
            val localX = MainApplication.getInstance().getLocalPlayerX()
            val localY = MainApplication.getInstance().getLocalPlayerY()
            radarSurfaceView?.updateLocalPosition(localX, localY)
        }
    }

    /**
     * Update entities in the radar view (manual update)
     */
    fun updateRadarEntities(entities: Map<Int, RadarEntity>) {
        radarSurfaceView?.updateEntities(entities)
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GX Radar Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Overlay display service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create foreground notification
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
                .setContentText("Radar overlay active")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GX Radar")
                .setContentText("Radar overlay active")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}
