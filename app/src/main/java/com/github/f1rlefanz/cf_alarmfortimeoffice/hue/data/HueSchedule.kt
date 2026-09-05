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
 * Die drei Betriebsarten einer Hue-Regel. Sie schliessen sich gegenseitig aus: eine Szene bringt
 * Helligkeit und Farbe selbst mit, eine Sonnenaufgangs-Rampe erzeugt sie ueber die Zeit, und
 * manuell stellt der Nutzer sie ein. Zwei davon gleichzeitig ergaeben zwei Wahrheiten fuer
 * denselben Lichtzustand.
 */
enum class HueRuleModus { MANUELL, SZENE, SONNENAUFGANG }

/**
 * Die EINE Herleitung des Modus - bewusst hier im Datenpaket und nicht in einer der drei
 * Oberflaechen (Regel-Editor, Regel-Liste, Hue-Tab). Drei eigene Herleitungen waeren drei
 * Wahrheiten, die auseinanderlaufen, sobald jemand eine davon anfasst.
 *
 * Die Reihenfolge ist tragend: Sonnenaufgang schlaegt Szene. Beides zusammen lehnt
 * `validateRule()` zwar ab, aber Bestandsdaten und ein kuenftiger Editor-Fehler duerfen hier
 * nicht in eine undefinierte Anzeige laufen.
 */
val HueScheduleRule.modus: HueRuleModus
    get() = when {
        sunrise?.enabled == true -> HueRuleModus.SONNENAUFGANG
        lightActions.any { it.isScene } -> HueRuleModus.SZENE
        else -> HueRuleModus.MANUELL
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
    val targetId: String, // Light ID or Group ID
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
    val isGroup: Boolean = false, // For UseCase compatibility

    // --- Szene ---------------------------------------------------------------------------
    // Additiv und nullbar, damit Bestands-JSON ohne Migration weiter dekodiert. Eine Szene ist
    // KEIN vierter Zieltyp, sondern ein Zusatz zu einem GRUPPEN-Ziel: der Aufruf geht an
    // `PUT /groups/<id>/action` mit `{"scene":"<id>"}` - genau den Pfad, den `isGroup = true`
    // ohnehin waehlt. Deshalb bleiben Ausfuehrung, `autoOffTargetsOf()` und `BridgeTimer`
    // unveraendert. [TargetType] wird bewusst NICHT um `SCENE` erweitert: `ignoreUnknownKeys`
    // deckt unbekannte SCHLUESSEL ab, nicht unbekannte ENUM-WERTE - ein APK-Downgrade wuerde
    // sonst zum harten Dekodierfehler, und `updateScheduleRules` faengt bewusst nicht ab.
    val sceneId: String? = null,   // BRIDGE-LOKAL - dieselbe Falle wie [targetId]
    val sceneName: String? = null  // Anker Teil 1; Teil 2 ist [targetName] (die Gruppe)
) {
    // Computed property for targetId access
    val lightId: String get() = targetId

    /**
     * Der EINE Diskriminator fuer ein Szenen-Ziel. Nicht [targetType] - der ist seit jeher
     * dekorativ (er wird gesetzt, aber nirgends gelesen; entschieden wird ueber [isGroup]).
     *
     * Fuer eine Szenen-Aktion gilt verbindlich: [targetType] = GROUP, [targetId]/[targetName] =
     * die Gruppe, [isGroup] = true, [on] = true und alle Helligkeits-/Farbfelder = null.
     * Das [on] = true ist eine GESPEICHERTE ZUSAGE, kein gesendeter Wert: `autoOffTargetsOf()`
     * filtert auf `on == true` - ohne das verloere jede Szenenregel ihr Auto-Aus. Gesendet wird
     * ausschliesslich `{"scene": ...}`.
     */
    val isScene: Boolean get() = !sceneId.isNullOrBlank()
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
    GROUP
    // ENTFERNT (nach v1.39.5): ZONE und ROOM hatten nie einen Erzeuger - `git log -S` findet
    // ueber die GESAMTE Historie null Commits, sie standen seit dem ersten Commit nur da. Damit
    // kann auch kein gespeichertes JSON sie enthalten, und das Entfernen ist in BEIDE
    // Dekodierrichtungen gefahrlos (die Warnung an [HueLightAction.sceneId] gilt dem ERWEITERN
    // um Werte, die eine aeltere App-Fassung nicht kennt - das ist der umgekehrte Fall).
}

/**
 * Action types
 */
@Serializable
enum class ActionType {
    TURN_ON,
    TURN_OFF
    // ENTFERNT (nach v1.39.5): DIM, BRIGHTEN, SET_COLOR, SET_TEMPERATURE, PULSE und COLOR_LOOP
    // hatten nie einen Erzeuger (`git log -S` = 0 Commits je Name), und [actionType] hat
    // ueberhaupt keinen Leser - ein `when` konnte also nicht brechen. Helligkeit und Farbe
    // stehen in eigenen Feldern, nicht in diesem Enum.
}
