package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
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
     * @param colorTemperature White color temperature in mireds (153-500). Mutually exclusive
     *        with hue/saturation on the bridge — callers should send only one color mode.
     * @param transitionTime Transition duration in deciseconds (1/10 s, 0-65535). Drives the
     *        bridge's native fade — used e.g. for sunrise ramps.
     * @param alert Bridge alert effect: "none", "select" (one flash) or "lselect" (repeated
     *        flash for ~15s). Used e.g. to give visible proof-of-life feedback for a "Test" action.
     */
    suspend fun controlLight(
        lightId: String,
        on: Boolean? = null,
        brightness: Int? = null,
        hue: Int? = null,
        saturation: Int? = null,
        colorTemperature: Int? = null,
        transitionTime: Int? = null,
        alert: String? = null
    ): Result<Unit>

    /**
     * Control a group of lights
     * @param groupId ID of the group to control
     * @param on Turn lights on/off
     * @param brightness Brightness level (0-254)
     * @param hue Color hue (0-65535)
     * @param saturation Color saturation (0-254)
     * @param colorTemperature White color temperature in mireds (153-500). Mutually exclusive
     *        with hue/saturation on the bridge — callers should send only one color mode.
     * @param transitionTime Transition duration in deciseconds (1/10 s, 0-65535). Drives the
     *        bridge's native fade — used e.g. for sunrise ramps.
     * @param alert Bridge alert effect: "none", "select" (one flash) or "lselect" (repeated
     *        flash for ~15s). Used e.g. to give visible proof-of-life feedback for a "Test" action.
     */
    suspend fun controlGroup(
        groupId: String,
        on: Boolean? = null,
        brightness: Int? = null,
        hue: Int? = null,
        saturation: Int? = null,
        colorTemperature: Int? = null,
        transitionTime: Int? = null,
        alert: String? = null
    ): Result<Unit>
    
    /**
     * Get current state of a light
     */
    suspend fun getLightState(lightId: String): Result<HueLight>
    
    /**
     * Get current state of a group
     */
    suspend fun getGroupState(groupId: String): Result<HueGroup>

    /**
     * Legt einen Zeitplan auf der Bridge an, den die Bridge selbst ausführt.
     *
     * @param resourcePath Ziel-Resource OHNE Username, z.B. `/groups/2/action`. Den Username
     *        setzt die Implementierung davor — sie besitzt die Verbindungsdaten, der Aufrufer
     *        soll sie nicht kennen müssen.
     * @param localtime Zeitmuster der Bridge, z.B. `PT00:30:00` für "in 30 Minuten".
     * @param autodelete Zeitplan nach dem Auslösen selbst entfernen.
     * @return die von der Bridge vergebene Zeitplan-ID.
     */
    suspend fun createBridgeSchedule(
        name: String,
        description: String,
        resourcePath: String,
        method: String,
        body: Map<String, Any>,
        localtime: String,
        autodelete: Boolean = true
    ): Result<String>

    /** Alle Zeitpläne der Bridge, nach Zeitplan-ID. */
    suspend fun getBridgeSchedules(): Result<Map<String, BridgeSchedule>>

    /** Löscht einen Zeitplan auf der Bridge. */
    suspend fun deleteBridgeSchedule(scheduleId: String): Result<Unit>
}
