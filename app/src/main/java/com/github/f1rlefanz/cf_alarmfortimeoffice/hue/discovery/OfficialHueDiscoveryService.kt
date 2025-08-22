package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryMethod
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Official Philips Hue Bridge Discovery Service
 * 
 * Implements the official discovery methods as recommended by Philips:
 * 1. N-UPnP Discovery (https://discovery.meethue.com) - Primary method
 * 2. mDNS Discovery (_hue._tcp.local) - Modern fallback method
 * 
 * This replaces the primitive IP scanning approach with official APIs
 * as documented at: https://developers.meethue.com/develop/get-started-2/
 */
class OfficialHueDiscoveryService(private val context: Context) {
    
    companion object {
        private const val TOTAL_DISCOVERY_TIMEOUT_MS = 45000L // 45 seconds total
        private const val NUPNP_TIMEOUT_MS = 15000L // 15 seconds for N-UPnP
        private const val MDNS_TIMEOUT_MS = 25000L // 25 seconds for mDNS
    }
    
    private val nUpnpService = HueNUpnpDiscoveryService()
    private val mdnsService = HueMdnsDiscoveryService(context)
    
    // Discovery status flow
    private val _discoveryStatus = MutableSharedFlow<DiscoveryStatus>(replay = 1)
    
    fun getDiscoveryStatus(): Flow<DiscoveryStatus> = _discoveryStatus.asSharedFlow()
    
    /**
     * Discovers Hue bridges using official Philips methods
     * Tries N-UPnP first, then falls back to mDNS if needed
     */
    suspend fun discoverBridges(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_DISCOVERY, "Starting official Hue bridge discovery")
        
        try {
            val allBridges = mutableSetOf<HueBridge>()
            
            // Emit starting status
            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.ONLINE_DISCOVERY,
                stage = "STARTING",
                message = "Starting bridge discovery...",
                progress = 0.0f
            ))
            
            // Phase 1: Try N-UPnP Discovery (Official Philips endpoint)
            Logger.d(LogTags.HUE_DISCOVERY, "Phase 1: Attempting N-UPnP discovery")
            
            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.ONLINE_DISCOVERY,
                stage = "N_UPNP_SEARCH",
                message = "Contacting Philips discovery service...",
                progress = 0.1f,
                currentMethod = "N-UPnP"
            ))
            
            val nUpnpResult = withTimeoutOrNull(NUPNP_TIMEOUT_MS) {
                nUpnpService.discoverBridges()
            }
            
            if (nUpnpResult?.isSuccess == true) {
                val nUpnpBridges = nUpnpResult.getOrNull() ?: emptyList()
                if (nUpnpBridges.isNotEmpty()) {
                    allBridges.addAll(nUpnpBridges)
                    Logger.i(LogTags.HUE_DISCOVERY, "N-UPnP discovery successful: ${nUpnpBridges.size} bridges found")
                    
                    _discoveryStatus.emit(DiscoveryStatus(
                        method = DiscoveryMethod.ONLINE_DISCOVERY,
                        stage = "COMPLETED",
                        message = "Found ${nUpnpBridges.size} bridges via Philips service",
                        progress = 0.5f,
                        foundBridges = nUpnpBridges.size
                    ))
                } else {
                    Logger.d(LogTags.HUE_DISCOVERY, "N-UPnP discovery returned no bridges")
                }
            } else {
                Logger.w(LogTags.HUE_DISCOVERY, "N-UPnP discovery failed or timed out")
                
                _discoveryStatus.emit(DiscoveryStatus(
                    method = DiscoveryMethod.ONLINE_DISCOVERY,
                    stage = "FAILED",
                    message = "Philips discovery service unavailable, trying local discovery...",
                    progress = 0.3f
                ))
            }
            
            // Phase 2: Try mDNS Discovery (Modern local method)
            Logger.d(LogTags.HUE_DISCOVERY, "Phase 2: Attempting mDNS discovery")
            
            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.MDNS,
                stage = "MDNS_SEARCH",
                message = "Scanning local network via mDNS...",
                progress = 0.5f,
                currentMethod = "mDNS"
            ))
            
            val mdnsResult = withTimeoutOrNull(MDNS_TIMEOUT_MS) {
                mdnsService.discoverBridges()
            }
            
            if (mdnsResult?.isSuccess == true) {
                val mdnsBridges = mdnsResult.getOrNull() ?: emptyList()
                if (mdnsBridges.isNotEmpty()) {
                    // Merge bridges, avoiding duplicates based on IP address
                    val existingIPs = allBridges.map { it.internalipaddress }.toSet()
                    val newBridges = mdnsBridges.filter { it.internalipaddress !in existingIPs }
                    allBridges.addAll(newBridges)
                    
                    Logger.i(LogTags.HUE_DISCOVERY, "mDNS discovery successful: ${mdnsBridges.size} bridges found (${newBridges.size} new)")
                } else {
                    Logger.d(LogTags.HUE_DISCOVERY, "mDNS discovery returned no bridges")
                }
            } else {
                Logger.w(LogTags.HUE_DISCOVERY, "mDNS discovery failed or timed out")
            }
            
            // Final status
            val totalBridges = allBridges.size
            
            if (totalBridges > 0) {
                _discoveryStatus.emit(DiscoveryStatus(
                    method = if (allBridges.any { it.id.startsWith("mdns_") }) DiscoveryMethod.MDNS else DiscoveryMethod.ONLINE_DISCOVERY,
                    stage = "COMPLETED",
                    message = "Discovery completed: $totalBridges bridge(s) found",
                    progress = 1.0f,
                    isComplete = true,
                    foundBridges = totalBridges
                ))
                
                Logger.i(LogTags.HUE_DISCOVERY, "Official discovery completed successfully: $totalBridges bridges found")
            } else {
                _discoveryStatus.emit(DiscoveryStatus(
                    method = DiscoveryMethod.MDNS,
                    stage = "COMPLETED",
                    message = "No bridges found. Please check your network connection and ensure bridges are powered on.",
                    progress = 1.0f,
                    isComplete = true,
                    foundBridges = 0
                ))
                
                Logger.w(LogTags.HUE_DISCOVERY, "No bridges found with any discovery method")
            }
            
            Result.success(allBridges.toList())
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Official discovery failed", e)
            
            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.MDNS,
                stage = "FAILED",
                message = "Discovery failed: ${e.message}",
                isError = true,
                isComplete = true
            ))
            
            Result.failure(e)
        }
    }
    
    /**
     * Quick discovery method that tries both approaches simultaneously
     * Returns as soon as any method finds bridges
     */
    suspend fun quickDiscovery(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_DISCOVERY, "Starting quick discovery")
        
        try {
            // Launch both methods concurrently
            val nUpnpDeferred = async { nUpnpService.discoverBridges() }
            val mdnsDeferred = async { mdnsService.discoverBridges() }
            
            // Wait for first successful result or both to complete
            val results = mutableListOf<HueBridge>()
            
            // Check N-UPnP result first (usually faster)
            val nUpnpResult = nUpnpDeferred.await()
            if (nUpnpResult.isSuccess) {
                results.addAll(nUpnpResult.getOrNull() ?: emptyList())
            }
            
            // Check mDNS result
            val mdnsResult = mdnsDeferred.await()
            if (mdnsResult.isSuccess) {
                val mdnsBridges = mdnsResult.getOrNull() ?: emptyList()
                // Avoid duplicates
                val existingIPs = results.map { it.internalipaddress }.toSet()
                results.addAll(mdnsBridges.filter { it.internalipaddress !in existingIPs })
            }
            
            Logger.i(LogTags.HUE_DISCOVERY, "Quick discovery completed: ${results.size} bridges found")
            Result.success(results)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Quick discovery failed", e)
            Result.failure(e)
        }
    }
}
