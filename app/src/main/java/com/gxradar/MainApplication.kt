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

class MainApplication : MultiDexApplication() {

    companion object {
        private const val TAG = "MainApplication"

        @Volatile
        private var instance: MainApplication? = null

        fun getInstance(): MainApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }

        const val PREFS_NAME = "gxradar_prefs"

        const val KEY_PLAYER_DOT = "playerDot"
        const val KEY_RADAR_X = "radarXBar"
        const val KEY_RADAR_Y = "radarYBar"
        const val KEY_RADAR_SIZE = "radarSizeWidthHeightBar"
        const val KEY_RADAR_SCALE = "radarScaleBar"
        const val KEY_RADAR_SHOW_CIRCLE = "radarShowCircle"

        const val KEY_HARVESTING_FIBER = "harvestingFiber"
        const val KEY_HARVESTING_HIDE = "harvestingHide"
        const val KEY_HARVESTING_ORE = "harvestingOre"
        const val KEY_HARVESTING_ROCK = "harvestingRock"
        const val KEY_HARVESTING_WOOD = "harvestingWood"

        const val KEY_MOB_HARVESTABLE = "mobHarvestable"
        const val KEY_MOB_ENEMY = "mobEnemy"
        const val KEY_MOB_BOSS = "mobBoss"
        const val KEY_MOB_MIST_BOSS = "mobMistBoss"

        const val KEY_CHEST = "chest"
        const val KEY_DUNGEON = "dungeon"
        const val KEY_MIST = "mist"

        const val KEY_VPN_RUNNING = "vpnRunningStatus"
        const val KEY_LOCAL_PLAYER_ID = "localPlayerId"
        const val KEY_LOCAL_PLAYER_X = "localPlayerX"
        const val KEY_LOCAL_PLAYER_Y = "localPlayerY"

        const val DEFAULT_RADAR_SIZE = 300
        const val DEFAULT_RADAR_SCALE = 50
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var sharedPreferences: SharedPreferences
        private set

    private var _idMapRepository: IdMapRepository? = null
    val idMapRepository: IdMapRepository
        get() = _idMapRepository ?: throw IllegalStateException("IdMapRepository not initialized")

    private var _mobsDatabase: MobsDatabase? = null
    val mobsDatabase: MobsDatabase
        get() = _mobsDatabase ?: throw IllegalStateException("MobsDatabase not initialized")

    private var _harvestablesDatabase: HarvestablesDatabase? = null
    val harvestablesDatabase: HarvestablesDatabase
        get() = _harvestablesDatabase ?: throw IllegalStateException("HarvestablesDatabase not initialized")

    private var _eventDispatcher: EventDispatcher? = null
    val eventDispatcher: EventDispatcher
        get() = _eventDispatcher ?: throw IllegalStateException("EventDispatcher not initialized")

    fun isEventDispatcherInitialized(): Boolean = _eventDispatcher != null

    @Volatile
    var databasesLoaded = false
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application onCreate started")

        try {
            // CRITICAL: Set instance FIRST before any other initialization
            instance = this
            Log.i(TAG, "Instance set")

            sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            initializeDefaultPreferences()

            // Initialize repositories
            _idMapRepository = IdMapRepository(this)
            _mobsDatabase = MobsDatabase(this)
            _harvestablesDatabase = HarvestablesDatabase(this)
            
            // Now it's safe to create EventDispatcher since instance is set
            _eventDispatcher = EventDispatcher(this)

            Log.i(TAG, "Basic initialization complete")

            // Load databases asynchronously
            applicationScope.launch {
                loadDatabases()
            }

            Log.i(TAG, "Application initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Application initialization failed", e)
        }
    }

    private suspend fun loadDatabases() {
        try {
            Log.i(TAG, "Loading databases...")

            idMapRepository.loadIdMap()
            Log.i(TAG, "ID map loaded")

            mobsDatabase.load()
            Log.i(TAG, "Mobs database loaded")

            harvestablesDatabase.load()
            Log.i(TAG, "Harvestables database loaded")

            eventDispatcher.setDatabases(mobsDatabase, harvestablesDatabase)
            Log.i(TAG, "EventDispatcher configured")

            databasesLoaded = mobsDatabase.isLoaded && harvestablesDatabase.isLoaded

            Log.i(TAG, "Databases loaded: mobs=${mobsDatabase.isLoaded}, harvestables=${harvestablesDatabase.isLoaded}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load databases", e)
            databasesLoaded = false
        }
    }

    private fun initializeDefaultPreferences() {
        try {
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

            listOf(
                KEY_HARVESTING_FIBER, KEY_HARVESTING_HIDE, KEY_HARVESTING_ORE,
                KEY_HARVESTING_ROCK, KEY_HARVESTING_WOOD,
                KEY_MOB_HARVESTABLE, KEY_MOB_ENEMY, KEY_MOB_BOSS, KEY_MOB_MIST_BOSS,
                KEY_CHEST, KEY_DUNGEON, KEY_MIST
            ).forEach { key ->
                if (!sharedPreferences.contains(key)) {
                    editor.putBoolean(key, true)
                }
            }

            editor.apply()
            Log.d(TAG, "Default preferences initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize preferences", e)
        }
    }

    fun getLocalPlayerId(): Int = sharedPreferences.getInt(KEY_LOCAL_PLAYER_ID, -1)
    fun setLocalPlayerId(id: Int) = sharedPreferences.edit().putInt(KEY_LOCAL_PLAYER_ID, id).apply()

    fun getLocalPlayerX(): Float = sharedPreferences.getFloat(KEY_LOCAL_PLAYER_X, 0f)
    fun setLocalPlayerX(x: Float) = sharedPreferences.edit().putFloat(KEY_LOCAL_PLAYER_X, x).apply()

    fun getLocalPlayerY(): Float = sharedPreferences.getFloat(KEY_LOCAL_PLAYER_Y, 0f)
    fun setLocalPlayerY(y: Float) = sharedPreferences.edit().putFloat(KEY_LOCAL_PLAYER_Y, y).apply()

    fun isVpnRunning(): Boolean = sharedPreferences.getBoolean(KEY_VPN_RUNNING, false)
    fun setVpnRunning(running: Boolean) = sharedPreferences.edit().putBoolean(KEY_VPN_RUNNING, running).apply()
}
