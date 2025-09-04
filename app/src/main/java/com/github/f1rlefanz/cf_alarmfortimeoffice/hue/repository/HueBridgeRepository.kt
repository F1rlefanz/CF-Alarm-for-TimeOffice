@file:OptIn(DelicateCoroutinesApi::class) // Opt-in für GlobalScope in legacy compatibility methods

package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.OfficialHueDiscoveryService
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UPDATED Repository for Hue Bridge operations with ROBUST connection management
 * 
 * FIXES THE CRITICAL ALARM PROBLEM:
 * ❌ BEFORE: In-memory connection state lost during alarm execution
 * ✅ AFTER: Persistent connection with automatic recovery
 * 
 * KEY IMPROVEMENTS:
 * 🔄 Uses HueBridgeConnectionManager for persistent storage
 * 💓 Automatic connection recovery for critical operations
 * 🚀 Guaranteed connection availability during alarm execution
 * 📊 Connection state monitoring and health checks
 * 
 * Implements Clean Architecture with Interface-based DI and Logger integration
 */
class HueBridgeRepository(private val context: Context) : IHueBridgeRepository {
    
    companion object {
        private const val APP_NAME = "CFAlarmForTimeOffice"
        private const val CONNECTION_TIMEOUT_MS = 10000L
    }
    
    // API Client for Hue communication
    private val apiClient = HueApiClient()
    
    // Official Discovery Service (replaces primitive IP scanning)
    private val officialDiscoveryService = OfficialHueDiscoveryService(context)
    
    // ROBUST Connection Manager (replaces in-memory variables)
    private val connectionManager = HueBridgeConnectionManager.getInstance(context)
    
    init {
        // Initialize connection manager on repository creation
        connectionManager.initialize()
        Logger.i(LogTags.HUE_BRIDGE, "🔗 BRIDGE-REPOSITORY: Initialized with robust connection management")
    }
    
    override fun getDiscoveryStatus(): Flow<DiscoveryStatus> = 
        officialDiscoveryService.getDiscoveryStatus()
    
    /**
     * IMPROVED: Uses official Philips discovery methods instead of IP scanning
     */
    override suspend fun discoverBridges(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_DISCOVERY, "Starting official Hue bridge discovery")
        
        try {
            // Use official discovery service (N-UPnP + mDNS)
            val discoveryResult = officialDiscoveryService.discoverBridges()
            
            if (discoveryResult.isSuccess) {
                val bridges = discoveryResult.getOrNull() ?: emptyList()
                Logger.i(LogTags.HUE_DISCOVERY, "Official discovery completed: ${bridges.size} bridges found")
                
                // Test each discovered bridge for actual connectivity
                val validBridges = bridges.filter { bridge ->
                    val testResult = testBridgeConnection(bridge)
                    testResult.isSuccess && testResult.getOrDefault(false)
                }
                
                Logger.i(LogTags.HUE_DISCOVERY, "Bridge connectivity test: ${validBridges.size}/${bridges.size} bridges reachable")
                Result.success(validBridges)
            } else {
                Logger.w(LogTags.HUE_DISCOVERY, "Official discovery failed", discoveryResult.exceptionOrNull())
                discoveryResult
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Bridge discovery failed with exception", e)
            Result.failure(e)
        }
    }
    
    override suspend fun testBridgeConnection(bridge: HueBridge): Result<Boolean> {
        return try {
            withContext(Dispatchers.IO) {
                apiClient.getBridgeConfig(bridge.internalipaddress)
                Logger.d(LogTags.HUE_BRIDGE, "Bridge ${bridge.internalipaddress} test successful")
                Result.success(true)
            }
        } catch (e: Exception) {
            Logger.d(LogTags.HUE_BRIDGE, "Bridge ${bridge.internalipaddress} test failed: ${e.message}")
            Result.success(false)
        }
    }
    
    override suspend fun connectToBridge(bridge: HueBridge): Result<String> = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_BRIDGE, "Attempting to connect to bridge ${bridge.internalipaddress}")
        
        try {
            val username = apiClient.createUser(bridge.internalipaddress, APP_NAME)
            
            // Store connection using robust connection manager
            val connectionResult = connectionManager.setConnection(bridge.internalipaddress, username)
            
            if (connectionResult.isSuccess) {
                Logger.i(LogTags.HUE_BRIDGE, "Successfully connected to bridge, username created and stored")
                Result.success(username)
            } else {
                Logger.e(LogTags.HUE_BRIDGE, "Failed to store bridge connection", connectionResult.exceptionOrNull())
                Result.failure(connectionResult.exceptionOrNull() ?: IllegalStateException("Connection storage failed"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to connect to bridge", e)
            Result.failure(e)
        }
    }
    
    /**
     * DEPRECATED: Legacy methods for backward compatibility
     * These methods now delegate to the robust connection manager
     */
    override fun setUsername(username: String) {
        Logger.w(LogTags.HUE_BRIDGE, "⚠️ DEPRECATED: setUsername() called - use connectToBridge() instead")
        // For backward compatibility, try to update if bridge IP is available
        val (bridgeIp, _) = connectionManager.getCurrentConnectionInfo()
        if (bridgeIp != null) {
            // Note: This is fire-and-forget for backward compatibility
            GlobalScope.launch {
                connectionManager.setConnection(bridgeIp, username)
            }
        }
    }
    
    override fun setBridgeIp(bridgeIp: String) {
        Logger.w(LogTags.HUE_BRIDGE, "⚠️ DEPRECATED: setBridgeIp() called - use connectToBridge() instead")
        // For backward compatibility, try to update if username is available
        val (_, username) = connectionManager.getCurrentConnectionInfo()
        if (username != null) {
            // Note: This is fire-and-forget for backward compatibility
            GlobalScope.launch {
                connectionManager.setConnection(bridgeIp, username)
            }
        }
    }
    
    override fun getCurrentBridgeIp(): String? = connectionManager.getCurrentConnectionInfo().first
    
    override fun getCurrentUsername(): String? = connectionManager.getCurrentConnectionInfo().second
    
    override suspend fun validateConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Use the robust connection manager for validation
            val (bridgeIp, username) = connectionManager.getValidatedConnection()
            Logger.d(LogTags.HUE_BRIDGE, "Connection validation successful via ConnectionManager")
            Result.success(true)
            
        } catch (e: Exception) {
            Logger.w(LogTags.HUE_BRIDGE, "Connection validation failed", e)
            Result.success(false)
        }
    }
}
