package com.tabletdisplay.transport

import com.tabletdisplay.protocol.ProtocolConstants
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LoopbackServer : Closeable {
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start() {
        check(serverSocket == null) { "Loopback server is already running" }
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(
                InetSocketAddress(
                    InetAddress.getByName(ProtocolConstants.LOOPBACK_ADDRESS),
                    ProtocolConstants.PORT
                ),
                1
            )
        }
    }

    fun accept(): Socket =
        checkNotNull(serverSocket) { "Loopback server is not running" }.accept()

    @Synchronized
    override fun close() {
        serverSocket?.close()
        serverSocket = null
    }
}
