package com.tabletdisplay.transport

import android.os.SystemClock
import android.util.Log
import com.tabletdisplay.input.NormalizedTouchEvent
import com.tabletdisplay.protocol.AndroidDisplayInfo
import com.tabletdisplay.protocol.HandshakeProtocol
import com.tabletdisplay.protocol.IncrementalMessageDecoder
import com.tabletdisplay.protocol.MessageType
import com.tabletdisplay.protocol.ProtocolConstants
import com.tabletdisplay.protocol.ProtocolException
import com.tabletdisplay.protocol.WireMessage
import com.tabletdisplay.protocol.WireProtocol
import com.tabletdisplay.video.VideoSession
import com.tabletdisplay.video.VideoSink
import java.io.Closeable
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

class TabletDisplayServer(
    private val displayInfo: AndroidDisplayInfo,
    private val videoSink: VideoSink,
    private val onStatus: (ServerStatus, String?) -> Unit
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
                } catch (exception: SocketException) {
                    if (running) reportFailure(exception)
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
        val decoder = IncrementalMessageDecoder()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val buffer = ByteArray(16 * 1024)
        var handshakeComplete = false
        val videoSession = VideoSession(videoSink) {
            publish(ServerStatus.STREAMING)
        }

        synchronized(sendLock) {
            clientOutput = output
            outgoingSequence = 1L
            activeStreamId = 0L
            contactSequence = 1L
        }

        try {
            while (running) {
                val count = try {
                    input.read(buffer)
                } catch (exception: SocketTimeoutException) {
                    throw SocketTimeoutException("Protocol idle timeout")
                }
                if (count < 0) throw EOFException("Windows host disconnected")

                for (message in decoder.feed(buffer, 0, count)) {
                    if (!handshakeComplete) {
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

                    if (videoSession.accept(message)) {
                        if (MessageType.fromCode(message.header.messageType) == MessageType.VIDEO_CONFIG) {
                            val streamId = ByteBuffer.wrap(message.payload)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int.toLong() and 0xFFFF_FFFFL
                            synchronized(sendLock) { activeStreamId = streamId }
                        }
                        continue
                    }
                    when (MessageType.fromCode(message.header.messageType)) {
                        MessageType.PING -> sendSequenced(output) { sequence ->
                            HandshakeProtocol.createPong(
                                sequence,
                                nowUs(),
                                message
                            )
                        }
                        MessageType.ERROR -> return
                        else -> throw ProtocolException(
                            com.tabletdisplay.protocol.ProtocolErrorKind.INVALID_PAYLOAD,
                            "Unexpected Windows-to-Android message"
                        )
                    }
                }
            }
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

    fun sendTouch(event: NormalizedTouchEvent) {
        if (!running) return
        if (!touchQueue.offer(event)) {
            touchQueue.poll()
            touchQueue.offer(event)
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
    }

    companion object {
        private const val TAG = "TabletDisplayServer"
        private const val TOUCH_QUEUE_CAPACITY = 128
    }
}
