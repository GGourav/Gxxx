package com.gxradar.parser

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photon Protocol 16 Parser - Enhanced Version
 *
 * Parses UDP payloads from Albion Online (port 5056) according to
 * the Photon Protocol 16 specification.
 */
class PhotonParser {

    companion object {
        private const val TAG = "PhotonParser"

        // Enable verbose logging for debugging
        private const val VERBOSE_LOGGING = true

        // Photon header constants
        private const val PHOTON_HEADER_SIZE = 12
        private const val COMMAND_HEADER_SIZE = 12

        // Command types - more complete list
        private const val CMD_TYPE_ACK_ONLY = 0
        private const val CMD_TYPE_CONNECT = 1
        private const val CMD_TYPE_VERIFY_CONNECT = 2
        private const val CMD_TYPE_DISCONNECT = 3
        private const val CMD_TYPE_PING = 4
        private const val CMD_TYPE_SEND_RELIABLE = 5
        private const val CMD_TYPE_SEND_UNRELIABLE = 6
        private const val CMD_TYPE_SEND_FRAGMENT = 7
        private const val CMD_TYPE_RELIABLE = 6
        private const val CMD_TYPE_UNRELIABLE = 7

        // Message types in payload
        private const val MSG_TYPE_OPERATION_REQUEST = 0x02.toByte()
        private const val MSG_TYPE_OPERATION_RESPONSE = 0x03.toByte()
        private const val MSG_TYPE_EVENT = 0x04.toByte()

        // Photon type codes
        private const val TYPE_NULL = 0x2A.toByte()
        private const val TYPE_BOOLEAN = 0x6F.toByte()
        private const val TYPE_BYTE = 0x62.toByte()
        private const val TYPE_SHORT = 0x6B.toByte()
        private const val TYPE_INTEGER = 0x69.toByte()
        private const val TYPE_LONG = 0x6C.toByte()
        private const val TYPE_FLOAT = 0x66.toByte()
        private const val TYPE_DOUBLE = 0x64.toByte()
        private const val TYPE_STRING = 0x73.toByte()
        private const val TYPE_BYTE_ARRAY = 0x78.toByte()
        private const val TYPE_INT_ARRAY = 0x6E.toByte()
        private const val TYPE_ARRAY = 0x61.toByte()
        private const val TYPE_HASHTABLE = 0x68.toByte()
        private const val TYPE_DICTIONARY = 0x44.toByte()
        private const val TYPE_OBJECT_ARRAY = 0x7A.toByte()
    }

    fun parse(payload: ByteArray): List<PhotonMessage> {
        val messages = mutableListOf<PhotonMessage>()

        if (payload.size < PHOTON_HEADER_SIZE) {
            if (VERBOSE_LOGGING) {
                Log.d(TAG, "Packet too small: ${payload.size} bytes")
            }
            return messages
        }

        if (VERBOSE_LOGGING) {
            Log.d(TAG, "Raw packet (${payload.size} bytes): ${toHexDump(payload, 64)}")
        }

        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)

