package com.tabletdisplay.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import java.security.SecureRandom

/** Stable random identifier of this installation and the Windows PCs it is paired with. */
class WifiIdentity(context: Context) : PairingStore {
    private val identity = context.getSharedPreferences("wifi_identity", Context.MODE_PRIVATE)
    private val pairings = context.getSharedPreferences("wifi_pairings", Context.MODE_PRIVATE)

    val id: ByteArray = identity.getString(KEY_ID, null)?.let(::fromHex) ?: ByteArray(WifiCrypto.ID_SIZE).also {
        SecureRandom().nextBytes(it)
        identity.edit().putString(KEY_ID, toHex(it)).apply()
    }

    val name: String = Build.MODEL ?: "Android"

    @Synchronized
    override fun find(windowsId: ByteArray): PairedWindows? {
        val value = pairings.getString(toHex(windowsId), null) ?: return null
        val separator = value.indexOf('|')
        if (separator < 0) return null
        val key = Base64.decode(value.substring(0, separator), Base64.NO_WRAP)
        return PairedWindows(windowsId, value.substring(separator + 1), key)
    }

    @Synchronized
    override fun save(windows: PairedWindows) {
        // App-private storage: only this app (and root) can read the pairing keys.
        pairings.edit()
            .putString(toHex(windows.id), Base64.encodeToString(windows.key, Base64.NO_WRAP) + "|" + windows.name)
            .apply()
    }

    companion object {
        private const val KEY_ID = "id"

        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun fromHex(text: String): ByteArray? =
            if (text.length != WifiCrypto.ID_SIZE * 2) null
            else ByteArray(WifiCrypto.ID_SIZE) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/** Publishes the Wi-Fi listener as DNS-SD service _tabletdisplay._tcp (protocol section 10.1). */
class WifiAdvertiser(context: Context, private val identity: WifiIdentity, private val port: Int) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = identity.name
            serviceType = SERVICE_TYPE
            this.port = this@WifiAdvertiser.port
            setAttribute("id", WifiIdentity.toHex(identity.id))
            setAttribute("v", "1")
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Wi-Fi service registered as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Wi-Fi service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registration
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    fun stop() {
        val registration = listener ?: return
        listener = null
        runCatching { nsd.unregisterService(registration) }
    }

    companion object {
        const val SERVICE_TYPE = "_tabletdisplay._tcp"
        private const val TAG = "WifiAdvertiser"
    }
}
