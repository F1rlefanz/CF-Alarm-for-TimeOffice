package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.DurationControlInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import kotlin.time.Duration

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
     * Triggers the bridge's native "select" alert (a single visible flash) on a light or group,
     * without changing its on/off state or other properties. Used to give the user visible
     * proof that the "Test" action actually reached the lights, instead of a silent API call.
     */
    suspend fun flashLight(targetId: String, isGroup: Boolean): Result<Unit>
    
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

    // =============================================================================
    // SUNRISE WAKE-UP LIGHT
    // =============================================================================

    /**
     * Starts a sunrise ramp on a single target: jumps to a dim, warm state immediately,
     * then performs a long native transition to a brighter, cooler state. The Hue bridge
     * interpolates brightness and color temperature itself — no app-side stepping.
     *
     * @param targetId Light or group ID
     * @param isGroup Whether [targetId] refers to a group
     * @param startKelvin Warm start temperature in Kelvin (e.g. 2000)
     * @param endKelvin Cooler end temperature in Kelvin (e.g. 4000)
     * @param endBrightness Target brightness at the end of the ramp (1-254)
     * @param durationMinutes Ramp duration in minutes (capped at the bridge's max transition time)
     */
    suspend fun startSunrise(
        targetId: String,
        isGroup: Boolean,
        startKelvin: Int,
        endKelvin: Int,
        endBrightness: Int,
        durationMinutes: Int
    ): Result<Unit>
    
    /**
     * Applies [actions] immediately, then switches every target OFF after [revertAfter] —
     * regardless of the state they were in before. This mirrors what [AutoOffWorker] does for
     * the real alarm (switch off, not "restore previous state"), so previewing a rule's
     * auto-off via "Regel testen" demonstrates the same end result on a shortened timer.
     *
     * Targets are deduplicated by (targetId, isGroup); only actions with `on == true` schedule
     * an auto-off (an action that only dims/recolors an already-on light has nothing to revert).
     *
     * @param actions The rule's light actions to apply as-is.
     * @param revertAfter Delay before the auto-off (OFF) is sent to each target.
     */
    suspend fun executeActionsWithAutoRevert(
        actions: List<LightAction>,
        revertAfter: Duration
    ): Result<BatchActionResult>

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
    val colorTemperature: Int? = null, // White color temperature in mireds (153-500)
    val transitionTime: Int? = null, // Transition duration in deciseconds (0-65535)
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
