package com.tabletdisplay.transport

import android.os.SystemClock
import android.util.Log
import com.tabletdisplay.input.NormalizedTouchEvent
import com.tabletdisplay.input.TouchAction
import com.tabletdisplay.protocol.AndroidDisplayInfo
import com.tabletdisplay.protocol.HandshakeProtocol
import com.tabletdisplay.protocol.IncrementalMessageDecoder
import com.tabletdisplay.protocol.MessageType
import com.tabletdisplay.protocol.ProtocolConstants
import com.tabletdisplay.protocol.ProtocolErrorKind
import com.tabletdisplay.protocol.ProtocolException
import com.tabletdisplay.protocol.WireMessage
import com.tabletdisplay.protocol.WireProtocol
import com.tabletdisplay.video.VideoConfig
import com.tabletdisplay.video.VideoSession
import com.tabletdisplay.video.VideoSink
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

enum class ServerStatus {
    LISTENING,
    CONNECTED,
    READY,
    STREAMING,
    ERROR,
    STOPPED
}

/** ERROR received from Windows; the session is closed without answering with another ERROR. */
class HostReportedException(message: String) : IOException(message)

class TabletDisplayServer(
    private val displayInfo: AndroidDisplayInfo,
    private val videoSink: VideoSink,
    private val onStatus: (ServerStatus, String?) -> Unit,
    private val onVideoConfig: (VideoConfig) -> Unit = {}
) : Closeable {
    @Volatile
    private var running = false
    private val loopbackServer = LoopbackServer()
    private val random = SecureRandom()
    private var worker: Thread? = null
    private var touchWorker: Thread? = null
    private val touchQueue = ArrayBlockingQueue<NormalizedTouchEvent>(TOUCH_QUEUE_CAPACITY)

    @Volatile
    private var clientSocket: Socket? = null
    private val sendLock = Any()

    @Volatile
    private var clientOutput: OutputStream? = null
    private var outgoingSequence = 1L
    private var activeStreamId = 0L
    private var contactSequence = 1L

    @Synchronized
    fun start() {
        check(!running) { "TabletDisplay server is already running" }
        running = true
        touchWorker = Thread(::runTouchSender, "TabletDisplayTouchSender").apply { start() }
        worker = Thread(::runServer, "TabletDisplayServer").apply { start() }
    }

    private fun runServer() {
        try {
            loopbackServer.start()
            publish(ServerStatus.LISTENING)
            while (running) {
                try {
                    loopbackServer.accept().use { socket ->
                        clientSocket = socket
                        configure(socket)
                        publish(ServerStatus.CONNECTED)
                        handleConnection(socket)
                    }
                } catch (exception: Exception) {
                    if (running) reportFailure(exception)
                } finally {
                    clientSocket = null
                }
                if (running) publish(ServerStatus.LISTENING)
            }
        } catch (exception: Exception) {
            if (running) reportFailure(exception)
        } finally {
            loopbackServer.close()
            publish(ServerStatus.STOPPED)
        }
    }

    private fun configure(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = ProtocolConstants.HANDSHAKE_TIMEOUT_MS
    }

    private fun handleConnection(socket: Socket) {
        val output = socket.getOutputStream()
        synchronized(sendLock) {
            clientOutput = output
            outgoingSequence = 1L
            activeStreamId = 0L
            contactSequence = 1L
        }
        val videoSession = VideoSession(
            sink = videoSink,
            onConfigured = { config ->
                synchronized(sendLock) { activeStreamId = config.streamId }
                onVideoConfig(config)
            },
            onKeyframeRequest = { request ->
                sendSequenced(output) { sequence ->
                    HandshakeProtocol.createRequestKeyframe(
                        sequence,
                        nowUs(),
                        request.streamId,
                        request.configId,
                        request.lastFrameId,
                        request.reason
                    )
                }
            },
            onFirstFrame = { publish(ServerStatus.STREAMING) }
        )

        try {
            receiveMessages(socket, output, videoSession)
        } catch (exception: Exception) {
            sendFatalError(output, exception)
            throw exception
        } finally {
            synchronized(sendLock) {
                if (clientOutput === output) {
                    clientOutput = null
                    activeStreamId = 0L
                }
            }
            touchQueue.clear()
            videoSession.close()
        }
    }

    private fun receiveMessages(socket: Socket, output: OutputStream, videoSession: VideoSession) {
        val decoder = IncrementalMessageDecoder()
        val input = socket.getInputStream()
        val buffer = ByteArray(16 * 1024)
        var handshakeComplete = false
        while (running) {
            val count = try {
                input.read(buffer)
            } catch (exception: SocketTimeoutException) {
                throw SocketTimeoutException("Protocol idle timeout")
            }
            if (count < 0) throw EOFException("Windows host disconnected")

            for (message in decoder.feed(buffer, 0, count)) {
                val type = MessageType.fromCode(message.header.messageType)
                if (!handshakeComplete) {
                    if (type != MessageType.HELLO) {
                        throw ProtocolException(
                            ProtocolErrorKind.UNEXPECTED_MESSAGE,
                            "First message must be HELLO"
                        )
                    }
                    val hello = HandshakeProtocol.parseHello(message)
                    val status = HandshakeProtocol.negotiationStatus(hello)
                    sendSequenced(output) { sequence ->
                        HandshakeProtocol.createHelloAck(
                            sequence,
                            nowUs(),
                            hello,
                            positiveNonce(),
                            status
                        )
                    }
                    if (status != HandshakeProtocol.STATUS_OK) return
                    sendSequenced(output) { sequence ->
                        HandshakeProtocol.createDisplayInfo(
                            sequence,
                            nowUs(),
                            displayInfo
                        )
                    }
                    socket.soTimeout = hello.idleTimeoutMs
                    handshakeComplete = true
                    publish(ServerStatus.READY)
                    continue
                }

                if (videoSession.accept(message)) continue
                when (type) {
                    MessageType.PING -> sendSequenced(output) { sequence ->
                        HandshakeProtocol.createPong(
                            sequence,
                            nowUs(),
                            message
                        )
                    }
                    MessageType.ERROR -> throw HostReportedException(
                        HandshakeProtocol.parseErrorText(message).ifBlank { "Windows host error" }
                    )
                    else -> throw ProtocolException(
                        ProtocolErrorKind.UNEXPECTED_MESSAGE,
                        "Unexpected Windows-to-Android message"
                    )
                }
            }
        }
    }

    /** Best-effort fatal ERROR before the connection is closed, as protocol section 5.9 requires. */
    private fun sendFatalError(output: OutputStream, exception: Exception) {
        val code = when (exception) {
            is HostReportedException, is EOFException, is SocketException -> return
            is ProtocolException -> when (exception.kind) {
                // The header could not be parsed safely: close without ERROR.
                ProtocolErrorKind.BAD_MAGIC, ProtocolErrorKind.INVALID_HEADER -> return
                ProtocolErrorKind.INVALID_FLAGS -> ERROR_MALFORMED_HEADER
                ProtocolErrorKind.UNSUPPORTED_VERSION -> ERROR_UNSUPPORTED_VERSION
                ProtocolErrorKind.UNKNOWN_CRITICAL_TYPE -> ERROR_UNKNOWN_CRITICAL_TYPE
                ProtocolErrorKind.PAYLOAD_TOO_LARGE -> ERROR_PAYLOAD_TOO_LARGE
                ProtocolErrorKind.INVALID_PAYLOAD -> ERROR_INVALID_PAYLOAD
                ProtocolErrorKind.SEQUENCE_GAP -> ERROR_SEQUENCE_GAP
                ProtocolErrorKind.UNEXPECTED_MESSAGE -> ERROR_UNEXPECTED_MESSAGE
            }
            is SocketTimeoutException -> ERROR_TIMEOUT
            else -> ERROR_INTERNAL
        }
        runCatching {
            sendSequenced(output) { sequence ->
                HandshakeProtocol.createError(
                    sequence,
                    nowUs(),
                    code,
                    SEVERITY_FATAL,
                    exception.message ?: exception.javaClass.simpleName
                )
            }
        }
    }

    fun sendTouch(event: NormalizedTouchEvent) {
        if (!running) return
        while (!touchQueue.offer(event)) {
            // Under backpressure only intermediate MOVE events may be lost: dropping DOWN or
            // UP would leave the Windows mouse button in the wrong state.
            val staleMove = touchQueue.firstOrNull { it.action == TouchAction.MOVE }
            if (staleMove != null) {
                touchQueue.remove(staleMove)
            } else if (event.action == TouchAction.MOVE) {
                return
            } else {
                touchQueue.poll()
            }
        }
    }

    private fun runTouchSender() {
        while (running) {
            val event = try {
                touchQueue.poll(250, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (!running) return
                continue
            } ?: continue
            sendTouchNow(event)
        }
    }

    private fun sendTouchNow(event: NormalizedTouchEvent) {
        try {
            synchronized(sendLock) {
                val output = clientOutput ?: return
                val streamId = activeStreamId
                if (streamId == 0L) return
                sendUnlocked(
                    output,
                    HandshakeProtocol.createTouch(
                        outgoingSequence++,
                        nowUs(),
                        streamId,
                        contactSequence++,
                        event
                    )
                )
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Could not send touch event", exception)
            runCatching { clientSocket?.close() }
        }
    }

    private fun sendSequenced(output: OutputStream, create: (Long) -> WireMessage) {
        synchronized(sendLock) {
            sendUnlocked(output, create(outgoingSequence++))
        }
    }

    private fun sendUnlocked(output: OutputStream, message: WireMessage) {
        output.write(WireProtocol.encodeMessage(message.header, message.payload))
        output.flush()
    }

    private fun positiveNonce(): Long = random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1L)

    private fun nowUs(): Long = (SystemClock.elapsedRealtimeNanos() / 1_000L).coerceAtLeast(1L)

    private fun reportFailure(exception: Exception) {
        val expectedDisconnect = exception is EOFException
        if (!expectedDisconnect) {
            Log.w(TAG, "Session failed", exception)
            publish(ServerStatus.ERROR, exception.message)
        }
    }

    private fun publish(status: ServerStatus, detail: String? = null) {
        onStatus(status, detail)
    }

    /**
     * Stops the server and releases the decoder. Must return before the Surface is destroyed,
     * because MediaCodec keeps rendering into it until it is released.
     */
    @Synchronized
    override fun close() {
        if (!running) return
        running = false
        runCatching { clientSocket?.close() }
        loopbackServer.close()
        touchWorker?.interrupt()
        worker?.join(1_000)
        touchWorker?.join(1_000)
        worker = null
        touchWorker = null
        touchQueue.clear()
        // Waits for an in-flight decoder call, which is bounded by short MediaCodec timeouts.
        videoSink.close()
    }

    companion object {
        private const val TAG = "TabletDisplayServer"
        private const val TOUCH_QUEUE_CAPACITY = 128
        private const val SEVERITY_FATAL = 3
        private const val ERROR_MALFORMED_HEADER = 1
        private const val ERROR_UNSUPPORTED_VERSION = 2
        private const val ERROR_UNKNOWN_CRITICAL_TYPE = 3
        private const val ERROR_PAYLOAD_TOO_LARGE = 4
        private const val ERROR_INVALID_PAYLOAD = 5
        private const val ERROR_UNEXPECTED_MESSAGE = 6
        private const val ERROR_INTERNAL = 8
        private const val ERROR_TIMEOUT = 9
        private const val ERROR_SEQUENCE_GAP = 10
    }
}
