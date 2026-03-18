package com.gxradar.parser

import android.content.Context
import android.util.Log
import com.gxradar.MainApplication
import com.gxradar.data.MobsDatabase
import com.gxradar.data.HarvestablesDatabase
import com.gxradar.logger.DiscoveryLogger
import com.gxradar.model.EntityType
import com.gxradar.model.RadarEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event Dispatcher for Photon messages.
 *
 * Handles all message types:
 * - Events (0x04): Entity updates, movements, spawns
 * - OperationRequests (0x02): Local player movement
 * - OperationResponses (0x03): Map changes, join data
 *
 * Routes events by name string (stable across patches) rather than
 * integer codes (which shift with every Albion patch).
 */
class EventDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "EventDispatcher"

        // Parameter key for event type code (key 252 = 0xFC)
        private const val EVENT_TYPE_KEY = 252

        // Parameter key for operation code
        private const val OP_CODE_KEY = 253

        // Event codes (from OpenRadar EventCodes.js)
        const val EVENT_LEAVE = 1
        const val EVENT_JOIN_FINISHED = 2
        const val EVENT_MOVE = 3
        const val EVENT_NEW_CHARACTER = 29
        const val EVENT_NEW_SIMPLE_HARVESTABLE_LIST = 39
        const val EVENT_NEW_HARVESTABLE_OBJECT = 40
        const val EVENT_HARVESTABLE_CHANGE_STATE = 46
        const val EVENT_NEW_MOB = 123

        // Operation codes
        const val OP_JOIN_FINISHED = 2
        const val OP_CHANGE_CLUSTER = 35
        const val OP_MOVE = 21
    }

    // Discovery logger for unknown events
    private val discoveryLogger = DiscoveryLogger(context)

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Event code to name mapping (from IdMapRepository)
    private val eventCodeNames = mutableMapOf<Int, String>()

    // Entity map (objectId -> RadarEntity)
    private val _entities = MutableStateFlow<Map<Int, RadarEntity>>(emptyMap())
    val entities: StateFlow<Map<Int, RadarEntity>> = _entities.asStateFlow()

    // Internal entity storage
    private val entityMap = java.util.concurrent.ConcurrentHashMap<Int, RadarEntity>()

    // Databases
    private var mobsDatabase: MobsDatabase? = null
    private var harvestablesDatabase: HarvestablesDatabase? = null

    // Local player state
    private var localPlayerId: Int = -1
    private var localPlayerX: Float = 0f
    private var localPlayerY: Float = 0f
    private var currentMapId: Int = -1

    init {
        initializeEventCodes()
    }

    /**
     * Set databases for entity identification
     */
    fun setDatabases(mobsDb: MobsDatabase?, harvestablesDb: HarvestablesDatabase?) {
        mobsDatabase = mobsDb
        harvestablesDatabase = harvestablesDb
        Log.i(TAG, "Databases set: mobs=${mobsDb?.isLoaded}, harvestables=${harvestablesDb?.isLoaded}")
    }

    /**
     * Initialize event code mappings from IdMapRepository
     */
    private fun initializeEventCodes() {
        val idMap = MainApplication.getInstance().idMapRepository
        idMap.getAllEventCodes().forEach { (code, name) ->
            eventCodeNames[code] = name
        }
        Log.i(TAG, "Event codes loaded: ${eventCodeNames.size}")
    }

    /**
     * Dispatch a parsed Photon message
     */
    fun dispatchEvent(message: PhotonParser.PhotonMessage) {
        scope.launch {
            try {
                when (message) {
                    is PhotonParser.PhotonEvent -> handleEvent(message)
                    is PhotonParser.PhotonRequest -> handleRequest(message)
                    is PhotonParser.PhotonResponse -> handleResponse(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching message: ${e.message}")
            }
        }
    }

    // ==================== EVENT HANDLERS ====================

    /**
     * Handle Event message
     */
    private fun handleEvent(event: PhotonParser.PhotonEvent) {
        val eventCode = event.eventCode
        val params = event.params

        // Log event for debugging
        Log.d(TAG, "Event $eventCode: ${eventCodeNames[eventCode] ?: "unknown"}")

        when (eventCode) {
            EVENT_LEAVE -> handleLeave(params)
            EVENT_MOVE -> handleMove(params)
            EVENT_NEW_CHARACTER -> handleNewCharacter(params)
            EVENT_NEW_SIMPLE_HARVESTABLE_LIST -> handleNewHarvestableList(params)
            EVENT_NEW_HARVESTABLE_OBJECT -> handleNewHarvestableObject(params)
            EVENT_HARVESTABLE_CHANGE_STATE -> handleHarvestableChangeState(params)
            EVENT_NEW_MOB -> handleNewMob(params)
            else -> {
                // Unknown event - log for discovery
                val eventName = eventCodeNames[eventCode]
                if (eventName != null) {
                    discoveryLogger.logDiscovery(eventName, eventCode, params)
                } else {
                    discoveryLogger.logUnknownEvent(eventCode, params)
                }
            }
        }

        // Update entity flow
        updateEntityFlow()
    }

    /**
     * Handle OperationRequest (client -> server)
     */
    private fun handleRequest(request: PhotonParser.PhotonRequest) {
        val opCode = request.operationCode
        val params = request.params

        Log.d(TAG, "Request $opCode")

        when (opCode) {
            OP_MOVE -> {
                // Local player movement
                val position = request.getPosition()
                if (position != null) {
                    localPlayerX = position.first
                    localPlayerY = position.second
                    MainApplication.getInstance().setLocalPlayerX(localPlayerX)
                    MainApplication.getInstance().setLocalPlayerY(localPlayerY)
                    Log.d(TAG, "Local player move: ($localPlayerX, $localPlayerY)")
                }
            }
        }
    }

    /**
     * Handle OperationResponse (server -> client)
     */
    private fun handleResponse(response: PhotonParser.PhotonResponse) {
        val opCode = response.operationCode
        val params = response.params

        Log.d(TAG, "Response $opCode: return=${response.returnCode}")

        when (opCode) {
            OP_JOIN_FINISHED -> handleJoinFinished(response)
            OP_CHANGE_CLUSTER -> handleChangeCluster(response)
        }

        // Update entity flow
        updateEntityFlow()
    }

    // ==================== SPECIFIC HANDLERS ====================

    /**
     * Handle JoinFinished (local player enters zone)
     */
    private fun handleJoinFinished(response: PhotonParser.PhotonResponse) {
        val params = response.params

        // Get local player ID
        val idMap = MainApplication.getInstance().idMapRepository
        val localIdKey = idMap.getJoinFinishedLocalObjectIdKey()
        val objectId = (params[localIdKey] as? Number)?.toInt() ?: return

        // Get position
        val position = response.getPosition()
        if (position != null) {
            localPlayerX = position.first
            localPlayerY = position.second
        } else {
            // Fallback: scan for coordinates
            val coords = findCoordinates(params)
            if (coords != null) {
                localPlayerX = coords.first
                localPlayerY = coords.second
            }
        }

        localPlayerId = objectId
        MainApplication.getInstance().setLocalPlayerId(objectId)
        MainApplication.getInstance().setLocalPlayerX(localPlayerX)
        MainApplication.getInstance().setLocalPlayerY(localPlayerY)

        // Clear entities on zone change
        entityMap.clear()

        Log.i(TAG, "JoinFinished: localId=$objectId, pos=($localPlayerX, $localPlayerY)")
    }

    /**
     * Handle ChangeCluster (map change)
     */
    private fun handleChangeCluster(response: PhotonParser.PhotonResponse) {
        val mapId = response.getMapId() ?: return

        if (mapId != currentMapId) {
            Log.i(TAG, "Map changed: $currentMapId -> $mapId")
            currentMapId = mapId
            entityMap.clear()
        }
    }

    /**
     * Handle Leave event
     */
    private fun handleLeave(params: Map<Int, Any?>) {
        val objectId = (params[0] as? Number)?.toInt() ?: return
        entityMap.remove(objectId)
        Log.d(TAG, "Entity left: $objectId")
    }

    /**
     * Handle Move event
     */
    private fun handleMove(params: Map<Int, Any?>) {
        val objectId = (params[0] as? Number)?.toInt() ?: return
        val posX = (params[4] as? Number)?.toFloat()
        val posY = (params[5] as? Number)?.toFloat()

        val entity = entityMap[objectId] ?: return

        if (posX != null && posY != null) {
            entityMap[objectId] = entity.copy(worldX = posX, worldY = posY)
        }
    }

    /**
     * Handle NewCharacter (other players)
     */
    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        // Skip local player
        if (objectId == localPlayerId) return

        // Get position
        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
        var posY = (params[idMap.getPosYKey()] as? Number)?.toFloat()

        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX == null || posY == null) return

        // Get player info
        val name = (params[idMap.getPlayerNameKey()] as? String) ?: ""
        val guild = (params[idMap.getPlayerGuildKey()] as? String) ?: ""
        val alliance = (params[idMap.getPlayerAllianceKey()] as? String) ?: ""
        val healthPercent = (params[idMap.getPlayerHealthKey()] as? Number)?.toFloat() ?: 1.0f

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.PLAYER,
            worldX = posX,
            worldY = posY,
            typeName = "PLAYER",
            tier = 0,
            enchant = 0,
            name = name,
            guild = guild,
            alliance = alliance,
            healthPercent = healthPercent
        )

        entityMap[objectId] = entity
        Log.d(TAG, "New player: $name at ($posX, $posY)")
    }

    /**
     * Handle NewSimpleHarvestableObjectList (batch resource spawn)
     */
    private fun handleNewHarvestableList(params: Map<Int, Any?>) {
        // Event 38: Batch spawn of resources
        // Structure: params[0] = IDs array, params[1] = types array, params[2] = tiers array, etc.
        
        val ids = (params[0] as? List<*>) ?: return
        val types = (params[1] as? List<*>) ?: return
        val tiers = (params[2] as? List<*>) ?: return
        val positions = (params[3] as? List<*>) ?: return
        val sizes = (params[4] as? List<*>) ?: return

        if (ids.size != types.size || ids.size != tiers.size) {
            Log.w(TAG, "Harvestable list size mismatch")
            return
        }

        Log.d(TAG, "Harvestable batch: ${ids.size} resources")

        for (i in ids.indices) {
            val id = (ids[i] as? Number)?.toInt() ?: continue
            val type = (types[i] as? Number)?.toInt() ?: continue
            val tier = (tiers[i] as? Number)?.toInt() ?: continue
            val size = (sizes[i] as? Number)?.toInt() ?: 0

            // Position is interleaved: [x0, y0, x1, y1, ...]
            val posIndex = i * 2
            val posX = if (posIndex < positions.size) (positions[posIndex] as? Number)?.toFloat() else null
            val posY = if (posIndex + 1 < positions.size) (positions[posIndex + 1] as? Number)?.toFloat() else null

            if (posX != null && posY != null) {
                addHarvestable(id, type, tier, posX, posY, 0, size) // Enchant 0 for batch spawn
            }
        }
    }

    /**
     * Handle NewHarvestableObject (individual resource spawn)
     */
    private fun handleNewHarvestableObject(params: Map<Int, Any?>) {
        // Event 40: Individual resource spawn
        val id = (params[0] as? Number)?.toInt() ?: return
        val type = (params[5] as? Number)?.toInt() ?: return  // typeNumber
        val tier = (params[7] as? Number)?.toInt() ?: return
        val location = (params[8] as? List<*>)
        val enchant = (params[11] as? Number)?.toInt() ?: 0
        val size = (params[10] as? Number)?.toInt() ?: 0
        val mobileTypeId = (params[6] as? Number)?.toInt() // For living resources

        // Safe extraction of position
        var posX: Float? = null
        var posY: Float? = null
        
        if (location != null && location.size >= 2) {
            posX = (location[0] as? Number)?.toFloat()
            posY = (location[1] as? Number)?.toFloat()
        }

        if (posX != null && posY != null) {
            addHarvestable(id, type, tier, posX, posY, enchant, size, mobileTypeId)
        }
    }

    /**
     * Add a harvestable resource to the map
     */
    private fun addHarvestable(
        id: Int,
        typeNumber: Int,
        tier: Int,
        posX: Float,
        posY: Float,
        enchant: Int,
        size: Int,
        mobileTypeId: Int? = null
    ) {
        // Determine if living resource
        val isLiving = mobileTypeId != null && mobileTypeId != 65535

        // Get resource type string
        val resourceType = when {
            isLiving -> mobsDatabase?.getResourceInfo(mobileTypeId!!)?.type
            else -> harvestablesDatabase?.getResourceTypeFromTypeNumber(typeNumber)
        }

        // Validate resource
        val isValid = harvestablesDatabase?.isValidResourceByTypeNumber(typeNumber, tier, enchant) ?: false
        if (!isValid && resourceType == null) {
            Log.d(TAG, "Invalid resource: type=$typeNumber, tier=$tier, enchant=$enchant")
            return
        }

        // Map resource type to EntityType
        val entityType = when (resourceType?.uppercase()) {
            "FIBER" -> EntityType.RESOURCE_FIBER
            "ORE" -> EntityType.RESOURCE_ORE
            "LOG", "WOOD" -> EntityType.RESOURCE_LOGS
            "ROCK", "STONE" -> EntityType.RESOURCE_ROCK
            "HIDE", "LEATHER" -> EntityType.RESOURCE_HIDE
            else -> {
                // Fallback to typeNumber ranges
                when (typeNumber) {
                    in 0..5 -> EntityType.RESOURCE_LOGS
                    in 6..10 -> EntityType.RESOURCE_ROCK
                    in 11..15 -> EntityType.RESOURCE_FIBER
                    in 16..22 -> EntityType.RESOURCE_HIDE
                    in 23..27 -> EntityType.RESOURCE_ORE
                    else -> EntityType.UNKNOWN
                }
            }
        }

        val entity = RadarEntity(
            id = id,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = resourceType ?: "RESOURCE",
            tier = tier,
            enchant = enchant
        )

        entityMap[id] = entity
    }

    /**
     * Handle HarvestableChangeState (resource depleted/updated)
     */
    private fun handleHarvestableChangeState(params: Map<Int, Any?>) {
        val id = (params[0] as? Number)?.toInt() ?: return
        val newSize = (params[1] as? Number)?.toInt()
        val newEnchant = (params[2] as? Number)?.toInt()

        // newSize undefined = resource depleted
        if (newSize == null || newSize <= 0) {
            entityMap.remove(id)
            Log.d(TAG, "Resource depleted: $id")
        } else {
            // Update entity
            val entity = entityMap[id]
            if (entity != null && newEnchant != null) {
                entityMap[id] = entity.copy(enchant = newEnchant)
            }
        }
    }

    /**
     * Handle NewMob event
     */
    private fun handleNewMob(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[0] as? Number)?.toInt() ?: return
        val typeId = (params[1] as? Number)?.toInt() ?: return

        // Get position
        val location = (params[7] as? List<*>)
        var posX: Float? = null
        var posY: Float? = null
        
        if (location != null && location.size >= 2) {
            posX = (location[0] as? Number)?.toFloat()
            posY = (location[1] as? Number)?.toFloat()
        }

        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX == null || posY == null) return

        // Get mob info from database
        val mobInfo = mobsDatabase?.getMobInfo(typeId)
        val isHarvestable = mobsDatabase?.isHarvestable(typeId) ?: false

        // Determine entity type
        val entityType: EntityType
        val typeName: String
        
        if (isHarvestable) {
            val resourceType = mobInfo?.resourceType ?: "UNKNOWN"
            entityType = when (resourceType) {
                "Hide" -> EntityType.RESOURCE_HIDE
                "Fiber" -> EntityType.RESOURCE_FIBER
                "Log" -> EntityType.RESOURCE_LOGS
                "Rock" -> EntityType.RESOURCE_ROCK
                "Ore" -> EntityType.RESOURCE_ORE
                else -> EntityType.NORMAL_MOB
            }
            typeName = resourceType
        } else if (mobInfo != null) {
            // Hostile mob - check category
            entityType = when {
                mobInfo.category.equals("boss", ignoreCase = true) -> EntityType.BOSS_MOB
                mobInfo.category.contains("boss", ignoreCase = true) -> EntityType.BOSS_MOB
                mobInfo.category.equals("miniboss", ignoreCase = true) -> EntityType.BOSS_MOB
                mobInfo.category.equals("champion", ignoreCase = true) -> EntityType.ENCHANTED_MOB
                else -> EntityType.NORMAL_MOB
            }
            typeName = mobInfo.uniqueName
        } else {
            entityType = EntityType.NORMAL_MOB
            typeName = "MOB_$typeId"
        }

        val tier = mobInfo?.tier ?: 0
        val enchant = (params[33] as? Number)?.toInt() ?: 0

        val entity = RadarEntity(
            id = objectId,
            type = entityType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchant = enchant,
            isBoss = entityType == EntityType.BOSS_MOB
        )

        entityMap[objectId] = entity
        Log.d(TAG, "New mob: typeId=$typeId, type=$entityType, tier=$tier at ($posX, $posY)")
    }

    // ==================== HELPER METHODS ====================

    /**
     * Find coordinates in params (Plan B: Float range scanner)
     */
    private fun findCoordinates(params: Map<Int, Any?>): Pair<Float, Float>? {
        val idMap = MainApplication.getInstance().idMapRepository
        val minValid = idMap.getMinValidCoordinate()
        val maxValid = idMap.getMaxValidCoordinate()

        val coords = mutableListOf<Float>()

        for ((_, value) in params) {
            if (value is Number) {
                val floatValue = value.toFloat()
                if (floatValue in minValid..maxValid) {
                    coords.add(floatValue)
                }
            }
        }

        return if (coords.size >= 2) {
            Pair(coords[0], coords[1])
        } else {
            null
        }
    }

    /**
     * Update entity flow for UI observers
     */
    private fun updateEntityFlow() {
        _entities.value = entityMap.toMap()
    }

    /**
     * Get all current entities
     */
    fun getEntities(): Map<Int, RadarEntity> = entityMap.toMap()

    /**
     * Get entity count
     */
    fun getEntityCount(): Int = entityMap.size

    /**
     * Clear all entities
     */
    fun clear() {
        entityMap.clear()
        updateEntityFlow()
    }
}
