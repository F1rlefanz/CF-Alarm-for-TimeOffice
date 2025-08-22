package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.network.HueBridgeHttpsClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.security.HueBridgeSecurityValidator
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Modern HTTPS-First Hue API Client (2025 Edition)
 * 
 * Implementiert bleeding-edge Standards:
 * - HTTPS-First mit intelligentem HTTP-Fallback
 * - RFC 1918 Private Network Validation  
 * - Protocol Capability Detection & Caching
 * - Security-by-Design für lokale IoT-Kommunikation
 * 
 * Migration Guide:
 * - Drop-in Replacement für HueApiClient
 * - Automatische Protocol Detection
 * - Erweiterte Security Validation
 * - Abwärtskompatibel zu Legacy Code
 */
class HueApiClientV2 {
    
    companion object {
        private const val DISCOVERY_URL = "https://discovery.meethue.com"
    }
    
    private val gson = Gson()
    private val httpsClient = HueBridgeHttpsClient()
    
    /**
     * Discover bridges using Philips N-UPnP service (HTTPS-only)
     */
    suspend fun discoverBridgesOnline(): List<BridgeDiscoveryResponse> = withContext(Dispatchers.IO) {
        try {
            Logger.d(LogTags.HUE_DISCOVERY, "🔍 MODERN: Attempting N-UPnP discovery via HTTPS")
            
            // N-UPnP Discovery über HTTPS (modern)
            val result = httpsClient.makeRequest("discovery.meethue.com", "/api/nupnp", "GET")
            
            if (result.isSuccess) {
                val responseBody = result.getOrThrow()
                val type = object : TypeToken<List<BridgeDiscoveryResponse>>() {}.type
                val bridges = gson.fromJson<List<BridgeDiscoveryResponse>>(responseBody, type)
                
                // Security Validation: Nur private IPs akzeptieren
                val validBridges = bridges.filter { bridge ->
                    val isValid = HueBridgeSecurityValidator.isPrivateNetworkAddress(bridge.internalipaddress)
                    if (!isValid) {
                        Logger.w(LogTags.HUE_SECURITY, "Filtering out non-private IP: ${bridge.internalipaddress}")
                    }
                    isValid
                }
                
                Logger.business(LogTags.HUE_DISCOVERY, "✅ N-UPnP discovery successful: ${validBridges.size}/${bridges.size} valid bridges")
                return@withContext validBridges
            } else {
                throw result.exceptionOrNull() ?: IOException("N-UPnP discovery failed")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "❌ N-UPnP discovery failed", e)
            throw e
        }
    }
    
