package com.gxradar.overlay

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.gxradar.MainApplication
import com.gxradar.model.EntityType
import com.gxradar.model.RadarEntity
import kotlin.math.sqrt

/**
 * Radar Surface View
 *
 * Renders the radar overlay at 30 FPS using SurfaceView canvas.
 * Draws entities as colored dots centered on the local player's world position.
 *
 * Entity Types (removed Crop - not useful for radar):
 * - Resources: FIBER, ORE, LOGS, ROCK, HIDE (T1-T8, enchant 0-4)
 * - Mobs: NORMAL, ENCHANTED, BOSS, MIST_BOSS
 * - Players: PLAYER, FRIENDLY, HOSTILE, NEUTRAL
 * - Objects: SILVER, CHEST, DUNGEON_PORTAL, MIST_WISP
 */
class RadarSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "RadarSurfaceView"

        // Render settings
        private const val DEFAULT_RADIUS = 150.0f
        private const val DOT_SIZE = 6.0f
        private const val PLAYER_DOT_SIZE = 8.0f
        private const val BOSS_DOT_SIZE = 10.0f
    }

    // Paint objects
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val circlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 10f
        isAntiAlias = true
    }

    // Entity colors (removed Crop - using for Fishing zones instead)
    private val colorFiber = Color.parseColor("#4CAF50")      // Green
    private val colorOre = Color.parseColor("#9E9E9E")        // Gray
    private val colorLogs = Color.parseColor("#8D6E63")       // Brown
    private val colorRock = Color.parseColor("#78909C")       // Blue-gray
    private val colorHide = Color.parseColor("#A1887F")       // Tan
    private val colorFishing = Color.parseColor("#00BCD4")    // Cyan (was Crop)

    private val colorEnchant1 = Color.parseColor("#4CAF50")   // Green
    private val colorEnchant2 = Color.parseColor("#2196F3")   // Blue
    private val colorEnchant3 = Color.parseColor("#9C27B0")   // Purple
    private val colorEnchant4 = Color.parseColor("#FFD700")   // Gold

    private val colorMobNormal = Color.parseColor("#FF5722")  // Deep Orange
    private val colorMobBoss = Color.parseColor("#F44336")    // Red
    private val colorMobEnchanted = Color.parseColor("#9C27B0") // Purple

    private val colorPlayerFriendly = Color.parseColor("#4CAF50") // Green
    private val colorPlayerHostile = Color.parseColor("#F44336")  // Red
    private val colorPlayerNeutral = Color.parseColor("#FFFFFF")  // White

    private val colorSilver = Color.parseColor("#C0C0C0")     // Silver
    private val colorChest = Color.parseColor("#FF9800")      // Orange
    private val colorDungeon = Color.parseColor("#9C27B0")    // Purple
    private val colorMist = Color.parseColor("#00BCD4")       // Cyan

    // Entity data
    private val entities = java.util.concurrent.ConcurrentHashMap<Int, RadarEntity>()

    // Local player position
    @Volatile
    private var localX = 0f

    @Volatile
    private var localY = 0f

    // Radar settings
    private var radarRadius = DEFAULT_RADIUS
    private var showCircle = true
    private var showLabels = false

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)

        loadSettings()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        radarRadius = minOf(width, height) / 2f * 0.9f
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed")
    }

    /**
     * Request a render frame
     */
    fun requestRender() {
        val canvas = holder.lockCanvas() ?: return

        try {
            drawRadar(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * Draw the radar
     */
    private fun drawRadar(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val centerX = width / 2
        val centerY = height / 2

        // Clear canvas with transparent background
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Draw radar circle background
        if (showCircle) {
            canvas.drawCircle(centerX, centerY, radarRadius, backgroundPaint)
            canvas.drawCircle(centerX, centerY, radarRadius, circlePaint)
        }

        // Draw center crosshair
        canvas.drawLine(centerX - 10, centerY, centerX + 10, centerY, circlePaint)
        canvas.drawLine(centerX, centerY - 10, centerX, centerY + 10, circlePaint)

        // Draw entities
        val prefs = MainApplication.getInstance().sharedPreferences
        val scale = prefs.getInt(MainApplication.KEY_RADAR_SCALE, MainApplication.DEFAULT_RADAR_SCALE) / 100f

        entities.values.forEach { entity ->
            // Calculate screen position
            val screenX = centerX + (entity.worldX - localX) * scale
            val screenY = centerY + (entity.worldY - localY) * scale

            // Check if within radar bounds
            val dx = screenX - centerX
            val dy = screenY - centerY
            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            if (distance > radarRadius) {
                return@forEach // Skip entities outside radar
            }

            // Check visibility settings
            if (!shouldDrawEntity(entity, prefs)) {
                return@forEach
            }

            // Draw entity
            drawEntity(canvas, entity, screenX, screenY, distance)
        }
    }

    /**
     * Check if entity should be drawn based on settings
     */
    private fun shouldDrawEntity(entity: RadarEntity, prefs: android.content.SharedPreferences): Boolean {
        return when (entity.type) {
            EntityType.RESOURCE_FIBER -> prefs.getBoolean(MainApplication.KEY_HARVESTING_FIBER, true)
            EntityType.RESOURCE_ORE -> prefs.getBoolean(MainApplication.KEY_HARVESTING_ORE, true)
            EntityType.RESOURCE_LOGS -> prefs.getBoolean(MainApplication.KEY_HARVESTING_WOOD, true)
            EntityType.RESOURCE_ROCK -> prefs.getBoolean(MainApplication.KEY_HARVESTING_ROCK, true)
            EntityType.RESOURCE_HIDE -> prefs.getBoolean(MainApplication.KEY_HARVESTING_HIDE, true)
            EntityType.NORMAL_MOB -> prefs.getBoolean(MainApplication.KEY_MOB_ENEMY, true)
            EntityType.ENCHANTED_MOB -> prefs.getBoolean(MainApplication.KEY_MOB_ENEMY, true)
            EntityType.BOSS_MOB -> prefs.getBoolean(MainApplication.KEY_MOB_BOSS, true)
            EntityType.MIST_BOSS -> prefs.getBoolean(MainApplication.KEY_MOB_MIST_BOSS, true)
            EntityType.PLAYER, EntityType.FRIENDLY_PLAYER, EntityType.HOSTILE_PLAYER, EntityType.NEUTRAL_PLAYER ->
                prefs.getBoolean(MainApplication.KEY_PLAYER_DOT, true)
            EntityType.CHEST -> prefs.getBoolean(MainApplication.KEY_CHEST, true)
            EntityType.DUNGEON_PORTAL -> prefs.getBoolean(MainApplication.KEY_DUNGEON, true)
            EntityType.MIST_WISP -> prefs.getBoolean(MainApplication.KEY_MIST, true)
            EntityType.SILVER -> true
            EntityType.UNKNOWN -> false
        }
    }

    /**
     * Draw an entity on the canvas
     */
    private fun drawEntity(canvas: Canvas, entity: RadarEntity, x: Float, y: Float, distance: Float) {
        val dotSize = when {
            entity.isBoss -> BOSS_DOT_SIZE
            entity.isPlayer() -> PLAYER_DOT_SIZE
            entity.enchant > 0 -> DOT_SIZE + entity.enchant
            else -> DOT_SIZE
        }

        // Get color based on entity type
        val color = getEntityColor(entity)

        // Draw main dot
        dotPaint.color = adjustAlphaByTier(color, entity.tier)
        canvas.drawCircle(x, y, dotSize, dotPaint)

        // Draw enchant ring
        if (entity.enchant > 0 && entity.isResource()) {
            val ringColor = when (entity.enchant) {
                1 -> colorEnchant1
                2 -> colorEnchant2
                3 -> colorEnchant3
                4 -> colorEnchant4
                else -> colorEnchant1
            }
            ringPaint.color = ringColor
            canvas.drawCircle(x, y, dotSize + 3, ringPaint)
        }

        // Draw label if enabled
        if (showLabels && entity.name.isNotEmpty()) {
            canvas.drawText(entity.name, x + dotSize + 2, y + 4, textPaint)
        }
    }

    /**
     * Get color for an entity
     */
    private fun getEntityColor(entity: RadarEntity): Int {
        return when (entity.type) {
            EntityType.RESOURCE_FIBER -> colorFiber
            EntityType.RESOURCE_ORE -> colorOre
            EntityType.RESOURCE_LOGS -> colorLogs
            EntityType.RESOURCE_ROCK -> colorRock
            EntityType.RESOURCE_HIDE -> colorHide
            EntityType.NORMAL_MOB -> colorMobNormal
            EntityType.ENCHANTED_MOB -> colorMobEnchanted
            EntityType.BOSS_MOB, EntityType.MIST_BOSS -> colorMobBoss
            EntityType.PLAYER, EntityType.NEUTRAL_PLAYER -> colorPlayerNeutral
            EntityType.FRIENDLY_PLAYER -> colorPlayerFriendly
            EntityType.HOSTILE_PLAYER -> colorPlayerHostile
            EntityType.SILVER -> colorSilver
            EntityType.CHEST -> colorChest
            EntityType.DUNGEON_PORTAL -> colorDungeon
            EntityType.MIST_WISP -> colorMist
            EntityType.UNKNOWN -> Color.GRAY
        }
    }

    /**
     * Adjust alpha based on tier (T1 dim -> T8 bright)
     */
    private fun adjustAlphaByTier(color: Int, tier: Int): Int {
        if (tier <= 0) return color

        val baseAlpha = 0.5f
        val tierBoost = (tier - 1) * 0.07f
        val alpha = minOf(1.0f, baseAlpha + tierBoost)

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return Color.argb((alpha * 255).toInt(), r, g, b)
    }

    /**
     * Update entities from external source
     */
    fun updateEntities(newEntities: Map<Int, RadarEntity>) {
        entities.clear()
        entities.putAll(newEntities)
    }

    /**
     * Update local player position
     */
    fun updateLocalPosition(x: Float, y: Float) {
        localX = x
        localY = y
    }

    /**
     * Load settings from SharedPreferences
     */
    private fun loadSettings() {
        val prefs = MainApplication.getInstance().sharedPreferences
        showCircle = prefs.getBoolean(MainApplication.KEY_RADAR_SHOW_CIRCLE, true)
    }

    /**
     * Get entity count
     */
    fun getEntityCount(): Int = entities.size
}
