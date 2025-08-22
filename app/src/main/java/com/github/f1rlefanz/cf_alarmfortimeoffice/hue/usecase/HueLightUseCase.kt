package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueDurationController
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.DurationControlInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/**
 * Enhanced UseCase for Hue Light operations
 * 
 * Implements business logic layer with:
 * - Validation and batch operations
 * - Color conversion utilities integration
 * - Duration-based control with automatic revert
 * - Advanced error handling and resilience
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2.1
 */
class HueLightUseCase(
    private val lightRepository: IHueLightRepository
) : IHueLightUseCaseAdvanced {
    
    /**
     * Duration controller for automatic light revert functionality
     */
    private val durationController = HueDurationController(lightRepository)
    
    companion object {
        private const val LIGHT_OPERATION_TIMEOUT_MS = 10000L
        private const val BATCH_OPERATION_TIMEOUT_MS = 30000L
        private const val MAX_BATCH_SIZE = 20
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
                        saturation = action.saturation
                    )
                } else {
                    lightRepository.controlLight(
                        lightId = action.targetId,
                        on = action.on,
                        brightness = action.brightness,
                        hue = action.hue,
                        saturation = action.saturation
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
     * Sets a light with automatic revert after specified duration
     * 
     * @param lightId ID of the light
     * @param colorPreset Color to set temporarily
     * @param brightness Brightness level (1-254)
     * @param durationMinutes How long to maintain the state before reverting
     * @return Result indicating success or failure
     */
    override suspend fun setLightWithDuration(
        lightId: String,
        colorPreset: HueColorConverter.ColorPreset,
        brightness: Int,
        durationMinutes: Int
    ): Result<Unit> {
        Logger.i(LogTags.HUE_USECASE, "Setting light $lightId with ${durationMinutes}min duration")
        
        return try {
            // Create temporary light state
            val temporaryState = HueDurationController.createAlarmLightState(
                brightness = HueConstants.Utils.clampBrightness(brightness),
                colorPreset = colorPreset
            )
            
            // Apply with duration control
            durationController.setLightWithDuration(
                lightId = lightId,
                temporaryState = temporaryState,
                duration = durationMinutes.minutes
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to set light with duration", e)
            Result.failure(e)
        }
    }
    
    /**
     * Sets a group with automatic revert after specified duration
     */
    override suspend fun setGroupWithDuration(
        groupId: String,
        colorPreset: HueColorConverter.ColorPreset,
        brightness: Int,
        durationMinutes: Int
    ): Result<Unit> {
        Logger.i(LogTags.HUE_USECASE, "Setting group $groupId with ${durationMinutes}min duration")
        
        return try {
            val temporaryAction = HueDurationController.createAlarmGroupAction(
                brightness = HueConstants.Utils.clampBrightness(brightness),
                colorPreset = colorPreset
            )
            
            durationController.setGroupWithDuration(
                groupId = groupId,
                temporaryAction = temporaryAction,
                duration = durationMinutes.minutes
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to set group with duration", e)
            Result.failure(e)
        }
    }
    
    /**
     * Creates a pulsing notification light effect
     */
    override suspend fun createNotificationEffect(
        lightId: String,
        colorPreset: HueColorConverter.ColorPreset,
        durationMinutes: Int
    ): Result<Unit> {
        Logger.i(LogTags.HUE_USECASE, "Creating notification effect for light $lightId")
        
        return try {
            val pulsingState = HueDurationController.createPulsingLightState(colorPreset)
            
            durationController.setLightWithDuration(
                lightId = lightId,
                temporaryState = pulsingState,
                duration = durationMinutes.minutes
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to create notification effect", e)
            Result.failure(e)
        }
    }
    
    /**
     * Manually reverts all lights and groups to their original states
     */
    override suspend fun revertAllLights(): Result<Unit> {
        Logger.i(LogTags.HUE_USECASE, "Reverting all lights to original states")
        
        return try {
            val activeControls = durationController.getActiveControls()
            
            // Revert all active lights
            activeControls.activeLights.forEach { lightId ->
                val revertResult = durationController.revertLight(lightId)
                if (revertResult.isFailure) {
                    Logger.w(LogTags.HUE_USECASE, "Failed to revert light $lightId", revertResult.exceptionOrNull())
                }
            }
            
            // Revert all active groups
            activeControls.activeGroups.forEach { groupId ->
                val revertResult = durationController.revertGroup(groupId)
                if (revertResult.isFailure) {
                    Logger.w(LogTags.HUE_USECASE, "Failed to revert group $groupId", revertResult.exceptionOrNull())
                }
            }
            
            Logger.i(LogTags.HUE_USECASE, "Reverted ${activeControls.activeLights.size} lights and ${activeControls.activeGroups.size} groups")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to revert all lights", e)
            Result.failure(e)
        }
    }
    
    /**
     * Gets information about active duration controls
     */
    override fun getActiveDurationControls(): DurationControlInfo {
        return durationController.getActiveControls()
    }
    
    /**
     * Cancels all pending automatic reverts
     */
    override fun cancelAllDurationControls() {
        Logger.i(LogTags.HUE_USECASE, "Cancelling all duration controls")
        durationController.cancelAllReverts()
    }
    
    /**
     * Cleanup method to be called when UseCase is no longer needed
     */
    fun cleanup() {
        Logger.d(LogTags.HUE_USECASE, "Cleaning up HueLightUseCase")
        durationController.cleanup()
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
            
            // Ensure at least one action is specified
            if (action.on == null && action.brightness == null && action.hue == null && action.saturation == null) {
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