        try {
            val peerId = buffer.short.toInt() and 0xFFFF
            val flags = buffer.get().toInt() and 0xFF
            val cmdCount = buffer.get().toInt() and 0xFF

            if (VERBOSE_LOGGING) {
                Log.d(TAG, "Photon Header: peerId=$peerId, flags=$flags, cmdCount=$cmdCount")
            }

            buffer.int // timestamp
            buffer.int // challenge

            for (i in 0 until cmdCount) {
                if (buffer.remaining() < COMMAND_HEADER_SIZE) {
                    Log.w(TAG, "Not enough data for command header at cmd $i")
                    break
                }

                val cmdStartPos = buffer.position()

                val cmdType = buffer.get().toInt() and 0xFF
                val channelId = buffer.get().toInt() and 0xFF
                val cmdFlags = buffer.get().toInt() and 0xFF
                buffer.get() // reserved

                val cmdLength = buffer.int
                buffer.int // reliable sequence

                if (VERBOSE_LOGGING) {
                    Log.d(TAG, "Cmd[$i]: type=$cmdType, channel=$channelId, flags=$cmdFlags, len=$cmdLength")
                }

                if (cmdLength < COMMAND_HEADER_SIZE || cmdLength > 65535) {
                    Log.w(TAG, "Invalid command length: $cmdLength")
                    break
                }

                val payloadLength = cmdLength - COMMAND_HEADER_SIZE
                if (payloadLength <= 0 || buffer.remaining() < payloadLength) {
                    if (VERBOSE_LOGGING) {
                        Log.d(TAG, "Skipping cmd[$i]: payloadLen=$payloadLength, remaining=${buffer.remaining()}")
                    }
                    buffer.position(cmdStartPos + cmdLength)
                    continue
                }

                if (cmdType == CMD_TYPE_SEND_RELIABLE ||
                    cmdType == CMD_TYPE_SEND_UNRELIABLE ||
                    cmdType == CMD_TYPE_RELIABLE ||
                    cmdType == CMD_TYPE_UNRELIABLE ||
                    cmdType == CMD_TYPE_SEND_FRAGMENT) {

                    val cmdPayload = ByteArray(payloadLength)
                    buffer.get(cmdPayload)

                    if (VERBOSE_LOGGING) {
                        Log.d(TAG, "Cmd[$i] payload (${payloadLength} bytes): ${toHexDump(cmdPayload, 32)}")
                    }

                    val message = parsePayload(cmdPayload)
                    if (message != null) {
                        messages.add(message)
                    }
                } else {
                    if (VERBOSE_LOGGING) {
                        Log.d(TAG, "Cmd[$i]: type $cmdType has no message payload, skipping")
                    }
                    buffer.position(cmdStartPos + cmdLength)
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Photon packet: ${e.message}")
            e.printStackTrace()
        }

        return messages
    }

    private fun toHexDump(data: ByteArray, maxBytes: Int = 64): String {
        val len = minOf(data.size, maxBytes)
        val sb = StringBuilder()
        for (i in 0 until len) {
            sb.append(String.format("%02X ", data[i]))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        if (data.size > maxBytes) sb.append("...")
        return sb.toString().trim()
    }

    private fun parsePayload(payload: ByteArray): PhotonMessage? {
        if (payload.isEmpty()) {
            return null
        }

        val buffer = ByteBuffer.wrap(payload)
        buffer.order(ByteOrder.BIG_ENDIAN)

        val messageType = buffer.get()

        if (VERBOSE_LOGGING) {
            Log.d(TAG, "Message type: 0x${String.format("%02X", messageType)} (${messageType.toInt() and 0xFF})")
        }

        return when (messageType) {
            MSG_TYPE_EVENT -> {
                if (VERBOSE_LOGGING) Log.d(TAG, "Parsing Event message")
                parseEvent(buffer)
            }
            MSG_TYPE_OPERATION_REQUEST -> {
                if (VERBOSE_LOGGING) Log.d(TAG, "Parsing OperationRequest message")
                parseOperationRequest(buffer)
            }
            MSG_TYPE_OPERATION_RESPONSE -> {
                if (VERBOSE_LOGGING) Log.d(TAG, "Parsing OperationResponse message")
                parseOperationResponse(buffer)
            }
            else -> {
                if (messageType != 0x00.toByte()) {
                    Log.d(TAG, "Unknown message type: 0x${String.format("%02X", messageType)}")
                }
                null
            }
        }
    }

    private fun parseEvent(buffer: ByteBuffer): PhotonEvent? {
        val eventCode = buffer.get().toInt() and 0xFF
        val paramCount = buffer.short.toInt() and 0xFFFF

        val params = HashMap<Int, Any?>()

        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) break

            val key = buffer.get().toInt() and 0xFF
            val value = parseValue(buffer)
            params[key] = value
        }

        if (eventCode == 3 && params[1] is ByteArray) {
            val bytes = params[1] as ByteArray
            if (bytes.size >= 17) {
                val leBuffer = ByteBuffer.wrap(bytes)
                leBuffer.order(ByteOrder.LITTLE_ENDIAN)

                leBuffer.position(9)
                val posX = leBuffer.float
                leBuffer.position(13)
                val posY = leBuffer.float

                params[4] = posX
                params[5] = posY
                params[252] = 3
            }
        }

        return PhotonEvent(eventCode, params)
    }

