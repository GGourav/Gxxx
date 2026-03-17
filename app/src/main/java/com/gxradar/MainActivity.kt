package com.gxradar

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gxradar.overlay.RadarOverlayService
import com.gxradar.vpn.AlbionVpnService
import kotlinx.coroutines.launch

/**
 * Main Activity for GX Radar.
 *
 * Handles:
 * - Permission request flow (Overlay, VPN, Notifications)
 * - VPN service start/stop controls
 * - Overlay service lifecycle
 * - UI state updates
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 0x0F
    }

    // UI Elements
    private lateinit var btnStartVpn: Button
    private lateinit var btnStopVpn: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvEntityCount: TextView

    // VPN service intent
    private var vpnIntent: Intent? = null

    // Overlay permission launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkPermissionsAndSetup()
    }

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "VPN permission granted")
            startVpnService()
        } else {
            Log.w(TAG, "VPN permission denied")
            tvStatus.text = "Status: VPN permission denied"
            Toast.makeText(this, "VPN permission is required for packet capture", Toast.LENGTH_LONG).show()
        }
    }

    // Notification permission launcher (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.w(TAG, "Notification permission denied - notifications will not show")
        }
        checkPermissionsAndSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        initViews()

        // Setup click listeners
        setupClickListeners()

        // Check and request permissions
        checkPermissionsAndSetup()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    /**
     * Initialize view references
     */
    private fun initViews() {
        btnStartVpn = findViewById(R.id.btnStartVpn)
        btnStopVpn = findViewById(R.id.btnStopVpn)
        tvStatus = findViewById(R.id.tvStatus)
        tvEntityCount = findViewById(R.id.tvEntityCount)
    }

    /**
     * Setup button click listeners
     */
    private fun setupClickListeners() {
        btnStartVpn.setOnClickListener {
            onStartVpnClicked()
        }

        btnStopVpn.setOnClickListener {
            onStopVpnClicked()
        }
    }

    /**
     * Check all required permissions and request as needed
     */
    private fun checkPermissionsAndSetup() {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Requesting overlay permission")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // All permissions granted
        Log.i(TAG, "All permissions granted")
        updateUIState()
    }

    /**
     * Handle Start VPN button click
     */
    private fun onStartVpnClicked() {
        Log.i(TAG, "Start VPN clicked")

        // Check overlay permission first
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            checkPermissionsAndSetup()
            return
        }

        // Request VPN permission
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // VPN permission not yet granted
            Log.i(TAG, "Requesting VPN permission")
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // VPN permission already granted
            Log.i(TAG, "VPN permission already granted")
            startVpnService()
        }
    }

    /**
     * Handle Stop VPN button click
     */
    private fun onStopVpnClicked() {
        Log.i(TAG, "Stop VPN clicked")
        stopVpnService()
        stopOverlayService()
    }

    /**
     * Start the VPN service
     */
    private fun startVpnService() {
        Log.i(TAG, "Starting VPN service")

        // Start VPN service
        val intent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Start overlay service
        startOverlayService()

        // Update UI
        tvStatus.text = "Status: VPN Running"
        btnStartVpn.isEnabled = false
        btnStopVpn.isEnabled = true

        // Mark VPN as running in preferences
        MainApplication.getInstance().setVpnRunning(true)

        Toast.makeText(this, "GX Radar started", Toast.LENGTH_SHORT).show()
    }

    /**
     * Stop the VPN service
     */
    private fun stopVpnService() {
        Log.i(TAG, "Stopping VPN service")

        val intent = Intent(this, AlbionVpnService::class.java).apply {
            action = AlbionVpnService.ACTION_STOP
        }
        startService(intent)

        // Update UI
        tvStatus.text = "Status: Stopped"
        btnStartVpn.isEnabled = true
        btnStopVpn.isEnabled = false

        // Mark VPN as stopped in preferences
        MainApplication.getInstance().setVpnRunning(false)

        Toast.makeText(this, "GX Radar stopped", Toast.LENGTH_SHORT).show()
    }

    /**
     * Start the overlay service
     */
    private fun startOverlayService() {
        Log.i(TAG, "Starting overlay service")

        val intent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Stop the overlay service
     */
    private fun stopOverlayService() {
        Log.i(TAG, "Stopping overlay service")

        val intent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_STOP
        }
        startService(intent)
    }

    /**
     * Update UI state based on current VPN status
     */
    private fun updateUIState() {
        val isRunning = MainApplication.getInstance().isVpnRunning()

        if (isRunning) {
            tvStatus.text = "Status: VPN Running"
            btnStartVpn.isEnabled = false
            btnStopVpn.isEnabled = true
        } else {
            tvStatus.text = "Status: Stopped"
            btnStartVpn.isEnabled = Settings.canDrawOverlays(this)
            btnStopVpn.isEnabled = false
        }

        // Update entity count
        lifecycleScope.launch {
            updateEntityCount()
        }
    }

    /**
     * Update entity count display
     */
    private fun updateEntityCount() {
        // This would be updated from the entity manager
        // For now, just show a placeholder
        tvEntityCount.text = "Entities detected: 0"
    }
}
