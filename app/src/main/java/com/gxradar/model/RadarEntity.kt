package com.gxradar.model

/**
 * Entity types recognized by the radar system.
 * Each type maps to specific rendering rules (color, shape, size).
 */
enum class EntityType {
    // Resources
    RESOURCE_FIBER,
    RESOURCE_ORE,
    RESOURCE_LOGS,
    RESOURCE_ROCK,
    RESOURCE_HIDE,
    RESOURCE_CROP,

    // Mobs
    NORMAL_MOB,
    ENCHANTED_MOB,
    BOSS_MOB,
    MIST_BOSS,

    // Players
    PLAYER,
    FRIENDLY_PLAYER,
    HOSTILE_PLAYER,
    NEUTRAL_PLAYER,

    // Objects
    SILVER,
    MIST_WISP,
    CHEST,
    DUNGEON_PORTAL,

    // Unknown/Fallback
    UNKNOWN
}

/**
 * Data class representing an entity on the radar.
 *
 * @param id Unique entity identifier from Photon protocol (objectId)
 * @param type Entity type for rendering
 * @param worldX World X coordinate in Albion coordinate space
 * @param worldY World Y coordinate in Albion coordinate space
 * @param typeName Raw type string from Photon (e.g., "T4_FIBER@2")
 * @param tier Entity tier (0 if not applicable, 1-8 for resources/mobs)
 * @param enchant Enchantment level (0-4, only applies to T4+ resources)
 * @param name Display name (player name, empty for non-players)
 * @param guild Guild name for players
 * @param alliance Alliance name for players
 * @param healthPercent Current health percentage (0.0-1.0)
 * @param isBoss Whether this mob is a boss variant
 */
data class RadarEntity(
    val id: Int,
    val type: EntityType,
    val worldX: Float,
    val worldY: Float,
    val typeName: String = "",
    val tier: Int = 0,
    val enchant: Int = 0,
    val name: String = "",
    val guild: String = "",
    val alliance: String = "",
    val healthPercent: Float = 1.0f,
    val isBoss: Boolean = false
) {
    /**
     * Calculate distance from a reference point
     */
    fun distanceFrom(x: Float, y: Float): Float {
        val dx = worldX - x
        val dy = worldY - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Check if entity is a resource type
     */
    fun isResource(): Boolean = type in listOf(
        EntityType.RESOURCE_FIBER,
        EntityType.RESOURCE_ORE,
        EntityType.RESOURCE_LOGS,
        EntityType.RESOURCE_ROCK,
        EntityType.RESOURCE_HIDE,
        EntityType.RESOURCE_CROP
    )

    /**
     * Check if entity is a mob type
     */
    fun isMob(): Boolean = type in listOf(
        EntityType.NORMAL_MOB,
        EntityType.ENCHANTED_MOB,
        EntityType.BOSS_MOB,
        EntityType.MIST_BOSS
    )

    /**
     * Check if entity is a player type
     */
    fun isPlayer(): Boolean = type in listOf(
        EntityType.PLAYER,
        EntityType.FRIENDLY_PLAYER,
        EntityType.HOSTILE_PLAYER,
        EntityType.NEUTRAL_PLAYER
    )

    /**
     * Get tier display string (e.g., "T4.2" for Tier 4 Enchant 2)
     */
    fun getTierDisplay(): String {
        return if (tier > 0) {
            if (enchant > 0) "T$tier.$enchant" else "T$tier"
        } else ""
    }

    companion object {
        /**
         * Parse tier from type name string
         * Format: T{tier}_{TYPE}[@{enchant}]
         * Example: "T4_FIBER@2" -> tier=4, enchant=2
         */
        fun parseTierFromTypeName(typeName: String): Pair<Int, Int> {
            if (typeName.isEmpty() || !typeName.startsWith("T")) {
                return Pair(0, 0)
            }

            try {
                // Extract tier (digit after T)
                val tierEnd = typeName.indexOf("_")
                if (tierEnd < 0) return Pair(0, 0)

                val tierStr = typeName.substring(1, tierEnd)
                val tier = tierStr.toIntOrNull() ?: return Pair(0, 0)

                // Extract enchant (after @ if present)
                val enchantStart = typeName.indexOf("@")
                val enchant = if (enchantStart >= 0) {
                    typeName.substring(enchantStart + 1).toIntOrNull() ?: 0
                } else {
                    0
                }

                return Pair(tier, enchant)
            } catch (e: Exception) {
                return Pair(0, 0)
            }
        }

        /**
         * Determine resource type from type name string
         */
        fun getResourceTypeFromTypeName(typeName: String): EntityType {
            val upper = typeName.uppercase()
            return when {
                upper.contains("FIBER") -> EntityType.RESOURCE_FIBER
                upper.contains("ORE") || upper.contains("IRON") || upper.contains("STEEL") ||
                upper.contains("TITANIUM") || upper.contains("RUNITE") -> EntityType.RESOURCE_ORE
                upper.contains("WOOD") || upper.contains("LOG") -> EntityType.RESOURCE_LOGS
                upper.contains("ROCK") || upper.contains("STONE") -> EntityType.RESOURCE_ROCK
                upper.contains("HIDE") || upper.contains("LEATHER") -> EntityType.RESOURCE_HIDE
                upper.contains("WHEAT") || upper.contains("CROP") -> EntityType.RESOURCE_CROP
                else -> EntityType.UNKNOWN
            }
        }
    }
}
