package com.tabletdisplay.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.tabletdisplay.protocol.ProtocolConstants
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class H264Decoder(private val surface: Surface) : VideoSink {
    private var codec: MediaCodec? = null
    private var callbacks: DecoderCallbacks? = null
    private var callbackThread: HandlerThread? = null
    private var hasQueuedInput = false
    private var configuration: VideoConfig? = null
    private var softwareDecoderName: String? = null
    private var sourceTimestampOriginUs: Long? = null
    private var localTimestampOriginUs = 0L
    private var renderLeadTimeUs = 0L
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching {
                decoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        ) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val thread = HandlerThread("H264Decoder", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        thread.start()
        val decoderCallbacks = DecoderCallbacks()
        try {
            // Asynchronous mode: each decoded frame goes to the Surface from the callback thread
            // as soon as the decoder finishes it, without waiting for the next network packet.
            decoder.setCallback(decoderCallbacks, Handler(thread.looper))
            decoder.configure(format, surface, null, 0)
            decoder.start()
        } catch (exception: Exception) {
            decoder.release()
            thread.quitSafely()
            throw exception
        }
        codec = decoder
        callbacks = decoderCallbacks
        callbackThread = thread
        hasQueuedInput = false
        this.softwareDecoderName = softwareDecoderName
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
        val currentCallbacks = checkNotNull(callbacks)
        val localPresentationTimeUs = mapPresentationTimeUs(presentationTimeUs)
        // Frames skipped while waiting for a recovery IDR are not a decoder stall.
        if (discontinuity) currentCallbacks.resetStallTracking()
        try {
            queueInput(current, currentCallbacks, bytes, offset, length, localPresentationTimeUs, discontinuity)
        } catch (exception: IllegalStateException) {
            // MediaCodec.CodecException is an IllegalStateException as well.
            restart(softwareDecoderName, "Decoder error", exception)
        }

        // The Host sends nothing while the picture is static, so a stall is judged by frames
        // queued since the last output rather than by time alone.
        if (currentCallbacks.queuedSinceOutput.incrementAndGet() >= NO_OUTPUT_FALLBACK_FRAMES &&
            SystemClock.elapsedRealtime() - currentCallbacks.lastOutputRealtimeMs > NO_OUTPUT_FALLBACK_MS
        ) {
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

    private fun queueInput(
        current: MediaCodec,
        decoderCallbacks: DecoderCallbacks,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        discontinuity: Boolean
    ) {
        decoderCallbacks.error?.let { throw it }
        if (discontinuity && hasQueuedInput) {
            current.flush()
            decoderCallbacks.inputBuffers.clear()
            // In asynchronous mode flush() stops input callbacks until start() is called again.
            current.start()
            hasQueuedInput = false
        }
        val inputIndex = decoderCallbacks.inputBuffers.poll(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: throw decoderCallbacks.error ?: IllegalStateException("Decoder input buffer timeout")
        val input = checkNotNull(current.getInputBuffer(inputIndex))
        input.clear()
        require(length <= input.remaining()) { "Access unit exceeds decoder input buffer" }
        input.put(bytes, offset, length)
        current.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
        hasQueuedInput = true
    }

    /** Callbacks of one codec instance; a restarted decoder gets a fresh set. */
    private class DecoderCallbacks : MediaCodec.Callback() {
        val inputBuffers = LinkedBlockingQueue<Int>()
        val queuedSinceOutput = AtomicInteger()

        @Volatile
        var lastOutputRealtimeMs = SystemClock.elapsedRealtime()
            private set

        @Volatile
        var error: MediaCodec.CodecException? = null
            private set

        fun resetStallTracking() {
            queuedSinceOutput.set(0)
            lastOutputRealtimeMs = SystemClock.elapsedRealtime()
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputBuffers.offer(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            // Surface-backed decoders may report a zero-sized output buffer:
            // the pixels live in the Surface rather than in a ByteBuffer.
            val isFrame = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0
            try {
                // Rendering immediately shows the frame on the next display refresh; the input
                // already arrives at the source cadence, so no timestamp-based pacing is needed.
                codec.releaseOutputBuffer(index, isFrame)
            } catch (exception: IllegalStateException) {
                // The codec was stopped by the network thread while this callback was queued.
                return
            }
            if (isFrame) resetStallTracking()
        }

        override fun onError(codec: MediaCodec, exception: MediaCodec.CodecException) {
            error = exception
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    @Synchronized
    override fun signalEndOfStream(presentationTimeUs: Long) {
        val current = codec ?: return
        val inputIndex = callbacks?.inputBuffers?.poll(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (inputIndex == null) {
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
        val thread = callbackThread
        codec = null
        callbacks = null
        callbackThread = null
        hasQueuedInput = false
        softwareDecoderName = null
        if (current != null) {
            runCatching { current.stop() }
            current.release()
        }
        thread?.quitSafely()
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
        private const val NO_OUTPUT_FALLBACK_MS = 2_000L
        private const val NO_OUTPUT_FALLBACK_FRAMES = 30
    }
}

internal fun renderLeadTimeUs(fpsNumerator: Int, fpsDenominator: Int): Long {
    require(fpsNumerator > 0 && fpsDenominator > 0)
    return 1_000_000L * fpsDenominator / fpsNumerator / 2L
}
