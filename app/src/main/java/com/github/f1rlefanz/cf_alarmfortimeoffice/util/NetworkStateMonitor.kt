package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
// PHASE 2 CLEANUP: NetworkRequest import removed (unused)
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Network State Monitor für Offline-Support Optimierungen
 *
 * PERFORMANCE FEATURES:
 * ✅ Reactive Network State Monitoring mit Flow
 * ✅ Automatische Background-Sync bei Netzwerk-Wiederherstellung
 * ✅ Intelligente Offline-Erkennung
 * ✅ Battery-efficient monitoring
 *
 * Note: minSdk is 26, so all modern network APIs are available
 */
@Suppress("unused") // Used for future offline-support features
class NetworkStateMonitor(private val connectivityManager: ConnectivityManager) {

    constructor(context: Context) : this(
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    )
    
    /**
     * Flow that emits true when network is available, false when offline
     */
    val isNetworkAvailable: Flow<Boolean> = callbackFlow {
        
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.d(LogTags.NETWORK, "Network available: $network")
                trySend(true)
            }
            
            override fun onLost(network: Network) {
                Logger.d(LogTags.NETWORK, "Network lost: $network")
                trySend(false)
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Logger.d(LogTags.NETWORK, "Network capabilities changed - hasInternet: $hasInternet")
                trySend(hasInternet)
            }
        }
        
        // Register network callback - registerDefaultNetworkCallback is available since API 24, we have 26
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        
        // Send initial state
        trySend(isCurrentlyConnected())
        
        awaitClose {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }.distinctUntilChanged()
    
    /**
     * Get current network state synchronously
     * Since minSdk is 26, we can use the modern API directly
     */
    fun isCurrentlyConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    /**
     * Check if we're on a metered connection (mobile data)
     * Useful for deciding whether to perform background sync
     */
    fun isMeteredConnection(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return true
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return true
        
        return !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    
    /**
     * Check if we're connected to Wi-Fi (or Ethernet/Local Network)
     * Useful for smart home communication (like Philips Hue Bridge)
     */
    fun isWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Checks whether [targetIp] (a literal IPv4 address, e.g. a local Hue Bridge) is reachable
     * from the phone's CURRENT network - i.e. whether the active network's own IPv4 address
     * shares a subnet with [targetIp], per its advertised prefix length.
     *
     * Unlike [isWifiConnected], this is not fooled by "connected to *some* Wi-Fi" (public
     * Wi-Fi, mobile hotspot, guest network) - it only returns true if the phone's IP is
     * actually in the same subnet as the target, regardless of what private range the home
     * network uses.
     *
     * Returns false (never throws) when: no active network, no LinkProperties, no IPv4
     * LinkAddress on the active network (e.g. cellular, IPv6-only), or [targetIp] is not a
     * valid dotted-quad IPv4 literal.
     */
    fun isReachableSubnet(targetIp: String): Boolean {
        val targetAddress = parseIpv4Literal(targetIp) ?: run {
            Logger.w(LogTags.NETWORK, "⚠️ NETWORK: Cannot parse target IP '$targetIp' as IPv4 literal")
            return false
        }
        val activeNetwork = connectivityManager.activeNetwork ?: run {
            Logger.d(LogTags.NETWORK, "No active network - subnet check for $targetIp fails")
            return false
        }
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return false

        val matched = linkProperties.linkAddresses.any { linkAddress ->
            val deviceAddress = linkAddress.address
            deviceAddress is Inet4Address &&
                isSameSubnet(deviceAddress.address, targetAddress.address, linkAddress.prefixLength)
        }
        if (!matched) {
            Logger.d(LogTags.NETWORK, "🌐 NETWORK: $targetIp not reachable - no matching local IPv4 subnet")
        }
        return matched
    }

    /** Parses a literal IPv4 dotted-quad WITHOUT any DNS/network I/O (no getByName side effects). */
    private fun parseIpv4Literal(ip: String): InetAddress? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val octet = parts[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            bytes[i] = octet.toByte()
        }
        return try {
            InetAddress.getByAddress(bytes)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** CIDR containment: do [deviceBytes] and [targetBytes] share the same [prefixLength]-bit prefix? */
        internal fun isSameSubnet(deviceBytes: ByteArray, targetBytes: ByteArray, prefixLength: Int): Boolean {
            if (deviceBytes.size != targetBytes.size) return false // family mismatch (v4 vs v6)
            if (prefixLength <= 0) return true
            val fullBytes = prefixLength / 8
            val remainingBits = prefixLength % 8
            for (i in 0 until fullBytes.coerceAtMost(deviceBytes.size)) {
                if (deviceBytes[i] != targetBytes[i]) return false
            }
            if (remainingBits > 0 && fullBytes < deviceBytes.size) {
                val mask = (0xFF shl (8 - remainingBits)) and 0xFF
                if ((deviceBytes[fullBytes].toInt() and mask) != (targetBytes[fullBytes].toInt() and mask)) return false
            }
            return true
        }
    }
}
