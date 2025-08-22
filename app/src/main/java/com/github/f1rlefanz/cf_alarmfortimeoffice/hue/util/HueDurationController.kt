package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.LightState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Duration-based Light Control System
 * 
 * Manages temporary light state changes with automatic revert functionality.
 * This system allows setting lights to specific states for a defined duration,
 * then automatically reverts them to their previous state.
 * 
 * Features:
 * - Automatic state capture before changes
 * - Timer-based revert functionality
 * - Support for individual lights and groups
 * - Cancellation of pending revert operations
 * - Thread-safe operation with coroutines
 * 
 * Use Cases:
 * - Alarm lighting that automatically returns to normal after X minutes
 * - Notification lighting that fades back to previous state
 * - Temporary scene activation during specific events
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2.1
 */
class HueDurationController(
    private val lightRepository: IHueLightRepository
) {
    
    /**
     * Storage for original states before temporary changes
     */
    private val originalLightStates = ConcurrentHashMap<String, LightState>()
    private val originalGroupStates = ConcurrentHashMap<String, GroupAction>()
    
    /**
     * Active timers for automatic revert
     */
    private val activeTimers = ConcurrentHashMap<String, Job>()
    
    /**
     * Coroutine scope for timer management
     */
    private val timerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Sets a light to a specific state for a limited duration
     * 
     * @param lightId ID of the light to control
     * @param temporaryState The state to set temporarily
     * @param duration How long to maintain the temporary state
     * @param revertOnFailure Whether to revert if setting the state fails
     * @return Result indicating success or failure
     */
    suspend fun setLightWithDuration(
        lightId: String,
        temporaryState: LightState,
        duration: Duration,
        revertOnFailure: Boolean = true
    ): Result<Unit> {
        Logger.i(LogTags.HUE_LIGHTS, "Setting light $lightId with duration ${duration.inWholeMinutes}min")
        
        return try {
            // Cancel any existing timer for this light
            cancelRevert(lightId)
            
            // Capture current state if not already stored
            if (!originalLightStates.containsKey(lightId)) {
                val currentStateResult = lightRepository.getLightState(lightId)
                
                if (currentStateResult.isSuccess) {
                    val currentLight = currentStateResult.getOrThrow()
                    originalLightStates[lightId] = currentLight.state
                    Logger.d(LogTags.HUE_LIGHTS, "Captured original state for light $lightId")
                } else {
                    Logger.w(LogTags.HUE_LIGHTS, "Failed to capture original state for light $lightId")
                    if (revertOnFailure) {
                        return Result.failure(Exception("Cannot capture original state for light $lightId"))
                    }
                }
            }
            
            // Apply temporary state
            val controlResult = lightRepository.controlLight(
                lightId = lightId,
                on = temporaryState.on,
                brightness = temporaryState.bri,
                hue = temporaryState.hue,
                saturation = temporaryState.sat
            )
            
            if (controlResult.isSuccess) {
                Logger.i(LogTags.HUE_LIGHTS, "Applied temporary state to light $lightId")
                
                // Schedule automatic revert
                scheduleRevert(lightId, duration, isGroup = false)
                
                Result.success(Unit)
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to apply temporary state to light $lightId")
                
                if (revertOnFailure) {
                    // Remove stored state since we couldn't apply changes
                    originalLightStates.remove(lightId)
                }
                
                controlResult
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting light with duration", e)
            Result.failure(e)
        }
    }
    
    /**
     * Sets a group to a specific state for a limited duration
     * 
     * @param groupId ID of the group to control
     * @param temporaryAction The action to apply temporarily
     * @param duration How long to maintain the temporary state
     * @param revertOnFailure Whether to revert if setting the state fails
     * @return Result indicating success or failure
     */
    suspend fun setGroupWithDuration(
        groupId: String,
        temporaryAction: GroupAction,
        duration: Duration,
        revertOnFailure: Boolean = true
    ): Result<Unit> {
        Logger.i(LogTags.HUE_LIGHTS, "Setting group $groupId with duration ${duration.inWholeMinutes}min")
        
        return try {
            // Cancel any existing timer for this group
            val groupKey = "group_$groupId"
            cancelRevert(groupKey)
            
            // Capture current state if not already stored
            if (!originalGroupStates.containsKey(groupId)) {
                val currentStateResult = lightRepository.getGroupState(groupId)
                
                if (currentStateResult.isSuccess) {
                    val currentGroup = currentStateResult.getOrThrow()
                    originalGroupStates[groupId] = currentGroup.action
                    Logger.d(LogTags.HUE_LIGHTS, "Captured original state for group $groupId")
                } else {
                    Logger.w(LogTags.HUE_LIGHTS, "Failed to capture original state for group $groupId")
                    if (revertOnFailure) {
                        return Result.failure(Exception("Cannot capture original state for group $groupId"))
                    }
                }
            }
            
            // Apply temporary action
            val controlResult = lightRepository.controlGroup(
                groupId = groupId,
                on = temporaryAction.on,
                brightness = temporaryAction.bri,
                hue = temporaryAction.hue,
                saturation = temporaryAction.sat
            )
            
            if (controlResult.isSuccess) {
                Logger.i(LogTags.HUE_LIGHTS, "Applied temporary action to group $groupId")
                
                // Schedule automatic revert
                scheduleRevert(groupKey, duration, isGroup = true)
                
                Result.success(Unit)
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to apply temporary action to group $groupId")
                
                if (revertOnFailure) {
                    // Remove stored state since we couldn't apply changes
                    originalGroupStates.remove(groupId)
                }
                
                controlResult
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting group with duration", e)
            Result.failure(e)
        }
    }
    
    /**
     * Manually reverts a light to its original state
     * 
     * @param lightId ID of the light to revert
     * @return Result indicating success or failure
     */
    suspend fun revertLight(lightId: String): Result<Unit> {
        Logger.i(LogTags.HUE_LIGHTS, "Manually reverting light $lightId")
        
        return try {
            // Cancel timer if active
            cancelRevert(lightId)
            
            // Get original state
            val originalState = originalLightStates.remove(lightId)
            
            if (originalState == null) {
                Logger.w(LogTags.HUE_LIGHTS, "No original state stored for light $lightId")
                return Result.failure(Exception("No original state for light $lightId"))
            }
            
            // Restore original state
            val revertResult = lightRepository.controlLight(
                lightId = lightId,
                on = originalState.on,
                brightness = originalState.bri,
                hue = originalState.hue,
                saturation = originalState.sat
            )
            
            if (revertResult.isSuccess) {
                Logger.i(LogTags.HUE_LIGHTS, "Successfully reverted light $lightId to original state")
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to revert light $lightId", revertResult.exceptionOrNull())
            }
            
            revertResult
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error reverting light", e)
            Result.failure(e)
        }
    }
    
    /**
     * Manually reverts a group to its original state
     * 
     * @param groupId ID of the group to revert
     * @return Result indicating success or failure
     */
    suspend fun revertGroup(groupId: String): Result<Unit> {
        Logger.i(LogTags.HUE_LIGHTS, "Manually reverting group $groupId")
        
        return try {
            val groupKey = "group_$groupId"
            
            // Cancel timer if active
            cancelRevert(groupKey)
            
            // Get original state
            val originalAction = originalGroupStates.remove(groupId)
            
            if (originalAction == null) {
                Logger.w(LogTags.HUE_LIGHTS, "No original state stored for group $groupId")
                return Result.failure(Exception("No original state for group $groupId"))
            }
            
            // Restore original state
            val revertResult = lightRepository.controlGroup(
                groupId = groupId,
                on = originalAction.on,
                brightness = originalAction.bri,
                hue = originalAction.hue,
                saturation = originalAction.sat
            )
            
            if (revertResult.isSuccess) {
                Logger.i(LogTags.HUE_LIGHTS, "Successfully reverted group $groupId to original state")
            } else {
                Logger.w(LogTags.HUE_LIGHTS, "Failed to revert group $groupId", revertResult.exceptionOrNull())
            }
            
            revertResult
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error reverting group", e)
            Result.failure(e)
        }
    }
    
    /**
     * Cancels any pending revert operation for a light or group
     * 
     * @param targetId ID of the light or group (use "group_" prefix for groups)
     */
    fun cancelRevert(targetId: String) {
        activeTimers[targetId]?.let { timer ->
            timer.cancel()
            activeTimers.remove(targetId)
            Logger.d(LogTags.HUE_LIGHTS, "Cancelled revert timer for $targetId")
        }
    }
    
    /**
     * Cancels all pending revert operations
     */
    fun cancelAllReverts() {
        Logger.i(LogTags.HUE_LIGHTS, "Cancelling all ${activeTimers.size} revert timers")
        
        activeTimers.values.forEach { timer ->
            timer.cancel()
        }
        activeTimers.clear()
    }
    
    /**
     * Gets information about active duration controls
     * 
     * @return DurationControlInfo with current state
     */
    fun getActiveControls(): DurationControlInfo {
        return DurationControlInfo(
            activeLights = originalLightStates.keys.toList(),
            activeGroups = originalGroupStates.keys.toList(),
            pendingReverts = activeTimers.keys.toList(),
            totalActiveTimers = activeTimers.size
        )
    }
    
    /**
     * Schedules automatic revert after specified duration
     */
    private fun scheduleRevert(targetId: String, duration: Duration, isGroup: Boolean) {
        Logger.d(LogTags.HUE_LIGHTS, "Scheduling revert for $targetId in ${duration.inWholeMinutes}min")
        
        val timer = timerScope.launch {
            try {
                delay(duration.inWholeMilliseconds)
                
                Logger.i(LogTags.HUE_LIGHTS, "⏰ Timer expired - reverting $targetId")
                
                if (isGroup) {
                    val groupId = targetId.removePrefix("group_")
                    val revertResult = revertGroup(groupId)
                    
                    if (revertResult.isSuccess) {
                        Logger.i(LogTags.HUE_LIGHTS, "✅ Auto-revert successful for group $groupId")
                    } else {
                        Logger.w(LogTags.HUE_LIGHTS, "❌ Auto-revert failed for group $groupId")
                    }
                } else {
                    val revertResult = revertLight(targetId)
                    
                    if (revertResult.isSuccess) {
                        Logger.i(LogTags.HUE_LIGHTS, "✅ Auto-revert successful for light $targetId")
                    } else {
                        Logger.w(LogTags.HUE_LIGHTS, "❌ Auto-revert failed for light $targetId")
                    }
                }
                
            } catch (e: CancellationException) {
                Logger.d(LogTags.HUE_LIGHTS, "Revert timer cancelled for $targetId")
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_LIGHTS, "Error during auto-revert for $targetId", e)
            } finally {
                activeTimers.remove(targetId)
            }
        }
        
        activeTimers[targetId] = timer
    }
    
    /**
     * Cleans up resources and cancels all timers
     */
    fun cleanup() {
        Logger.i(LogTags.HUE_LIGHTS, "Cleaning up HueDurationController")
        
        cancelAllReverts()
        originalLightStates.clear()
        originalGroupStates.clear()
        timerScope.cancel()
    }
    
    /**
     * Extension function for easy duration creation
     */
    companion object {
        /**
         * Common duration presets
         */
        val DURATION_1_MINUTE = 1.minutes
        val DURATION_5_MINUTES = 5.minutes
        val DURATION_10_MINUTES = 10.minutes
        val DURATION_15_MINUTES = 15.minutes
        val DURATION_30_MINUTES = 30.minutes
        val DURATION_1_HOUR = 60.minutes
        
        /**
         * Creates a temporary light state for common scenarios
         */
        fun createAlarmLightState(
            brightness: Int = 254,
            colorPreset: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.WARM_WHITE
        ): LightState {
            val color = HueColorConverter.getPresetColor(colorPreset)
            
            return LightState(
                on = true,
                bri = brightness.coerceIn(1, 254),
                hue = color.hue,
                sat = color.saturation,
                xy = color.xy,
                alert = "none",
                effect = "none",
                transitiontime = 10, // 1 second transition
                reachable = true
            )
        }
        
        /**
         * Creates a temporary group action for common scenarios
         */
        fun createAlarmGroupAction(
            brightness: Int = 254,
            colorPreset: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.WARM_WHITE
        ): GroupAction {
            val color = HueColorConverter.getPresetColor(colorPreset)
            
            return GroupAction(
                on = true,
                bri = brightness.coerceIn(1, 254),
                hue = color.hue,
                sat = color.saturation,
                xy = color.xy,
                alert = "none",
                effect = "none",
                transitiontime = 10 // 1 second transition
            )
        }
        
        /**
         * Creates a pulsing light state for notifications
         */
        fun createPulsingLightState(
            baseColor: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.BLUE
        ): LightState {
            val color = HueColorConverter.getPresetColor(baseColor)
            
            return LightState(
                on = true,
                bri = 254,
                hue = color.hue,
                sat = color.saturation,
                xy = color.xy,
                alert = "lselect", // Long pulse
                effect = "none",
                transitiontime = 5, // Fast transition
                reachable = true
            )
        }
    }
}

/**
 * Information about currently active duration controls
 */
data class DurationControlInfo(
    val activeLights: List<String>,
    val activeGroups: List<String>, 
    val pendingReverts: List<String>,
    val totalActiveTimers: Int
)
