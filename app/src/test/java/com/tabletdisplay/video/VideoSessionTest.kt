package com.tabletdisplay.video

import com.tabletdisplay.protocol.HeaderFlags
import com.tabletdisplay.protocol.MessageType
import com.tabletdisplay.protocol.WireHeader
import com.tabletdisplay.protocol.WireMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionTest {
    @Test
    fun configAndFrameReachSink() {
        val sink = RecordingSink()
        var firstFrames = 0
        val session = VideoSession(sink) { firstFrames++ }

        assertTrue(session.accept(configMessage()))
        assertTrue(session.accept(frameMessage()))

        assertEquals(1280, sink.config?.width)
        assertEquals(800, sink.config?.height)
        assertEquals(1, sink.frames)
        assertEquals(123_456L, sink.presentationTimeUs)
        assertTrue(sink.keyFrame)
        assertTrue(sink.discontinuity)
        assertEquals(1, firstFrames)
    }

    private fun configMessage(): WireMessage {
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68)
        val payload = littleEndian(28 + csd.size).apply {
            putInt(1)
            putInt(1)
            putShort(1280.toShort())
            putShort(800.toShort())
            putShort(30)
            putShort(1)
            put(66)
            put(40)
            putShort(1)
            putInt(5_000_000)
            putInt(csd.size)
            put(csd)
        }.array()
        return message(MessageType.VIDEO_CONFIG, HeaderFlags.CRITICAL, payload)
    }

    private fun frameMessage(): WireMessage {
        val accessUnit = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val payload = littleEndian(32 + accessUnit.size).apply {
            putInt(1)
            putInt(1)
            putLong(1)
            putLong(123_456)
            putInt(accessUnit.size)
            putInt(0)
            put(accessUnit)
        }.array()
        return message(
            MessageType.VIDEO_FRAME,
            HeaderFlags.CRITICAL or HeaderFlags.KEY_FRAME or HeaderFlags.DISCONTINUITY,
            payload
        )
    }

    private fun message(type: MessageType, flags: Int, payload: ByteArray) = WireMessage(
        WireHeader(type.code, flags, payload.size, 1, 1),
        payload
    )

    private fun littleEndian(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private class RecordingSink : VideoSink {
        var config: VideoConfig? = null
        var frames = 0
        var presentationTimeUs = 0L
        var keyFrame = false
        var discontinuity = false

        override fun configure(config: VideoConfig) {
            this.config = config
        }

        override fun queueAccessUnit(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            presentationTimeUs: Long,
            keyFrame: Boolean,
            discontinuity: Boolean
        ) {
            frames++
            this.presentationTimeUs = presentationTimeUs
            this.keyFrame = keyFrame
            this.discontinuity = discontinuity
        }

        override fun signalEndOfStream(presentationTimeUs: Long) = Unit
        override fun close() = Unit
    }
}
