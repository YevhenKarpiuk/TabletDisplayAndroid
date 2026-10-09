package com.tabletdisplay.video

import com.tabletdisplay.protocol.HeaderFlags
import com.tabletdisplay.protocol.MessageType
import com.tabletdisplay.protocol.WireMessage
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VideoConfig(
    val streamId: Long,
    val configId: Long,
    val width: Int,
    val height: Int,
    val fpsNumerator: Int,
    val fpsDenominator: Int,
    val profileIdc: Int,
    val levelIdc: Int,
    val bitrate: Long,
    val codecSpecificData: ByteArray
)

interface VideoSink : Closeable {
    fun configure(config: VideoConfig)

    fun queueAccessUnit(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        keyFrame: Boolean,
        discontinuity: Boolean
    )

    fun signalEndOfStream(presentationTimeUs: Long)
}

class VideoSession(
    private val sink: VideoSink,
    private val onFirstFrame: () -> Unit = {}
) : Closeable {
    private var streamId = 0L
    private var configId = 0L
    private var nextFrameId = 1L
    private var configured = false
    private var firstFrameDelivered = false

    fun accept(message: WireMessage): Boolean = when (
        MessageType.fromCode(message.header.messageType)
    ) {
        MessageType.VIDEO_CONFIG -> {
            configure(message.payload)
            true
        }
        MessageType.VIDEO_FRAME -> {
            queueFrame(message)
            true
        }
        else -> false
    }

    private fun configure(payload: ByteArray) {
        val input = littleEndian(payload)
        val newStreamId = u32(input)
        val newConfigId = u32(input)
        val width = u16(input)
        val height = u16(input)
        val fpsNumerator = u16(input)
        val fpsDenominator = u16(input)
        val profileIdc = u8(input)
        val levelIdc = u8(input)
        input.short
        val bitrate = u32(input)
        val csdLength = u32(input).toInt()
        val csd = ByteArray(csdLength)
        input.get(csd)

        sink.configure(
            VideoConfig(
                streamId = newStreamId,
                configId = newConfigId,
                width = width,
                height = height,
                fpsNumerator = fpsNumerator,
                fpsDenominator = fpsDenominator,
                profileIdc = profileIdc,
                levelIdc = levelIdc,
                bitrate = bitrate,
                codecSpecificData = csd
            )
        )
        streamId = newStreamId
        configId = newConfigId
        nextFrameId = 1
        configured = true
        firstFrameDelivered = false
    }

    private fun queueFrame(message: WireMessage) {
        check(configured) { "VIDEO_FRAME received before VIDEO_CONFIG" }
        val input = littleEndian(message.payload)
        val frameStreamId = u32(input)
        val frameConfigId = u32(input)
        val frameId = input.long
        val presentationTimeUs = input.long
        val dataLength = u32(input).toInt()
        input.int
        check(frameStreamId == streamId && frameConfigId == configId) {
            "VIDEO_FRAME uses stale stream/config"
        }
        check(frameId == nextFrameId) { "VIDEO_FRAME id gap" }
        nextFrameId++

        if (message.header.flags and HeaderFlags.END_OF_STREAM != 0) {
            sink.signalEndOfStream(presentationTimeUs)
            return
        }
        val keyFrame = message.header.flags and HeaderFlags.KEY_FRAME != 0
        val discontinuity = message.header.flags and HeaderFlags.DISCONTINUITY != 0
        sink.queueAccessUnit(
            message.payload,
            32,
            dataLength,
            presentationTimeUs,
            keyFrame,
            discontinuity
        )
        if (!firstFrameDelivered) {
            firstFrameDelivered = true
            onFirstFrame()
        }
    }

    override fun close() {
        configured = false
        sink.close()
    }

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun u8(input: ByteBuffer): Int = input.get().toInt() and 0xFF
    private fun u16(input: ByteBuffer): Int = input.short.toInt() and 0xFFFF
    private fun u32(input: ByteBuffer): Long = input.int.toLong() and 0xFFFF_FFFFL
}
