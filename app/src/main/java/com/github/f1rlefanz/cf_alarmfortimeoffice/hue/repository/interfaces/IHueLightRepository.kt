package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight

/**
 * Interface for Hue Light repository operations
 * Follows Clean Architecture principles with testable abstractions
 */
interface IHueLightRepository {
    
    /**
     * Get all available lights from connected bridge
     */
    suspend fun getLights(): Result<List<HueLight>>
    
    /**
     * Get all available groups from connected bridge
     */
    suspend fun getGroups(): Result<List<HueGroup>>
    
    /**
     * Control a specific light
     * @param lightId ID of the light to control
     * @param on Turn light on/off
     * @param brightness Brightness level (0-254)
     * @param hue Color hue (0-65535)
     * @param saturation Color saturation (0-254)
     */
    suspend fun controlLight(
        lightId: String,
        on: Boolean? = null,
        brightness: Int? = null,
        hue: Int? = null,
        saturation: Int? = null
    ): Result<Unit>
    
    /**
     * Control a group of lights
     * @param groupId ID of the group to control
     * @param on Turn lights on/off
     * @param brightness Brightness level (0-254)
     * @param hue Color hue (0-65535)
     * @param saturation Color saturation (0-254)
     */
    suspend fun controlGroup(
        groupId: String,
        on: Boolean? = null,
        brightness: Int? = null,
        hue: Int? = null,
        saturation: Int? = null
    ): Result<Unit>
    
    /**
     * Get current state of a light
     */
    suspend fun getLightState(lightId: String): Result<HueLight>
    
    /**
     * Get current state of a group
     */
    suspend fun getGroupState(groupId: String): Result<HueGroup>
}
