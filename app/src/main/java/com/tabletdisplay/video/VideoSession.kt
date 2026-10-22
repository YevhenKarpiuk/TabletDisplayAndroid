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

data class KeyframeRequest(
    val streamId: Long,
    val configId: Long,
    val lastFrameId: Long,
    val reason: Int
) {
    companion object {
        const val REASON_FRAME_GAP = 1
        const val REASON_DECODER_ERROR = 2
        const val REASON_SURFACE_RESTORED = 3
        const val REASON_RECONNECT = 4
        const val REASON_QUEUE_OVERFLOW = 5
    }
}

/** Thrown by a [VideoSink] that restarted its decoder and needs an IDR to continue. */
class DecoderRecoveryException(message: String, cause: Throwable?) :
    RuntimeException(message, cause)

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

    /** Releases per-connection decoder state; the sink stays usable for the next connection. */
    fun reset()
}

class VideoSession(
    private val sink: VideoSink,
    private val onConfigured: (VideoConfig) -> Unit = {},
    private val onKeyframeRequest: (KeyframeRequest) -> Unit = {},
    private val onFirstFrame: () -> Unit = {}
) : Closeable {
    private var streamId = 0L
    private var configId = 0L
    private var fps = 1
    private var nextFrameId = 1L
    private var lastDecodedFrameId = 0L
    private var configured = false
    private var firstFrameDelivered = false
    private var awaitingKeyframe = false
    private var droppedFrames = 0

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

        val config = VideoConfig(
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
        sink.configure(config)
        streamId = newStreamId
        configId = newConfigId
        fps = (fpsNumerator / fpsDenominator).coerceAtLeast(1)
        nextFrameId = 1
        lastDecodedFrameId = 0
        configured = true
        firstFrameDelivered = false
        awaitingKeyframe = false
        droppedFrames = 0
        onConfigured(config)
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
        // TCP delivers every frame in order, so a gap means the Host violated the protocol.
        check(frameId == nextFrameId) { "VIDEO_FRAME id gap" }
        nextFrameId++

        if (message.header.flags and HeaderFlags.END_OF_STREAM != 0) {
            sink.signalEndOfStream(presentationTimeUs)
            return
        }
        val keyFrame = message.header.flags and HeaderFlags.KEY_FRAME != 0
        val discontinuity = message.header.flags and HeaderFlags.DISCONTINUITY != 0
        if (awaitingKeyframe) {
            if (!keyFrame) {
                // Re-send the request if the Host apparently missed it (about every 2 s).
                if (++droppedFrames % (fps * 2) == 0) requestKeyframe()
                return
            }
            awaitingKeyframe = false
            droppedFrames = 0
        }
        try {
            sink.queueAccessUnit(
                message.payload,
                32,
                dataLength,
                presentationTimeUs,
                keyFrame,
                discontinuity
            )
        } catch (exception: DecoderRecoveryException) {
            awaitingKeyframe = true
            droppedFrames = 0
            requestKeyframe()
            return
        }
        lastDecodedFrameId = frameId
        if (!firstFrameDelivered) {
            firstFrameDelivered = true
            onFirstFrame()
        }
    }

    private fun requestKeyframe() {
        onKeyframeRequest(
            KeyframeRequest(
                streamId = streamId,
                configId = configId,
                lastFrameId = lastDecodedFrameId,
                reason = KeyframeRequest.REASON_DECODER_ERROR
            )
        )
    }

    override fun close() {
        configured = false
        sink.reset()
    }

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun u8(input: ByteBuffer): Int = input.get().toInt() and 0xFF
    private fun u16(input: ByteBuffer): Int = input.short.toInt() and 0xFFFF
    private fun u32(input: ByteBuffer): Long = input.int.toLong() and 0xFFFF_FFFFL
}
