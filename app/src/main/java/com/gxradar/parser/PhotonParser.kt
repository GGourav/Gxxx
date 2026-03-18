package com.gxradar.parser

import android.util.Log
import com.gxradar.model.RadarEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photon Protocol 16 Parser - Enhanced Version
 *
 * Parses UDP payloads from Albion Online (port 5056) according to
 * the Photon Protocol 16 specification.
 *
 * ENHANCEMENTS:
 * - Added OperationRequest parsing (messageType 0x02)
 * - Added OperationResponse parsing (messageType 0x03)
 * - Fixed Move event position extraction (Little-Endian)
 * - Added proper ByteArray handling
 *
 * PHOTON HEADER (12 bytes, BIG-ENDIAN):
 *   [0–1]   PeerID       uint16  big-endian
 *   [2]     Flags        uint8
 *   [3]     CmdCount     uint8   — number of commands that follow
 *   [4–7]   Timestamp    uint32  — skip
 *   [8–11]  Challenge    uint32  — skip
 *
 * COMMAND (repeats CmdCount times):
 *   [0]     CmdType      uint8   6=reliable, 7=unreliable
 *   [1]     ChannelId    uint8   — parse ALL channels
 *   [2]     CmdFlags     uint8
 *   [3]     Reserved     uint8   — skip
 *   [4–7]   CmdLength    uint32  — total bytes including 12-byte header
 *   [8–11]  ReliableSeq  uint32  — skip
 *   [12+]   Payload      (CmdLength - 12 bytes)
 *
 * PAYLOAD FIRST BYTE = Message Type:
 *   0x02 = OperationRequest   (client → server) — PARSE for player movement
 *   0x03 = OperationResponse  (server → client) — PARSE for map changes
 *   0x04 = Event              — PARSE for entity updates
 */
class PhotonParser {

    companion object {
        private const val TAG = "PhotonParser"

        // Photon header constants
        private const val PHOTON_HEADER_SIZE = 12
        private const val COMMAND_HEADER_SIZE = 12

        // Command types
        private const val CMD_TYPE_RELIABLE = 6
        private const val CMD_TYPE_UNRELIABLE = 7

        // Message types
        private const val MSG_TYPE_OPERATION_REQUEST = 0x02.toByte()
        private const val MSG_TYPE_OPERATION_RESPONSE = 0x03.toByte()
        private const val MSG_TYPE_EVENT = 0x04.toByte()

        // Photon type codes (complete)
        private const val TYPE_NULL = 0x2A.toByte()        // '*'
        private const val TYPE_BOOLEAN = 0x6F.toByte()     // 'o'
        private const val TYPE_BYTE = 0x62.toByte()        // 'b'
        private const val TYPE_SHORT = 0x6B.toByte()       // 'k'
        private const val TYPE_INTEGER = 0x69.toByte()     // 'i'
        private const val TYPE_LONG = 0x6C.toByte()        // 'l'
        private const val TYPE_FLOAT = 0x66.toByte()       // 'f'
        private const val TYPE_DOUBLE = 0x64.toByte()      // 'd'
        private const val TYPE_STRING = 0x73.toByte()      // 's'
        private const val TYPE_BYTE_ARRAY = 0x78.toByte()  // 'x'
        private const val TYPE_INT_ARRAY = 0x6E.toByte()   // 'n'
        private const val TYPE_ARRAY = 0x61.toByte()       // 'a'
        private const val TYPE_HASHTABLE = 0x68.toByte()   // 'h'
        private const val TYPE_DICTIONARY = 0x44.toByte()  // 'D'
        private const val TYPE_OBJECT_ARRAY = 0x7A.toByte() // 'z'
    }

    /**
     * Parse a Photon UDP payload and extract messages
     *
     * @param payload Raw UDP payload bytes
     * @return List of parsed Photon messages (events, requests, responses)
     */
    fun parse(payload: ByteArray): List<PhotonMessage> {
        val messages = mutableListOf<PhotonMessage>()

        if (payload.size < PHOTON_HEADER_SIZE) {
            return messages
        }

        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)

