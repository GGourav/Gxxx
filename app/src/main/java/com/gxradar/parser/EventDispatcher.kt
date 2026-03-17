package com.gxradar.parser

import android.content.Context
import android.util.Log
import com.gxradar.MainApplication
import com.gxradar.logger.DiscoveryLogger
import com.gxradar.model.EntityType
import com.gxradar.model.RadarEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Event Dispatcher for Photon events.
 *
 * Routes events by name string (stable across patches) rather than
 * integer codes (which shift with every Albion patch).
 *
 * Dispatch Pattern:
 * 1. Parse event → extract all params into HashMap<Int, Any?>
 * 2. Get the Albion event code integer from key 252
 * 3. Resolve to name string using seed table + Discovery Logger population
 * 4. Dispatch by name — stable across patches
 */
class EventDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "EventDispatcher"

        // Parameter key for event type code (key 252 = 0xFC)
        private const val EVENT_TYPE_KEY = 252
    }

    // Discovery logger for unknown events
    private val discoveryLogger = DiscoveryLogger(context)

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Event code to name mapping (from IdMapRepository)
    private val eventCodeNames = mutableMapOf<Int, String>()

    // Entity map (objectId -> RadarEntity)
    private val entities = java.util.concurrent.ConcurrentHashMap<Int, RadarEntity>()

    // Handler map (event name -> handler function)
    private val handlers = HashMap<String, (Map<Int, Any?>) -> Unit>()

    init {
        initializeEventCodes()
        initializeHandlers()
    }

    /**
     * Initialize event code mappings from IdMapRepository
     */
    private fun initializeEventCodes() {
        val idMap = MainApplication.getInstance().idMapRepository
        idMap.getAllEventCodes().forEach { (code, name) ->
            eventCodeNames[code] = name
        }
    }

    /**
     * Initialize event handlers for all radar-relevant events
     */
    private fun initializeHandlers() {
        // Core events
        handlers["JoinFinished"] = { params -> handleJoinFinished(params) }
        handlers["NewCharacter"] = { params -> handleNewCharacter(params) }
        handlers["Leave"] = { params -> handleLeave(params) }
        handlers["Move"] = { params -> handleMove(params) }
        handlers["ForcedMovement"] = { params -> handleMove(params) }
        handlers["ChangeCluster"] = { params -> handleChangeCluster(params) }

        // Harvesting events
        handlers["NewSimpleHarvestableObject"] = { params -> handleNewHarvestable(params) }
        handlers["NewSimpleHarvestableObjectList"] = { params -> handleNewHarvestableList(params) }
        handlers["NewHarvestableObject"] = { params -> handleNewHarvestable(params) }
        handlers["HarvestFinished"] = { params -> handleHarvestFinished(params) }

        // Mob events
        handlers["NewMob"] = { params -> handleNewMob(params) }

        // Health events
        handlers["HealthUpdate"] = { params -> handleHealthUpdate(params) }

        // Silver events
        handlers["NewSilverObject"] = { params -> handleNewSilver(params) }

        // Item/loot events
        handlers["NewSimpleItem"] = { params -> handleNewItem(params) }
        handlers["NewFishingZoneObject"] = { params -> handleNewFishingZone(params) }

        // Chest events
        handlers["NewLootChest"] = { params -> handleNewChest(params) }
        handlers["NewTreasureChest"] = { params -> handleNewChest(params) }
        handlers["NewMatchLootChestObject"] = { params -> handleNewChest(params) }

        // Carriable events
        handlers["NewCarriableObject"] = { params -> handleNewCarriable(params) }

        // Dungeon/Portal events
        handlers["NewRandomDungeonExit"] = { params -> handleNewDungeon(params) }
        handlers["NewExpeditionExit"] = { params -> handleNewDungeon(params) }
        handlers["NewHellgateExitPortal"] = { params -> handleNewDungeon(params) }
        handlers["NewMistsDungeonExit"] = { params -> handleNewDungeon(params) }
        handlers["NewPortalEntrance"] = { params -> handleNewPortal(params) }
        handlers["NewPortalExit"] = { params -> handleNewPortal(params) }

        // Mist events
        handlers["NewMistsCagedWisp"] = { params -> handleNewMistWisp(params) }
        handlers["NewMistsWispSpawn"] = { params -> handleNewMistWisp(params) }
        handlers["NewMistDungeonRoomMobSoul"] = { params -> handleNewMistWisp(params) }

        // Discard silently
        handlers["InventoryMoveItem"] = { _ -> /* discard */ }
    }

    /**
     * Dispatch a parsed Photon event
     */
    fun dispatchEvent(event: PhotonParser.PhotonEvent) {
        scope.launch {
            try {
                // Get event type code from params[252]
                val codeInt = (event.params[EVENT_TYPE_KEY] as? Number)?.toInt() ?: return@launch

                // Resolve to name string
                val eventName = eventCodeNames[codeInt] ?: run {
                    // Log unknown event for discovery
                    discoveryLogger.logUnknownEvent(codeInt, event.params)
                    return@launch
                }

                // Dispatch by name
                val handler = handlers[eventName]
                if (handler != null) {
                    handler(event.params)
                } else {
                    // Log discovery for unhandled but known events
                    discoveryLogger.logDiscovery(eventName, -1, event.params)
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error dispatching event: ${e.message}")
            }
        }
    }

    // ==================== EVENT HANDLERS ====================

    /**
     * Handle JoinFinished event (CRITICAL for Bug 1 fix)
     *
     * Fires when the local player enters any zone — BEFORE any NewCharacter events.
     * Contains the local player's ObjectId and starting position.
     */
    private fun handleJoinFinished(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        // Get local player object ID
        val localIdKey = idMap.getJoinFinishedLocalObjectIdKey()
        val objectId = (params[localIdKey] as? Number)?.toInt() ?: return

        // Get position
        val posXKey = idMap.getJoinFinishedPosXKey()
        val posYKey = idMap.getJoinFinishedPosYKey()

        var posX = (params[posXKey] as? Number)?.toFloat()
        var posY = (params[posYKey] as? Number)?.toFloat()

        // Plan B: Float range scanner for positions
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX != null && posY != null) {
            // Store local player info
            MainApplication.getInstance().setLocalPlayerId(objectId)
            MainApplication.getInstance().setLocalPlayerX(posX)
            MainApplication.getInstance().setLocalPlayerY(posY)

            // Clear entity map on zone change
            entities.clear()

            Log.i(TAG, "JoinFinished: localId=$objectId, pos=($posX, $posY)")
        }
    }

    /**
     * Handle NewCharacter event (other players)
     */
    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        // Skip local player
        val localId = MainApplication.getInstance().getLocalPlayerId()
        if (objectId == localId) return

        // Get position
        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
        var posY = (params[idMap.getPosYKey()] as? Number)?.toFloat()

        // Plan B: Float range scanner
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX == null || posY == null) return

        // Get player info
        val name = params[idMap.getPlayerNameKey()] as? String ?: ""
        val guild = params[idMap.getPlayerGuildKey()] as? String ?: ""
        val alliance = params[idMap.getPlayerAllianceKey()] as? String ?: ""
        val healthPercent = (params[idMap.getPlayerHealthKey()] as? Number)?.toFloat() ?: 1.0f

        // Determine player type (simplified - would need faction data for full implementation)
        val playerType = EntityType.PLAYER

        val entity = RadarEntity(
            id = objectId,
            type = playerType,
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

        entities[objectId] = entity
    }

    /**
     * Handle Leave event
     */
    private fun handleLeave(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        entities.remove(objectId)
    }

    /**
     * Handle Move event
     */
    private fun handleMove(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        val entity = entities[objectId] ?: return

        // Get new position
        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
        var posY = (params[idMap.getPosYKey()] as? Number)?.toFloat()

        // Plan B: Float range scanner
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX != null && posY != null) {
            // Update entity position
            entities[objectId] = entity.copy(worldX = posX, worldY = posY)
        }
    }

    /**
     * Handle ChangeCluster event (zone transition)
     */
    private fun handleChangeCluster(params: Map<Int, Any?>) {
        // Clear entities on zone change
        entities.clear()
        Log.i(TAG, "ChangeCluster: cleared entities")
    }

    /**
     * Handle NewSimpleHarvestableObject event (resources)
     */
    private fun handleNewHarvestable(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        // Get position
        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
        var posY = (params[idMap.getPosYKey()] as? Number)?.toFloat()

        // Plan B: Float range scanner
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX == null || posY == null) return

        // Get type name
        val typeName = findTypeName(params) ?: return

        // Parse tier and enchant from type name
        val (tier, enchant) = RadarEntity.parseTierFromTypeName(typeName)

        // Determine resource type
        val resourceType = RadarEntity.getResourceTypeFromTypeName(typeName)

        val entity = RadarEntity(
            id = objectId,
            type = resourceType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchant = enchant
        )

        entities[objectId] = entity
    }

    /**
     * Handle NewSimpleHarvestableObjectList event (batch resources)
     */
    private fun handleNewHarvestableList(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val listKey = idMap.getHarvestableListKey()

        @Suppress("UNCHECKED_CAST")
        val list = params[listKey] as? List<Map<String, Any?>> ?: return

        list.forEach { item ->
            // Process each item in the list
            // The structure may vary; log for discovery
            discoveryLogger.logDiscovery("NewSimpleHarvestableObjectList_item", -1, item as Map<Int, Any?>)
        }
    }

    /**
     * Handle HarvestFinished event
     */
    private fun handleHarvestFinished(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        entities.remove(objectId)
    }

    /**
     * Handle NewMob event
     */
    private fun handleNewMob(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        // Get position
        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
        var posY = (params[idMap.getPosYKey()] as? Number)?.toFloat()

        // Plan B: Float range scanner
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }

        if (posX == null || posY == null) return

        // Get type name
        val typeName = findTypeName(params) ?: "UNKNOWN_MOB"

        // Parse tier and enchant
        val (tier, enchant) = RadarEntity.parseTierFromTypeName(typeName)

        // Check if boss
        val isBoss = (params[idMap.getMobIsBossKey()] as? Boolean) ?: false

        // Get health
        val healthPercent = (params[idMap.getMobHealthKey()] as? Number)?.toFloat() ?: 1.0f

        // Determine mob type
        val mobType = when {
            isBoss -> EntityType.BOSS_MOB
            enchant > 0 -> EntityType.ENCHANTED_MOB
            else -> EntityType.NORMAL_MOB
        }

        val entity = RadarEntity(
            id = objectId,
            type = mobType,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = tier,
            enchant = enchant,
            isBoss = isBoss,
            healthPercent = healthPercent
        )

        entities[objectId] = entity
    }

    /**
     * Handle HealthUpdate event
     */
    private fun handleHealthUpdate(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        val entity = entities[objectId] ?: return

        val healthPercent = (params[idMap.getMobHealthKey()] as? Number)?.toFloat() ?: return

        entities[objectId] = entity.copy(healthPercent = healthPercent)
    }

    /**
     * Handle NewSilverObject event
     */
    private fun handleNewSilver(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

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

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.SILVER,
            worldX = posX,
            worldY = posY,
            typeName = "SILVER"
        )

        entities[objectId] = entity
    }

    /**
     * Handle NewSimpleItem event
     */
    private fun handleNewItem(params: Map<Int, Any?>) {
        // Log for discovery
        discoveryLogger.logDiscovery("NewSimpleItem", -1, params)
    }

    /**
     * Handle NewFishingZoneObject event
     */
    private fun handleNewFishingZone(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

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

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.RESOURCE_CROP, // Use CROP for fishing zones
            worldX = posX,
            worldY = posY,
            typeName = "FISHING_ZONE"
        )

        entities[objectId] = entity
    }

    /**
     * Handle NewLootChest / NewTreasureChest events
     */
    private fun handleNewChest(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

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

        val typeName = findTypeName(params) ?: "CHEST"
        val rarity = (params[idMap.getChestRarityKey()] as? Number)?.toInt() ?: 0

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.CHEST,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = rarity
        )

        entities[objectId] = entity
    }

    /**
     * Handle NewCarriableObject event
     */
    private fun handleNewCarriable(params: Map<Int, Any?>) {
        discoveryLogger.logDiscovery("NewCarriableObject", -1, params)
    }

    /**
     * Handle dungeon exit events
     */
    private fun handleNewDungeon(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

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

        val typeName = findTypeName(params) ?: "DUNGEON"
        val rarity = (params[idMap.getDungeonRarityKey()] as? Number)?.toInt() ?: 0

        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = rarity
        )

        entities[objectId] = entity
    }

    /**
     * Handle portal events
     */
    private fun handleNewPortal(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository

        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return

        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
   
