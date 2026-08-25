package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api.HueApiClient
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeScheduleCommand
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeScheduleCreate
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.waehleNutzbareSzenen
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class HueLightRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : IHueLightRepository {
    
    // Context enables the bridge-ID pinning audit layer in HueTrustManager
    private val apiClient = HueApiClient(context)
    
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
    
    override suspend fun getScenes(): Result<List<HueScene>> = withContext(Dispatchers.IO) {
        try {
            val (bridgeIp, username) = getValidatedConnectionInfo()

            Logger.d(LogTags.HUE_LIGHTS, "Fetching scenes from bridge $bridgeIp")

            val roh = apiClient.getScenes(bridgeIp, username).values.toList()

            // Gefiltert wird in einer REINEN Funktion, damit kein Konsument die drei
            // Ausschlussgruende doppelt fuehrt - und damit sie pruefbar ist (waehleNutzbareSzenen).
            val auswahl = waehleNutzbareSzenen(roh)

            // Die Zahl der weggefilterten Eintraege MUSS ins Log: sonst ist "meine Szene fehlt
            // in der Liste" nicht diagnostizierbar - der Nutzer sieht nur eine kuerzere Liste.
            Logger.i(
                LogTags.HUE_LIGHTS,
                "Successfully retrieved ${auswahl.nutzbar.size} von ${auswahl.gesamt} Szenen " +
                    "(ausgefiltert: ${auswahl.ohneNamen} ohne Namen, " +
                    "${auswahl.automatischVerwaltet} automatisch verwaltet, " +
                    "${auswahl.ohneRaum} ohne Raum/Zone)"
            )
            Result.success(auswahl.nutzbar)

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Failed to get scenes", e)
            Result.failure(e)
        }
    }

    override suspend fun applyScene(groupId: String, sceneId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val (bridgeIp, username) = getValidatedConnectionInfo()

                val angenommen = apiClient.applyScene(bridgeIp, username, groupId, sceneId)
                if (angenommen) {
                    Logger.i(LogTags.HUE_LIGHTS, "Szene $sceneId in Gruppe $groupId angewendet")
                    Result.success(Unit)
                } else {
                    // Die Bridge antwortet auch bei Ablehnung mit HTTP 200 - `angenommen` ist
                    // bereits das Urteil aus dem BODY, nicht der Status.
                    Logger.w(LogTags.HUE_LIGHTS, "Bridge lehnte Szene $sceneId in Gruppe $groupId ab")
                    Result.failure(IOException("Bridge lehnte die Szene ab"))
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_LIGHTS, "Failed to apply scene $sceneId to group $groupId", e)
                Result.failure(e)
            }
        }

    override suspend fun controlLight(
        lightId: String,
        on: Boolean?,
        brightness: Int?,
        hue: Int?,
        saturation: Int?,
        colorTemperature: Int?,
        transitionTime: Int?,
        alert: String?
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
                colorTemperature?.let {
                    if (it in HueConstants.Lights.MIN_COLOR_TEMPERATURE..HueConstants.Lights.MAX_COLOR_TEMPERATURE) put("ct", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid color temperature: $it (must be ${HueConstants.Lights.MIN_COLOR_TEMPERATURE}-${HueConstants.Lights.MAX_COLOR_TEMPERATURE} mireds)")
                }
                transitionTime?.let {
                    if (it in 0..65535) put("transitiontime", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid transition time: $it (must be 0-65535 deciseconds)")
                }
                alert?.let { put("alert", it) }
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
        saturation: Int?,
        colorTemperature: Int?,
        transitionTime: Int?,
        alert: String?
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
                colorTemperature?.let {
                    if (it in HueConstants.Lights.MIN_COLOR_TEMPERATURE..HueConstants.Lights.MAX_COLOR_TEMPERATURE) put("ct", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid color temperature: $it (must be ${HueConstants.Lights.MIN_COLOR_TEMPERATURE}-${HueConstants.Lights.MAX_COLOR_TEMPERATURE} mireds)")
                }
                transitionTime?.let {
                    if (it in 0..65535) put("transitiontime", it)
                    else Logger.w(LogTags.HUE_LIGHTS, "Invalid transition time: $it (must be 0-65535 deciseconds)")
                }
                alert?.let { put("alert", it) }
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

    override suspend fun createBridgeSchedule(
        name: String,
        description: String,
        resourcePath: String,
        method: String,
        body: Map<String, Any>,
        localtime: String,
        autodelete: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (bridgeIp, username) = getValidatedConnectionInfo()

            // Die Bridge fuehrt das Command spaeter SELBST aus und braucht dafuer eine
            // vollstaendige, autorisierte Adresse - inklusive Username.
            val create = BridgeScheduleCreate(
                name = name,
                description = description,
                command = BridgeScheduleCommand(
                    address = "${HueConstants.Bridge.API_BASE_PATH}/$username$resourcePath",
                    method = method,
                    body = body
                ),
                localtime = localtime,
                autodelete = autodelete
            )

            apiClient.createSchedule(bridgeIp, username, create)
                .onSuccess { Logger.i(LogTags.HUE_BRIDGE, "🌉⏱️ Bridge-Zeitplan '$name' angelegt (id=$it, $localtime)") }
                .onFailure { Logger.w(LogTags.HUE_BRIDGE, "🌉⚠️ Bridge-Zeitplan '$name' abgelehnt: ${it.message}") }

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to create bridge schedule '$name'", e)
            Result.failure(e)
        }
    }

    override suspend fun getBridgeSchedules(): Result<Map<String, BridgeSchedule>> = withContext(Dispatchers.IO) {
        try {
            val (bridgeIp, username) = getValidatedConnectionInfo()
            apiClient.getSchedules(bridgeIp, username)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to load bridge schedules", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteBridgeSchedule(scheduleId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val (bridgeIp, username) = getValidatedConnectionInfo()
            apiClient.deleteSchedule(bridgeIp, username, scheduleId)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to delete bridge schedule $scheduleId", e)
            Result.failure(e)
        }
    }
}
