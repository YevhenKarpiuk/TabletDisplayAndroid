package com.tabletdisplay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlin.math.min

enum class MessageType(val code: Int) {
    HELLO(0x0001),
    HELLO_ACK(0x0002),
    DISPLAY_INFO(0x0003),
    VIDEO_CONFIG(0x0010),
    VIDEO_FRAME(0x0011),
    REQUEST_KEYFRAME(0x0012),
    TOUCH(0x0020),
    PING(0x0030),
    PONG(0x0031),
    ERROR(0x00FF);

    companion object {
        fun fromCode(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}

object HeaderFlags {
    const val CRITICAL = 0x0001
    const val KEY_FRAME = 0x0002
    const val DISCONTINUITY = 0x0004
    const val END_OF_STREAM = 0x0008
}

enum class ProtocolErrorKind {
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    INVALID_HEADER,
    INVALID_FLAGS,
    UNKNOWN_CRITICAL_TYPE,
    PAYLOAD_TOO_LARGE,
    INVALID_PAYLOAD,
    SEQUENCE_GAP
}

class ProtocolException(
    val kind: ProtocolErrorKind,
    message: String
) : IllegalArgumentException(message)

data class WireHeader(
    val messageType: Int,
    val flags: Int,
    val payloadLength: Int,
    val sequence: Long,
    val timestampUs: Long
)

data class WireMessage(
    val header: WireHeader,
    val payload: ByteArray
)

object WireProtocol {
    fun decodeHeader(
        bytes: ByteArray,
        offset: Int = 0,
        maxPayload: Int = ProtocolConstants.MAX_PAYLOAD_SIZE
    ): WireHeader {
        requireRange(bytes, offset, ProtocolConstants.HEADER_SIZE)
        for (index in ProtocolConstants.MAGIC.indices) {
            if (bytes[offset + index] != ProtocolConstants.MAGIC[index]) {
                throw ProtocolException(ProtocolErrorKind.BAD_MAGIC, "Bad protocol magic")
            }
        }

        val buffer = ByteBuffer.wrap(bytes, offset, ProtocolConstants.HEADER_SIZE)
            .slice()
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val major = buffer.get().toInt() and 0xFF
        val minor = buffer.get().toInt() and 0xFF
        if (major != ProtocolConstants.VERSION_MAJOR ||
            minor != ProtocolConstants.VERSION_MINOR
        ) {
            throw ProtocolException(
                ProtocolErrorKind.UNSUPPORTED_VERSION,
                "Unsupported protocol version"
            )
        }
        if (buffer.short.toInt() and 0xFFFF != ProtocolConstants.HEADER_SIZE) {
            throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "Invalid header size")
        }

        val type = buffer.short.toInt() and 0xFFFF
        val flags = buffer.short.toInt() and 0xFFFF
        val payloadLengthLong = buffer.int.toLong() and 0xFFFF_FFFFL
        if (payloadLengthLong > maxPayload.toLong() ||
            payloadLengthLong > ProtocolConstants.MAX_PAYLOAD_SIZE.toLong()
        ) {
            throw ProtocolException(
                ProtocolErrorKind.PAYLOAD_TOO_LARGE,
                "Payload exceeds negotiated limit"
            )
        }

        val sequence = buffer.long
        val timestampUs = buffer.long
        if (sequence <= 0 || timestampUs <= 0) {
            throw ProtocolException(
                ProtocolErrorKind.INVALID_HEADER,
                "Sequence or timestamp is outside v1 range"
            )
        }

        val header = WireHeader(
            messageType = type,
            flags = flags,
            payloadLength = payloadLengthLong.toInt(),
            sequence = sequence,
            timestampUs = timestampUs
        )
        validateFlags(header)
        return header
    }

    fun encodeMessage(header: WireHeader, payload: ByteArray): ByteArray {
        validatePayload(header, payload)
        validateFlags(header)
        if (header.sequence <= 0 || header.timestampUs <= 0) {
            throw ProtocolException(
                ProtocolErrorKind.INVALID_HEADER,
                "Zero sequence or timestamp"
            )
        }

        return ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(ProtocolConstants.MAGIC)
                put(ProtocolConstants.VERSION_MAJOR.toByte())
                put(ProtocolConstants.VERSION_MINOR.toByte())
                putShort(ProtocolConstants.HEADER_SIZE.toShort())
                putShort(header.messageType.toShort())
                putShort(header.flags.toShort())
                putInt(header.payloadLength)
                putLong(header.sequence)
                putLong(header.timestampUs)
                put(payload)
            }
            .array()
    }