    /**
     * Get bridge configuration with HTTPS-First approach
     */
    suspend fun getBridgeConfig(bridgeIp: String, username: String? = null): HueBridgeConfig = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            throw SecurityException("Bridge IP $bridgeIp is not in private network range")
        }
        
        val endpoint = if (username != null) {
            "/api/$username/config"
        } else {
            "/api/config"
        }
        
        Logger.d(LogTags.HUE_HTTPS, "Getting bridge config from $bridgeIp via HTTPS-first")
        
        val result = httpsClient.makeRequest(bridgeIp, endpoint, "GET")
        
        if (result.isSuccess) {
            val responseBody = result.getOrThrow()
            Logger.d(LogTags.HUE_HTTPS, "Bridge config retrieved successfully via HTTPS-first")
            return@withContext gson.fromJson(responseBody, HueBridgeConfig::class.java)
        } else {
            throw result.exceptionOrNull() ?: IOException("Failed to get bridge config")
        }
    }
    
    /**
     * Create user on bridge (requires link button press) - HTTPS-First
     */
    suspend fun createUser(bridgeIp: String, appName: String): String = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            throw SecurityException("Bridge IP $bridgeIp is not in private network range")
        }
        
        val requestBody = mapOf("devicetype" to appName)
        val json = gson.toJson(requestBody)
        
        Logger.i(LogTags.HUE_HTTPS, "Creating user on bridge $bridgeIp via HTTPS-first")
        
        val result = httpsClient.makeRequest(bridgeIp, "/api", "POST", json)
        
        if (result.isSuccess) {
            val responseBody = result.getOrThrow()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val responseList = gson.fromJson<List<Map<String, Any>>>(responseBody, type)
            
            responseList.firstOrNull()?.let { firstResponse ->
                when {
                    firstResponse.containsKey("success") -> {
                        val successMap = firstResponse["success"]
                        if (successMap is Map<*, *>) {
                            val username = successMap["username"] as? String
                            if (username != null) {
                                Logger.business(LogTags.HUE_HTTPS, "✅ User created successfully on bridge $bridgeIp")
                                return@withContext username
                            }
                        }
                        throw IOException("Username not found in response")
                    }
                    firstResponse.containsKey("error") -> {
                        val errorMap = firstResponse["error"]
                        if (errorMap is Map<*, *>) {
                            val errorType = errorMap["type"] as? Double
                            if (errorType == 101.0) {
                                throw IOException("Link button not pressed. Please press the link button on your Hue bridge and try again.")
                            } else {
                                throw IOException("Bridge error: ${errorMap["description"]}")
                            }
                        }
                        throw IOException("Invalid error response format")
                    }
                }
            }
        }
        
        throw result.exceptionOrNull() ?: IOException("Failed to create user")
    }
    
    /**
     * Get all lights from bridge - HTTPS-First
     */
    suspend fun getLights(bridgeIp: String, username: String): Map<String, HueLight> = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            throw SecurityException("Bridge IP $bridgeIp is not in private network range")
        }
        
        Logger.d(LogTags.HUE_HTTPS, "Getting lights from bridge $bridgeIp via HTTPS-first")
        
        val result = httpsClient.makeRequest(bridgeIp, "/api/$username/lights", "GET")
        
        if (result.isSuccess) {
            val responseBody = result.getOrThrow()
            Logger.d(LogTags.HUE_LIGHTS, "Lights API response received via HTTPS-first")
            
            return@withContext try {
                val type = object : TypeToken<Map<String, HueLight>>() {}.type
                gson.fromJson(responseBody, type) ?: emptyMap()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_LIGHTS, "Failed to parse lights response", e)
                emptyMap()
            }
        } else {
            throw result.exceptionOrNull() ?: IOException("Failed to get lights")
        }
    }
    
    /**
     * Get all groups from bridge - HTTPS-First
     */
    suspend fun getGroups(bridgeIp: String, username: String): Map<String, HueGroup> = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            throw SecurityException("Bridge IP $bridgeIp is not in private network range")
        }
        
        Logger.d(LogTags.HUE_HTTPS, "Getting groups from bridge $bridgeIp via HTTPS-first")
        
        val result = httpsClient.makeRequest(bridgeIp, "/api/$username/groups", "GET")
        
        if (result.isSuccess) {
            val responseBody = result.getOrThrow()
            Logger.d(LogTags.HUE_LIGHTS, "Groups API response received via HTTPS-first")
            
            return@withContext try {
                val type = object : TypeToken<Map<String, HueGroup>>() {}.type
                gson.fromJson(responseBody, type) ?: emptyMap()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_LIGHTS, "Failed to parse groups response", e)
                emptyMap()
            }
        } else {
            throw result.exceptionOrNull() ?: IOException("Failed to get groups")
        }
    }
    
    /**
     * Control a light - HTTPS-First
     */
    suspend fun controlLight(
        bridgeIp: String,
        username: String,
        lightId: String,
        update: LightStateUpdate
    ): Boolean = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            Logger.e(LogTags.HUE_SECURITY, "Rejecting light control for non-private IP: $bridgeIp")
            return@withContext false
        }
        
        try {
            val json = gson.toJson(update)
            Logger.d(LogTags.HUE_HTTPS, "Controlling light $lightId on bridge $bridgeIp via HTTPS-first")
            
            val result = httpsClient.makeRequest(bridgeIp, "/api/$username/lights/$lightId/state", "PUT", json)
            
            val success = result.isSuccess
            if (success) {
                Logger.d(LogTags.HUE_LIGHTS, "Light $lightId controlled successfully via HTTPS-first")
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to control light $lightId: ${result.exceptionOrNull()?.message}")
            }
            return@withContext success
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error controlling light $lightId", e)
            return@withContext false
        }
    }
    
    /**
     * Control a group - HTTPS-First
     */
    suspend fun controlGroup(
        bridgeIp: String,
        username: String,
        groupId: String,
        update: GroupUpdate
    ): Boolean = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            Logger.e(LogTags.HUE_SECURITY, "Rejecting group control for non-private IP: $bridgeIp")
            return@withContext false
        }
        
        try {
            val json = gson.toJson(update)
            Logger.d(LogTags.HUE_HTTPS, "Controlling group $groupId on bridge $bridgeIp via HTTPS-first")
            
            val result = httpsClient.makeRequest(bridgeIp, "/api/$username/groups/$groupId/action", "PUT", json)
            
            val success = result.isSuccess
            if (success) {
                Logger.d(LogTags.HUE_LIGHTS, "Group $groupId controlled successfully via HTTPS-first")
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to control group $groupId: ${result.exceptionOrNull()?.message}")
            }
            return@withContext success
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error controlling group $groupId", e)
            return@withContext false
        }
    }
    
    /**
     * Set light state using raw Map - HTTPS-First
     */
    suspend fun setLightState(
        bridgeIp: String,
        username: String,
        lightId: String,
        stateChange: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            Logger.e(LogTags.HUE_SECURITY, "Rejecting light state change for non-private IP: $bridgeIp")
            return@withContext false
        }
        
        try {
            val json = gson.toJson(stateChange)
            Logger.d(LogTags.HUE_HTTPS, "Setting light $lightId state on bridge $bridgeIp via HTTPS-first")
            
            val result = httpsClient.makeRequest(bridgeIp, "/api/$username/lights/$lightId/state", "PUT", json)
            
            val success = result.isSuccess
            Logger.d(LogTags.HUE_LIGHTS, "Light $lightId state update via HTTPS-first: $success")
            return@withContext success
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting light state for $lightId", e)
            return@withContext false
        }
    }
    
    /**
     * Set group action using raw Map - HTTPS-First
     */
    suspend fun setGroupAction(
        bridgeIp: String,
        username: String,
        groupId: String,
        actionChange: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            Logger.e(LogTags.HUE_SECURITY, "Rejecting group action for non-private IP: $bridgeIp")
            return@withContext false
        }
        
        try {
            val json = gson.toJson(actionChange)
            Logger.d(LogTags.HUE_HTTPS, "Setting group $groupId action on bridge $bridgeIp via HTTPS-first")
            
            val result = httpsClient.makeRequest(bridgeIp, "/api/$username/groups/$groupId/action", "PUT", json)
            
            val success = result.isSuccess
            Logger.d(LogTags.HUE_LIGHTS, "Group $groupId action update via HTTPS-first: $success")
            return@withContext success
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting group action for $groupId", e)
            return@withContext false
        }
    }
    
    /**
     * Test bridge connectivity and determine optimal protocol
     */
    suspend fun testBridgeConnectivity(bridgeIp: String): String? = withContext(Dispatchers.IO) {
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(bridgeIp)) {
            Logger.w(LogTags.HUE_SECURITY, "Bridge connectivity test rejected for non-private IP: $bridgeIp")
            return@withContext null
        }
        
        Logger.i(LogTags.HUE_PROTOCOL, "🔍 Testing bridge connectivity for $bridgeIp")
        
        val capability = httpsClient.testBridgeConnectivity(bridgeIp)
        
        return@withContext if (capability != null) {
            Logger.business(LogTags.HUE_PROTOCOL, "✅ Bridge $bridgeIp reachable via ${capability.preferredProtocol.uppercase()}")
            capability.preferredProtocol
        } else {
            Logger.w(LogTags.HUE_PROTOCOL, "❌ Bridge $bridgeIp unreachable via both HTTPS and HTTP")
            null
        }
    }
}
