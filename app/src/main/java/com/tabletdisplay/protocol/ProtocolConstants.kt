package com.tabletdisplay.protocol

object ProtocolConstants {
    const val LOOPBACK_ADDRESS = "127.0.0.1"
    const val PORT = 27183
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 0
    const val VERSION = VERSION_MAJOR
    const val HEADER_SIZE = 32
    const val MAX_ACCESS_UNIT_SIZE = 8 * 1024 * 1024
    const val MAX_PAYLOAD_SIZE = MAX_ACCESS_UNIT_SIZE + 32
    const val MAX_CSD_SIZE = 64 * 1024
    const val MAX_ERROR_TEXT_SIZE = 1024
    const val MIN_VIDEO_DIMENSION = 128
    const val MAX_VIDEO_DIMENSION = 4096
    const val HANDSHAKE_TIMEOUT_MS = 5_000

    val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x31)
}