    fun validatePayload(header: WireHeader, payload: ByteArray) {
        requirePayload(payload.size == header.payloadLength, "Payload length mismatch")
        val type = MessageType.fromCode(header.messageType) ?: return

        when (type) {
            MessageType.HELLO -> {
                requirePayload(payload.size == 32, "HELLO size")
                requirePayload(u8(payload, 0) == 1 && u8(payload, 1) == 0, "HELLO role")
                requirePayload(
                    u8(payload, 2) == 1 && u8(payload, 3) == 1 &&
                        u8(payload, 4) == 0 && u8(payload, 5) == 0,
                    "HELLO version range"
                )
                requirePayload(u16(payload, 6) == 0, "HELLO reserved")
                val advertisedMax = u32(payload, 24)
                val heartbeat = u16(payload, 28)
                val idle = u16(payload, 30)
                requirePayload(
                    advertisedMax in ProtocolConstants.HEADER_SIZE.toLong()..
                        ProtocolConstants.MAX_PAYLOAD_SIZE.toLong(),
                    "HELLO max payload"
                )
                requirePayload(heartbeat > 0 && idle >= heartbeat * 3, "HELLO heartbeat")
            }

            MessageType.HELLO_ACK -> {
                requirePayload(payload.size == 32, "HELLO_ACK size")
                requirePayload(u16(payload, 0) in 0..3, "HELLO_ACK status")
                requirePayload(u8(payload, 2) == 1 && u8(payload, 3) == 0, "HELLO_ACK version")
                requirePayload(u32(payload, 4) == 0L, "HELLO_ACK reserved")
            }

            MessageType.DISPLAY_INFO -> {
                requirePayload(payload.size == 40, "DISPLAY_INFO size")
                requirePayload(
                    u32(payload, 0) > 0 && u32(payload, 4) > 0 &&
                        u32(payload, 16) > 0 && u32(payload, 20) > 0,
                    "DISPLAY_INFO dimensions"
                )
                requirePayload(
                    u32(payload, 28) <= ProtocolConstants.MAX_ACCESS_UNIT_SIZE.toLong(),
                    "DISPLAY_INFO access unit"
                )
                requirePayload(u16(payload, 36) in setOf(0, 90, 180, 270), "DISPLAY_INFO rotation")
                requirePayload(u16(payload, 38) == 0, "DISPLAY_INFO reserved")
            }

            MessageType.VIDEO_CONFIG -> {
                requirePayload(payload.size >= 28, "VIDEO_CONFIG size")
                val width = u16(payload, 8)
                val height = u16(payload, 10)
                val fpsNumerator = u16(payload, 12)
                val fpsDenominator = u16(payload, 14)
                val profile = u8(payload, 16)
                val configFlags = u16(payload, 18)
                val csdLength = u32(payload, 24)
                requirePayload(u32(payload, 0) != 0L && u32(payload, 4) != 0L, "VIDEO_CONFIG ids")
                requirePayload(
                    (width == 1280 && height == 800) ||
                        (width == 800 && height == 1280) ||
                        (width == 1920 && height == 1200) ||
                        (width == 1200 && height == 1920),
                    "VIDEO_CONFIG mode"
                )
                requirePayload(
                    (fpsNumerator == 30 || fpsNumerator == 60) &&
                        fpsDenominator == 1,
                    "VIDEO_CONFIG fps"
                )
                requirePayload(profile in setOf(66, 77, 100), "VIDEO_CONFIG profile")
                requirePayload(configFlags == 1, "VIDEO_CONFIG flags")
                requirePayload(
                    csdLength in 1L..ProtocolConstants.MAX_CSD_SIZE.toLong() &&
                        payload.size.toLong() == 28L + csdLength,
                    "VIDEO_CONFIG CSD length"
                )
                requirePayload(hasAnnexBStartCode(payload, 28), "VIDEO_CONFIG CSD framing")
            }

            MessageType.VIDEO_FRAME -> {
                requirePayload(payload.size >= 32, "VIDEO_FRAME size")
                val dataLength = u32(payload, 24)
                val eos = header.flags and HeaderFlags.END_OF_STREAM != 0
                requirePayload(
                    u32(payload, 0) != 0L &&
                        u32(payload, 4) != 0L &&
                        i64(payload, 8) > 0,
                    "VIDEO_FRAME ids"
                )
                requirePayload(u32(payload, 28) == 0L, "VIDEO_FRAME reserved")
                requirePayload(
                    dataLength <= ProtocolConstants.MAX_ACCESS_UNIT_SIZE.toLong() &&
                        payload.size.toLong() == 32L + dataLength,
                    "VIDEO_FRAME data length"
                )
                requirePayload(if (eos) dataLength == 0L else dataLength > 0L, "VIDEO_FRAME EOS")
                if (dataLength > 0L) {
                    requirePayload(hasAnnexBStartCode(payload, 32), "VIDEO_FRAME Annex B")
                }
                if (header.flags and HeaderFlags.DISCONTINUITY != 0) {
                    requirePayload(
                        header.flags and HeaderFlags.KEY_FRAME != 0,
                        "VIDEO_FRAME discontinuity must be key"
                    )
                }
            }

            MessageType.REQUEST_KEYFRAME -> {
                requirePayload(payload.size == 24, "REQUEST_KEYFRAME size")
                requirePayload(
                    u32(payload, 0) != 0L && u32(payload, 4) != 0L,
                    "REQUEST_KEYFRAME ids"
                )
                requirePayload(u16(payload, 16) in 1..5, "REQUEST_KEYFRAME reason")
                requirePayload(
                    u16(payload, 18) == 0 && u32(payload, 20) == 0L,
                    "REQUEST_KEYFRAME reserved"
                )
            }

            MessageType.TOUCH -> {
                requirePayload(payload.size == 32, "TOUCH size")
                requirePayload(u32(payload, 0) != 0L, "TOUCH stream")
                requirePayload(u32(payload, 4) == 0L, "TOUCH pointer")
                requirePayload(u8(payload, 8) in 1..4, "TOUCH action")
                requirePayload(u8(payload, 9) and 0xFE == 0, "TOUCH buttons")
                requirePayload(u32(payload, 16) != 0L && i64(payload, 20) > 0, "TOUCH sequence")
                requirePayload(u32(payload, 28) == 0L, "TOUCH reserved")
            }

            MessageType.PING -> requirePayload(payload.size == 16, "PING size")
            MessageType.PONG -> requirePayload(payload.size == 24, "PONG size")

            MessageType.ERROR -> {
                requirePayload(payload.size >= 20, "ERROR size")
                val code = u16(payload, 0)
                val textLength = u16(payload, 16)
                requirePayload(code in 1..10, "ERROR code")
                requirePayload(u8(payload, 2) in 1..3 && u8(payload, 3) == 0, "ERROR severity")
                requirePayload(
                    u16(payload, 6) == 0 && u16(payload, 18) == 0,
                    "ERROR reserved"
                )
                requirePayload(
                    textLength <= ProtocolConstants.MAX_ERROR_TEXT_SIZE &&
                        payload.size == 20 + textLength,
                    "ERROR text length"
                )
                requirePayload(isStrictUtf8(payload, 20, textLength), "ERROR UTF-8")
            }
        }
    }

