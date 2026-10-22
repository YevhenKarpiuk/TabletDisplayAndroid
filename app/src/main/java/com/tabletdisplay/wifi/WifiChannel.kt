package com.tabletdisplay.wifi

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** Session keys of an authenticated Wi-Fi connection (protocol section 10.4). */
class WifiSessionKeys(val windowsToAndroid: ByteArray, val androidToWindows: ByteArray)

/** A Windows installation this tablet is paired with. */
data class PairedWindows(val id: ByteArray, val name: String, val key: ByteArray)

interface PairingStore {
    fun find(windowsId: ByteArray): PairedWindows?
    fun save(windows: PairedWindows)
}

/** Lets the user compare the six-digit code with the one Windows shows. */
fun interface PairingConfirmation {
    /** Blocks until the user accepts or rejects; false on timeout. */
    fun confirm(windowsName: String, code: String): Boolean
}

sealed interface HandshakeResult {
    class Session(val keys: WifiSessionKeys, val windowsName: String) : HandshakeResult
    class Paired(val windowsName: String) : HandshakeResult
    object Rejected : HandshakeResult
}

class WifiProtocolException(message: String) : IOException(message)

/** Android side of the Wi-Fi handshake: pairing (10.3) or an authenticated session (10.4). */
class WifiHandshake(
    private val androidId: ByteArray,
    private val androidName: String,
    private val store: PairingStore,
    private val confirmation: PairingConfirmation,
    private val random: SecureRandom = SecureRandom()
) {
    fun run(input: InputStream, output: OutputStream): HandshakeResult {
        val first = readFrame(input)
        if (first.size < 5 || !first.copyOfRange(0, 4).contentEquals(WifiCrypto.ascii("TDW1"))) {
            throw WifiProtocolException("Not a TabletDisplay Wi-Fi client")
        }
        return when (first[4].toInt()) {
            TYPE_PAIR_HELLO -> pair(first, input, output)
            TYPE_SESSION_HELLO -> session(first, input, output)
            else -> throw WifiProtocolException("Unknown Wi-Fi handshake type")
        }
    }

    private fun pair(hello: ByteArray, input: InputStream, output: OutputStream): HandshakeResult {
        val reader = FieldReader(hello, 5)
        val windowsId = reader.bytes(WifiCrypto.ID_SIZE)
        val windowsName = reader.text()
        val pkW = reader.bytes(WifiCrypto.PUBLIC_KEY_SIZE)
        reader.end()

        val keyPair = WifiCrypto.generateKeyPair()
        val pkA = WifiCrypto.publicKeyBytes(keyPair)
        val z = WifiCrypto.sharedSecret(keyPair, pkW)
        val nA = randomBytes(WifiCrypto.PAIRING_NONCE_SIZE)
        writeFrame(output, byteArrayOf(TYPE_PAIR_REPLY.toByte()) + androidId + text(androidName) + pkA +
            WifiCrypto.commitment(pkA, pkW, nA))

        val nonceFrame = readFrame(input)
        val nW = FieldReader(nonceFrame, 0).run {
            expectType(TYPE_PAIR_NONCE_W)
            bytes(WifiCrypto.PAIRING_NONCE_SIZE).also { end() }
        }
        writeFrame(output, byteArrayOf(TYPE_PAIR_NONCE_A.toByte()) + nA)

        val code = WifiCrypto.comparisonCode(pkW, pkA, nW, nA)
        val accepted = confirmation.confirm(windowsName, code)
        writeFrame(output, byteArrayOf(TYPE_PAIR_DECISION.toByte(), if (accepted) 1 else 0))
        val decision = readFrame(input)
        val peerAccepted = FieldReader(decision, 0).run {
            expectType(TYPE_PAIR_DECISION)
            (bytes(1)[0].toInt() == 1).also { end() }
        }
        if (!accepted || !peerAccepted) return HandshakeResult.Rejected
        store.save(PairedWindows(windowsId, windowsName, WifiCrypto.pairingKey(z, pkW, pkA, nW, nA)))
        return HandshakeResult.Paired(windowsName)
    }

    private fun session(hello: ByteArray, input: InputStream, output: OutputStream): HandshakeResult {
        val reader = FieldReader(hello, 5)
        val windowsId = reader.bytes(WifiCrypto.ID_SIZE)
        val sW = reader.bytes(WifiCrypto.SESSION_NONCE_SIZE)
        reader.end()

        val windows = store.find(windowsId)
        if (windows == null) {
            writeFrame(output, byteArrayOf(TYPE_SESSION_REPLY.toByte(), STATUS_NOT_PAIRED.toByte()))
            return HandshakeResult.Rejected
        }
        val sA = randomBytes(WifiCrypto.SESSION_NONCE_SIZE)
        writeFrame(output, byteArrayOf(TYPE_SESSION_REPLY.toByte(), STATUS_OK.toByte()) + androidId + sA +
            WifiCrypto.serverMac(windows.key, windowsId, androidId, sW, sA))

        val proof = readFrame(input)
        val macW = FieldReader(proof, 0).run {
            expectType(TYPE_SESSION_PROOF)
            bytes(WifiCrypto.MAC_SIZE).also { end() }
        }
        val expected = WifiCrypto.clientMac(windows.key, windowsId, androidId, sW, sA)
        if (!java.security.MessageDigest.isEqual(macW, expected)) {
            throw WifiProtocolException("Windows failed Wi-Fi authentication")
        }
        return HandshakeResult.Session(
            WifiSessionKeys(
                WifiCrypto.windowsToAndroidKey(windows.key, sW, sA),
                WifiCrypto.androidToWindowsKey(windows.key, sW, sA)
            ),
            windows.name
        )
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun text(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    private class FieldReader(private val bytes: ByteArray, private var offset: Int) {
        fun expectType(type: Int) {
            if (bytes(1)[0].toInt() != type) throw WifiProtocolException("Unexpected Wi-Fi handshake message")
        }

        fun bytes(count: Int): ByteArray {
            if (offset + count > bytes.size) throw WifiProtocolException("Truncated Wi-Fi handshake message")
            return bytes.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun text(): String = String(bytes(bytes(1)[0].toInt() and 0xFF), Charsets.UTF_8)

        fun end() {
            if (offset != bytes.size) throw WifiProtocolException("Oversized Wi-Fi handshake message")
        }
    }

    companion object {
        const val TYPE_PAIR_HELLO = 0x01
        const val TYPE_PAIR_REPLY = 0x02
        const val TYPE_PAIR_NONCE_W = 0x03
        const val TYPE_PAIR_NONCE_A = 0x04
        const val TYPE_PAIR_DECISION = 0x05
        const val TYPE_SESSION_HELLO = 0x10
        const val TYPE_SESSION_REPLY = 0x11
        const val TYPE_SESSION_PROOF = 0x12
        const val STATUS_OK = 0
        const val STATUS_NOT_PAIRED = 1
        private const val MAX_FRAME = 65_536

        fun readFrame(input: InputStream): ByteArray {
            val data = DataInputStream(input)
            val header = ByteArray(4).also(data::readFully)
            val length = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8) or
                ((header[2].toInt() and 0xFF) shl 16) or ((header[3].toInt() and 0xFF) shl 24)
            if (length < 1 || length > MAX_FRAME) throw WifiProtocolException("Invalid Wi-Fi handshake length")
            return ByteArray(length).also(data::readFully)
        }

        fun writeFrame(output: OutputStream, body: ByteArray) {
            output.write(WifiCrypto.littleEndian32(body.size) + body)
            output.flush()
        }
    }
}

/** Decrypts the records of one direction (protocol section 10.5) into a byte stream. */
class SecureInputStream(raw: InputStream, private val key: ByteArray) : InputStream() {
    private val data = DataInputStream(raw)
    private var counter = 0L
    private var buffer = ByteArray(0)
    private var position = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position == buffer.size) {
            if (!nextRecord()) return -1
        }
        val count = minOf(length, buffer.size - position)
        System.arraycopy(buffer, position, target, offset, count)
        position += count
        return count
    }

    private fun nextRecord(): Boolean {
        val header = ByteArray(4)
        try {
            data.readFully(header)
        } catch (_: EOFException) {
            return false
        }
        val length = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or ((header[3].toInt() and 0xFF) shl 24)
        if (length <= WifiCrypto.TAG_SIZE || length > MAX_RECORD_PLAINTEXT + WifiCrypto.TAG_SIZE) {
            throw WifiProtocolException("Invalid Wi-Fi record length")
        }
        val body = ByteArray(length).also(data::readFully)
        buffer = try {
            WifiCrypto.openRecord(key, counter++, length, body)
        } catch (exception: java.security.GeneralSecurityException) {
            throw WifiProtocolException("Wi-Fi record authentication failed")
        }
        position = 0
        return true
    }

    companion object {
        const val MAX_RECORD_PLAINTEXT = 16 * 1024 * 1024
    }
}

/** Collects writes and encrypts them into records when flushed (protocol section 10.5). */
class SecureOutputStream(private val raw: OutputStream, private val key: ByteArray) : OutputStream() {
    private val pending = ByteArrayOutputStream()
    private var counter = 0L

    override fun write(value: Int) = pending.write(value)

    override fun write(source: ByteArray, offset: Int, length: Int) = pending.write(source, offset, length)

    override fun flush() {
        val bytes = pending.toByteArray()
        pending.reset()
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(bytes.size - offset, SecureInputStream.MAX_RECORD_PLAINTEXT)
            raw.write(WifiCrypto.sealRecord(key, counter++, bytes, offset, length))
            offset += length
        }
        raw.flush()
    }

    override fun close() = raw.close()
}
