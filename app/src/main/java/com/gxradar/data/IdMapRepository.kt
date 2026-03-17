package com.gxradar.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Repository for managing Photon protocol parameter key mappings.
 *
 * Loads and caches id_map.json which contains:
 * - Parameter key mappings for entity data extraction
 * - Event code seeds for initial event name resolution
 * - Known type prefixes for Plan B fallback parsing
 * - Coordinate validation ranges for Plan B position scanning
 */
class IdMapRepository(private val context: Context) {

    companion object {
        private const val TAG = "IdMapRepository"
        private const val ID_MAP_FILE = "id_map.json"
    }

    // Cached ID map data
    private var idMap: IdMap? = null

    // Event code to name mapping (populated from seeds + discovery)
    private val eventCodeNames = mutableMapOf<Int, String>()

    // Gson instance for JSON parsing
    private val gson = Gson()

    /**
     * Load ID map from assets
     */
    suspend fun loadIdMap() = withContext(Dispatchers.IO) {
        try {
            val jsonString = context.assets.open(ID_MAP_FILE).bufferedReader().use(BufferedReader::readText)
            idMap = gson.fromJson(jsonString, IdMap::class.java)

            // Populate event code names from seeds
            idMap?.eventCodeSeeds?.forEach { (codeStr, name) ->
                codeStr.toIntOrNull()?.let { code ->
                    eventCodeNames[code] = name
                }
            }

            Log.i(TAG, "ID map loaded successfully: ${idMap?.version}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ID map: ${e.message}")
            // Use defaults
            idMap = IdMap()
        }
    }

    /**
     * Get common object ID key
     */
    fun getObjectIdKey(): Int = idMap?.common?.objectIdKey ?: 0

    /**
     * Get X position key
     */
    fun getPosXKey(): Int = idMap?.common?.posXKey ?: 8

    /**
     * Get Y position key
     */
    fun getPosYKey(): Int = idMap?.common?.posYKey ?: 9

    /**
     * Get JoinFinished local object ID key
     */
    fun getJoinFinishedLocalObjectIdKey(): Int = idMap?.joinFinished?.localObjectIdKey ?: 0

    /**
     * Get JoinFinished X position key
     */
    fun getJoinFinishedPosXKey(): Int = idMap?.joinFinished?.posXKey ?: 8

    /**
     * Get JoinFinished Y position key
     */
    fun getJoinFinishedPosYKey(): Int = idMap?.joinFinished?.posYKey ?: 9

    /**
     * Get harvestable type name key
     */
    fun getHarvestableTypeNameKey(): Int = idMap?.harvestable?.typeNameKey ?: 1

    /**
     * Get harvestable list key (for batch events)
     */
    fun getHarvestableListKey(): Int = idMap?.harvestable?.listKey ?: 2

    /**
     * Get harvestable tier key
     */
    fun getHarvestableTierKey(): Int = idMap?.harvestable?.tierKey ?: 7

    /**
     * Get harvestable enchant key
     */
    fun getHarvestableEnchantKey(): Int = idMap?.harvestable?.enchantKey ?: 11

    /**
     * Get mob type name key
     */
    fun getMobTypeNameKey(): Int = idMap?.mob?.typeNameKey ?: 1

    /**
     * Get mob tier key
     */
    fun getMobTierKey(): Int = idMap?.mob?.tierKey ?: 7

    /**
     * Get mob enchant key
     */
    fun getMobEnchantKey(): Int = idMap?.mob?.enchantKey ?: 11

    /**
     * Get mob isBoss key
     */
    fun getMobIsBossKey(): Int = idMap?.mob?.isBossKey ?: 50

    /**
     * Get mob health key
     */
    fun getMobHealthKey(): Int = idMap?.mob?.healthKey ?: 12

    /**
     * Get player name key
     */
    fun getPlayerNameKey(): Int = idMap?.player?.nameKey ?: 1

    /**
     * Get player guild key
     */
    fun getPlayerGuildKey(): Int = idMap?.player?.guildKey ?: 3

    /**
     * Get player alliance key
     */
    fun getPlayerAllianceKey(): Int = idMap?.player?.allianceKey ?: 4

    /**
     * Get player faction flag key
     */
    fun getPlayerFactionFlagKey(): Int = idMap?.player?.factionFlagKey ?: 23

    /**
     * Get player health key
     */
    fun getPlayerHealthKey(): Int = idMap?.player?.healthKey ?: 12

    /**
     * Get player mount key
     */
    fun getPlayerMountKey(): Int = idMap?.player?.mountKey ?: 26

    /**
     * Get chest type name key
     */
    fun getChestTypeNameKey(): Int = idMap?.chest?.typeNameKey ?: 1

    /**
     * Get chest rarity key
     */
    fun getChestRarityKey(): Int = idMap?.chest?.rarityKey ?: 7

    /**
     * Get dungeon type name key
     */
    fun getDungeonTypeNameKey(): Int = idMap?.dungeon?.typeNameKey ?: 1

    /**
     * Get dungeon rarity key
     */
    fun getDungeonRarityKey(): Int = idMap?.dungeon?.rarityKey ?: 7

