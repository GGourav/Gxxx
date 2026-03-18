package com.gxradar

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.multidex.MultiDexApplication
import com.gxradar.data.IdMapRepository
import com.gxradar.data.MobsDatabase
import com.gxradar.data.HarvestablesDatabase
import com.gxradar.parser.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Main Application class for GX Radar.
 */
class MainApplication : MultiDexApplication() {

    companion object {
        private const val TAG = "MainApplication"

        @Volatile
        private var instance: MainApplication? = null

        fun getInstance(): MainApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }

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

        // Default values
        const val DEFAULT_RADAR_SIZE = 300
        const val DEFAULT_RADAR_SCALE = 50
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var sharedPreferences: SharedPreferences
        private set

    lateinit var idMapRepository: IdMapRepository
        private set

    lateinit var mobsDatabase: MobsDatabase
        private set

    lateinit var harvestablesDatabase: HarvestablesDatabase
        private set

    lateinit var eventDispatcher: EventDispatcher
        private set

    // Flag to track if eventDispatcher is initialized
    private var _eventDispatcherInitialized = false
    
    fun isEventDispatcherInitialized(): Boolean = _eventDispatcherInitialized

    @Volatile
    var databasesLoaded = false
        private set

    override fun onCreate() {
        super.onCreate()

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        initializeDefaultPreferences()

        idMapRepository = IdMapRepository(this)
        mobsDatabase = MobsDatabase(this)
        harvestablesDatabase = HarvestablesDatabase(this)
        eventDispatcher = EventDispatcher(this)
        _eventDispatcherInitialized = true

        instance = this

        applicationScope.launch {
            loadDatabases()
        }

        Log.i(TAG, "Application initialized")
    }

    private suspend fun loadDatabases() {
        Log.i(TAG, "Loading databases...")

        idMapRepository.loadIdMap()
        mobsDatabase.load()
        harvestablesDatabase.load()
        eventDispatcher.setDatabases(mobsDatabase, harvestablesDatabase)

        databasesLoaded = mobsDatabase.isLoaded && harvestablesDatabase.isLoaded

        Log.i(TAG, "Databases loaded: mobs=${mobsDatabase.isLoaded}, harvestables=${harvestablesDatabase.isLoaded}")
    }

    private fun initializeDefaultPreferences() {
        val editor = sharedPreferences.edit()

        if (!sharedPreferences.contains(KEY_PLAYER_DOT)) {
            editor.putBoolean(KEY_PLAYER_DOT, true)
        }

        if (!sharedPreferences.contains(KEY_RADAR_SIZE)) {
            editor.putInt(KEY_RADAR_SIZE, DEFAULT_RADAR_SIZE)
        }
        if (!sharedPreferences.contains(KEY_RADAR_SCALE)) {
            editor.putInt(KEY_RADAR_SCALE, DEFAULT_RADAR_SCALE)
        }
        if (!sharedPreferences.contains(KEY_RADAR_SHOW_CIRCLE)) {
            editor.putBoolean(KEY_RADAR_SHOW_CIRCLE, true)
        }

        val harvestingKeys = listOf(
            KEY_HARVESTING_FIBER, KEY_HARVESTING_HIDE, KEY_HARVESTING_ORE,
            KEY_HARVESTING_ROCK, KEY_HARVESTING_WOOD
        )
        harvestingKeys.forEach { key ->
            if (!sharedPreferences.contains(key)) {
                editor.putBoolean(key, true)
            }
        }

        val mobKeys = listOf(
            KEY_MOB_HARVESTABLE, KEY_MOB_SKINNABLE, KEY_MOB_ENEMY,
            KEY_MOB_BOSS, KEY_MOB_MIST_BOSS, KEY_MOB_OTHER
        )
        mobKeys.forEach { key ->
            if (!sharedPreferences.contains(key)) {
                editor.putBoolean(key, true)
            }
        }

        if (!sharedPreferences.contains(KEY_CHEST)) {
            editor.putBoolean(KEY_CHEST, true)
        }
        if (!sharedPreferences.contains(KEY_DUNGEON)) {
            editor.putBoolean(KEY_DUNGEON, true)
        }
        if (!sharedPreferences.contains(KEY_MIST)) {
            editor.putBoolean(KEY_MIST, true)
        }

        editor.apply()
    }

    fun getLocalPlayerId(): Int {
        return sharedPreferences.getInt(KEY_LOCAL_PLAYER_ID, -1)
    }

    fun setLocalPlayerId(id: Int) {
        sharedPreferences.edit().putInt(KEY_LOCAL_PLAYER_ID, id).apply()
    }

    fun getLocalPlayerX(): Float {
        return sharedPreferences.getFloat(KEY_LOCAL_PLAYER_X, 0f)
    }

    fun setLocalPlayerX(x: Float) {
        sharedPreferences.edit().putFloat(KEY_LOCAL_PLAYER_X, x).apply()
    }

    fun getLocalPlayerY(): Float {
        return sharedPreferences.getFloat(KEY_LOCAL_PLAYER_Y, 0f)
    }

    fun setLocalPlayerY(y: Float) {
        sharedPreferences.edit().putFloat(KEY_LOCAL_PLAYER_Y, y).apply()
    }

    fun isVpnRunning(): Boolean {
        return sharedPreferences.getBoolean(KEY_VPN_RUNNING, false)
    }

    fun setVpnRunning(running: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_VPN_RUNNING, running).apply()
    }
}