        try {
            // Parse Photon header
            val peerId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.get().toInt() and 0xFF
            val cmdCount = buffer.get().toInt() and 0xFF

            // Skip timestamp and challenge
            buffer.int // timestamp
            buffer.int // challenge

            // Parse each command
            for (i in 0 until cmdCount) {
                if (buffer.remaining() < COMMAND_HEADER_SIZE) {
                    break
                }

                val cmdStartPos = buffer.position()

                // Parse command header
                val cmdType = buffer.get().toInt() and 0xFF
                val channelId = buffer.get().toInt() and 0xFF
                val cmdFlags = buffer.get().toInt() and 0xFF
                buffer.get() // reserved

                val cmdLength = buffer.int
                buffer.int // reliable sequence

                // Calculate payload length
                val payloadLength = cmdLength - COMMAND_HEADER_SIZE
                if (payloadLength <= 0 || buffer.remaining() < payloadLength) {
                    buffer.position(cmdStartPos + cmdLength)
                    continue
                }

                // Only process reliable and unreliable commands
                if (cmdType == CMD_TYPE_RELIABLE || cmdType == CMD_TYPE_UNRELIABLE) {
                    // Extract payload
                    val cmdPayload = ByteArray(payloadLength)
                    buffer.get(cmdPayload)

                    // Parse payload - handle all message types
                    val message = parsePayload(cmdPayload)
                    if (message != null) {
                        messages.add(message)
                    }
                } else {
                    // Skip to next command
                    buffer.position(cmdStartPos + cmdLength)
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Photon packet: ${e.message}")
        }

        return messages
    }

    /**
     * Parse a command payload and extract message data
     * Handles Event (0x04), OperationRequest (0x02), OperationResponse (0x03)
     */
    private fun parsePayload(payload: ByteArray): PhotonMessage? {
        if (payload.isEmpty()) {
            return null
        }

        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val messageType = buffer.get()

        return when (messageType) {
            MSG_TYPE_EVENT -> parseEvent(buffer)
            MSG_TYPE_OPERATION_REQUEST -> parseOperationRequest(buffer)
            MSG_TYPE_OPERATION_RESPONSE -> parseOperationResponse(buffer)
            else -> {
                Log.d(TAG, "Unknown message type: 0x${String.format("%02X", messageType)}")
                null
            }
        }
    }

    /**
     * Parse Event message (0x04)
     */
    private fun parseEvent(buffer: ByteBuffer): PhotonEvent? {
        val eventCode = buffer.get().toInt() and 0xFF
        val paramCount = buffer.short.toInt() and 0xFFFF

        val params = HashMap<Int, Any?>()

        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) {
                break
            }

            val key = buffer.get().toInt() and 0xFF
            val value = parseValue(buffer)

            params[key] = value
        }

        // CRITICAL: Extract positions for Move event (code 3)
        // Positions are Little-Endian in ByteArray at key 1
        if (eventCode == 3 && params[1] is ByteArray) {
            val bytes = params[1] as ByteArray
            if (bytes.size >= 17) {
                // Extract Little-Endian floats at offsets 9 and 13
                val leBuffer = ByteBuffer.wrap(bytes)
                leBuffer.order(ByteOrder.LITTLE_ENDIAN)

                leBuffer.position(9)
                val posX = leBuffer.float
                leBuffer.position(13)
                val posY = leBuffer.float

                params[4] = posX
                params[5] = posY
                params[252] = 3 // Event code marker
            }
        }

