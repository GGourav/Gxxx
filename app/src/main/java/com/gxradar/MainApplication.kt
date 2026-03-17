package com.gxradar

import android.app.Application
import android.content.SharedPreferences
import androidx.multidex.MultiDexApplication
import com.gxradar.data.IdMapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main Application class for GX Radar.
 *
 * Handles:
 * - MultiDex initialization for large DEX file support
 * - SharedPreferences initialization with default values
 * - Global application-wide state management
 * - IdMapRepository initialization
 */
class MainApplication : MultiDexApplication() {

    // Application-scoped coroutine scope for background operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared preferences instance
    lateinit var sharedPreferences: SharedPreferences
        private set

    // ID Map repository for Photon protocol key mappings
    lateinit var idMapRepository: IdMapRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize default preferences (Fix for Bug 3 - playerDot defaults to false)
        initializeDefaultPreferences()

        // Initialize ID Map repository
        idMapRepository = IdMapRepository(this)
        applicationScope.launch {
            idMapRepository.loadIdMap()
        }

        // Store application instance for global access
        instance = this
    }

    /**
     * Initialize default SharedPreferences values.
     *
     * CRITICAL: This fixes Bug 3 where playerDot SharedPreference defaults to false
     * on fresh install, causing all player dots to be suppressed even when correctly parsed.
     *
     * Uses contains() guard so defaults are only written once on first run.
     */
    private fun initializeDefaultPreferences() {
        val editor = sharedPreferences.edit()

        // Fix Bug 3: playerDot defaults to true
        if (!sharedPreferences.contains(KEY_PLAYER_DOT)) {
            editor.putBoolean(KEY_PLAYER_DOT, true)
        }

        // Radar display defaults
        if (!sharedPreferences.contains(KEY_RADAR_SIZE)) {
            editor.putInt(KEY_RADAR_SIZE, DEFAULT_RADAR_SIZE)
        }
        if (!sharedPreferences.contains(KEY_RADAR_SCALE)) {
            editor.putInt(KEY_RADAR_SCALE, DEFAULT_RADAR_SCALE)
        }
        if (!sharedPreferences.contains(KEY_RADAR_SHOW_CIRCLE)) {
            editor.putBoolean(KEY_RADAR_SHOW_CIRCLE, true)
        }

        // Harvesting defaults - enable all resource types by default
        val harvestingKeys = listOf(
            KEY_HARVESTING_FIBER, KEY_HARVESTING_HIDE, KEY_HARVESTING_ORE,
            KEY_HARVESTING_ROCK, KEY_HARVESTING_WOOD, KEY_HARVESTING_FISHING
        )
        harvestingKeys.forEach { key ->
            if (!sharedPreferences.contains(key)) {
                editor.putBoolean(key, true)
            }
        }

        // Mob defaults
        val mobKeys = listOf(
            KEY_MOB_HARVESTABLE, KEY_MOB_SKINNABLE, KEY_MOB_ENEMY,
            KEY_MOB_BOSS, KEY_MOB_MIST_BOSS, KEY_MOB_OTHER
        )
        mobKeys.forEach { key ->
            if (!sharedPreferences.contains(key)) {
                editor.putBoolean(key, true)
            }
        }

        // Chest and dungeon defaults
        if (!sharedPreferences.contains(KEY_CHEST)) {
            editor.putBoolean(KEY_CHEST, true)
        }
        if (!sharedPreferences.contains(KEY_DUNGEON)) {
            editor.putBoolean(KEY_DUNGEON, true)
        }
        if (!sharedPreferences.contains(KEY_MIST)) {
            editor.putBoolean(KEY_MIST, true)
        }

        // Apply all defaults
        editor.apply()
    }

    /**
     * Get the current local player ID
     */
    fun getLocalPlayerId(): Int {
        return sharedPreferences.getInt(KEY_LOCAL_PLAYER_ID, -1)
    }

    /**
     * Set the current local player ID
     */
    fun setLocalPlayerId(id: Int) {
        sharedPreferences.edit().putInt(KEY_LOCAL_PLAYER_ID, id).apply()
    }

    /**
     * Get the current local player world X coordinate
     */
    fun getLocalPlayerX(): Float {
        return sharedPreferences.getFloat(KEY_LOCAL_PLAYER_X, 0f)
    }

    /**
     * Set the current local player world X coordinate
     */
    fun setLocalPlayerX(x: Float) {
        sharedPreferences.edit().putFloat(KEY_LOCAL_PLAYER_X, x).apply()
    }

    /**
     * Get the current local player world Y coordinate
     */
    fun getLocalPlayerY(): Float {
        return sharedPreferences.getFloat(KEY_LOCAL_PLAYER_Y, 0f)
    }

    /**
     * Set the current local player world Y coordinate
     */
    fun setLocalPlayerY(y: Float) {
        sharedPreferences.edit().putFloat(KEY_LOCAL_PLAYER_Y, y).apply()
    }

    /**
     * Check if VPN service is running
     */
    fun isVpnRunning(): Boolean {
        return sharedPreferences.getBoolean(KEY_VPN_RUNNING, false)
    }

    /**
     * Set VPN service running state
     */
    fun setVpnRunning(running: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_VPN_RUNNING, running).apply()
    }

    companion object {
        // Singleton instance
        @Volatile
        private var instance: MainApplication? = null

        fun getInstance(): MainApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }

        // SharedPreferences constants
        const val PREFS_NAME = "gxradar_prefs"

        // Player preferences
        const val KEY_PLAYER_DOT = "playerDot"
        const val KEY_PLAYER_NICKNAME = "playerNickname"
        const val KEY_PLAYER_HEALTH = "playerHealth"
        const val KEY_PLAYER_MOUNTED = "playerMounted"
        const val KEY_PLAYER_DISTANCE = "playerDistance"
        const val KEY_PLAYER_GUILD_NAME = "playerGuildName"
        const val KEY_PLAYER_SOUND = "playerSound"
        const val KEY_PLAYER_ALLIED_GUILDS = "playerAlliedGuilds"

        // Radar display preferences
        const val KEY_RADAR_X = "radarXBar"
        const val KEY_RADAR_Y = "radarYBar"
        const val KEY_RADAR_SIZE = "radarSizeWidthHeightBar"
        const val KEY_RADAR_SCALE = "radarScaleBar"
        const val KEY_RADAR_SHOW_CIRCLE = "radarShowCircle"
        const val KEY_RADAR_SHOW_SQUARE = "radarShowSquare"
        const val KEY_RADAR_SHOW_TOP_MOST = "radarShowTopMost"

        // Harvesting preferences
        const val KEY_HARVESTING_FIBER = "harvestingFiber"
        const val KEY_HARVESTING_HIDE = "harvestingHide"
        const val KEY_HARVESTING_ORE = "harvestingOre"
        const val KEY_HARVESTING_ROCK = "harvestingRock"
        const val KEY_HARVESTING_WOOD = "harvestingWood"
        const val KEY_HARVESTING_FISHING = "harvestingZoneFishing"
        const val KEY_HARVESTING_SIZE = "harvestingSize"
        const val KEY_HARVESTING_WIDTH_HEIGHT = "harvestingWidthHeightBar"

        // Mob preferences
        const val KEY_MOB_HARVESTABLE = "mobHarvestable"
        const val KEY_MOB_SKINNABLE = "mobSkinnable"
        const val KEY_MOB_ENEMY = "mobEnemy"
        const val KEY_MOB_BOSS = "mobBoss"
        const val KEY_MOB_MIST_BOSS = "mobMistBoss"
        const val KEY_MOB_OTHER = "mobOther"

        // Chest preferences
        const val KEY_CHEST = "chest"
        const val KEY_CHEST_STANDARD = "chestStandard"
        const val KEY_CHEST_UNCOMMON = "chestUncommon"
        const val KEY_CHEST_RARE = "chestRare"
        const val KEY_CHEST_LEGENDARY = "chestLegendary"

        // Dungeon preferences
        const val KEY_DUNGEON = "dungeon"
        const val KEY_DUNGEON_COMMON = "dungeonCommon"
        const val KEY_DUNGEON_UNCOMMON = "dungeonUncommon"
        const val KEY_DUNGEON_RARE = "dungeonRare"
        const val KEY_DUNGEON_EPIC = "dungeonEpic"
        const val KEY_DUNGEON_LEGENDARY = "dungeonLegendary"

        // Mist preferences
        const val KEY_MIST = "mist"
        const val KEY_MIST_PORTAL = "mistPortal"
        const val KEY_MIST_WISP = "mistWisp"

        // VPN state preferences
        const val KEY_VPN_RUNNING = "vpnRunningStatus"
        const val KEY_VPN_START_TIME = "vpnStartTime"
        const val KEY_LOCAL_PLAYER_ID = "localPlayerId"
        const val KEY_LOCAL_PLAYER_X = "localPlayerX"
        const val KEY_LOCAL_PLAYER_Y = "localPlayerY"

        // Auth preferences
        const val KEY_LOGIN = "login"
        const val KEY_LOGIN_DATE = "logindate"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "mToken"
        const val KEY_SAVE_PASSWORD = "savePasswordCheckBox"

        // Default values
        const val DEFAULT_RADAR_SIZE = 300
        const val DEFAULT_RADAR_SCALE = 50
    }
}
