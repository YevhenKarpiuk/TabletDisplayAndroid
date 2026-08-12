package com.tabletdisplay.video

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.tabletdisplay.protocol.ProtocolConstants
import java.nio.ByteBuffer
import kotlin.math.max

class H264Decoder(private val surface: Surface) : VideoSink {
    private var codec: MediaCodec? = null
    private val outputInfo = MediaCodec.BufferInfo()
    private var hasQueuedInput = false
    private var configuration: VideoConfig? = null
    private var usingSoftwareDecoder = false
    private var sourceTimestampOriginUs: Long? = null
    private var localTimestampOriginUs = 0L
    private var renderLeadTimeUs = 0L

    @Synchronized
    override fun configure(config: VideoConfig) {
        require(config.width > 0 && config.height > 0)
        require(config.fpsNumerator > 0 && config.fpsDenominator > 0)
        releaseCodec()
        configuration = config
        sourceTimestampOriginUs = null
        renderLeadTimeUs =
            1_000_000L * config.fpsDenominator / config.fpsNumerator
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
            setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets.first))
            setByteBuffer("csd-1", ByteBuffer.wrap(parameterSets.second))
        }
        val decoder = if (softwareDecoderName == null) {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } else {
            Log.i(TAG, "Falling back to software AVC decoder: $softwareDecoderName")
            MediaCodec.createByCodecName(softwareDecoderName)
        }
        codec = decoder.apply {
            configure(format, surface, null, 0)
            start()
        }
        hasQueuedInput = false
        usingSoftwareDecoder = softwareDecoderName != null
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
        var current = checkNotNull(codec) { "Decoder is not configured" }
        val localPresentationTimeUs = mapPresentationTimeUs(presentationTimeUs)
        if (queueAndRender(
                current,
                bytes,
                offset,
                length,
                localPresentationTimeUs,
                discontinuity
            )
        ) {
            return
        }

        val softwareDecoder = softwareDecoderName()
        check(!usingSoftwareDecoder && softwareDecoder != null) {
            "Decoder produced no output frame"
        }
        val config = checkNotNull(configuration)
        releaseCodec()
        startCodec(config, softwareDecoder)
        current = checkNotNull(codec)
        check(
            queueAndRender(
                current,
                bytes,
                offset,
                length,
                localPresentationTimeUs,
                discontinuity = false
            )
        ) { "Software decoder produced no output frame" }
    }

    private fun queueAndRender(
        current: MediaCodec,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        discontinuity: Boolean
    ): Boolean {
        if (discontinuity && hasQueuedInput) {
            current.flush()
        }
        val inputIndex = current.dequeueInputBuffer(INPUT_TIMEOUT_US)
        check(inputIndex >= 0) { "Decoder input buffer timeout" }
        val input = checkNotNull(current.getInputBuffer(inputIndex))
        input.clear()
        check(length <= input.remaining()) { "Access unit exceeds decoder input buffer" }
        input.put(bytes, offset, length)
        current.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
        hasQueuedInput = true
        return drainOutput(current, waitForFrame = true)
    }

    @Synchronized
    override fun signalEndOfStream(presentationTimeUs: Long) {
        val current = codec ?: return
        val inputIndex = current.dequeueInputBuffer(INPUT_TIMEOUT_US)
        check(inputIndex >= 0) { "Decoder EOS input buffer timeout" }
        current.queueInputBuffer(
            inputIndex,
            0,
            0,
            mapPresentationTimeUs(presentationTimeUs),
            MediaCodec.BUFFER_FLAG_END_OF_STREAM
        )
        drainOutput(current, waitForFrame = false)
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
    private fun drainOutput(current: MediaCodec, waitForFrame: Boolean): Boolean {
        var shouldWait = waitForFrame
        var rendered = false
        while (true) {
            val timeoutUs = if (shouldWait) OUTPUT_TIMEOUT_US else 0
            when (val outputIndex = current.dequeueOutputBuffer(outputInfo, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return rendered
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    // Surface-backed decoders may report a zero-sized output buffer:
                    // the pixels live in the Surface rather than in a ByteBuffer.
                    val isFrame = outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
                    if (isFrame) {
                        // Keep one frame of headroom and let SurfaceFlinger present at the
                        // source cadence. Late frames are still rendered immediately.
                        val renderTimeNs = max(
                            outputInfo.presentationTimeUs * 1_000L,
                            System.nanoTime()
                        )
                        current.releaseOutputBuffer(outputIndex, renderTimeNs)
                    } else {
                        current.releaseOutputBuffer(outputIndex, false)
                    }
                    rendered = rendered || isFrame
                    shouldWait = false
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        releaseCodec()
        configuration = null
        sourceTimestampOriginUs = null
    }

    private fun releaseCodec() {
        val current = codec
        codec = null
        hasQueuedInput = false
        usingSoftwareDecoder = false
        if (current != null) {
            runCatching { current.stop() }
            current.release()
        }
    }

    private fun softwareDecoderName(): String? =
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
        private const val TAG = "H264Decoder"
        private const val INPUT_TIMEOUT_US = 5_000_000L
        private const val OUTPUT_TIMEOUT_US = 2_000_000L
    }
}
