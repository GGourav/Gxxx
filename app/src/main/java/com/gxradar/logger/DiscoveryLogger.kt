package com.gxradar.logger

import android.content.Context
import android.util.Log
import com.gxradar.MainApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Discovery Logger for unknown Photon events and parameters.
 *
 * When the parser encounters unknown event codes or parameter keys,
 * this logger captures the full parameter structure for analysis.
 *
 * Features:
 * - Ring buffer for in-memory logging
 * - File-based persistence for discovery logs
 * - JSON output format for easy analysis
 * - Async logging to avoid blocking the parser thread
 */
class DiscoveryLogger(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryLogger"

        // Ring buffer size
        private const val BUFFER_SIZE = 1000

        // Log file name
        private const val LOG_FILE_NAME = "discovery_log.json"

        // Date format for timestamps
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // Ring buffer for recent entries
    private val buffer = ConcurrentLinkedQueue<LogEntry>()

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Log file
    private val logFile: File by lazy {
        File(context.filesDir, LOG_FILE_NAME)
    }

    /**
     * Log an unknown event code with its parameters
     */
    fun logUnknownEvent(eventCode: Int, params: Map<Int, Any?>) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            type = "UNKNOWN_EVENT",
            eventCode = eventCode,
            eventName = null,
            paramKey = null,
            params = params.toMap()
        )

        addToBuffer(entry)
        writeToFileAsync(entry)

        Log.d(TAG, "Unknown event: code=$eventCode, paramCount=${params.size}")
    }

    /**
     * Log a discovery event (known but unhandled or for mapping)
     */
    fun logDiscovery(eventName: String, paramKey: Int, params: Map<Int, Any?>) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            type = "DISCOVERY",
            eventCode = null,
            eventName = eventName,
            paramKey = paramKey,
            params = params.toMap()
        )

        addToBuffer(entry)

        // Only write to file if there are interesting params
        if (params.isNotEmpty()) {
            writeToFileAsync(entry)
        }

        Log.d(TAG, "Discovery: event=$eventName, key=$paramKey, paramCount=${params.size}")
    }

    /**
     * Add entry to ring buffer
     */
    private fun addToBuffer(entry: LogEntry) {
        // Remove oldest entries if buffer is full
        while (buffer.size >= BUFFER_SIZE) {
            buffer.poll()
        }
        buffer.offer(entry)
    }

    /**
     * Write entry to log file asynchronously
     */
    private fun writeToFileAsync(entry: LogEntry) {
        scope.launch {
            try {
                val json = entry.toJson()
                FileWriter(logFile, true).use { writer ->
                    writer.append(json.toString())
                    writer.append("\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write discovery log: ${e.message}")
            }
        }
    }

    /**
     * Get all buffered entries
     */
    fun getBufferedEntries(): List<LogEntry> {
        return buffer.toList()
    }

    /**
     * Get recent entries by type
     */
    fun getRecentEntries(type: String, limit: Int = 100): List<LogEntry> {
        return buffer
            .filter { it.type == type }
            .takeLast(limit)
    }

    /**
     * Clear the buffer
     */
    fun clearBuffer() {
        buffer.clear()
    }

    /**
     * Get log file path
     */
    fun getLogFilePath(): String = logFile.absolutePath

    /**
     * Clear log file
     */
    fun clearLogFile() {
        scope.launch {
            try {
                logFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear log file: ${e.message}")
            }
        }
    }

    /**
     * Export buffer to JSON
     */
    fun exportToJson(): JSONObject {
        val json = JSONObject()
        val entries = JSONObject()

        buffer.forEachIndexed { index, entry ->
            entries.put(index.toString(), entry.toJson())
        }

        json.put("version", MainApplication.getInstance().idMapRepository.getAllEventCodes().size)
        json.put("entries", entries)
        json.put("exportTime", DATE_FORMAT.format(Date()))

        return json
    }

    /**
     * Log entry data class
     */
    data class LogEntry(
        val timestamp: Long,
        val type: String,
        val eventCode: Int?,
        val eventName: String?,
        val paramKey: Int?,
        val params: Map<Int, Any?>
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("timestamp", timestamp)
                put("datetime", DATE_FORMAT.format(Date(timestamp)))
                put("type", type)
                eventCode?.let { put("eventCode", it) }
                eventName?.let { put("eventName", it) }
                paramKey?.let { put("paramKey", it) }

                // Convert params to JSON
                val paramsJson = JSONObject()
                params.forEach { (key, value) ->
                    paramsJson.put(key.toString(), value?.toString() ?: "null")
                }
                put("params", paramsJson)
            }
        }
    }
}
