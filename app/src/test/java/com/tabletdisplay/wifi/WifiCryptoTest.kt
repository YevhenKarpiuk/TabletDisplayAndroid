package com.tabletdisplay.wifi

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Vectors of doc/PROTOCOL.md section 10.6, shared with the Windows tests. */
class WifiCryptoTest {
    private val pkW = hex(
        "04e8027af4ad682be3a309ea73145fbd202cb00420aa9fcbd0b1dc761e87bda40c" +
            "6ab23185a2fe7743ee6a1127dd204eb2a8ba11ab134f7b25f60a38642c405af3"
    )
    private val pkA = hex(
        "042a03eb4a58771474e7f228eab79050860361563168751ddd7bb43865001a1c6e" +
            "48fa12b852bcde33f8bd247b6a0f4afaa512c81f37fc21eb7ffd8be9d3cff67b"
    )
    private val z = hex("c0f8077e1eba2503e67030e472e0a50116a9ac779f1619dee9693b2f5085fd2b")
    private val k = hex("0287e6bb7e19591233de56920f2a42d43c45c0a1e0d73ed08e0bae9a2eccc76c")
    private val nW = ByteArray(16) { it.toByte() }
    private val nA = ByteArray(16) { (16 + it).toByte() }
    private val idW = ByteArray(16) { (32 + it).toByte() }
    private val idA = ByteArray(16) { (48 + it).toByte() }
    private val sW = ByteArray(32) { (64 + it).toByte() }
    private val sA = ByteArray(32) { (96 + it).toByte() }

    @Test
    fun sharedSecretMatchesVector() {
        val ownPublic = WifiCrypto.generateKeyPair().public as ECPublicKey
        val dA = BigInteger(1, WifiCrypto.sha256(WifiCrypto.ascii("android private")))
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(dA, ownPublic.params))
        assertArrayEquals(z, WifiCrypto.sharedSecret(privateKey, ownPublic, pkW))
    }

    @Test
    fun pairingDerivationsMatchVectors() {
        assertArrayEquals(
            hex("643e92289bac675c26a92ac4217b459aee1ebab1bd7c11494a54f0191d2947b3"),
            WifiCrypto.commitment(pkA, pkW, nA)
        )
        assertEquals("063562", WifiCrypto.comparisonCode(pkW, pkA, nW, nA))
        assertArrayEquals(k, WifiCrypto.pairingKey(z, pkW, pkA, nW, nA))
    }

    @Test
    fun sessionDerivationsMatchVectors() {
        assertArrayEquals(
            hex("1c9b48aa5eb9ddd2911c27552dc9ff16b9b34dc0d6550e38137717fd2d54a805"),
            WifiCrypto.serverMac(k, idW, idA, sW, sA)
        )
        assertArrayEquals(
            hex("3c229f72f9b85f815b7f52edd31fd9ff88e19a602f20590eedb5963c57343e0e"),
            WifiCrypto.clientMac(k, idW, idA, sW, sA)
        )
        assertArrayEquals(
            hex("712c4bef122d7bd457a06f902b1fd586a44501cb96bec1ed55fe778666ff2307"),
            WifiCrypto.windowsToAndroidKey(k, sW, sA)
        )
        assertArrayEquals(
            hex("031be4c4307f4092841bfd943ed9ef8c0aea9e99d5afc70fa8bf5d9130519221"),
            WifiCrypto.androidToWindowsKey(k, sW, sA)
        )
    }

    @Test
    fun recordMatchesVectorAndRoundTrips() {
        val key = WifiCrypto.windowsToAndroidKey(k, sW, sA)
        val plaintext = WifiCrypto.ascii("TabletDisplay record")
        val record = WifiCrypto.sealRecord(key, 1, plaintext, 0, plaintext.size)
        assertArrayEquals(
            hex("240000008606ab897ebbd39aea03bc14a402399a7773490910c0124457071d5122b2ad641b9d6241"),
            record
        )

        val input = SecureInputStream(
            (WifiCrypto.sealRecord(key, 0, ByteArray(0) + 7, 0, 1) + record).inputStream(),
            key
        )
        assertEquals(7, input.read())
        assertArrayEquals(plaintext, input.readBytes())
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
