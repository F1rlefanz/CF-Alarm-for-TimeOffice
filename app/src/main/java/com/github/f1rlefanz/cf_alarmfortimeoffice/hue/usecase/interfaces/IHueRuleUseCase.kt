package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import java.time.LocalTime

/**
 * Interface for Hue Rule UseCase operations
 * Business logic layer following Clean Architecture
 */
interface IHueRuleUseCase {
    
    /**
     * Get all schedule rules
     */
    suspend fun getAllRules(): Result<List<HueSchedule>>
    
    /**
     * Create new schedule rule with validation
     */
    suspend fun createRule(rule: HueSchedule): Result<HueSchedule>
    
    /**
     * Update existing rule with validation
     */
    suspend fun updateRule(rule: HueSchedule): Result<HueSchedule>
    
    /**
     * Delete rule by ID
     */
    suspend fun deleteRule(ruleId: String): Result<Unit>
    
    /**
     * Get rule by ID
     */
    suspend fun getRule(ruleId: String): Result<HueSchedule>
    
    /**
     * Find applicable rules for current shift and time
     * Core business logic for alarm integration
     *
     * @param weckzeit Epoch-ms der Weckzeit (#140). Gesetzt, entfallen Regeln, deren Tageslicht-
     *   Schwelle am Wecktag vor oder auf der Weckzeit liegt (`laeuftBeiTageslicht`). `null` = kein
     *   Tageslicht-Filter (im Zweifel Licht).
     */
    suspend fun findApplicableRules(
        shift: ShiftMatch, 
        currentTime: LocalTime,
        weckzeit: Long? = null
    ): Result<List<HueSchedule>>
    
    /**
     * Execute rules for alarm trigger
     * Converts rules to light actions and executes them
     */
    suspend fun executeRulesForAlarm(
        shift: ShiftMatch,
        alarmTime: LocalTime,
        /** Wie bei [findApplicableRules]; beim Schlummern die ERSTE Weckzeit (Snooze-Merker). */
        weckzeit: Long? = null
    ): Result<RuleExecutionResult>

    /**
     * Execute ONLY the pre-alarm sunrise ramps for a shift (rules whose sunrise is enabled
     * and configured to start before the alarm). Called by the pre-alarm worker ahead of
     * the actual alarm; the at-alarm path then skips these rules.
     *
     * Matched by shift name (the value the alarm carries) so the worker doesn't need a
     * full ShiftMatch.
     *
     * @param minutenBisWecker Minuten von jetzt bis zur Weckzeit. Gesetzt, legt der Lauf nach der
     *   Rampe schon das Auto-Aus auf der Bridge an (`HueRuleUseCase.vorabAutoAusZiele`) - sonst
     *   bliebe das Licht an, wenn der Wecker danach geloescht wird und nie feuert. `null` (Auftrag
     *   ohne Weckzeit aus einer aelteren Version): kein Vorab-Auto-Aus.
     * @param weckzeit Epoch-ms der Weckzeit, fuer die Tageslicht-Bedingung beim FEUERN (der Ort kann
     *   sich seit der Planung geaendert haben). `null` = kein Tageslicht-Filter.
     */
    suspend fun executeSunrisePreAlarm(
        shiftName: String,
        minutenBisWecker: Int?,
        weckzeit: Long? = null
    ): Result<RuleExecutionResult>

    /**
     * Lead time in minutes for pre-alarm sunrise: the longest duration among enabled
     * "start before alarm" sunrise rules matching [shiftName], or null if none apply.
     * Used by the scheduler to decide how early to start the ramp.
     *
     * Nimmt die Regeln als Parameter und liest NICHT selbst: Der Aufrufer steht in einer
     * Schleife ueber die Alarme, ein Read pro Alarm waere ein vollstaendiger DataStore-Read
     * je Durchgang (real: 8 × "Retrieved 0 schedule rules" bei 4 Alarmen). Einmal laden,
     * durchreichen — siehe [getAllRules]. Aus demselben Grund kommt der [stand] vom Aufrufer
     * ([tageslichtStand], einmal je Durchgang).
     *
     * Mit [weckzeit] UND [stand] zaehlen nur Regeln, die bei dieser Weckzeit laufen (#140) - eine
     * Regel, die zur Weckzeit entfiele, bekommt auch keinen Vorlauf. Fehlt eines davon: kein Filter.
     */
    fun getPreAlarmSunriseLeadMinutes(
        rules: List<HueSchedule>,
        shiftName: String,
        weckzeit: Long? = null,
        stand: TageslichtStand? = null
    ): Int?

    /**
     * Validate rule configuration
     * Business logic validation for rule creation/update
     */
    suspend fun validateRule(rule: HueSchedule): Result<RuleValidationResult>

