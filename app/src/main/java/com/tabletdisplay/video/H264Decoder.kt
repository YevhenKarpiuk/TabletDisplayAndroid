package com.tabletdisplay.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.tabletdisplay.protocol.ProtocolConstants
import java.nio.ByteBuffer

class H264Decoder(private val surface: Surface) : VideoSink {
    private var codec: MediaCodec? = null
    private val outputInfo = MediaCodec.BufferInfo()
    private var hasQueuedInput = false
    private var configuration: VideoConfig? = null
    private var softwareDecoderName: String? = null
    private var sourceTimestampOriginUs: Long? = null
    private var localTimestampOriginUs = 0L
    private var renderLeadTimeUs = 0L
    private var outputWaitUs = 0L
    private var lastOutputRealtimeMs = 0L
    private var released = false

    @Synchronized
    override fun configure(config: VideoConfig) {
        check(!released) { "Decoder is released" }
        require(config.width > 0 && config.height > 0)
        require(config.fpsNumerator > 0 && config.fpsDenominator > 0)
        releaseCodec()
        configuration = config
        sourceTimestampOriginUs = null
        renderLeadTimeUs = renderLeadTimeUs(
            config.fpsNumerator,
            config.fpsDenominator
        )
        outputWaitUs = outputWaitTimeUs(config.fpsNumerator, config.fpsDenominator)
        startCodec(config, softwareDecoderName = null)
    }

