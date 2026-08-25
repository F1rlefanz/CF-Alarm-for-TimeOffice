package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleExecutionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Die Sonnenaufgangs-AUSFUEHRUNG - herausgeloest aus [HueRuleUseCase], damit sie einen eigenen,
 * benennbaren Ort hat. Reine Verschiebung, kein Redesign: Auswahl, Validierung und Auto-Aus
 * bleiben in [HueRuleUseCase].
 *
 * ABSICHTLICH KEIN HILT-BINDING: [HueRuleUseCase] erzeugt diese Klasse selbst und behaelt damit
 * seinen zweiparametrigen Konstruktor. Es gibt keinen zweiten Besitzer der Sonnenaufgangs-Rampe.
 *
 * [loadRules] ist bewusst der Lade-Weg von [HueRuleUseCase.getAllRules] (inkl. dessen Logging und
 * Fehlerverpackung) und NICHT ein direkter Repository-Zugriff - ein zweiter Ladeweg waere eine
 * zweite Wahrheit.
 */
internal class HueSunriseExecutor(
    private val lightUseCase: IHueLightUseCaseAdvanced,
    private val loadRules: suspend () -> Result<List<HueSchedule>>
) {

    /**
     * Fuehrt NUR die Vorab-Rampen aus (Regeln mit aktivem Sonnenaufgang, der vor dem Wecker
     * startet). Aufgerufen vom Pre-Alarm-Worker; der Pfad zur Weckzeit ueberspringt diese Regeln
     * dann bzw. schnappt sie ueber [finalizeSunriseForRule] auf den Endzustand.
     */
    suspend fun executeSunrisePreAlarm(shiftName: String): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "🌅 Executing PRE-ALARM sunrise ramps for shift: $shiftName")

        return try {
            val rules = loadRules().getOrElse { error ->
                Logger.e(LogTags.HUE_USECASE, "Failed to load rules for pre-alarm sunrise", error)
                return Result.failure(error)
            }
            val sunriseRules = matchingPreAlarmSunriseRules(rules, shiftName)

            val errors = mutableListOf<String>()
            var attempted = 0
            var succeeded = 0

            for (rule in sunriseRules) {
                val sunrise = rule.sunrise ?: continue
                val result = runSunriseForRule(rule, sunrise)
                attempted += result.attempted
                succeeded += result.succeeded
                errors.addAll(result.errors)
            }

            val result = RuleExecutionResult(
                rulesExecuted = sunriseRules.size,
                actionsExecuted = attempted,
                successfulActions = succeeded,
                errors = errors
            )

            Logger.i(LogTags.HUE_USECASE, "🌅 Pre-alarm sunrise complete: ${sunriseRules.size} rule(s), $succeeded/$attempted targets")
            Result.success(result)

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to execute pre-alarm sunrise", e)
            Result.failure(e)
        }
    }

    /**
     * Vorlaufzeit in Minuten fuer die Vorab-Rampe: die laengste Dauer unter den passenden
     * "vor dem Wecker startenden" Sonnenaufgangs-Regeln, sonst null.
     *
     * Nimmt die Regeln als Parameter und liest NICHT selbst - siehe KDoc in
     * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase].
     */
    fun getPreAlarmSunriseLeadMinutes(rules: List<HueSchedule>, shiftName: String): Int? {
        return try {
            matchingPreAlarmSunriseRules(rules, shiftName)
                .mapNotNull { it.sunrise?.durationMinutes }
                .maxOrNull()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to compute pre-alarm sunrise lead minutes", e)
            null
        }
    }

    /**
     * Enabled "start before alarm" sunrise rules whose shift pattern matches [shiftName]
     * (or the universal [HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN]).
     *
     * [shiftName] ist hier der Definitionsname, den der Alarm traegt - derselbe Massstab wie in
     * [HueRuleUseCase.findApplicableRules]. Kein Keyword-Vergleich, keine Teiltreffer.
     */
    private fun matchingPreAlarmSunriseRules(rules: List<HueSchedule>, shiftName: String): List<HueSchedule> {
        return rules.filter { rule ->
            val sunrise = rule.sunrise
            rule.enabled &&
                sunrise != null &&
                sunrise.enabled &&
                sunrise.startBeforeAlarm &&
                (rule.shiftPattern.equals(shiftName, ignoreCase = true) ||
                    rule.shiftPattern.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true))
        }
    }

    /**
     * Runs the sunrise ramp on every target of [rule] via the light use case.
     */
    suspend fun runSunriseForRule(rule: HueSchedule, sunrise: SunriseConfig): SunriseRunResult {
        // Szenen-Ziele sind hier ausgeschlossen: eine Rampe erzeugt den Lichtzustand ueber die
        // Zeit, eine Szene bringt ihn fertig mit. validateRule() lehnt die Kombination bereits
        // ab - dieser Filter ist die zweite Linie fuer Bestandsdaten und kuenftige Editor-Fehler.
        val targets = rule.lightActions
            .filter { !it.isScene }
            .map { it.targetId to it.isGroup }
            .filter { it.first.isNotBlank() }
            .distinct()

        if (targets.isEmpty()) {
            Logger.w(LogTags.HUE_USECASE, "🌅 Sunrise rule ${rule.name} has no targets")
            return SunriseRunResult(0, 0, listOf("Sunrise rule ${rule.name} has no targets"))
        }

        val errors = mutableListOf<String>()
        var succeeded = 0

        targets.forEach { (targetId, isGroup) ->
            val result = lightUseCase.startSunrise(
                targetId = targetId,
                isGroup = isGroup,
                startKelvin = sunrise.startKelvin,
                endKelvin = sunrise.endKelvin,
                endBrightness = sunrise.endBrightness,
                durationMinutes = sunrise.durationMinutes
            )
            if (result.isSuccess) {
                succeeded++
            } else {
                errors.add("Sunrise failed for $targetId: ${result.exceptionOrNull()?.message}")
            }
        }

        Logger.i(LogTags.HUE_USECASE, "🌅 Sunrise for rule ${rule.name}: $succeeded/${targets.size} targets")
        return SunriseRunResult(targets.size, succeeded, errors)
    }

    /**
     * Snaps a pre-alarm sunrise rule's targets to the END state (full brightness + end color
     * temperature) with a short transition. Used at alarm time as a safety net so the lights
     * always reach the wake-up state even if the pre-alarm ramp never ran.
     */
    suspend fun finalizeSunriseForRule(rule: HueSchedule, sunrise: SunriseConfig): SunriseRunResult {
        // Szenen-Ziele sind hier ausgeschlossen: eine Rampe erzeugt den Lichtzustand ueber die
        // Zeit, eine Szene bringt ihn fertig mit. validateRule() lehnt die Kombination bereits
        // ab - dieser Filter ist die zweite Linie fuer Bestandsdaten und kuenftige Editor-Fehler.
        val targets = rule.lightActions
            .filter { !it.isScene }
            .map { it.targetId to it.isGroup }
            .filter { it.first.isNotBlank() }
            .distinct()

        if (targets.isEmpty()) {
            Logger.w(LogTags.HUE_USECASE, "🌅 Sunrise rule ${rule.name} has no targets")
            return SunriseRunResult(0, 0, listOf("Sunrise rule ${rule.name} has no targets"))
        }

        val endCt = HueColorConverter.kelvinToHueMireds(sunrise.endKelvin)
        val actions = targets.map { (targetId, isGroup) ->
            LightAction(
                targetId = targetId,
                isGroup = isGroup,
                on = true,
                brightness = sunrise.endBrightness,
                colorTemperature = endCt,
                transitionTime = HueConstants.Lights.SLOW_TRANSITION_TIME,
                actionDescription = "Sunrise finalize: ${rule.name}"
            )
        }

        val batch = lightUseCase.executeBatchLightActions(actions)
        return if (batch.isSuccess) {
            val result = batch.getOrNull()
            SunriseRunResult(
                attempted = actions.size,
                succeeded = result?.successfulActions ?: 0,
                errors = result?.failedActions?.mapNotNull { it.error } ?: emptyList()
            )
        } else {
            SunriseRunResult(actions.size, 0, listOf("Sunrise finalize failed for rule ${rule.name}: ${batch.exceptionOrNull()?.message}"))
        }
    }

    /** Outcome of running a sunrise ramp across a rule's targets. */
    data class SunriseRunResult(
        val attempted: Int,
        val succeeded: Int,
        val errors: List<String>
    )
}