    private fun parseOperationRequest(buffer: ByteBuffer): PhotonRequest? {
        val operationCode = buffer.get().toInt() and 0xFF
        val paramCount = buffer.short.toInt() and 0xFFFF

        val params = HashMap<Int, Any?>()

        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) break

            val key = buffer.get().toInt() and 0xFF
            val value = parseValue(buffer)
            params[key] = value
        }

        params[253] = operationCode

        Log.d(TAG, "OperationRequest: code=$operationCode, params=${params.keys}")

        return PhotonRequest(operationCode, params)
    }

    private fun parseOperationResponse(buffer: ByteBuffer): PhotonResponse? {
        val operationCode = buffer.get().toInt() and 0xFF
        val returnCode = buffer.short.toInt() and 0xFFFF

        val debugMsgTypeCode = buffer.get().toInt() and 0xFF
        val debugMessage = parseValue(buffer, debugMsgTypeCode.toByte())

        val paramCount = buffer.short.toInt() and 0xFFFF

        val params = HashMap<Int, Any?>()

        for (i in 0 until paramCount) {
            if (buffer.remaining() < 2) break

            val key = buffer.get().toInt() and 0xFF
            val value = parseValue(buffer)
            params[key] = value
        }

        params[253] = operationCode

        if (operationCode == 2) {
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

    private fun parseValue(buffer: ByteBuffer): Any? {
        if (buffer.remaining() < 1) return null
        val typeCode = buffer.get()
        return parseValue(buffer, typeCode)
    }

    private fun parseValue(buffer: ByteBuffer, typeCode: Byte): Any? {
        return when (typeCode) {
            TYPE_NULL -> null

            TYPE_BOOLEAN -> {
                if (buffer.remaining() >= 1) buffer.get().toInt() != 0 else null
            }

            TYPE_BYTE -> {
                if (buffer.remaining() >= 1) buffer.get().toInt() and 0xFF else null
            }

            TYPE_SHORT -> {
                if (buffer.remaining() >= 2) buffer.short.toInt() else null
            }

            TYPE_INTEGER -> {
                if (buffer.remaining() >= 4) buffer.int else null
            }

            TYPE_LONG -> {
                if (buffer.remaining() >= 8) buffer.long else null
            }

            TYPE_FLOAT -> {
                if (buffer.remaining() >= 4) buffer.float else null
            }

            TYPE_DOUBLE -> {
                if (buffer.remaining() >= 8) buffer.double else null
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
                            if (buffer.remaining() >= 4) ints[i] = buffer.int else break
                        }
                        ints
                    } else null
                } else null
            }

            TYPE_ARRAY -> {
                if (buffer.remaining() >= 3) {
                    val length = buffer.short.toInt() and 0xFFFF
                    buffer.get() // elemType
                    val items = ArrayList<Any?>(length)
                    for (i in 0 until length) {
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
                    buffer.get() // keyType
                    buffer.get() // valType
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

    sealed class PhotonMessage {
        abstract val params: Map<Int, Any?>
    }

    data class PhotonEvent(
        val eventCode: Int,
        override val params: Map<Int, Any?>
    ) : PhotonMessage() {
        fun getEntityId(): Int? = (params[0] as? Number)?.toInt()
        fun getPosX(): Float? = (params[4] as? Number)?.toFloat()
        fun getPosY(): Float? = (params[5] as? Number)?.toFloat()
    }

    data class PhotonRequest(
        val operationCode: Int,
        override val params: Map<Int, Any?>
    ) : PhotonMessage() {
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
                        if (x != null && y != null) return Pair(x, y)
                    }
                }
            }
            return null
        }
    }

    data class PhotonResponse(
        val operationCode: Int,
        val returnCode: Int,
        val debugMessage: Any?,
        override val params: Map<Int, Any?>
    ) : PhotonMessage() {
        fun getMapId(): Int? = (params[0] as? Number)?.toInt()

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
                        if (x != null && y != null) return Pair(x, y)
                    }
                }
            }
            return null
        }

        fun isBlackZone(): Boolean = (params[103] as? Number)?.toInt() == 2
    }
}