    private fun startCodec(config: VideoConfig, softwareDecoderName: String?) {
        val parameterSets = splitParameterSets(config.codecSpecificData)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            config.width,
            config.height
        ).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ProtocolConstants.MAX_ACCESS_UNIT_SIZE)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fpsNumerator / config.fpsDenominator)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate.toInt())
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setFloat(
                MediaFormat.KEY_OPERATING_RATE,
                config.fpsNumerator.toFloat() / config.fpsDenominator
            )
            setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets.first))
            setByteBuffer("csd-1", ByteBuffer.wrap(parameterSets.second))
        }
        val decoder = if (softwareDecoderName == null) {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } else {
            Log.i(TAG, "Using software AVC decoder: $softwareDecoderName")
            MediaCodec.createByCodecName(softwareDecoderName)
        }
        try {
            decoder.configure(format, surface, null, 0)
            decoder.start()
        } catch (exception: Exception) {
            decoder.release()
            throw exception
        }
        codec = decoder
        hasQueuedInput = false
        this.softwareDecoderName = softwareDecoderName
        lastOutputRealtimeMs = SystemClock.elapsedRealtime()
    }

    @Synchronized
    override fun queueAccessUnit(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        keyFrame: Boolean,
        discontinuity: Boolean
    ) {
        val current = checkNotNull(codec) { "Decoder is not configured" }
        val localPresentationTimeUs = mapPresentationTimeUs(presentationTimeUs)
        // Frames skipped while waiting for a recovery IDR are not a decoder stall.
        if (discontinuity) lastOutputRealtimeMs = SystemClock.elapsedRealtime()
        val rendered = try {
            queueAndRender(current, bytes, offset, length, localPresentationTimeUs, discontinuity)
        } catch (exception: IllegalStateException) {
            // MediaCodec.CodecException is an IllegalStateException as well.
            restart(softwareDecoderName, "Decoder error", exception)
        }

        val now = SystemClock.elapsedRealtime()
        if (rendered) {
            lastOutputRealtimeMs = now
        } else if (now - lastOutputRealtimeMs > NO_OUTPUT_FALLBACK_MS) {
            // Some hardware decoders accept input but never produce frames for a stream
            // they cannot handle. Switch to the platform software decoder when one exists.
            restart(
                softwareDecoderName ?: findSoftwareDecoderName(),
                "Decoder produced no output frame",
                null
            )
        }
    }

    private fun restart(softwareDecoder: String?, reason: String, cause: Exception?): Nothing {
        val config = checkNotNull(configuration) { "Decoder is not configured" }
        Log.w(TAG, "$reason; restarting decoder", cause)
        releaseCodec()
        startCodec(config, softwareDecoder)
        throw DecoderRecoveryException(reason, cause)
    }

    private fun queueAndRender(
        current: MediaCodec,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        discontinuity: Boolean
    ): Boolean {
        var rendered = false
        if (discontinuity && hasQueuedInput) {
            current.flush()
            hasQueuedInput = false
        }
        val deadline = SystemClock.elapsedRealtime() + INPUT_TIMEOUT_MS
        var inputIndex = current.dequeueInputBuffer(0)
        while (inputIndex < 0) {
            // Output buffers that are not released keep the decoder from accepting input.
            rendered = drainOutput(current, waitUs = 0) || rendered
            check(SystemClock.elapsedRealtime() < deadline) { "Decoder input buffer timeout" }
            inputIndex = current.dequeueInputBuffer(INPUT_POLL_US)
        }
        val input = checkNotNull(current.getInputBuffer(inputIndex))
        input.clear()
        require(length <= input.remaining()) { "Access unit exceeds decoder input buffer" }
        input.put(bytes, offset, length)
        current.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
        hasQueuedInput = true
        // Wait at most one frame interval; a later frame drains anything decoded after that.
        return drainOutput(current, waitUs = outputWaitUs) || rendered
    }

    @Synchronized
    override fun signalEndOfStream(presentationTimeUs: Long) {
        val current = codec ?: return
        val inputIndex = current.dequeueInputBuffer(INPUT_TIMEOUT_MS * 1_000L)
        if (inputIndex < 0) {
            Log.w(TAG, "Decoder EOS input buffer timeout")
            return
        }
        current.queueInputBuffer(
            inputIndex,
            0,
            0,
            mapPresentationTimeUs(presentationTimeUs),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        drainOutput(current, waitUs = 0)
    }

    private fun mapPresentationTimeUs(sourceTimestampUs: Long): Long {
        val sourceOrigin = sourceTimestampOriginUs
        if (sourceOrigin == null) {
            sourceTimestampOriginUs = sourceTimestampUs
            localTimestampOriginUs = System.nanoTime() / 1_000L + renderLeadTimeUs
            return localTimestampOriginUs
        }
        return localTimestampOriginUs + (sourceTimestampUs - sourceOrigin).coerceAtLeast(0L)
    }

    @Suppress("DEPRECATION")
    private fun drainOutput(current: MediaCodec, waitUs: Long): Boolean {
        var timeoutUs = waitUs
        var rendered = false
        while (true) {
            when (val outputIndex = current.dequeueOutputBuffer(outputInfo, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    // Surface-backed decoders may report a zero-sized output buffer:
                    // the pixels live in the Surface rather than in a ByteBuffer.
                    val isFrame = outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
                    // Input already arrives at the source cadence. Rendering on the next
                    // display refresh avoids clock-drift between Windows timestamps and
                    // Android's monotonic clock from accumulating queued Surface frames.
                    current.releaseOutputBuffer(outputIndex, isFrame)
                    rendered = rendered || isFrame
                    timeoutUs = 0
                }
            }
        }
    }

    /** Releases the codec of the finished connection; the decoder can be configured again. */
    @Synchronized
    override fun reset() {
        releaseCodec()
        configuration = null
        sourceTimestampOriginUs = null
    }

    /** Releases the codec permanently; called before the output Surface is destroyed. */
    @Synchronized
    override fun close() {
        released = true
        reset()
    }

    private fun releaseCodec() {
        val current = codec
        codec = null
        hasQueuedInput = false
        softwareDecoderName = null
        if (current != null) {
            runCatching { current.stop() }
            current.release()
        }
    }

    private fun findSoftwareDecoderName(): String? =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .filter {
                it.supportedTypes.any { type ->
                    type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }
            }
            .map { it.name }
            .firstOrNull {
                it.startsWith("c2.android.", ignoreCase = true) ||
                    it.startsWith("OMX.google.", ignoreCase = true)
            }

    private fun splitParameterSets(csd: ByteArray): Pair<ByteArray, ByteArray> {
        val units = annexBUnits(csd)
        val sps = units.firstOrNull { (it[startCodeLength(it)].toInt() and 0x1F) == 7 }
        val pps = units.firstOrNull { (it[startCodeLength(it)].toInt() and 0x1F) == 8 }
        return Pair(
            requireNotNull(sps) { "CSD has no SPS" },
            requireNotNull(pps) { "CSD has no PPS" }
        )
    }

    private fun annexBUnits(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= bytes.size - 3) {
            val length = startCodeLength(bytes, index)
            if (length != 0) {
                starts += index
                index += length
            } else {
                index++
            }
        }
        require(starts.isNotEmpty()) { "CSD is not Annex B" }
        return starts.mapIndexed { unitIndex, start ->
            bytes.copyOfRange(start, starts.getOrElse(unitIndex + 1) { bytes.size })
        }
    }

    private fun startCodeLength(bytes: ByteArray, offset: Int = 0): Int {
        if (offset + 3 <= bytes.size &&
            bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte()
        ) {
            if (bytes[offset + 2] == 1.toByte()) return 3
            if (offset + 4 <= bytes.size &&
                bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte()
            ) return 4
        }
        return 0
    }

    companion object {
        /** Largest AVC frame of the decoder that createDecoderByType picks, or null if unknown. */
        fun maximumFrameSize(): Pair<Int, Int>? = runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = codecs.findDecoderForFormat(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 800)
            ) ?: return null
            val capabilities = codecs.codecInfos.first { it.name == name }
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .videoCapabilities ?: return null
            capabilities.supportedWidths.upper to capabilities.supportedHeights.upper
        }.getOrNull()

        private const val TAG = "H264Decoder"
        private const val INPUT_TIMEOUT_MS = 250L
        private const val INPUT_POLL_US = 2_000L
        private const val NO_OUTPUT_FALLBACK_MS = 2_000L
    }
}

internal fun renderLeadTimeUs(fpsNumerator: Int, fpsDenominator: Int): Long {
    require(fpsNumerator > 0 && fpsDenominator > 0)
    return 1_000_000L * fpsDenominator / fpsNumerator / 2L
}

internal fun outputWaitTimeUs(fpsNumerator: Int, fpsDenominator: Int): Long {
    require(fpsNumerator > 0 && fpsDenominator > 0)
    return 1_000_000L * fpsDenominator / fpsNumerator
}