    private fun validateFlags(header: WireHeader) {
        val known = MessageType.fromCode(header.messageType)
        if (known == null) {
            if (header.flags and HeaderFlags.CRITICAL != 0) {
                throw ProtocolException(
                    ProtocolErrorKind.UNKNOWN_CRITICAL_TYPE,
                    "Unknown critical message type"
                )
            }
            if (header.flags != 0) {
                throw ProtocolException(
                    ProtocolErrorKind.INVALID_FLAGS,
                    "Optional unknown type has flags"
                )
            }
            return
        }

        if (header.flags and HeaderFlags.CRITICAL == 0) {
            throw ProtocolException(
                ProtocolErrorKind.INVALID_FLAGS,
                "Known message is not critical"
            )
        }
        val allowed = if (known == MessageType.VIDEO_FRAME) 0x000F else HeaderFlags.CRITICAL
        if (header.flags and allowed.inv() != 0) {
            throw ProtocolException(
                ProtocolErrorKind.INVALID_FLAGS,
                "Reserved header flag is set"
            )
        }
    }

    private fun requirePayload(condition: Boolean, message: String) {
        if (!condition) {
            throw ProtocolException(ProtocolErrorKind.INVALID_PAYLOAD, message)
        }
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw ProtocolException(ProtocolErrorKind.INVALID_HEADER, "Truncated header")
        }
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

