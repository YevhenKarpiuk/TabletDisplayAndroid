package com.tabletdisplay.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable

class H264Decoder : Closeable {
    private var codec: MediaCodec? = null

    fun configure(surface: Surface, width: Int, height: Int) {
        require(width > 0 && height > 0)
        close()

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height),
                surface,
                null,
                0
            )
            start()
        }
    }

    @Synchronized
    override fun close() {
        val current = codec ?: return
        codec = null
        runCatching { current.stop() }
        current.release()
    }
}
