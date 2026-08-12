package com.tabletdisplay.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ProtocolCapabilities {
    const val H264_ANNEX_B = 1L shl 0
    const val H264_BASELINE = 1L shl 1
    const val H264_MAIN = 1L shl 2
    const val H264_HIGH = 1L shl 3
    const val TOUCH_SINGLE = 1L shl 4
    const val KEYFRAME_REQUEST = 1L shl 5
    const val DISPLAY_INFO = 1L shl 6
    const val HEARTBEAT = 1L shl 7

    const val AVC_PROFILES = H264_BASELINE or H264_MAIN or H264_HIGH
    const val ALL_V1 = H264_ANNEX_B or AVC_PROFILES or TOUCH_SINGLE or
        KEYFRAME_REQUEST or DISPLAY_INFO or HEARTBEAT
    const val REQUIRED = H264_ANNEX_B or KEYFRAME_REQUEST or DISPLAY_INFO or HEARTBEAT
}

data class HostHello(
    val nonce: Long,
    val capabilities: Long,
    val maxPayload: Int,
    val heartbeatMs: Int,
    val idleTimeoutMs: Int
)

data class AndroidDisplayInfo(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val refreshRateMilliHz: Int,
    val maxAvcWidth: Int,
    val maxAvcHeight: Int,
    val maxAvcFps: Int,
    val profileMask: Int,
    val rotation: Int = 0
)

object HandshakeProtocol {
    const val STATUS_OK = 0
    const val STATUS_UNSUPPORTED_VERSION = 1
    const val STATUS_MISSING_CAPABILITY = 2

    fun parseHello(message: WireMessage): HostHello {
        require(message.header.messageType == MessageType.HELLO.code) {
            "First message must be HELLO"
        }
        val payload = buffer(message.payload)
        payload.position(8)
        val nonce = payload.long
        val capabilities = payload.long
        val maxPayload = payload.int
        val heartbeatMs = payload.short.toInt() and 0xFFFF
        val idleTimeoutMs = payload.short.toInt() and 0xFFFF
        return HostHello(
            nonce = nonce,
            capabilities = capabilities,
            maxPayload = maxPayload,
            heartbeatMs = heartbeatMs,
            idleTimeoutMs = idleTimeoutMs
        )
    }

    fun negotiationStatus(hello: HostHello): Int {
        val missingRequired = hello.capabilities and ProtocolCapabilities.REQUIRED !=
            ProtocolCapabilities.REQUIRED
        val missingProfile = hello.capabilities and ProtocolCapabilities.AVC_PROFILES == 0L
        return if (missingRequired || missingProfile) STATUS_MISSING_CAPABILITY else STATUS_OK
    }

    fun createHelloAck(
        sequence: Long,
        timestampUs: Long,
        hello: HostHello,
        androidNonce: Long,
        status: Int = negotiationStatus(hello)
    ): WireMessage {
        val payload = buffer(32).apply {
            putShort(status.toShort())
            put(ProtocolConstants.VERSION_MAJOR.toByte())
            put(ProtocolConstants.VERSION_MINOR.toByte())
            putInt(0)
            putLong(hello.nonce)
            putLong(androidNonce)
            putLong(hello.capabilities and ProtocolCapabilities.ALL_V1)
        }.array()
        return message(MessageType.HELLO_ACK, sequence, timestampUs, payload)
    }

    fun createDisplayInfo(
        sequence: Long,
        timestampUs: Long,
        info: AndroidDisplayInfo
    ): WireMessage {
        require(info.width > 0 && info.height > 0)
        val payload = buffer(40).apply {
            putInt(info.width)
            putInt(info.height)
            putInt(info.densityDpi)
            putInt(info.refreshRateMilliHz)
            putInt(info.maxAvcWidth)
            putInt(info.maxAvcHeight)
            putInt(info.maxAvcFps)
            putInt(ProtocolConstants.MAX_ACCESS_UNIT_SIZE)
            putInt(info.profileMask)
            putShort(info.rotation.toShort())
            putShort(0)
        }.array()
        return message(MessageType.DISPLAY_INFO, sequence, timestampUs, payload)
    }

    fun createPong(
        sequence: Long,
        timestampUs: Long,
        ping: WireMessage
    ): WireMessage {
        require(ping.header.messageType == MessageType.PING.code)
        val input = buffer(ping.payload)
        val token = input.long
        val senderTime = input.long
        val payload = buffer(24).apply {
            putLong(token)
            putLong(senderTime)
            putLong(timestampUs)
        }.array()
        return message(MessageType.PONG, sequence, timestampUs, payload)
    }

    private fun message(
        type: MessageType,
        sequence: Long,
        timestampUs: Long,
        payload: ByteArray
    ): WireMessage = WireMessage(
        WireHeader(
            messageType = type.code,
            flags = HeaderFlags.CRITICAL,
            payloadLength = payload.size,
            sequence = sequence,
            timestampUs = timestampUs
        ),
        payload
    )

    private fun buffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun buffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