        return PhotonEvent(eventCode, params)
    }

    /**
     * Parse OperationRequest message (0x02)
     * Used for local player movement (Operation 21)
     */
    private fun parseOperationRequest(buffer: ByteBuffer): PhotonRequest? {
        val operationCode = buffer.get().toInt() and 0xFF
        val paramCount = buffer.short.toInt() and 0xFFFF

        val params = HashMap<Int, Any?>()

        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) {
                break
            }

            val key = buffer.get().toInt() and 0xFF
            val value = parseValue(buffer)

            params[key] = value
        }

        // Mark message type
        params[253] = operationCode

        Log.d(TAG, "OperationRequest: code=$operationCode, params=${params.keys}")

        return PhotonRequest(operationCode, params)
    }

    /**
     * Parse OperationResponse message (0x03)
     * Used for map changes (Operation 35) and join data (Operation 2)
     */
    private fun parseOperationResponse(buffer: ByteBuffer): PhotonResponse? {
        val operationCode = buffer.get().toInt() and 0xFF
        val returnCode = buffer.short.toInt() and 0xFFFF

        // Debug message has inline type code
        val debugMsgTypeCode = buffer.get().toInt() and 0xFF
        val debugMessage = parseValue(buffer, debugMsgTypeCode.toByte())

        val paramCount = buffer.short.toInt() and 0xFFFF

        val params = HashMap<Int, Any?>()

        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) {
                break
            }

            val key = buffer.get().toInt() and 0xFF
            val value = parseValue(buffer)

            params[key] = value
        }

        // Mark message type
        params[253] = operationCode

        // Extract position from JoinFinished (Operation 2)
        if (operationCode == 2) {
            // Position may be at key 9 as Buffer or Array
            when (val posData = params[9]) {
                is ByteArray -> {
                    if (posData.size >= 8) {
                        val leBuffer = ByteBuffer.wrap(posData)
                        leBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        params[4] = leBuffer.float
                        params[5] = leBuffer.getFloat(4)
                    }
                }
                is List<*> -> {
                    if (posData.size >= 2) {
                        params[4] = (posData[0] as? Number)?.toFloat()
                        params[5] = (posData[1] as? Number)?.toFloat()
                    }
                }
            }
        }

        Log.d(TAG, "OperationResponse: code=$operationCode, return=$returnCode")

        return PhotonResponse(operationCode, returnCode, debugMessage, params)
    }

    /**
     * Parse a Photon-typed value from the buffer
     */
    private fun parseValue(buffer: ByteBuffer): Any? {
        if (buffer.remaining() < 1) {
            return null
        }

        val typeCode = buffer.get()
        return parseValue(buffer, typeCode)
    }

    /**
     * Parse a Photon-typed value with known type code
     */
    private fun parseValue(buffer: ByteBuffer, typeCode: Byte): Any? {
        return when (typeCode) {
            TYPE_NULL -> null

            TYPE_BOOLEAN -> {
                if (buffer.remaining() >= 1) {
                    buffer.get().toInt() != 0
                } else null
            }

            TYPE_BYTE -> {
                if (buffer.remaining() >= 1) {
                    buffer.get().toInt() and 0xFF
                } else null
            }

            TYPE_SHORT -> {
                if (buffer.remaining() >= 2) {
                    buffer.short.toInt()
                } else null
            }

            TYPE_INTEGER -> {
                if (buffer.remaining() >= 4) {
                    buffer.int
                } else null
            }

            TYPE_LONG -> {
                if (buffer.remaining() >= 8) {
                    buffer.long
                } else null
            }

            TYPE_FLOAT -> {
                if (buffer.remaining() >= 4) {
                    buffer.float
                } else null
            }

            TYPE_DOUBLE -> {
                if (buffer.remaining() >= 8) {
                    buffer.double
                } else null
            }

            TYPE_STRING -> {
                if (buffer.remaining() >= 2) {
                    val length = buffer.short.toInt() and 0xFFFF
                    if (buffer.remaining() >= length) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        String(bytes, Charsets.UTF_8)
                    } else null
                } else null
            }

            TYPE_BYTE_ARRAY -> {
                if (buffer.remaining() >= 4) {
                    val length = buffer.int
                    if (buffer.remaining() >= length && length >= 0 && length < 65536) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        bytes
                    } else null
                } else null
            }

            TYPE_INT_ARRAY -> {
                if (buffer.remaining() >= 4) {
                    val count = buffer.int
                    if (count >= 0 && count < 65536) {
                        val ints = IntArray(count)
                        for (i in 0 until count) {
                            if (buffer.remaining() >= 4) {
                                ints[i] = buffer.int
                            } else break
                        }
                        ints
                    } else null
                } else null
            }

            TYPE_ARRAY -> {
                if (buffer.remaining() >= 3) {
                    val length = buffer.short.toInt() and 0xFFFF
                    val elemType = buffer.get()
                    val items = ArrayList<Any?>(length)
                    for (i in 0 until length) {
                        // Push back the element type for recursive parsing
                        buffer.position(buffer.position() - 1)
                        items.add(parseValue(buffer))
                    }
                    items
                } else null
            }

            TYPE_HASHTABLE -> {
                if (buffer.remaining() >= 2) {
                    val count = buffer.short.toInt() and 0xFFFF
                    val map = HashMap<Any?, Any?>(count)
                    for (i in 0 until count) {
                        val key = parseValue(buffer)
                        val value = parseValue(buffer)
                        map[key] = value
                    }
                    map
                } else null
            }

            TYPE_DICTIONARY -> {
                if (buffer.remaining() >= 4) {
                    val keyType = buffer.get()
                    val valType = buffer.get()
                    val count = buffer.short.toInt() and 0xFFFF
                    val map = HashMap<Any?, Any?>(count)
                    for (i in 0 until count) {
                        val key = parseValue(buffer)
                        val value = parseValue(buffer)
                        map[key] = value
                    }
                    map
                } else null
            }

            TYPE_OBJECT_ARRAY -> {
                if (buffer.remaining() >= 2) {
                    val length = buffer.short.toInt() and 0xFFFF
                    val items = ArrayList<Any?>(length)
                    for (i in 0 until length) {
                        items.add(parseValue(buffer))
                    }
                    items
                } else null
            }

            else -> {
                Log.w(TAG, "Unknown Photon type code: 0x${String.format("%02X", typeCode)}")
                null
            }
        }
    }

    // ==================== MESSAGE DATA CLASSES ====================

    /**
     * Base interface for all Photon messages
     */
    sealed class PhotonMessage {
        abstract val params: Map<Int, Any?>
    }

    /**
     * Event message (0x04)
     */
    data class PhotonEvent(
        val eventCode: Int,
        override val params: Map<Int, Any?>
    ) : PhotonMessage() {
        /**
         * Get entity ID (usually key 0)
         */
        fun getEntityId(): Int? = (params[0] as? Number)?.toInt()

        /**
         * Get position X (key 4 for Move, extracted from ByteArray)
         */
        fun getPosX(): Float? = (params[4] as? Number)?.toFloat()

        /**
         * Get position Y (key 5 for Move, extracted from ByteArray)
         */
        fun getPosY(): Float? = (params[5] as? Number)?.toFloat()
    }

    /**
     * OperationRequest message (0x02)
     */
    data class PhotonRequest(
        val operationCode: Int,
        override val params: Map<Int, Any?>
    ) : PhotonMessage() {
        /**
         * Get position for Move operation (Op 21)
         */
        fun getPosition(): Pair<Float, Float>? {
            when (val posData = params[1]) {
                is ByteArray -> {
                    if (posData.size >= 8) {
                        val buffer = ByteBuffer.wrap(posData)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        return Pair(buffer.float, buffer.getFloat(4))
                    }
                }
                is List<*> -> {
                    if (posData.size >= 2) {
                        val x = (posData[0] as? Number)?.toFloat()
                        val y = (posData[1] as? Number)?.toFloat()
                        if (x != null && y != null) {
                            return Pair(x, y)
                        }
                    }
                }
            }
            return null
        }
    }

    /**
     * OperationResponse message (0x03)
     */
    data class PhotonResponse(
        val operationCode: Int,
        val returnCode: Int,
        val debugMessage: Any?,
        override val params: Map<Int, Any?>
    ) : PhotonMessage() {
        /**
         * Get map ID for ChangeCluster response (Op 35)
         */
        fun getMapId(): Int? = (params[0] as? Number)?.toInt()

        /**
         * Get position for JoinFinished response (Op 2)
         */
        fun getPosition(): Pair<Float, Float>? {
            when (val posData = params[9]) {
                is ByteArray -> {
                    if (posData.size >= 8) {
                        val buffer = ByteBuffer.wrap(posData)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        return Pair(buffer.float, buffer.getFloat(4))
                    }
                }
                is List<*> -> {
                    if (posData.size >= 2) {
                        val x = (posData[0] as? Number)?.toFloat()
                        val y = (posData[1] as? Number)?.toFloat()
                        if (x != null && y != null) {
                            return Pair(x, y)
                        }
                    }
                }
            }
            return null
        }

        /**
         * Check if in black zone (key 103 == 2)
         */
        fun isBlackZone(): Boolean = (params[103] as? Number)?.toInt() == 2
    }
}
