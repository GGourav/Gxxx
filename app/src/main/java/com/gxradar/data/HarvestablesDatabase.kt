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
 * Harvestables Database - Ported from OpenRadar HarvestablesDatabase.js
 *
 * Validates static resource combinations and maps typeNumber to resource type.
 * 
 * typeNumber Ranges:
 * - WOOD (Logs): 0-5
 * - ROCK (Stone): 6-10
 * - FIBER: 11-15
 * - HIDE (Leather): 16-22
 * - ORE: 23-27
 *
 * All resources have T1-T8 tiers and 0-4 enchantment levels.
 */
class HarvestablesDatabase(private val context: Context) {

    companion object {
        private const val TAG = "HarvestablesDatabase"
        private const val HARVESTABLES_FILE = "harvestables.min.json"

        // typeNumber ranges for each resource type
        private val WOOD_RANGE = 0..5
        private val ROCK_RANGE = 6..10
        private val FIBER_RANGE = 11..15
        private val HIDE_RANGE = 16..22
        private val ORE_RANGE = 23..27
    }

    // Resource type data from JSON
    private val harvestableTypes = mutableMapOf<String, MutableList<TierEntry>>()

    // Valid combinations: "RESOURCE-TIER-ENCHANT"
    private val validCombinations = mutableSetOf<String>()

    // Loaded flag
    var isLoaded = false
        private set

    // Statistics
    var typesLoaded = 0
        private set
    var combinationsLoaded = 0
        private set

    // Gson instance
    private val gson = Gson()

    /**
     * Load harvestables database from assets
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading harvestables database...")

            val jsonString = context.assets.open(HARVESTABLES_FILE).bufferedReader().use(BufferedReader::readText)
            val data: Map<String, List<TierEntryJson>> = gson.fromJson(
                jsonString,
                object : TypeToken<Map<String, List<TierEntryJson>>>() {}.type
            )

            // Parse each resource type
            data.forEach { (resourceType, entries) ->
                harvestableTypes[resourceType] = mutableListOf()
                
                entries.forEach { entry ->
                    val tierEntry = TierEntry(
                        tier = entry.tier,
                        item = entry.item ?: "",
                        respawn = entry.respawn ?: 0,
                        harvest = entry.harvest ?: 0,
                        tool = entry.tool ?: false,
                        maxCharges = entry.maxcharges ?: 0,
                        startCharges = entry.startcharges ?: 0,
                        chargeUp = entry.chargeup ?: 0.0
                    )
                    harvestableTypes[resourceType]!!.add(tierEntry)

                    // Add valid combinations for this tier (enchant 0-4)
                    for (enchant in 0..4) {
                        validCombinations.add("$resourceType-${entry.tier}-$enchant")
                    }
                }

                typesLoaded++
            }

            combinationsLoaded = validCombinations.size
            isLoaded = true

            Log.i(TAG, "Harvestables database loaded: $typesLoaded types, $combinationsLoaded combinations")
            Log.i(TAG, "Resource types: ${harvestableTypes.keys}")
            
            // Log sample tiers per type
            harvestableTypes.forEach { (type, entries) ->
                val tiers = entries.map { it.tier }.distinct().sorted()
                Log.d(TAG, "$type: T${tiers.first()}-T${tiers.last()} (${entries.size} entries)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load harvestables database: ${e.message}")
            isLoaded = false
        }
    }

    /**
     * Check if a resource combination is valid
     * @param resourceType WOOD, ROCK, FIBER, HIDE, ORE
     * @param tier 1-8
     * @param enchant 0-4
     */
    fun isValidResource(resourceType: String, tier: Int, enchant: Int): Boolean {
        val key = "${resourceType.uppercase()}-$tier-$enchant"
        return validCombinations.contains(key)
    }

    /**
     * Check if a resource combination is valid by typeNumber
     * @param typeNumber 0-27
     * @param tier 1-8
     * @param enchant 0-4
     */
    fun isValidResourceByTypeNumber(typeNumber: Int, tier: Int, enchant: Int): Boolean {
        val resourceType = getResourceTypeFromTypeNumber(typeNumber) ?: return false
        return isValidResource(resourceType, tier, enchant)
    }

    /**
     * Get resource type from typeNumber
     * @param typeNumber 0-27
     * @return WOOD, ROCK, FIBER, HIDE, ORE or null
     */
    fun getResourceTypeFromTypeNumber(typeNumber: Int): String? {
        return when (typeNumber) {
            in WOOD_RANGE -> "WOOD"
            in ROCK_RANGE -> "ROCK"
            in FIBER_RANGE -> "FIBER"
            in HIDE_RANGE -> "HIDE"
            in ORE_RANGE -> "ORE"
            else -> null
        }
    }

    /**
     * Get display name for resource type (for UI)
     */
    fun getResourceDisplayName(resourceType: String): String {
        return when (resourceType.uppercase()) {
            "WOOD" -> "Logs"
            "ROCK" -> "Stone"
            "FIBER" -> "Fiber"
            "HIDE" -> "Hide"
            "ORE" -> "Ore"
            else -> resourceType
        }
    }

    /**
     * Get valid tiers for a resource type
     */
    fun getValidTiers(resourceType: String): List<Int> {
        val entries = harvestableTypes[resourceType.uppercase()] ?: return emptyList()
        return entries.map { it.tier }.distinct().sorted()
    }

    /**
     * Get all valid enchantments (always 0-4)
     */
    fun getValidEnchantments(): List<Int> = listOf(0, 1, 2, 3, 4)

    /**
     * Get resource type data
     */
    fun getResourceData(resourceType: String): List<TierEntry>? {
        return harvestableTypes[resourceType.uppercase()]
    }

    /**
     * Get typeNumber range for resource type
     */
    fun getTypeNumberRange(resourceType: String): IntRange {
        return when (resourceType.uppercase()) {
            "WOOD" -> WOOD_RANGE
            "ROCK" -> ROCK_RANGE
            "FIBER" -> FIBER_RANGE
            "HIDE" -> HIDE_RANGE
            "ORE" -> ORE_RANGE
            else -> 0..0
        }
    }

    /**
     * Check if typeNumber is valid (0-27)
     */
    fun isValidTypeNumber(typeNumber: Int): Boolean {
        return typeNumber in 0..27
    }

    // Data classes

    /**
     * Tier entry from harvestables.min.json
     */
    data class TierEntryJson(
        @SerializedName("tier") val tier: Int,
        @SerializedName("item") val item: String?,
        @SerializedName("respawn") val respawn: Int?,
        @SerializedName("harvest") val harvest: Int?,
        @SerializedName("tool") val tool: Boolean?,
        @SerializedName("maxcharges") val maxcharges: Int?,
        @SerializedName("startcharges") val startcharges: Int?,
        @SerializedName("chargeup") val chargeup: Double?
    )

    /**
     * Processed tier entry
     */
    data class TierEntry(
        val tier: Int,
        val item: String,
        val respawn: Int,
        val harvest: Int,
        val tool: Boolean,
        val maxCharges: Int,
        val startCharges: Int,
        val chargeUp: Double
    )
}
