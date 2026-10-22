package com.tabletdisplay.video

import com.tabletdisplay.protocol.HeaderFlags
import com.tabletdisplay.protocol.MessageType
import com.tabletdisplay.protocol.WireHeader
import com.tabletdisplay.protocol.WireMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionTest {
    @Test
    fun configAndFrameReachSink() {
        val sink = RecordingSink()
        var firstFrames = 0
        val session = VideoSession(sink, onFirstFrame = { firstFrames++ })

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

    @Test
    fun decoderRecoveryRequestsKeyframeAndSkipsDeltaFrames() {
        val sink = RecordingSink().apply { failNextFrame = true }
        val requests = mutableListOf<KeyframeRequest>()
        val session = VideoSession(sink, onKeyframeRequest = { requests += it })

        session.accept(configMessage())
        session.accept(frameMessage(frameId = 1))
        assertEquals(1, requests.size)
        assertEquals(KeyframeRequest.REASON_DECODER_ERROR, requests.single().reason)
        assertEquals(0L, requests.single().lastFrameId)

        session.accept(frameMessage(frameId = 2, flags = HeaderFlags.CRITICAL))
        assertEquals(0, sink.frames)

        session.accept(frameMessage(frameId = 3))
        assertEquals(1, sink.frames)
        assertEquals(1, requests.size)
    }

    @Test
    fun closingSessionResetsButDoesNotReleaseSink() {
        val sink = RecordingSink()
        VideoSession(sink).close()
        assertEquals(1, sink.resets)
        assertFalse(sink.closed)
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

    private fun frameMessage(
        frameId: Long = 1,
        flags: Int = HeaderFlags.CRITICAL or HeaderFlags.KEY_FRAME or HeaderFlags.DISCONTINUITY
    ): WireMessage {
        val accessUnit = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val payload = littleEndian(32 + accessUnit.size).apply {
            putInt(1)
            putInt(1)
            putLong(frameId)
            putLong(123_456)
            putInt(accessUnit.size)
            putInt(0)
            put(accessUnit)
        }.array()
        return message(MessageType.VIDEO_FRAME, flags, payload)
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
        var failNextFrame = false
        var resets = 0
        var closed = false

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
            if (failNextFrame) {
                failNextFrame = false
                throw DecoderRecoveryException("test", null)
            }
            frames++
            this.presentationTimeUs = presentationTimeUs
            this.keyFrame = keyFrame
            this.discontinuity = discontinuity
        }

        override fun signalEndOfStream(presentationTimeUs: Long) = Unit
        override fun reset() {
            resets++
        }

        override fun close() {
            closed = true
        }
    }
}