    /**
     * Get mist type name key
     */
    fun getMistTypeNameKey(): Int = idMap?.mist?.typeNameKey ?: 1

    /**
     * Get mist rarity key
     */
    fun getMistRarityKey(): Int = idMap?.mist?.rarityKey ?: 7

    /**
     * Get known prefixes for Plan B fallback
     */
    fun getKnownPrefixes(): Map<String, List<String>> = idMap?.knownPrefixes ?: emptyMap()

    /**
     * Check if key is set to force Plan B (-1)
     */
    fun isForcePlanB(key: Int?): Boolean = key == -1

    /**
     * Get minimum valid coordinate for Plan B
     */
    fun getMinValidCoordinate(): Float = idMap?.coordinatePlanB?.minValid ?: -32768.0f

    /**
     * Get maximum valid coordinate for Plan B
     */
    fun getMaxValidCoordinate(): Float = idMap?.coordinatePlanB?.maxValid ?: 32768.0f

    /**
     * Resolve event code to name string
     */
    fun resolveEventName(code: Int): String? = eventCodeNames[code]

    /**
     * Add discovered event code mapping
     */
    fun addDiscoveredEventCode(code: Int, name: String) {
        eventCodeNames[code] = name
    }

    /**
     * Get all known event codes
     */
    fun getAllEventCodes(): Map<Int, String> = eventCodeNames.toMap()

    // Data classes for JSON mapping

    data class IdMap(
        @SerializedName("version") val version: String = "unknown",
        @SerializedName("comment") val comment: String = "",
        @SerializedName("common") val common: CommonKeys = CommonKeys(),
        @SerializedName("joinFinished") val joinFinished: JoinFinishedKeys = JoinFinishedKeys(),
        @SerializedName("harvestable") val harvestable: HarvestableKeys = HarvestableKeys(),
        @SerializedName("mob") val mob: MobKeys = MobKeys(),
        @SerializedName("player") val player: PlayerKeys = PlayerKeys(),
        @SerializedName("silver") val silver: SilverKeys = SilverKeys(),
        @SerializedName("chest") val chest: ChestKeys = ChestKeys(),
        @SerializedName("dungeon") val dungeon: DungeonKeys = DungeonKeys(),
        @SerializedName("mist") val mist: MistKeys = MistKeys(),
        @SerializedName("knownPrefixes") val knownPrefixes: Map<String, List<String>> = emptyMap(),
        @SerializedName("eventCodeSeeds") val eventCodeSeeds: Map<String, String> = emptyMap(),
        @SerializedName("coordinatePlanB") val coordinatePlanB: CoordinatePlanB = CoordinatePlanB()
    )

    data class CommonKeys(
        @SerializedName("objectIdKey") val objectIdKey: Int = 0,
        @SerializedName("posXKey") val posXKey: Int = 8,
        @SerializedName("posYKey") val posYKey: Int = 9
    )

    data class JoinFinishedKeys(
        @SerializedName("localObjectIdKey") val localObjectIdKey: Int = 0,
        @SerializedName("posXKey") val posXKey: Int = 8,
        @SerializedName("posYKey") val posYKey: Int = 9
    )

    data class HarvestableKeys(
        @SerializedName("typeNameKey") val typeNameKey: Int = 1,
        @SerializedName("listKey") val listKey: Int = 2,
        @SerializedName("tierKey") val tierKey: Int = 7,
        @SerializedName("enchantKey") val enchantKey: Int = 11
    )

    data class MobKeys(
        @SerializedName("typeNameKey") val typeNameKey: Int = 1,
        @SerializedName("tierKey") val tierKey: Int = 7,
        @SerializedName("enchantKey") val enchantKey: Int = 11,
        @SerializedName("isBossKey") val isBossKey: Int = 50,
        @SerializedName("healthKey") val healthKey: Int = 12
    )

    data class PlayerKeys(
        @SerializedName("nameKey") val nameKey: Int = 1,
        @SerializedName("guildKey") val guildKey: Int = 3,
        @SerializedName("allianceKey") val allianceKey: Int = 4,
        @SerializedName("factionFlagKey") val factionFlagKey: Int = 23,
        @SerializedName("healthKey") val healthKey: Int = 12,
        @SerializedName("mountKey") val mountKey: Int = 26
    )

    data class SilverKeys(
        @SerializedName("typeNameKey") val typeNameKey: Int = 1,
        @SerializedName("amountKey") val amountKey: Int = 2
    )

    data class ChestKeys(
        @SerializedName("typeNameKey") val typeNameKey: Int = 1,
        @SerializedName("rarityKey") val rarityKey: Int = 7
    )

    data class DungeonKeys(
        @SerializedName("typeNameKey") val typeNameKey: Int = 1,
        @SerializedName("rarityKey") val rarityKey: Int = 7
    )

    data class MistKeys(
        @SerializedName("typeNameKey") val typeNameKey: Int = 1,
        @SerializedName("rarityKey") val rarityKey: Int = 7
    )

    data class CoordinatePlanB(
        @SerializedName("minValid") val minValid: Float = -32768.0f,
        @SerializedName("maxValid") val maxValid: Float = 32768.0f
    )
}