    private fun i64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun hasAnnexBStartCode(bytes: ByteArray, offset: Int): Boolean {
        if (offset + 3 > bytes.size || u8(bytes, offset) != 0 || u8(bytes, offset + 1) != 0) {
            return false
        }
        if (u8(bytes, offset + 2) == 1) {
            return true
        }
        return offset + 4 <= bytes.size &&
            u8(bytes, offset + 2) == 0 &&
            u8(bytes, offset + 3) == 1
    }

    private fun isStrictUtf8(bytes: ByteArray, offset: Int, length: Int): Boolean =
        runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
        }.isSuccess
}

class IncrementalMessageDecoder(
    private val maxPayload: Int = ProtocolConstants.MAX_PAYLOAD_SIZE,
    private val enforceSequence: Boolean = true
) {
    private val headerBytes = ByteArray(ProtocolConstants.HEADER_SIZE)
    private var headerFilled = 0
    private var currentHeader: WireHeader? = null
    private var payload = ByteArray(0)
    private var payloadFilled = 0
    private var expectedSequence = 1L

    fun feed(
        bytes: ByteArray,
        startOffset: Int = 0,
        length: Int = bytes.size - startOffset
    ): List<WireMessage> {
        require(startOffset >= 0 && length >= 0 && startOffset <= bytes.size - length)
        val output = mutableListOf<WireMessage>()
        var offset = startOffset
        val end = startOffset + length

        while (offset < end) {
            if (currentHeader == null) {
                val count = min(end - offset, headerBytes.size - headerFilled)
                bytes.copyInto(headerBytes, headerFilled, offset, offset + count)
                offset += count
                headerFilled += count
                if (headerFilled != headerBytes.size) {
                    continue
                }

                val decodedHeader = WireProtocol.decodeHeader(
                    headerBytes,
                    maxPayload = maxPayload
                )
                if (enforceSequence && decodedHeader.sequence != expectedSequence) {
                    throw ProtocolException(
                        ProtocolErrorKind.SEQUENCE_GAP,
                        "Message sequence gap"
                    )
                }
                expectedSequence = decodedHeader.sequence + 1
                currentHeader = decodedHeader
                payload = ByteArray(decodedHeader.payloadLength)
                payloadFilled = 0
                if (payload.isEmpty()) {
                    completeMessage(output)
                }
                continue
            }

            val count = min(end - offset, payload.size - payloadFilled)
            bytes.copyInto(payload, payloadFilled, offset, offset + count)
            offset += count
            payloadFilled += count
            if (payloadFilled == payload.size) {
                completeMessage(output)
            }
        }
        return output
    }

    fun reset() {
        headerFilled = 0
        currentHeader = null
        payload = ByteArray(0)
        payloadFilled = 0
        expectedSequence = 1
    }

    private fun completeMessage(output: MutableList<WireMessage>) {
        val header = checkNotNull(currentHeader)
        WireProtocol.validatePayload(header, payload)
        if (MessageType.fromCode(header.messageType) != null) {
            output += WireMessage(header, payload)
        }
        headerFilled = 0
        currentHeader = null
        payload = ByteArray(0)
        payloadFilled = 0
    }
}
