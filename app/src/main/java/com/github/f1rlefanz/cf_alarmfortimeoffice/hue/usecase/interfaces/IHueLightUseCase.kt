package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.DurationControlInfo

/**
 * Interface for Hue Light UseCase operations
 * Business logic layer following Clean Architecture
 */
interface IHueLightUseCase {
    
    // =============================================================================
    // CORE LIGHT OPERATIONS
    // =============================================================================
    
    /**
     * Get all available lights and groups
     * Combines lights and groups for UI display
     */
    suspend fun getAllLightTargets(): Result<LightTargets>
    
    /**
     * Execute light action with business logic validation
     */
    suspend fun executeLightAction(action: LightAction): Result<LightActionResult>
    
    /**
     * Execute multiple light actions as batch
     * Used for rule execution and alarm triggers
     */
    suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult>
    
    /**
     * Test light/group connectivity
     */
    suspend fun testLightConnection(targetId: String, isGroup: Boolean): Result<Boolean>
    
    /**
     * Get current state of all lights for UI updates
     */
    suspend fun refreshLightStates(): Result<LightTargets>
}

/**
 * Enhanced interface for advanced Hue Light operations
 * Extends core functionality with color conversion and duration control
 */
interface IHueLightUseCaseAdvanced : IHueLightUseCase {
    
    // =============================================================================
    // COLOR CONVERSION OPERATIONS
    // =============================================================================
    
    /**
     * Set light to specific RGB color
     */
    suspend fun setLightRgbColor(
        lightId: String,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int? = null
    ): Result<Unit>
    
    /**
     * Set group to specific RGB color
     */
    suspend fun setGroupRgbColor(
        groupId: String,
        red: Int,
        green: Int,
        blue: Int,
        brightness: Int? = null
    ): Result<Unit>
    
    /**
     * Set light to color preset
     */
    suspend fun setLightColorPreset(
        lightId: String,
        colorPreset: HueColorConverter.ColorPreset,
        brightness: Int? = null
    ): Result<Unit>
    
    // =============================================================================
    // DURATION-BASED CONTROL
    // =============================================================================
    
    /**
     * Set light with automatic revert after duration
     */
    suspend fun setLightWithDuration(
        lightId: String,
        colorPreset: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.WARM_WHITE,
        brightness: Int = 150, // HueConstants.Defaults.ALARM_BRIGHTNESS
        durationMinutes: Int = 15 // HueConstants.Defaults.ALARM_DURATION
    ): Result<Unit>
    
    /**
     * Set group with automatic revert after duration
     */
    suspend fun setGroupWithDuration(
        groupId: String,
        colorPreset: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.WARM_WHITE,
        brightness: Int = 150, // HueConstants.Defaults.ALARM_BRIGHTNESS
        durationMinutes: Int = 15 // HueConstants.Defaults.ALARM_DURATION
    ): Result<Unit>
    
    /**
     * Create notification effect with pulsing
     */
    suspend fun createNotificationEffect(
        lightId: String,
        colorPreset: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.BLUE,
        durationMinutes: Int = 5 // HueConstants.Defaults.NOTIFICATION_DURATION
    ): Result<Unit>
    
    /**
     * Manually revert all lights to original states
     */
    suspend fun revertAllLights(): Result<Unit>
    
    /**
     * Get information about active duration controls
     */
    fun getActiveDurationControls(): DurationControlInfo
    
    /**
     * Cancel all pending automatic reverts
     */
    fun cancelAllDurationControls()
}

/**
 * Combined light targets for UI
 */
data class LightTargets(
    val lights: List<HueLight> = emptyList(),
    val groups: List<HueGroup> = emptyList()
)

/**
 * Light action definition
 */
data class LightAction(
    val targetId: String,
    val isGroup: Boolean,
    val on: Boolean? = null,
    val brightness: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val actionDescription: String? = null
)

/**
 * Result of light action execution
 */
data class LightActionResult(
    val success: Boolean,
    val targetId: String,
    val error: String? = null
)

/**
 * Result of batch action execution
 */
data class BatchActionResult(
    val totalActions: Int,
    val successfulActions: Int,
    val failedActions: List<LightActionResult>,
    val overallSuccess: Boolean
)
