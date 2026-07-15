package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
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
     * Legt das Auto-Aus als Zeitplan AUF DER BRIDGE ab: pro Ziel ein Timer
     * ("in +N Minuten ausschalten"), den die Bridge selbst ausführt.
     *
     * WARUM NICHT DAS HANDY: Ein Auto-Aus per WorkManager erreicht die Bridge nur aus dem
     * Heim-WLAN. Wer nach dem Wecken aus dem Haus geht, nimmt das Handy mit — und die Lampen
     * blieben an. Die Bridge steht dagegen immer im richtigen Netz.
     *
     * WARUM DAS SICHER IST: Der Aufruf gehört an die Stelle, an der die Regeln die Lampen gerade
     * eingeschaltet haben. Ging das Licht an, war die Bridge erreichbar — dann klappt auch der
     * Zeitplan. War sie nicht erreichbar, ging kein Licht an, und es gibt nichts auszuschalten.
     *
     * Räumt eigene Timer aus einem früheren Lauf vorher ab (Snooze, erneut gefeuerter Alarm),
     * damit nicht ein alter Timer zu früh auslöst.
     *
     * @return Anzahl der tatsächlich angelegten Timer.
     */
    suspend fun scheduleBridgeAutoOff(
        targets: List<AutoOffTarget>,
        shiftName: String
    ): Result<Int>

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
}

/**
 * Enhanced interface for advanced Hue Light operations
 * Extends core functionality with the sunrise ramp and the rule-preview auto-revert.
 */
interface IHueLightUseCaseAdvanced : IHueLightUseCase {

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
     * regardless of the state they were in before. This mirrors what [scheduleBridgeAutoOff]
     * does for the real alarm (switch off, not "restore previous state"), so previewing a rule's
     * auto-off via "Regel testen" demonstrates the same end result on a shortened timer.
     *
     * Bewusst app-seitig und NICHT über einen Bridge-Timer: die Vorschau dauert Sekunden, der
     * Nutzer sieht dabei zu, und ein Bridge-Zeitplan wäre hier nur Ballast auf fremdem Gerät.
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
}

/**
 * A light/group that should be switched off [delayMinutes] after a rule turned it on.
 *
 * Die Verzoegerung rechnet HueRuleUseCase aus (Regel-Dauer plus ggf. Sonnenaufgangs-Versatz);
 * hier steht nur noch das Ergebnis.
 */
data class AutoOffTarget(
    val targetId: String,
    val isGroup: Boolean,
    val delayMinutes: Int
)

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
