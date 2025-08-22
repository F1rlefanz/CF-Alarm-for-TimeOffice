package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Official mDNS-based Hue Bridge Discovery Service
 * Uses Android's NsdManager API to discover Hue bridges via mDNS
 * 
 * This implements the official Philips Hue discovery method as documented at:
 * https://developers.meethue.com/develop/get-started-2/
 */
class HueMdnsDiscoveryService(private val context: Context) {
    
    companion object {
        private const val HUE_SERVICE_TYPE = "_hue._tcp"
        private const val DISCOVERY_TIMEOUT_MS = 10000L // 10 seconds
    }
    
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    
    /**
     * Discovers Hue bridges using mDNS (Multicast DNS)
     * This is the modern, recommended method by Philips
     */
    suspend fun discoverBridges(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_DISCOVERY, "Starting mDNS discovery for Hue bridges")
        
        try {
            val discoveredBridges = mutableListOf<HueBridge>()
            
            val discoveryResult = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    
                    val discoveryListener = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Logger.e(LogTags.HUE_DISCOVERY, "mDNS discovery start failed: $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(emptyList())
                            }
                        }
                        
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                            Logger.w(LogTags.HUE_DISCOVERY, "mDNS discovery stop failed: $errorCode")
                        }
                        
                        override fun onDiscoveryStarted(serviceType: String) {
                            Logger.d(LogTags.HUE_DISCOVERY, "mDNS discovery started for: $serviceType")
                        }
                        
                        override fun onDiscoveryStopped(serviceType: String) {
                            Logger.d(LogTags.HUE_DISCOVERY, "mDNS discovery stopped")
                            if (continuation.isActive) {
                                continuation.resume(discoveredBridges.toList())
                            }
                        }
                        
                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            Logger.d(LogTags.HUE_DISCOVERY, "mDNS service found: ${serviceInfo.serviceName}")
                            
                            // Resolve the service to get IP address
                            resolveService(serviceInfo) { resolvedService ->
                                resolvedService?.let { service ->
                                    val bridge = createHueBridgeFromService(service)
                                    bridge?.let {
                                        discoveredBridges.add(it)
                                        Logger.i(LogTags.HUE_DISCOVERY, "Hue bridge discovered: ${it.ipAddress}")
                                    }
                                }
                            }
                        }
                        
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                            Logger.d(LogTags.HUE_DISCOVERY, "mDNS service lost: ${serviceInfo.serviceName}")
                        }
                    }
                    
                    // Start discovery
                    try {
                        nsdManager.discoverServices(HUE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    } catch (e: Exception) {
                        Logger.e(LogTags.HUE_DISCOVERY, "Failed to start mDNS discovery", e)
                        if (continuation.isActive) {
                            continuation.resume(emptyList())
                        }
                        return@suspendCancellableCoroutine
                    }
                    
                    // Set up cancellation
                    continuation.invokeOnCancellation {
                        try {
                            nsdManager.stopServiceDiscovery(discoveryListener)
                        } catch (e: Exception) {
                            Logger.w(LogTags.HUE_DISCOVERY, "Failed to stop mDNS discovery", e)
                        }
                    }
                }
            }
            
            val bridges = discoveryResult ?: emptyList()
            Logger.i(LogTags.HUE_DISCOVERY, "mDNS discovery completed: ${bridges.size} bridges found")
            Result.success(bridges)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "mDNS discovery failed", e)
            Result.failure(e)
        }
    }
    
    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo, callback: (NsdServiceInfo?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Logger.w(LogTags.HUE_DISCOVERY, "Failed to resolve service: ${serviceInfo.serviceName}, error: $errorCode")
                callback(null)
            }
            
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Extract hostname/host for logging (backward compatible)
                val hostInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceInfo.hostAddresses?.firstOrNull()?.hostName ?: "unknown"
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host?.hostName ?: "unknown"
                }
                Logger.d(LogTags.HUE_DISCOVERY, "Service resolved: ${serviceInfo.serviceName} -> $hostInfo")
                callback(serviceInfo)
            }
        }
        
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Failed to resolve service", e)
            callback(null)
        }
    }
    
    /**
     * Creates HueBridge from resolved NsdServiceInfo
     * MODERNIZED: Uses hostname for API 34+ while maintaining backward compatibility
     */
    private fun createHueBridgeFromService(serviceInfo: NsdServiceInfo): HueBridge? {
        return try {
            // Modern approach: Use hostAddresses for API 34+, fallback to host for older versions
            val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: Use hostAddresses array (first address)
                serviceInfo.hostAddresses?.firstOrNull()?.hostAddress
            } else {
                // Legacy API: Use deprecated host property for backward compatibility
                @Suppress("DEPRECATION")
                serviceInfo.host?.hostAddress
            }
            
            if (hostAddress != null) {
                // Extract bridge ID from service name if possible
                // Hue service names typically contain the last 6 digits of bridge ID
                val bridgeId = extractBridgeIdFromServiceName(serviceInfo.serviceName) 
                    ?: "mdns_${hostAddress.replace(".", "_")}"
                
                HueBridge(
                    id = bridgeId,
                    ipAddress = hostAddress,
                    name = serviceInfo.serviceName,
                    discoveryMethod = com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryMethod.MDNS
                )
            } else {
                Logger.w(LogTags.HUE_DISCOVERY, "Service has no IP address: ${serviceInfo.serviceName}")
                null
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Failed to create bridge from service", e)
            null
        }
    }
    
    private fun extractBridgeIdFromServiceName(serviceName: String): String? {
        return try {
            // Hue bridges typically advertise as "Philips Hue - XXXXXX" where XXXXXX is last 6 digits of bridge ID
            val regex = Regex(".*-\\s*(\\w{6}).*")
            val match = regex.find(serviceName)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Logger.w(LogTags.HUE_DISCOVERY, "Could not extract bridge ID from service name: $serviceName", e)
            null
        }
    }
}
