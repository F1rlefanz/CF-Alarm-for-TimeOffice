package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeTimer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Enhanced UseCase for Hue Light operations
 * 
 * Implements business logic layer with:
 * - Validation and batch operations
 * - Color conversion utilities integration
 * - Sunrise wake-up ramp and rule-preview auto-off (executeActionsWithAutoRevert)
 * - Advanced error handling and resilience
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2.1
 */
class HueLightUseCase @Inject constructor(
    private val lightRepository: IHueLightRepository
) : IHueLightUseCaseAdvanced {
    
    /**
     * Scope for the auto-revert timer in [executeActionsWithAutoRevert]. Deliberately separate
     * from any caller's (e.g. ViewModel) scope so the scheduled OFF still fires even if the
     * screen that triggered the preview is left before the timer elapses.
     */
    private val autoRevertScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val LIGHT_OPERATION_TIMEOUT_MS = 10000L
        private const val BATCH_OPERATION_TIMEOUT_MS = 30000L
        private const val MAX_BATCH_SIZE = 20

        /** Pause between the sunrise's initial state and the long fade, so the bridge applies them separately. */
        private const val SUNRISE_STEP_DELAY_MS = 250L
    }
    
    override suspend fun getAllLightTargets(): Result<LightTargets> {
        Logger.d(LogTags.HUE_USECASE, "Getting all light targets with business logic")
        
        return try {
            coroutineScope {
                // Execute both operations concurrently
                val lightsDeferred = async { lightRepository.getLights() }
                val groupsDeferred = async { lightRepository.getGroups() }
                
                val lightsResult = lightsDeferred.await()
                val groupsResult = groupsDeferred.await()
                
                // Handle partial failures gracefully
                val lights = if (lightsResult.isSuccess) {
                    lightsResult.getOrNull() ?: emptyList()
                } else {
                    Logger.w(LogTags.HUE_USECASE, "Failed to get lights", lightsResult.exceptionOrNull())
                    emptyList()
                }
                
                val groups = if (groupsResult.isSuccess) {
                    groupsResult.getOrNull() ?: emptyList()
                } else {
                    Logger.w(LogTags.HUE_USECASE, "Failed to get groups", groupsResult.exceptionOrNull())
                    emptyList()
                }
                
                // Return combined result
                val lightTargets = LightTargets(
                    lights = lights,
                    groups = groups
                )
                
                Logger.i(LogTags.HUE_USECASE, "Retrieved ${lights.size} lights and ${groups.size} groups")
                Result.success(lightTargets)
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to get light targets", e)
            Result.failure(Exception("Failed to retrieve lights and groups: ${e.message}", e))
        }
    }
    
    override suspend fun executeLightAction(action: LightAction): Result<LightActionResult> {
        Logger.d(LogTags.HUE_USECASE, "Executing light action for ${action.targetId}")
        
        return try {
            // Validate action parameters
            val validationResult = validateLightAction(action)
            if (validationResult.isFailure) {
                val error = validationResult.exceptionOrNull()?.message ?: "Invalid action"
                return Result.success(
                    LightActionResult(
                        success = false,
                        targetId = action.targetId,
                        error = error
                    )
                )
            }
            
            // Execute action with timeout
            val result = withTimeoutOrNull(LIGHT_OPERATION_TIMEOUT_MS) {
                if (action.isGroup) {
                    lightRepository.controlGroup(
                        groupId = action.targetId,
                        on = action.on,
                        brightness = action.brightness,
                        hue = action.hue,
                        saturation = action.saturation,
                        colorTemperature = action.colorTemperature,
                        transitionTime = action.transitionTime
                    )
                } else {
                    lightRepository.controlLight(
                        lightId = action.targetId,
                        on = action.on,
                        brightness = action.brightness,
                        hue = action.hue,
                        saturation = action.saturation,
                        colorTemperature = action.colorTemperature,
                        transitionTime = action.transitionTime
                    )
                }
            }
            
            if (result == null) {
                Logger.w(LogTags.HUE_USECASE, "Light action timed out for ${action.targetId}")
                return Result.success(
                    LightActionResult(
                        success = false,
                        targetId = action.targetId,
                        error = "Operation timed out"
                    )
                )
            }
            
            val actionResult = if (result.isSuccess) {
                Logger.i(LogTags.HUE_USECASE, "Light action successful for ${action.targetId}")
                LightActionResult(
                    success = true,
                    targetId = action.targetId
                )
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                Logger.w(LogTags.HUE_USECASE, "Light action failed for ${action.targetId}: $error")
                LightActionResult(
                    success = false,
                    targetId = action.targetId,
                    error = error
                )
            }
            
            Result.success(actionResult)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Light action execution failed", e)
            Result.success(
                LightActionResult(
                    success = false,
                    targetId = action.targetId,
                    error = "Execution failed: ${e.message}"
                )
            )
        }
    }
    
    override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> {
        Logger.i(LogTags.HUE_USECASE, "Executing batch light actions: ${actions.size} actions")
        
        return try {
            // Validate batch size
            if (actions.size > MAX_BATCH_SIZE) {
                Logger.w(LogTags.HUE_USECASE, "Batch size ${actions.size} exceeds maximum $MAX_BATCH_SIZE")
                return Result.failure(IllegalArgumentException("Batch size exceeds maximum of $MAX_BATCH_SIZE"))
            }
            
            if (actions.isEmpty()) {
                Logger.w(LogTags.HUE_USECASE, "Empty batch action list provided")
                return Result.success(
                    BatchActionResult(
                        totalActions = 0,
                        successfulActions = 0,
                        failedActions = emptyList(),
                        overallSuccess = true
                    )
                )
            }
            
            // Execute all actions concurrently with overall timeout
            val results = withTimeoutOrNull(BATCH_OPERATION_TIMEOUT_MS) {
                coroutineScope {
                    actions.map { action ->
                        async { executeLightAction(action) }
                    }.awaitAll()
                }
            }
            
            if (results == null) {
                Logger.w(LogTags.HUE_USECASE, "Batch operation timed out")
                return Result.failure(Exception("Batch operation timed out after ${BATCH_OPERATION_TIMEOUT_MS}ms"))
            }
            
            // Process results
            val actionResults = results.mapNotNull { it.getOrNull() }
            val successfulActions = actionResults.count { it.success }
            val failedActions = actionResults.filter { !it.success }
            val overallSuccess = failedActions.isEmpty()
            
            val batchResult = BatchActionResult(
                totalActions = actions.size,
                successfulActions = successfulActions,
                failedActions = failedActions,
                overallSuccess = overallSuccess
            )
            
            Logger.i(
                LogTags.HUE_USECASE, 
                "Batch operation completed: $successfulActions/${actions.size} successful"
            )
            
            Result.success(batchResult)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Batch operation failed", e)
            Result.failure(Exception("Batch operation failed: ${e.message}", e))
        }
    }

    override suspend fun scheduleBridgeAutoOff(
        targets: List<AutoOffTarget>,
        shiftName: String
    ): Result<Int> {
        if (targets.isEmpty()) {
            Logger.d(LogTags.HUE_BRIDGE, "🌉⏱️ Kein Auto-Aus konfiguriert, kein Bridge-Zeitplan noetig")
            return Result.success(0)
        }

        return try {
            clearOwnBridgeSchedules()

            // Ein Zeitplan traegt GENAU EIN Command, also einen Zielpfad. Mehrere Ziele heissen
            // deshalb mehrere Zeitpläne. Unkritisch: die Bridge fasst 100 (real gemessen: 98
            // frei), und autodelete raeumt jeden nach dem Ausloesen selbst wieder ab.
            var created = 0
            targets.forEach { target ->
                val result = lightRepository.createBridgeSchedule(
                    name = BridgeTimer.scheduleName(target.targetId, target.isGroup),
                    description = BridgeTimer.scheduleDescription(shiftName, target.delayMinutes),
                    resourcePath = BridgeTimer.resourcePath(target.targetId, target.isGroup),
                    method = "PUT",
                    body = mapOf("on" to false),
                    localtime = BridgeTimer.timerPattern(target.delayMinutes),
                    autodelete = true
                )
                if (result.isSuccess) created++
            }

            if (created < targets.size) {
                // WARN, nicht DEBUG: Seit dem Wegfall des WorkManager-Fallbacks ist dieser
                // Zeitplan der EINZIGE Weg, wie die Lampen wieder ausgehen. Ein stiller
                // Fehlschlag hier heisst: Licht bleibt an, und niemand erfaehrt warum.
                // Release-Logs enthalten nur WARN+.
                Logger.w(
                    LogTags.HUE_BRIDGE,
                    "🌉⚠️ Nur $created/${targets.size} Auto-Aus-Zeitplaene auf der Bridge angelegt - " +
                        "die uebrigen Lampen bleiben an"
                )
            } else {
                Logger.i(LogTags.HUE_BRIDGE, "🌉✅ $created Auto-Aus-Zeitplan(e) auf der Bridge angelegt ($shiftName)")
            }

            Result.success(created)

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to schedule bridge-side auto-off", e)
            Result.failure(e)
        }
    }

    /**
     * Entfernt Auto-Aus-Zeitpläne aus einem frueheren Lauf.
     *
     * Ohne das legt jeder Snooze einen weiteren Timer an - und der aelteste, laengst laufende
     * schaltet mitten im naechsten Weckvorgang aus. Angefasst wird ausschliesslich, was unser
     * Praefix traegt: auf der Bridge liegen auch Zeitpläne der Hue-App und der Dimmer-Schalter.
     *
     * Fehlschlaege sind hier bewusst nicht fatal - ein nicht abgeraeumter Alt-Timer ist
     * unschoen, aber kein Grund, den neuen gar nicht erst anzulegen.
     */
    private suspend fun clearOwnBridgeSchedules() {
        lightRepository.getBridgeSchedules().fold(
            onSuccess = { schedules ->
                schedules
                    .filter { (_, schedule) -> BridgeTimer.isOwnSchedule(schedule) }
                    .forEach { (id, schedule) ->
                        lightRepository.deleteBridgeSchedule(id)
                            .onSuccess { Logger.d(LogTags.HUE_BRIDGE, "🌉🧹 Alt-Zeitplan '${schedule.name}' (id=$id) entfernt") }
                            .onFailure { Logger.w(LogTags.HUE_BRIDGE, "🌉⚠️ Alt-Zeitplan id=$id nicht entfernt: ${it.message}") }
                    }
            },
            onFailure = {
                Logger.w(LogTags.HUE_BRIDGE, "🌉⚠️ Bestehende Bridge-Zeitpläne nicht lesbar, raeume nicht auf: ${it.message}")
            }
        )
    }

    override suspend fun executeActionsWithAutoRevert(
        actions: List<LightAction>,
        revertAfter: Duration
    ): Result<BatchActionResult> {
        Logger.i(
            LogTags.HUE_USECASE,
            "Executing ${actions.size} action(s) with auto-off after ${revertAfter.inWholeSeconds}s"
        )

        return try {
            val applyResult = executeBatchLightActions(actions)

            // Only targets that were actually switched ON have something to turn back off.
            val autoOffTargets = actions
                .filter { it.on == true && it.targetId.isNotBlank() }
                .map { it.targetId to it.isGroup }
                .distinct()

            if (autoOffTargets.isNotEmpty()) {
                scheduleAutoOff(autoOffTargets, revertAfter)
            } else {
                Logger.d(LogTags.HUE_USECASE, "No targets require auto-off (no action turned a light ON)")
            }

            applyResult
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to execute actions with auto-revert", e)
            Result.failure(e)
        }
    }

    /**
     * Fires an OFF action at every (targetId, isGroup) pair after [revertAfter], on a scope
     * independent from the caller so the caller returning early doesn't cancel it.
     */
    private fun scheduleAutoOff(targets: List<Pair<String, Boolean>>, revertAfter: Duration) {
        autoRevertScope.launch {
            try {
                delay(revertAfter.inWholeMilliseconds)

                val offActions = targets.map { (targetId, isGroup) ->
                    LightAction(targetId = targetId, isGroup = isGroup, on = false)
                }
                val result = executeBatchLightActions(offActions)

                if (result.isSuccess) {
                    val batch = result.getOrNull()
                    Logger.i(
                        LogTags.HUE_USECASE,
                        "⏰ Auto-off preview complete: ${batch?.successfulActions ?: 0}/${batch?.totalActions ?: 0} targets switched off"
                    )
                } else {
                    Logger.w(LogTags.HUE_USECASE, "Auto-off preview failed", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_USECASE, "Error during auto-off preview", e)
            }
        }
    }

    override suspend fun testLightConnection(targetId: String, isGroup: Boolean): Result<Boolean> {
        Logger.d(LogTags.HUE_USECASE, "Testing connection for ${if (isGroup) "group" else "light"} $targetId")
        
        return try {
            val result = withTimeoutOrNull(LIGHT_OPERATION_TIMEOUT_MS) {
                if (isGroup) {
                    lightRepository.getGroupState(targetId)
                } else {
                    lightRepository.getLightState(targetId)
                }
            }
            
            if (result == null) {
                Logger.w(LogTags.HUE_USECASE, "Connection test timed out for $targetId")
                Result.success(false)
            } else {
                val isConnected = result.isSuccess
                Logger.d(LogTags.HUE_USECASE, "Connection test result for $targetId: $isConnected")
                Result.success(isConnected)
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Connection test failed for $targetId", e)
            Result.success(false)
        }
    }

    override suspend fun flashLight(targetId: String, isGroup: Boolean): Result<Unit> {
        Logger.d(LogTags.HUE_USECASE, "Flashing ${if (isGroup) "group" else "light"} $targetId for test feedback")

        return try {
            val result = withTimeoutOrNull(LIGHT_OPERATION_TIMEOUT_MS) {
                if (isGroup) {
                    lightRepository.controlGroup(groupId = targetId, alert = HueConstants.Lights.ALERT_SELECT)
                } else {
                    lightRepository.controlLight(lightId = targetId, alert = HueConstants.Lights.ALERT_SELECT)
                }
            }

            when {
                result == null -> {
                    Logger.w(LogTags.HUE_USECASE, "Flash request timed out for $targetId")
                    Result.failure(Exception("Flash request timed out"))
                }
                result.isSuccess -> {
                    Logger.i(LogTags.HUE_USECASE, "Flash sent successfully to $targetId")
                    Result.success(Unit)
                }
                else -> {
                    Logger.w(LogTags.HUE_USECASE, "Flash failed for $targetId", result.exceptionOrNull())
                    Result.failure(result.exceptionOrNull() ?: Exception("Flash failed"))
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Flash failed for $targetId", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshLightStates(): Result<LightTargets> {
        Logger.d(LogTags.HUE_USECASE, "Refreshing all light states")
        
        // This is the same as getAllLightTargets, but semantically different
        // Could be extended to include caching logic in the future
        return getAllLightTargets()
    }
    
    // =============================================================================
    // ENHANCED FEATURES: COLOR CONVERSION & DURATION CONTROL
    // =============================================================================
    
    /**
     * Sets a light to a specific RGB color
     * 
     * @param lightId ID of the light
     * @param red Red component (0-255)
     * @param green Green component (0-255)
     * @param blue Blue component (0-255)
     * @param brightness Optional brightness override (1-254)
     * @return Result indicating success or failure
     */
    override suspend fun setLightRgbColor(
        lightId: String,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int?
    ): Result<Unit> {
        Logger.d(LogTags.HUE_USECASE, "Setting light $lightId to RGB($red, $green, $blue)")
        
        return try {
            // Convert RGB to Hue color space
            val hueColor = HueColorConverter.rgbToHueColor(red, green, blue)
            
            // Execute light control with converted values
            lightRepository.controlLight(
                lightId = lightId,
                on = true,
                brightness = brightness ?: HueConstants.Lights.DEFAULT_BRIGHTNESS,
                hue = hueColor.hue,
                saturation = hueColor.saturation
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to set RGB color for light $lightId", e)
            Result.failure(e)
        }
    }
    
    /**
     * Sets a group to a specific RGB color
     */
    override suspend fun setGroupRgbColor(
        groupId: String,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int?
    ): Result<Unit> {
        Logger.d(LogTags.HUE_USECASE, "Setting group $groupId to RGB($red, $green, $blue)")
        
        return try {
            val hueColor = HueColorConverter.rgbToHueColor(red, green, blue)
            
            lightRepository.controlGroup(
                groupId = groupId,
                on = true,
                brightness = brightness ?: HueConstants.Lights.DEFAULT_BRIGHTNESS,
                hue = hueColor.hue,
                saturation = hueColor.saturation
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to set RGB color for group $groupId", e)
            Result.failure(e)
        }
    }
    
    /**
     * Sets a light to a color preset
     */
    override suspend fun setLightColorPreset(
        lightId: String,
        colorPreset: HueColorConverter.ColorPreset,
        brightness: Int?
    ): Result<Unit> {
        Logger.d(LogTags.HUE_USECASE, "Setting light $lightId to color preset $colorPreset")
        
        return try {
            val hueColor = HueColorConverter.getPresetColor(colorPreset)
            
            lightRepository.controlLight(
                lightId = lightId,
                on = true,
                brightness = brightness ?: HueConstants.Lights.DEFAULT_BRIGHTNESS,
                hue = hueColor.hue,
                saturation = hueColor.saturation
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to set color preset for light $lightId", e)
            Result.failure(e)
        }
    }
    
    /**
     * Starts a sunrise ramp on a single light or group.
     *
     * Two PUTs drive the bridge's native fade:
     *  1. Immediately (transition 0): on + minimal brightness + warm color temperature.
     *  2. A long native transition to the target brightness + cooler color temperature.
     *
     * A short delay between the two lets the bridge register the initial state before the
     * fade begins (otherwise fast back-to-back PUTs can coalesce into one tick).
     */
    override suspend fun startSunrise(
        targetId: String,
        isGroup: Boolean,
        startKelvin: Int,
        endKelvin: Int,
        endBrightness: Int,
        durationMinutes: Int
    ): Result<Unit> {
        Logger.i(
            LogTags.HUE_USECASE,
            "🌅 Starting sunrise on ${if (isGroup) "group" else "light"} $targetId over ${durationMinutes}min"
        )

        return try {
            // Convert the user-facing Kelvin values to Hue mireds.
            val startCt = HueColorConverter.kelvinToHueMireds(startKelvin)
            val endCt = HueColorConverter.kelvinToHueMireds(endKelvin)
            val targetBri = HueConstants.Utils.clampBrightness(endBrightness)
            // Bridge transition time is in deciseconds (1/10 s); cap at the API maximum.
            val transitionDs = (durationMinutes.coerceAtLeast(0) * 600)
                .coerceAtMost(HueConstants.Lights.MAX_TRANSITION_TIME)

            // Step 1: jump to dim + warm immediately.
            val initial = if (isGroup) {
                lightRepository.controlGroup(
                    groupId = targetId,
                    on = true,
                    brightness = HueConstants.Lights.MIN_BRIGHTNESS,
                    colorTemperature = startCt,
                    transitionTime = 0
                )
            } else {
                lightRepository.controlLight(
                    lightId = targetId,
                    on = true,
                    brightness = HueConstants.Lights.MIN_BRIGHTNESS,
                    colorTemperature = startCt,
                    transitionTime = 0
                )
            }

            if (initial.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Sunrise initial state failed for $targetId", initial.exceptionOrNull())
                return initial
            }

            // Let the bridge apply the initial state before kicking off the long fade.
            delay(SUNRISE_STEP_DELAY_MS)

            // Step 2: long native transition to bright + cooler.
            if (isGroup) {
                lightRepository.controlGroup(
                    groupId = targetId,
                    brightness = targetBri,
                    colorTemperature = endCt,
                    transitionTime = transitionDs
                )
            } else {
                lightRepository.controlLight(
                    lightId = targetId,
                    brightness = targetBri,
                    colorTemperature = endCt,
                    transitionTime = transitionDs
                )
            }

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to start sunrise for $targetId", e)
            Result.failure(e)
        }
    }

    /**
     * Validates a light action for business logic compliance
     */
    private fun validateLightAction(action: LightAction): Result<Unit> {
        return try {
            // Validate target ID
            if (action.targetId.isBlank()) {
                return Result.failure(IllegalArgumentException("Target ID cannot be empty"))
            }
            
            // Validate brightness range
            action.brightness?.let { brightness ->
                if (!HueConstants.Validation.isValidBrightness(brightness)) {
                    return Result.failure(
                        IllegalArgumentException("Brightness must be between ${HueConstants.Lights.MIN_BRIGHTNESS} and ${HueConstants.Lights.MAX_BRIGHTNESS}")
                    )
                }
            }
            
            // Validate hue range
            action.hue?.let { hue ->
                if (!HueConstants.Validation.isValidHue(hue)) {
                    return Result.failure(
                        IllegalArgumentException("Hue must be between ${HueConstants.Lights.MIN_HUE} and ${HueConstants.Lights.MAX_HUE}")
                    )
                }
            }
            
            // Validate saturation range
            action.saturation?.let { saturation ->
                if (!HueConstants.Validation.isValidSaturation(saturation)) {
                    return Result.failure(
                        IllegalArgumentException("Saturation must be between ${HueConstants.Lights.MIN_SATURATION} and ${HueConstants.Lights.MAX_SATURATION}")
                    )
                }
            }

            // Validate color temperature range (mireds)
            action.colorTemperature?.let { ct ->
                if (!HueConstants.Validation.isValidColorTemperature(ct)) {
                    return Result.failure(
                        IllegalArgumentException("Color temperature must be between ${HueConstants.Lights.MIN_COLOR_TEMPERATURE} and ${HueConstants.Lights.MAX_COLOR_TEMPERATURE} mireds")
                    )
                }
            }

            // Validate transition time range (deciseconds)
            action.transitionTime?.let { tt ->
                if (!HueConstants.Validation.isValidTransitionTime(tt)) {
                    return Result.failure(
                        IllegalArgumentException("Transition time must be between ${HueConstants.Lights.MIN_TRANSITION_TIME} and ${HueConstants.Lights.MAX_TRANSITION_TIME} deciseconds")
                    )
                }
            }

            // Ensure at least one action is specified
            if (action.on == null && action.brightness == null && action.hue == null &&
                action.saturation == null && action.colorTemperature == null
            ) {
                return Result.failure(
                    IllegalArgumentException("At least one light property must be specified")
                )
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