    /**
     * Execute a rule immediately for previewing ("Regel testen"): applies the configured
     * lights/color, or — for a sunrise rule — runs a shortened, observable warm→cool ramp.
     */
    suspend fun executeRuleNow(rule: HueSchedule): Result<RuleExecutionResult>

    /**
     * Gleicht die Ziele aller gespeicherten Regeln gegen die Lampen/Gruppen von [targets] ab:
     * schreibt bridge-lokale IDs, die auf DIESER Bridge nicht mehr gelten, ueber den gespeicherten
     * Zielnamen um und meldet zurueck, was sich nicht zuordnen liess.
     *
     * NUR MIT EINER ANTWORT DER BRIDGE AUFRUFEN. [targets] muss aus einem ERFOLGREICHEN
     * `getAllLightTargets()` stammen - eine nicht erreichbare Bridge darf niemals dazu fuehren,
     * dass Regeln umgeschrieben oder als "unbekannt" markiert werden.
     *
     * Idempotent: nach einem erfolgreichen Lauf findet der naechste alle IDs vor und schreibt
     * nichts mehr.
     */
    suspend fun reconcileTargets(targets: LightTargets): Result<TargetReconcileResult>

    /**
     * Ort und Zone fuer die Tageslicht-Bedingung der Regeln (#140), EINMAL je Durchgang zu laden
     * und an die reinen Pruefungen weiterzureichen (Planer-Schleife, Regel-Editor). Nie werfend:
     * gesperrter Nutzer, Lesefehler oder kein Ort ergeben [TageslichtStand.ohneOrt] - dann laeuft
     * jede Regel.
     */
    suspend fun tageslichtStand(): TageslichtStand
}

/** Warum ein Regel-Ziel auf der aktuellen Bridge nicht zuzuordnen war. */
enum class UnresolvedReason {
    /** Kein gespeicherter Zielname - es gibt keinen Anker, an dem sich etwas wiederfinden liesse. */
    NO_NAME,

    /** Name gespeichert, aber auf dieser Bridge gibt es kein Ziel dieser Art mit diesem Namen. */
    NOT_FOUND,

    /** Mehrere Ziele tragen denselben Namen - lieber nicht zuordnen als falsch zuordnen. */
    AMBIGUOUS
}

/**
 * Ein Regel-Ziel, das auf der aktuellen Bridge ins Leere zeigt. Wird NICHT persistiert: es ergibt
 * sich bei jedem Abgleich neu. Ein gespeichertes "unbekannt"-Kennzeichen wuerde veralten, sobald
 * die richtige Bridge wieder da ist.
 */
data class UnresolvedRuleTarget(
    val ruleId: String,
    val ruleName: String,
    val targetId: String,
    val targetName: String?,
    val isGroup: Boolean,
    val reason: UnresolvedReason,
    /**
     * Gesetzt = nicht das Gruppen-, sondern das SZENEN-Ziel ist das Problem. Additiv und
     * nullbar, damit bestehende Aufrufer und Tests unveraendert gueltig bleiben.
     */
    val sceneName: String? = null
) {
    /**
     * Beschriftung fuer die Oberflaeche: der Name, wenn es einen gibt, sonst die rohe ID.
     *
     * Dies ist die EINZIGE Beschriftungsquelle fuer nicht zuordenbare Ziele - Regel-Liste,
     * Ziel-Karte im Hue-Tab und der Fertig-Dialog des Konfigurations-Imports lesen alle hier.
     * Deshalb erben sie den Szenenfall, ohne selbst etwas zu formulieren.
     */
    val label: String
        get() = when {
            sceneName != null -> "Szene «$sceneName» in ${targetName?.takeIf { it.isNotBlank() } ?: "Gruppe $targetId"}"
            else -> targetName?.takeIf { it.isNotBlank() }
                ?: "${if (isGroup) "Gruppe" else "Licht"} $targetId"
        }
}

/** Ergebnis eines Ziel-Abgleichs. */
data class TargetReconcileResult(
    /** Anzahl der Aktionen, die eine neue, auf dieser Bridge gueltige ID bekommen haben. */
    val remapped: Int,
    val unresolved: List<UnresolvedRuleTarget>
)

/**
 * Result of rule execution for alarm
 *
 * @param autoOffTestNote Set only by the "Regel testen" preview path when the preview was
 * shortened compared to the real alarm execution: either an auto-off delay (rule has an
 * auto-off configured) or a sunrise ramp duration (sunrise rule). User-facing hint that the
 * real alarm still uses the full configured duration. Null for the real alarm path.
 */
data class RuleExecutionResult(
    val rulesExecuted: Int,
    val actionsExecuted: Int,
    val successfulActions: Int,
    val errors: List<String>,
    val autoOffTestNote: String? = null
)

/**
 * Result of rule validation
 */
data class RuleValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
