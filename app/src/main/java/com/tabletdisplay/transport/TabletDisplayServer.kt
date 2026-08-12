package com.tabletdisplay.transport

import android.os.SystemClock
import android.util.Log
import com.tabletdisplay.protocol.AndroidDisplayInfo
import com.tabletdisplay.protocol.HandshakeProtocol
import com.tabletdisplay.protocol.IncrementalMessageDecoder
import com.tabletdisplay.protocol.MessageType
import com.tabletdisplay.protocol.ProtocolConstants
import com.tabletdisplay.protocol.ProtocolException
import com.tabletdisplay.protocol.WireMessage
import com.tabletdisplay.protocol.WireProtocol
import java.io.Closeable
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom

enum class ServerStatus {
    LISTENING,
    CONNECTED,
    READY,
    ERROR,
    STOPPED
}

class TabletDisplayServer(
    private val displayInfo: AndroidDisplayInfo,
    private val onStatus: (ServerStatus, String?) -> Unit
) : Closeable {
    @Volatile
    private var running = false
    private val loopbackServer = LoopbackServer()
    private val random = SecureRandom()
    private var worker: Thread? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Synchronized
    fun start() {
        check(!running) { "TabletDisplay server is already running" }
        running = true
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
        var outgoingSequence = 1L

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
                    send(
                        output,
                        HandshakeProtocol.createHelloAck(
                            outgoingSequence++,
                            nowUs(),
                            hello,
                            positiveNonce(),
                            status
                        )
                    )
                    if (status != HandshakeProtocol.STATUS_OK) return
                    send(
                        output,
                        HandshakeProtocol.createDisplayInfo(
                            outgoingSequence++,
                            nowUs(),
                            displayInfo
                        )
                    )
                    socket.soTimeout = hello.idleTimeoutMs
                    handshakeComplete = true
                    publish(ServerStatus.READY)
                    continue
                }

                when (MessageType.fromCode(message.header.messageType)) {
                    MessageType.PING -> send(
                        output,
                        HandshakeProtocol.createPong(outgoingSequence++, nowUs(), message)
                    )
                    MessageType.VIDEO_CONFIG,
                    MessageType.VIDEO_FRAME -> Unit
                    MessageType.ERROR -> return
                    else -> throw ProtocolException(
                        com.tabletdisplay.protocol.ProtocolErrorKind.INVALID_PAYLOAD,
                        "Unexpected Windows-to-Android message"
                    )
                }
            }
        }
    }

    private fun send(output: java.io.OutputStream, message: WireMessage) {
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
        worker?.join(1_000)
        worker = null
    }

    companion object {
        private const val TAG = "TabletDisplayServer"
    }
}
