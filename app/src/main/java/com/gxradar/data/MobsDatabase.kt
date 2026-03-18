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
 * Mobs Database - Ported from OpenRadar MobsDatabase.js
 *
 * Maps mob TypeID to resource type and tier for living resources.
 * Uses the formula: TypeID = ArrayIndex + OFFSET (15)
 *
 * Discovery: Index in mobs.json = server TypeID - 15
 * Reference: OpenRadar MobsDatabase.js
 * Verified: T4_MOB_HIDE_SWAMP_MONITORLIZARD at index 410 = TypeID 425
 */
class MobsDatabase(private val context: Context) {

    companion object {
        private const val TAG = "MobsDatabase"
        private const val MOBS_FILE = "mobs.min.json"
        
        /**
         * OFFSET between mobs.json array index and server TypeID
         * Server TypeID = ArrayIndex + 15
         */
        const val OFFSET = 15
    }

    // Mob info cache: TypeID -> MobInfo
    private val mobsById = mutableMapOf<Int, MobInfo>()

    // TypeIDs that are harvestable (drop resources)
    private val harvestableTypeIds = mutableSetOf<Int>()

    // Name to TypeID lookup
    private val mobsByName = mutableMapOf<String, Int>()

    // Loaded flag
    var isLoaded = false
        private set

    // Statistics
    var totalMobs = 0
        private set
    var harvestables = 0
        private set

    // Gson instance
    private val gson = Gson()

    /**
     * Load mobs database from assets
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Loading mobs database...")

            val jsonString = context.assets.open(MOBS_FILE).bufferedReader().use(BufferedReader::readText)
            val mobsList: List<MobEntry> = gson.fromJson(jsonString, object : TypeToken<List<MobEntry>>() {}.type)

            mobsList.forEachIndexed { index, mob ->
                val typeId = index + OFFSET

                // Parse resource type from loot field
                val resourceType = normalizeResourceType(mob.l)
                val resourceTier = mob.lt ?: mob.t

                if (resourceType != null) {
                    harvestableTypeIds.add(typeId)
                    harvestables++
                }

                // Store mob info
                mobsById[typeId] = MobInfo(
                    typeId = typeId,
                    uniqueName = mob.u ?: "",
                    tier = mob.t,
                    category = mob.c ?: "",
                    nameLocaleTag = mob.n ?: "",
                    resourceType = resourceType,
                    resourceTier = resourceTier,
                    isHarvestable = resourceType != null
                )

                // Name lookup
                mob.u?.let { name ->
                    mobsByName[name] = typeId
                }

                totalMobs++
            }

            isLoaded = true

            Log.i(TAG, "Mobs database loaded: $totalMobs mobs, $harvestables harvestables")
            Log.i(TAG, "Sample harvestables: ${harvestableTypeIds.take(5).map { typeId ->
                mobsById[typeId]?.let { "${it.uniqueName}(T${it.tier})" } ?: typeId.toString()
            }}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load mobs database: ${e.message}")
            isLoaded = false
        }
    }

    /**
     * Normalize resource type from loot field
     * Maps: HIDE_CRITTER, FIBER_GUARDIAN, etc. -> Hide, Fiber, etc.
     */
    private fun normalizeResourceType(lootType: String?): String? {
        if (lootType == null) return null

        val upper = lootType.uppercase()

        // Ignore non-resource types
        if (upper.startsWith("SILVERCOINS") || upper.startsWith("DEADRAT")) {
            return null
        }

        // Check for resource type prefix (order matters!)
        return when {
            upper.startsWith("HIDE") || upper.startsWith("LEATHER") -> "Hide"
            upper.startsWith("FIBER") -> "Fiber"
            upper.startsWith("WOOD") -> "Log"
            upper.startsWith("ROCK") || upper.startsWith("STONE") -> "Rock"
            upper.startsWith("ORE") -> "Ore"
            else -> null
        }
    }

    /**
     * Get mob info by TypeID
     */
    fun getMobInfo(typeId: Int): MobInfo? = mobsById[typeId]

    /**
     * Check if TypeID is a harvestable resource mob
     */
    fun isHarvestable(typeId: Int): Boolean = harvestableTypeIds.contains(typeId)

    /**
     * Get resource info if mob is harvestable
     */
    fun getResourceInfo(typeId: Int): ResourceInfo? {
        val info = mobsById[typeId] ?: return null
        if (!info.isHarvestable) return null
        return ResourceInfo(
            type = info.resourceType ?: return null,
            tier = info.resourceTier
        )
    }

    /**
     * Get TypeID by unique name
     */
    fun getTypeIdByName(uniqueName: String): Int? = mobsByName[uniqueName]

    /**
     * Get all harvestable TypeIDs
     */
    fun getAllHarvestableTypeIds(): Set<Int> = harvestableTypeIds.toSet()

    /**
     * Get mobs by resource type
     */
    fun getMobsByResourceType(resourceType: String): List<MobInfo> {
        return mobsById.values.filter { it.resourceType == resourceType }
    }

    /**
     * Get mobs by tier
     */
    fun getMobsByTier(tier: Int): List<MobInfo> {
        return mobsById.values.filter { it.tier == tier }
    }

    // Data classes

    /**
     * Mob entry from mobs.min.json
     */
    data class MobEntry(
        @SerializedName("u") val u: String?,      // uniqueName
        @SerializedName("t") val t: Int,          // tier
        @SerializedName("c") val c: String?,      // category
        @SerializedName("n") val n: String?,      // nameLocaleTag
        @SerializedName("l") val l: String?,      // loot type
        @SerializedName("lt") val lt: Int?,       // loot tier
        @SerializedName("fame") val fame: Int?,   // fame
        @SerializedName("hp") val hp: Int?,       // hitpoints
        @SerializedName("avatar") val avatar: String?,
        @SerializedName("danger") val danger: String?
    )

    /**
     * Processed mob info
     */
    data class MobInfo(
        val typeId: Int,
        val uniqueName: String,
        val tier: Int,
        val category: String,
        val nameLocaleTag: String,
        val resourceType: String?,  // Hide, Fiber, Log, Rock, Ore
        val resourceTier: Int,
        val isHarvestable: Boolean
    )

    /**
     * Resource drop info
     */
    data class ResourceInfo(
        val type: String,
        val tier: Int
    )
                  }
