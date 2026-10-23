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
import com.tabletdisplay.wifi.HandshakeResult
import com.tabletdisplay.wifi.PairingConfirmation
import com.tabletdisplay.wifi.PairingStore
import com.tabletdisplay.wifi.SecureInputStream
import com.tabletdisplay.wifi.SecureOutputStream
import com.tabletdisplay.wifi.WifiHandshake
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

/** Direct Wi-Fi access (protocol section 10): identity, pairings and the pairing dialog. */
class WifiOptions(
    val androidId: ByteArray,
    val androidName: String,
    val store: PairingStore,
    val confirmation: PairingConfirmation,
    val onPaired: (String) -> Unit
)

class TabletDisplayServer(
    private val displayInfo: AndroidDisplayInfo,
    private val videoSink: VideoSink,
    private val onStatus: (ServerStatus, String?) -> Unit,
    private val onVideoConfig: (VideoConfig) -> Unit = {},
    private val wifi: WifiOptions? = null,
    // true: keep the orientation of the stream; false: rotate with the sensor.
    private val onOrientationPolicy: (Boolean) -> Unit = {}
) : Closeable {
    @Volatile
    private var running = false
    private val loopbackServer = LoopbackServer()
    private val random = SecureRandom()
    private var worker: Thread? = null
    private var wifiWorker: Thread? = null
    private var touchWorker: Thread? = null
    // One protocol session at a time, over USB or Wi-Fi; others are answered with BUSY.
    private val sessionActive = AtomicBoolean(false)
    @Volatile
    private var wifiServer: ServerSocket? = null
    // Wi-Fi connections still in their handshake, closed when the server stops.
    private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()
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
        if (wifi != null) wifiWorker = Thread(::runWifiServer, "TabletDisplayWifiServer").apply { start() }
    }

    private fun runServer() {
        try {
            loopbackServer.start()
            publish(ServerStatus.LISTENING)
            while (running) {
                try {
                    loopbackServer.accept().use { socket ->
                        configure(socket)
                        serveSession(socket, socket.getInputStream(), socket.getOutputStream())
                    }
                } catch (exception: Exception) {
                    if (running) reportFailure(exception)
                }
            }
        } catch (exception: Exception) {
            if (running) reportFailure(exception)
        } finally {
            loopbackServer.close()
            publish(ServerStatus.STOPPED)
        }
    }

    private fun runWifiServer() {
        try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ProtocolConstants.WIFI_PORT), 4)
            }
            wifiServer = server
            while (running) {
                val socket = server.accept()
                pendingSockets += socket
                Thread({ serveWifiConnection(socket) }, "TabletDisplayWifiConnection").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (exception: Exception) {
            if (running) Log.w(TAG, "Wi-Fi listener stopped", exception)
        } finally {
            runCatching { wifiServer?.close() }
            wifiServer = null
        }
    }

    private fun serveWifiConnection(socket: Socket) {
        val options = checkNotNull(wifi)
        try {
            socket.use {
                configure(socket)
                // The user may need time to compare the pairing code on both screens.
                socket.soTimeout = PAIRING_TIMEOUT_MS
                val rawInput = socket.getInputStream()
                val rawOutput = socket.getOutputStream()
                val handshake = WifiHandshake(
                    options.androidId,
                    options.androidName,
                    options.store,
                    options.confirmation
                )
                when (val result = handshake.run(rawInput, rawOutput)) {
                    is HandshakeResult.Paired -> options.onPaired(result.windowsName)
                    HandshakeResult.Rejected -> Unit
                    is HandshakeResult.Session -> {
                        socket.soTimeout = ProtocolConstants.HANDSHAKE_TIMEOUT_MS
                        pendingSockets -= socket
                        serveSession(
                            socket,
                            SecureInputStream(rawInput, result.keys.windowsToAndroid),
                            SecureOutputStream(rawOutput, result.keys.androidToWindows)
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            // A failed Wi-Fi handshake (for example an unpaired or foreign client) must not
            // disturb the screen while another session streams.
            if (running) Log.w(TAG, "Wi-Fi connection failed", exception)
        } finally {
            pendingSockets -= socket
        }
    }

    /** Runs one protocol session, or answers BUSY while another tablet session is active. */
    private fun serveSession(socket: Socket, input: InputStream, output: OutputStream) {
        if (!sessionActive.compareAndSet(false, true)) {
            rejectBusy(input, output)
            return
        }
        try {
            clientSocket = socket
            publish(ServerStatus.CONNECTED)
            handleConnection(socket, input, output)
        } catch (exception: Exception) {
            if (running) reportFailure(exception)
        } finally {
            clientSocket = null
            sessionActive.set(false)
            if (running) publish(ServerStatus.LISTENING)
        }
    }

    private fun rejectBusy(input: InputStream, output: OutputStream) {
        val decoder = IncrementalMessageDecoder()
        val buffer = ByteArray(4 * 1024)
        while (running) {
            val count = input.read(buffer)
            if (count < 0) return
            val hello = decoder.feed(buffer, 0, count).firstOrNull() ?: continue
            if (MessageType.fromCode(hello.header.messageType) != MessageType.HELLO) return
            val parsed = HandshakeProtocol.parseHello(hello)
            sendUnlocked(
                output,
                HandshakeProtocol.createHelloAck(1, nowUs(), parsed, positiveNonce(), HandshakeProtocol.STATUS_BUSY)
            )
            return
        }
    }

    private fun configure(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = ProtocolConstants.HANDSHAKE_TIMEOUT_MS
    }

    private fun handleConnection(socket: Socket, input: InputStream, output: OutputStream) {
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
            receiveMessages(socket, input, output, videoSession)
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

    private fun receiveMessages(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        videoSession: VideoSession
    ) {
        val decoder = IncrementalMessageDecoder()
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
                    MessageType.ORIENTATION_POLICY -> onOrientationPolicy(message.payload[0].toInt() == 1)
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
        runCatching { wifiServer?.close() }
        pendingSockets.forEach { runCatching { it.close() } }
        wifiWorker?.join(1_000)
        wifiWorker = null
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
        private const val PAIRING_TIMEOUT_MS = 130_000
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
