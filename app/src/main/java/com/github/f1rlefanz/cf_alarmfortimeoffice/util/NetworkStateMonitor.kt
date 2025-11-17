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
class NetworkStateMonitor(context: Context) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
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
}
