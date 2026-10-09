package com.tabletdisplay.protocol

import com.tabletdisplay.input.NormalizedTouchEvent
import com.tabletdisplay.input.TouchAction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class HandshakeProtocolTest {
    @Test
    fun helloProducesAckDisplayInfoAndPong() {
        val hello = helloMessage()
        val parsed = HandshakeProtocol.parseHello(hello)
        assertEquals(0x1122334455667788L, parsed.nonce)
        assertEquals(HandshakeProtocol.STATUS_OK, HandshakeProtocol.negotiationStatus(parsed))

        val ack = HandshakeProtocol.createHelloAck(1, 10, parsed, 0x1234)
        WireProtocol.validatePayload(ack.header, ack.payload)
        assertEquals(MessageType.HELLO_ACK.code, ack.header.messageType)

        val info = HandshakeProtocol.createDisplayInfo(
            2,
            11,
            AndroidDisplayInfo(1920, 1200, 240, 60_000, 1920, 1200, 60, 7)
        )
        WireProtocol.validatePayload(info.header, info.payload)

        val pingPayload = littleEndian(16).putLong(77).putLong(12).array()
        val ping = WireMessage(
            WireHeader(MessageType.PING.code, HeaderFlags.CRITICAL, 16, 2, 12),
            pingPayload
        )
        val pong = HandshakeProtocol.createPong(3, 13, ping)
        WireProtocol.validatePayload(pong.header, pong.payload)
        assertEquals(77L, littleEndian(pong.payload).long)
    }

    @Test
    fun missingHeartbeatCapabilityIsRejected() {
        val parsed = HandshakeProtocol.parseHello(helloMessage(capabilities = 0x7F))
        assertEquals(
            HandshakeProtocol.STATUS_MISSING_CAPABILITY,
            HandshakeProtocol.negotiationStatus(parsed)
        )
    }

    @Test
    fun touchCoordinatesAreSerializedAsNormalizedU16() {
        val touch = HandshakeProtocol.createTouch(
            sequence = 4,
            timestampUs = 20,
            streamId = 1,
            contactSequence = 7,
            event = NormalizedTouchEvent(
                TouchAction.DOWN,
                pointerId = 0,
                x = 0.5f,
                y = 1f,
                timestampNanos = 19_000,
                pressure = 0.25f
            )
        )
        WireProtocol.validatePayload(touch.header, touch.payload)
        val payload = littleEndian(touch.payload)
        assertEquals(1, payload.int)
        assertEquals(0, payload.int)
        assertEquals(1, payload.get().toInt())
        assertEquals(1, payload.get().toInt())
        assertEquals(32_768, payload.short.toInt() and 0xFFFF)
        assertEquals(65_535, payload.short.toInt() and 0xFFFF)
        assertEquals(16_384, payload.short.toInt() and 0xFFFF)
        assertEquals(7, payload.int)
        assertEquals(19L, payload.long)
        assertEquals(0, payload.int)
    }

    private fun helloMessage(capabilities: Long = ProtocolCapabilities.ALL_V1): WireMessage {
        val payload = littleEndian(32).apply {
            put(1)
            put(0)
            put(1)
            put(1)
            put(0)
            put(0)
            putShort(0)
            putLong(0x1122334455667788L)
            putLong(capabilities)
            putInt(ProtocolConstants.MAX_PAYLOAD_SIZE)
            putShort(1_000)
            putShort(5_000)
        }.array()
        return WireMessage(
            WireHeader(MessageType.HELLO.code, HeaderFlags.CRITICAL, 32, 1, 1),
            payload
        ).also { WireProtocol.validatePayload(it.header, it.payload) }
    }

    private fun littleEndian(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
