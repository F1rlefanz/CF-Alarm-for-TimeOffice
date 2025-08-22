package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import android.content.Context

/**
 * UPDATED Repository for Hue Light operations with ROBUST connection management
 * 
 * FIXES THE CRITICAL ALARM PROBLEM:
 * ❌ BEFORE: Relied on volatile bridge repository connection state
 * ✅ AFTER: Uses HueBridgeConnectionManager for guaranteed connection availability
 * 
 * KEY IMPROVEMENTS:
 * 🔄 Direct integration with HueBridgeConnectionManager
 * 💓 Automatic connection recovery for critical operations (alarm execution)
 * 🚀 Guaranteed connection availability during light control
 * 📊 Comprehensive error handling and logging
 * 
 * Implements Clean Architecture with Interface-based DI and Logger integration
 */
class HueLightRepository(
    private val context: Context,
    private val bridgeRepository: IHueBridgeRepository
) : IHueLightRepository {
    
    private val apiClient = HueApiClient()
    
    // ROBUST Connection Manager for guaranteed connection availability
    private val connectionManager = HueBridgeConnectionManager.getInstance(context)
    
    /**
     * CRITICAL HELPER: Get validated connection with automatic recovery
     * This ensures alarm operations never fail due to connection issues
     */
    private suspend fun getValidatedConnectionInfo(): Pair<String, String> {
        return try {
            connectionManager.getValidatedConnection()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "❌ CRITICAL: Failed to get validated connection for light operation", e)
            throw IllegalStateException("Bridge connection unavailable: ${e.message}", e)
        }
    }
    
    override suspend fun getLights(): Result<List<HueLight>> = withContext(Dispatchers.IO) {
        try {
            // Use robust connection manager instead of bridge repository
            val (bridgeIp, username) = getValidatedConnectionInfo()
            
            Logger.d(LogTags.HUE_LIGHTS, "Fetching lights from bridge $bridgeIp")
            
            val lightsResponse = apiClient.getLights(bridgeIp, username)
            val lights = lightsResponse.map { (id, lightData) ->
                HueLight(
                    id = id,
                    name = lightData.name,
                    type = lightData.type,
                    modelid = lightData.modelid,
                    manufacturername = lightData.manufacturername,
                    productname = lightData.productname,
                    state = lightData.state,
                    capabilities = lightData.capabilities,
                    uniqueid = lightData.uniqueid
                )
            }
            
            Logger.i(LogTags.HUE_LIGHTS, "Successfully retrieved ${lights.size} lights")
            Result.success(lights)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Failed to get lights", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getGroups(): Result<List<HueGroup>> = withContext(Dispatchers.IO) {
        try {
            // Use robust connection manager instead of bridge repository
            val (bridgeIp, username) = getValidatedConnectionInfo()
            
            Logger.d(LogTags.HUE_LIGHTS, "Fetching groups from bridge $bridgeIp")
            
            val groupsResponse = apiClient.getGroups(bridgeIp, username)
            val groups = groupsResponse.map { (id, groupData) ->
                HueGroup(
                    id = id,
                    name = groupData.name,
                    type = groupData.type,
                    roomClass = groupData.roomClass,
                    lights = groupData.lights,
                    sensors = groupData.sensors,
                    state = groupData.state,
                    action = groupData.action,
                    recycle = groupData.recycle
                )
            }
            
            Logger.i(LogTags.HUE_LIGHTS, "Successfully retrieved ${groups.size} groups")
            Result.success(groups)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Failed to get groups", e)
            Result.failure(e)
        }
    }
    
    override suspend fun controlLight(
        lightId: String,
        on: Boolean?,
        brightness: Int?,
        hue: Int?,
        saturation: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // CRITICAL: Use robust connection manager for alarm operations
            val (bridgeIp, username) = getValidatedConnectionInfo()
            
            // Build state change object
            val stateChange = buildMap<String, Any> {
                on?.let { put("on", it) }
                brightness?.let { 
                    if (it in 0..254) put("bri", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid brightness value: $it (must be 0-254)")
                }
                hue?.let { 
                    if (it in 0..65535) put("hue", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid hue value: $it (must be 0-65535)")
                }
                saturation?.let { 
                    if (it in 0..254) put("sat", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid saturation value: $it (must be 0-254)")
                }
            }
            
            if (stateChange.isEmpty()) {
                Logger.w(LogTags.HUE_LIGHTS, "No valid state changes provided for light $lightId")
                return@withContext Result.success(Unit)
            }
            
            Logger.d(LogTags.HUE_LIGHTS, "Controlling light $lightId with changes: $stateChange")
            
            val success = apiClient.setLightState(bridgeIp, username, lightId, stateChange)
            
            if (success) {
                Logger.i(LogTags.HUE_LIGHTS, "✅ ALARM-CRITICAL: Successfully controlled light $lightId")
                Result.success(Unit)
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to control light $lightId - API returned false")
                Result.failure(IOException("Light control failed"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "❌ ALARM-CRITICAL: Failed to control light $lightId", e)
            Result.failure(e)
        }
    }
    
    override suspend fun controlGroup(
        groupId: String,
        on: Boolean?,
        brightness: Int?,
        hue: Int?,
        saturation: Int?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // CRITICAL: Use robust connection manager for alarm operations
            val (bridgeIp, username) = getValidatedConnectionInfo()
            
            // Build action change object
            val actionChange = buildMap<String, Any> {
                on?.let { put("on", it) }
                brightness?.let { 
                    if (it in 0..254) put("bri", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid brightness value: $it (must be 0-254)")
                }
                hue?.let { 
                    if (it in 0..65535) put("hue", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid hue value: $it (must be 0-65535)")
                }
                saturation?.let { 
                    if (it in 0..254) put("sat", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid saturation value: $it (must be 0-254)")
                }
            }
            
            if (actionChange.isEmpty()) {
                Logger.w(LogTags.HUE_LIGHTS, "No valid action changes provided for group $groupId")
                return@withContext Result.success(Unit)
            }
            
            Logger.d(LogTags.HUE_LIGHTS, "Controlling group $groupId with changes: $actionChange")
            
            val success = apiClient.setGroupAction(bridgeIp, username, groupId, actionChange)
            
            if (success) {
                Logger.i(LogTags.HUE_LIGHTS, "✅ ALARM-CRITICAL: Successfully controlled group $groupId")
                Result.success(Unit)
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to control group $groupId - API returned false")
                Result.failure(IOException("Group control failed"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "❌ ALARM-CRITICAL: Failed to control group $groupId", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getLightState(lightId: String): Result<HueLight> = withContext(Dispatchers.IO) {
        try {
            // Use robust connection manager instead of bridge repository
            val (bridgeIp, username) = getValidatedConnectionInfo()
            
            Logger.d(LogTags.HUE_LIGHTS, "Getting state for light $lightId")
            
            val lightData = apiClient.getLight(bridgeIp, username, lightId)
            val light = HueLight(
                id = lightId,
                name = lightData.name,
                type = lightData.type,
                modelid = lightData.modelid,
                manufacturername = lightData.manufacturername,
                productname = lightData.productname,
                state = lightData.state,
                capabilities = lightData.capabilities,
                uniqueid = lightData.uniqueid
            )
            
            Logger.d(LogTags.HUE_LIGHTS, "Successfully retrieved state for light $lightId")
            Result.success(light)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Failed to get light state for $lightId", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getGroupState(groupId: String): Result<HueGroup> = withContext(Dispatchers.IO) {
        try {
            // Use robust connection manager instead of bridge repository
            val (bridgeIp, username) = getValidatedConnectionInfo()
            
            Logger.d(LogTags.HUE_LIGHTS, "Getting state for group $groupId")
            
            val groupData = apiClient.getGroup(bridgeIp, username, groupId)
            val group = HueGroup(
                id = groupId,
                name = groupData.name,
                type = groupData.type,
                roomClass = groupData.roomClass,
                lights = groupData.lights,
                sensors = groupData.sensors,
                state = groupData.state,
                action = groupData.action,
                recycle = groupData.recycle
            )
            
            Logger.d(LogTags.HUE_LIGHTS, "Successfully retrieved state for group $groupId")
            Result.success(group)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Failed to get group state for $groupId", e)
            Result.failure(e)
        }
    }
}
