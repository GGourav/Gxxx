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
 */
class EventDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "EventDispatcher"
        private const val EVENT_TYPE_KEY = 252
    }

    private val discoveryLogger = DiscoveryLogger(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventCodeNames = mutableMapOf<Int, String>()
    private val entities = java.util.concurrent.ConcurrentHashMap<Int, RadarEntity>()
    private val handlers = HashMap<String, (Map<Int, Any?>) -> Unit>()

    init {
        initializeEventCodes()
        initializeHandlers()
    }

    private fun initializeEventCodes() {
        val idMap = MainApplication.getInstance().idMapRepository
        idMap.getAllEventCodes().forEach { (code, name) ->
            eventCodeNames[code] = name
        }
    }

    private fun initializeHandlers() {
        handlers["JoinFinished"] = { params -> handleJoinFinished(params) }
        handlers["NewCharacter"] = { params -> handleNewCharacter(params) }
        handlers["Leave"] = { params -> handleLeave(params) }
        handlers["Move"] = { params -> handleMove(params) }
        handlers["ForcedMovement"] = { params -> handleMove(params) }
        handlers["ChangeCluster"] = { params -> handleChangeCluster(params) }
        handlers["NewSimpleHarvestableObject"] = { params -> handleNewHarvestable(params) }
        handlers["NewSimpleHarvestableObjectList"] = { params -> handleNewHarvestableList(params) }
        handlers["NewHarvestableObject"] = { params -> handleNewHarvestable(params) }
        handlers["HarvestFinished"] = { params -> handleHarvestFinished(params) }
        handlers["NewMob"] = { params -> handleNewMob(params) }
        handlers["HealthUpdate"] = { params -> handleHealthUpdate(params) }
        handlers["NewSilverObject"] = { params -> handleNewSilver(params) }
        handlers["NewSimpleItem"] = { params -> handleNewItem(params) }
        handlers["NewFishingZoneObject"] = { params -> handleNewFishingZone(params) }
        handlers["NewLootChest"] = { params -> handleNewChest(params) }
        handlers["NewTreasureChest"] = { params -> handleNewChest(params) }
        handlers["NewMatchLootChestObject"] = { params -> handleNewChest(params) }
        handlers["NewCarriableObject"] = { params -> handleNewCarriable(params) }
        handlers["NewRandomDungeonExit"] = { params -> handleNewDungeon(params) }
        handlers["NewExpeditionExit"] = { params -> handleNewDungeon(params) }
        handlers["NewHellgateExitPortal"] = { params -> handleNewDungeon(params) }
        handlers["NewMistsDungeonExit"] = { params -> handleNewDungeon(params) }
        handlers["NewPortalEntrance"] = { params -> handleNewPortal(params) }
        handlers["NewPortalExit"] = { params -> handleNewPortal(params) }
        handlers["NewMistsCagedWisp"] = { params -> handleNewMistWisp(params) }
        handlers["NewMistsWispSpawn"] = { params -> handleNewMistWisp(params) }
        handlers["NewMistDungeonRoomMobSoul"] = { params -> handleNewMistWisp(params) }
        handlers["InventoryMoveItem"] = { _ -> }
    }

    fun dispatchEvent(event: PhotonParser.PhotonEvent) {
        scope.launch {
            try {
                val codeInt = (event.params[EVENT_TYPE_KEY] as? Number)?.toInt() ?: return@launch
                val eventName = eventCodeNames[codeInt] ?: run {
                    discoveryLogger.logUnknownEvent(codeInt, event.params)
                    return@launch
                }
                val handler = handlers[eventName]
                if (handler != null) {
                    handler(event.params)
                } else {
                    discoveryLogger.logDiscovery(eventName, -1, event.params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error dispatching event: ${e.message}")
            }
        }
    }

    private fun handleJoinFinished(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val localIdKey = idMap.getJoinFinishedLocalObjectIdKey()
        val objectId = (params[localIdKey] as? Number)?.toInt() ?: return
        val posXKey = idMap.getJoinFinishedPosXKey()
        val posYKey = idMap.getJoinFinishedPosYKey()
        var posX = (params[posXKey] as? Number)?.toFloat()
        var posY = (params[posYKey] as? Number)?.toFloat()
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }
        if (posX != null && posY != null) {
            MainApplication.getInstance().setLocalPlayerId(objectId)
            MainApplication.getInstance().setLocalPlayerX(posX)
            MainApplication.getInstance().setLocalPlayerY(posY)
            entities.clear()
            Log.i(TAG, "JoinFinished: localId=$objectId, pos=($posX, $posY)")
        }
    }

    private fun handleNewCharacter(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return
        val localId = MainApplication.getInstance().getLocalPlayerId()
        if (objectId == localId) return
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
        val name = params[idMap.getPlayerNameKey()] as? String ?: ""
        val guild = params[idMap.getPlayerGuildKey()] as? String ?: ""
        val alliance = params[idMap.getPlayerAllianceKey()] as? String ?: ""
        val healthPercent = (params[idMap.getPlayerHealthKey()] as? Number)?.toFloat() ?: 1.0f
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

    private fun handleLeave(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return
        entities.remove(objectId)
    }

    private fun handleMove(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return
        val entity = entities[objectId] ?: return
        var posX = (params[idMap.getPosXKey()] as? Number)?.toFloat()
        var posY = (params[idMap.getPosYKey()] as? Number)?.toFloat()
        if (posX == null || posY == null) {
            val coords = findCoordinates(params)
            if (coords != null) {
                posX = coords.first
                posY = coords.second
            }
        }
        if (posX != null && posY != null) {
            entities[objectId] = entity.copy(worldX = posX, worldY = posY)
        }
    }

    private fun handleChangeCluster(params: Map<Int, Any?>) {
        entities.clear()
        Log.i(TAG, "ChangeCluster: cleared entities")
    }

    private fun handleNewHarvestable(params: Map<Int, Any?>) {
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
        val typeName = findTypeName(params) ?: return
        val (tier, enchant) = RadarEntity.parseTierFromTypeName(typeName)
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

    private fun handleNewHarvestableList(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val listKey = idMap.getHarvestableListKey()
        @Suppress("UNCHECKED_CAST")
        val list = params[listKey] as? List<Map<String, Any?>> ?: return
        list.forEach { item ->
            discoveryLogger.logDiscovery("NewSimpleHarvestableObjectList_item", -1, item as Map<Int, Any?>)
        }
    }

    private fun handleHarvestFinished(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return
        entities.remove(objectId)
    }

    private fun handleNewMob(params: Map<Int, Any?>) {
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
        val typeName = findTypeName(params) ?: "UNKNOWN_MOB"
        val (tier, enchant) = RadarEntity.parseTierFromTypeName(typeName)
        val isBoss = (params[idMap.getMobIsBossKey()] as? Boolean) ?: false
        val healthPercent = (params[idMap.getMobHealthKey()] as? Number)?.toFloat() ?: 1.0f
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

    private fun handleHealthUpdate(params: Map<Int, Any?>) {
        val idMap = MainApplication.getInstance().idMapRepository
        val objectId = (params[idMap.getObjectIdKey()] as? Number)?.toInt() ?: return
        val entity = entities[objectId] ?: return
        val healthPercent = (params[idMap.getMobHealthKey()] as? Number)?.toFloat() ?: return
        entities[objectId] = entity.copy(healthPercent = healthPercent)
    }

    private fun handleNewSilver(params: Map<Int, Any?>) {
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
            type = EntityType.SILVER,
            worldX = posX,
            worldY = posY,
            typeName = "SILVER"
        )
        entities[objectId] = entity
    }

    private fun handleNewItem(params: Map<Int, Any?>) {
        discoveryLogger.logDiscovery("NewSimpleItem", -1, params)
    }

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
            type = EntityType.RESOURCE_CROP,
            worldX = posX,
            worldY = posY,
            typeName = "FISHING_ZONE"
        )
        entities[objectId] = entity
    }

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

    private fun handleNewCarriable(params: Map<Int, Any?>) {
        discoveryLogger.logDiscovery("NewCarriableObject", -1, params)
    }

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

    private fun handleNewPortal(params: Map<Int, Any?>) {
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
        val typeName = findTypeName(params) ?: "PORTAL"
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.DUNGEON_PORTAL,
            worldX = posX,
            worldY = posY,
            typeName = typeName
        )
        entities[objectId] = entity
    }

    private fun handleNewMistWisp(params: Map<Int, Any?>) {
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
        val typeName = findTypeName(params) ?: "MIST_WISP"
        val rarity = (params[idMap.getMistRarityKey()] as? Number)?.toInt() ?: 0
        val entity = RadarEntity(
            id = objectId,
            type = EntityType.MIST_WISP,
            worldX = posX,
            worldY = posY,
            typeName = typeName,
            tier = rarity
        )
        entities[objectId] = entity
    }

    // Helper methods
    private fun findTypeName(params: Map<Int, Any?>): String? {
        val idMap = MainApplication.getInstance().idMapRepository
        val typeNameKey = idMap.getHarvestableTypeNameKey()
        if (!idMap.isForcePlanB(typeNameKey)) {
            val typeName = params[typeNameKey] as? String
            if (!typeName.isNullOrBlank()) {
                return typeName
            }
        }
        for ((_, value) in params) {
            if (value is String) {
                if (value.matches(Regex("^T[1-8]_.*"))) {
                    return value
                }
            }
        }
        return null
    }

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

    fun getEntities(): Map<Int, RadarEntity> = entities.toMap()

    fun getEntityCount(): Int = entities.size
}
