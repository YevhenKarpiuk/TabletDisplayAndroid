package com.tabletdisplay.wifi

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives and derivations of the Wi-Fi transport, protocol section 10. */
object WifiCrypto {
    const val PUBLIC_KEY_SIZE = 65
    const val ID_SIZE = 16
    const val PAIRING_NONCE_SIZE = 16
    const val SESSION_NONCE_SIZE = 32
    const val MAC_SIZE = 32
    const val TAG_SIZE = 16

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    /** Uncompressed P-256 point: 04 || X || Y. */
    fun publicKeyBytes(keyPair: KeyPair): ByteArray {
        val point = (keyPair.public as ECPublicKey).w
        return byteArrayOf(4) + unsigned32(point.affineX) + unsigned32(point.affineY)
    }

    /** X coordinate of the shared point, big-endian. */
    fun sharedSecret(own: KeyPair, peerPublicKey: ByteArray): ByteArray =
        sharedSecret(own.private, (own.public as ECPublicKey), peerPublicKey)

    fun sharedSecret(privateKey: PrivateKey, ownPublic: ECPublicKey, peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == PUBLIC_KEY_SIZE && peerPublicKey[0] == 4.toByte()) {
            "Invalid P-256 public key"
        }
        val point = ECPoint(
            BigInteger(1, peerPublicKey.copyOfRange(1, 33)),
            BigInteger(1, peerPublicKey.copyOfRange(33, 65))
        )
        val peer = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ownPublic.params))
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    fun commitment(pkA: ByteArray, pkW: ByteArray, nA: ByteArray): ByteArray =
        sha256(ascii("TDW1 commit"), pkA, pkW, nA)

    /** Six-digit comparison code both devices show during pairing. */
    fun comparisonCode(pkW: ByteArray, pkA: ByteArray, nW: ByteArray, nA: ByteArray): String {
        val digest = sha256(ascii("TDW1 sas"), pkW, pkA, nW, nA)
        val value = ((digest[0].toLong() and 0xFF) shl 24) or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)
        return "%06d".format(value % 1_000_000L)
    }

    fun pairingKey(z: ByteArray, pkW: ByteArray, pkA: ByteArray, nW: ByteArray, nA: ByteArray): ByteArray =
        hmac(z, ascii("TDW1 pair"), pkW, pkA, nW, nA)

    fun serverMac(k: ByteArray, idW: ByteArray, idA: ByteArray, sW: ByteArray, sA: ByteArray): ByteArray =
        hmac(k, ascii("TDW1 server"), idW, idA, sW, sA)

    fun clientMac(k: ByteArray, idW: ByteArray, idA: ByteArray, sW: ByteArray, sA: ByteArray): ByteArray =
        hmac(k, ascii("TDW1 client"), idW, idA, sW, sA)

    fun windowsToAndroidKey(k: ByteArray, sW: ByteArray, sA: ByteArray): ByteArray =
        hmac(k, ascii("TDW1 w2a"), sW, sA)

    fun androidToWindowsKey(k: ByteArray, sW: ByteArray, sA: ByteArray): ByteArray =
        hmac(k, ascii("TDW1 a2w"), sW, sA)

    /** One record: u32 N || AES-256-GCM(plaintext) || tag, N = ciphertext + tag, AAD = N. */
    fun sealRecord(key: ByteArray, counter: Long, plaintext: ByteArray, offset: Int, length: Int): ByteArray {
        val recordLength = length + TAG_SIZE
        val header = littleEndian32(recordLength)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, recordNonce(counter)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext, offset, length)
    }

    /** Opens the body (ciphertext || tag) of a record whose length field was [recordLength]. */
    fun openRecord(key: ByteArray, counter: Long, recordLength: Int, body: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, recordNonce(counter)))
        cipher.updateAAD(littleEndian32(recordLength))
        return cipher.doFinal(body)
    }

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    fun littleEndian32(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun recordNonce(counter: Long): ByteArray {
        require(counter >= 0) { "Record counter overflow" }
        val nonce = ByteArray(12)
        for (index in 0 until 8) nonce[4 + index] = (counter ushr (8 * index)).toByte()
        return nonce
    }

    private fun unsigned32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}
