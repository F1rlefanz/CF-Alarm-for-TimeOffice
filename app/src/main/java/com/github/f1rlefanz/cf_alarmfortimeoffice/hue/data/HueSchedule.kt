package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Type alias for backward compatibility
 */
typealias HueSchedule = HueScheduleRule

/**
 * Hue Schedule Rule for shift-based automation
 * @Immutable annotation optimizes Compose performance
 */
@Immutable
@Serializable
data class HueScheduleRule(
    val id: String = generateId(),
    val name: String,
    val shiftPattern: String, // e.g., "Frühdienst", "Spätdienst", "Nachtdienst"
    val enabled: Boolean = true,
    val timeRanges: List<HueTimeRange>,
    val priority: Int = 0, // Higher priority rules override lower ones
    val sunrise: SunriseConfig? = null // Optional sunrise wake-up light; null = plain on/off rule
) {
    companion object {
        fun generateId(): String = "rule_${System.currentTimeMillis()}"
    }
    
    /**
     * Computed property for compatibility with HueRuleUseCase
     * Extracts all light actions from time ranges
     */
    val lightActions: List<HueLightAction>
        get() = timeRanges.flatMap { it.actions }
}

/**
 * Container for the light actions of a rule.
 *
 * Historisch modellierte diese Klasse ein Zeitfenster (Start/Ende/relativeTo/Offset/Wochentage),
 * doch die Ausfuehrung ([com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase])
 * hat davon NIE etwas aufgeloest — Hue-Regeln feuern ihre Actions schlicht zur Weckzeit. Die
 * ungenutzten Timing-Felder sind entfernt; geblieben ist die reine Actions-Huelle. Auto-Aus danach
 * kommt aus [HueLightAction.duration] + dem Bridge-Timer, nicht aus einem Fenster.
 */
@Immutable
@Serializable
data class HueTimeRange(
    val actions: List<HueLightAction>
)

/**
 * Sunrise wake-up light configuration.
 *
 * Drives a gradual brightness + color-temperature ramp (warm → cool) using the Hue
 * bridge's native transition time, so the bridge interpolates the fade itself — no
 * app-side stepping required.
 *
 * @param enabled Whether the sunrise ramp is active for the owning rule
 * @param durationMinutes How long the ramp takes (1-90; capped by the bridge's max transition time)
 * @param startKelvin Color temperature at the start of the ramp (warm, e.g. 2000K)
 * @param endKelvin Color temperature at the end of the ramp (cooler, e.g. 4000K)
 * @param endBrightness Target brightness at the end of the ramp (1-254)
 * @param startBeforeAlarm true = ramp finishes AT the alarm time (starts durationMinutes earlier);
 *        false = ramp STARTS at the alarm time
 */
@Immutable
@Serializable
data class SunriseConfig(
    val enabled: Boolean = false,
    val durationMinutes: Int = 15,
    val startKelvin: Int = 2000,
    val endKelvin: Int = 4000,
    val endBrightness: Int = 254,
    val startBeforeAlarm: Boolean = true
)

/**
 * Light action to perform
 */
@Immutable
@Serializable
data class HueLightAction(
    val targetType: TargetType,
    val targetId: String, // Light ID, Group ID, or Zone ID
    val targetName: String? = null, // For display purposes
    val actionType: ActionType,
    val on: Boolean? = null, // Turn on/off state
    val brightness: Int? = null, // 0-254
    val hue: Int? = null, // 0-65535
    val saturation: Int? = null, // 0-254
    val colorTemperature: Int? = null, // 153-500
    val color: HueColor? = null,
    val transitionTime: Int = 10, // in deciseconds (1/10 second)
    val duration: Int? = null, // Duration in minutes before reverting
    val isGroup: Boolean = false // For UseCase compatibility
) {
    // Computed property for targetId access
    val lightId: String get() = targetId
}

/**
 * Color representation
 */
@Immutable
@Serializable
data class HueColor(
    val hue: Int? = null, // 0-65535
    val saturation: Int? = null, // 0-254
    val xy: List<Float>? = null, // CIE color space
    val rgb: String? = null // For UI display #RRGGBB
)

/**
 * Target type for actions
 */
@Serializable
enum class TargetType {
    LIGHT,
    GROUP,
    ZONE,
    ROOM
}

/**
 * Action types
 */
@Serializable
enum class ActionType {
    TURN_ON,
    TURN_OFF,
    DIM,
    BRIGHTEN,
    SET_COLOR,
    SET_TEMPERATURE,
    PULSE,
    COLOR_LOOP
}
