package com.gxradar.parser

import android.util.Log
import com.gxradar.model.RadarEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photon Protocol 16 Parser
 *
 * Parses UDP payloads from Albion Online (port 5056) according to
 * the Photon Protocol 16 specification.
 *
 * PHOTON HEADER (12 bytes, BIG-ENDIAN):
 *   [0–1]   PeerID       uint16  big-endian
 *   [2]     Flags        uint8
 *   [3]     CmdCount     uint8   — number of commands that follow
 *   [4–7]   Timestamp    uint32  — skip
 *   [8–11]  Challenge    uint32  — skip
 *
 * COMMAND (repeats CmdCount times):
 *   [0]     CmdType      uint8   6=reliable, 7=unreliable  ← parse BOTH
 *   [1]     ChannelId    uint8   — parse ALL channels, do not filter
 *   [2]     CmdFlags     uint8
 *   [3]     Reserved     uint8   — skip
 *   [4–7]   CmdLength    uint32  — total bytes including this 12-byte header
 *   [8–11]  ReliableSeq  uint32  — skip
 *   [12+]   Payload      (CmdLength - 12 bytes)
 *
 * PAYLOAD FIRST BYTE = Message Type:
 *   0x02 = OperationRequest   (client → server) — SKIP
 *   0x03 = OperationResponse  (server → client) — SKIP
 *   0x04 = Event              — PARSE THIS
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
     * Parse a Photon UDP payload and extract events
     *
     * @param payload Raw UDP payload bytes
     * @return List of parsed Photon events
     */
    fun parse(payload: ByteArray): List<PhotonEvent> {
        val events = mutableListOf<PhotonEvent>()

        if (payload.size < PHOTON_HEADER_SIZE) {
            return events
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

                    // Parse payload
                    val event = parsePayload(cmdPayload)
                    if (event != null) {
                        events.add(event)
                    }
                } else {
                    // Skip to next command
                    buffer.position(cmdStartPos + cmdLength)
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Photon packet: ${e.message}")
        }

        return events
    }

    /**
     * Parse a command payload and extract event data
     */
    private fun parsePayload(payload: ByteArray): PhotonEvent? {
        if (payload.isEmpty()) {
            return null
        }

        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val messageType = buffer.get()

        // Only process events (0x04)
        if (messageType != MSG_TYPE_EVENT) {
            return null
        }

        // Parse event
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

        return PhotonEvent(eventCode, params)
    }

    /**
     * Parse a Photon-typed value from the buffer
     */
    private fun parseValue(buffer: ByteBuffer): Any? {
        if (buffer.remaining() < 1) {
            return null
        }

        val typeCode = buffer.get()

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
                    if (buffer.remaining() >= length) {
                        val bytes = ByteArray(length)
                        buffer.get(bytes)
                        bytes
                    } else null
                } else null
            }

            TYPE_INT_ARRAY -> {
                if (buffer.remaining() >= 4) {
                    val count = buffer.int
                    val ints = IntArray(count)
                    for (i in 0 until count) {
                        if (buffer.remaining() >= 4) {
                            ints[i] = buffer.int
                        } else break
                    }
                    ints
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
                Log.w(TAG, "Unknown Photon type code: 0x${typeCode.toString(16)}")
                null
            }
        }
    }

    /**
     * Data class representing a parsed Photon event
     */
    data class PhotonEvent(
        val eventCode: Int,
        val params: Map<Int, Any?>
    )
}
