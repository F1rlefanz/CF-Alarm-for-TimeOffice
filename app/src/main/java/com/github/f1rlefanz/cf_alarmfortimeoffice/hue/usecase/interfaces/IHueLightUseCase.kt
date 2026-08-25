package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
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
     * Laesst EINE Lampe ein paar Sekunden blinken, ohne ihren An/Aus-Zustand oder sonst etwas
     * dauerhaft zu veraendern. Sichtbarer Beweis, dass der "Test"-Knopf die Bridge wirklich
     * erreicht - statt eines stillen API-Aufrufs.
     *
     * NIMMT BEWUSST NUR EINE LAMPE, KEIN GRUPPEN-FLAG: Gruppen ueberschneiden sich beliebig
     * (eine Lampe liegt real in drei Gruppen gleichzeitig), ueber Gruppen zu blinken heisst
     * also mehrere Alerts auf derselben Lampe. Die Lampen-Ebene ist die einzige, auf der "jede
     * Lampe genau einmal" strukturell gilt. Siehe HueViewModel.runLightTest.
     *
     * Bewusst "lselect" und nicht "select": Ein einzelner Blitz ist als Beweis zu leise - er
     * geht im Zweifel unter, und dann wirkt der Test-Knopf tot. Das anhaltende Blinken ist im
     * Hue-Umfeld ausserdem das gelernte "diese Lampe meine ich". Die Implementierung bricht
     * es nach ein paar Sekunden aktiv ab, statt die vollen 15s von lselect stehenzulassen.
     */
    suspend fun flashLight(lightId: String): Result<Unit>
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
 *
 * [lightsFailed]/[groupsFailed] halten fest, dass eine der beiden Abfragen NICHT durchkam,
 * die andere aber schon (der Teilerfolg-Zweig in [IHueLightUseCase.getAllLightTargets]). Ohne
 * diese Unterscheidung ist "keine Gruppen" von "Gruppen nicht abrufbar" nicht zu trennen - eine
 * Bridge ganz ohne Gruppen ist normal, eine abgelehnte Abfrage ist ein Fehler. Der
 * Ziel-Abgleich ([com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueTargetReconciler])
 * haengt daran: er darf Ziele einer Art, deren Abfrage gescheitert ist, weder umschreiben noch
 * als "auf dieser Bridge unbekannt" melden.
 *
 * [scenes]/[scenesFailed] folgen derselben Regel. Ein Szenen-Ausfall ist dabei ausdruecklich
 * ein TEILausfall: er wertet [IHueLightUseCase.getAllLightTargets] NICHT zum Fehlschlag auf,
 * weil eine Bridge ohne nutzbare Szenen voellig normal ist und die Lampenauswahl davon
 * unberuehrt funktioniert.
 */
data class LightTargets(
    val lights: List<HueLight> = emptyList(),
    val groups: List<HueGroup> = emptyList(),
    val scenes: List<HueScene> = emptyList(),
    val lightsFailed: Boolean = false,
    val groupsFailed: Boolean = false,
    val scenesFailed: Boolean = false
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
    val actionDescription: String? = null,

    /**
     * Gesetzt = diese Aktion wendet eine SZENE an; [targetId] ist dann die Gruppen-Id und
     * [isGroup] ist true. Alles Uebrige (on/brightness/hue/saturation/colorTemperature) bleibt
     * null - die Szene bestimmt das selbst, und zwei Wahrheiten im selben PUT gaebe es sonst.
     */
    val sceneId: String? = null
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
