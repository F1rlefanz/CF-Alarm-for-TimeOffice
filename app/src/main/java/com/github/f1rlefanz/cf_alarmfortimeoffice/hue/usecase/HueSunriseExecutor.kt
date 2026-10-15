package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleExecutionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Die Sonnenaufgangs-AUSFUEHRUNG - herausgeloest aus [HueRuleUseCase], damit sie einen eigenen,
 * benennbaren Ort hat. Reine Verschiebung, kein Redesign: Auswahl, Validierung und die Auto-Aus-
 * RECHNUNG bleiben in [HueRuleUseCase] - auch fuer das Vorab-Auto-Aus der Rampe
 * ([HueRuleUseCase.vorabAutoAusZiele]).
 *
 * ABSICHTLICH KEIN HILT-BINDING: [HueRuleUseCase] erzeugt diese Klasse selbst. Es gibt keinen
 * zweiten Besitzer der Sonnenaufgangs-Rampe.
 *
 * [loadRules] ist bewusst der Lade-Weg von [HueRuleUseCase.getAllRules] (inkl. dessen Logging und
 * Fehlerverpackung) und NICHT ein direkter Repository-Zugriff - ein zweiter Ladeweg waere eine
 * zweite Wahrheit. Dasselbe gilt fuer [ladeStand] = [HueRuleUseCase.tageslichtStand] (nie werfend,
 * Entsperr-Gate in der Quelle).
 */
internal class HueSunriseExecutor(
    private val lightUseCase: IHueLightUseCaseAdvanced,
    private val ladeStand: suspend () -> TageslichtStand,
    /** [HueRuleUseCase.fruehAusFuer] - derselbe Weg wie im Weckzeit-Pfad, damit beide Timer gleich enden. */
    private val fruehAusFuer: suspend (List<HueSchedule>, Long?) -> FruehAus?,
    private val loadRules: suspend () -> Result<List<HueSchedule>>
) {

    /**
     * Fuehrt NUR die Vorab-Rampen aus (Regeln mit aktivem Sonnenaufgang, der vor dem Wecker
     * startet). Aufgerufen vom Pre-Alarm-Worker; der Pfad zur Weckzeit ueberspringt diese Regeln
     * dann bzw. schnappt sie ueber [finalizeSunriseForRule] auf den Endzustand.
     *
     * Mit [weckzeit] wird die Tageslicht-Bedingung (#140) HIER, beim Feuern, noch einmal geprueft -
     * der Ort kann sich seit der Planung geaendert haben. Das Vorab-Auto-Aus bekommt dieselbe,
     * bereits gefilterte Liste.
     */
    suspend fun executeSunrisePreAlarm(
        shiftName: String,
        minutenBisWecker: Int?,
        weckzeit: Long?
    ): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "🌅 Executing PRE-ALARM sunrise ramps for shift: $shiftName")

        return try {
            val rules = loadRules().getOrElse { error ->
                Logger.e(LogTags.HUE_USECASE, "Failed to load rules for pre-alarm sunrise", error)
                return Result.failure(error)
            }
            val kandidaten = matchingPreAlarmSunriseRules(rules, shiftName, weckzeit = null, stand = null)
            // Den Ort nur lesen, wenn eine Kandidatin ueberhaupt eine Schwelle traegt.
            val sunriseRules = if (weckzeit != null && kandidaten.any { it.hellAb != HellAb.IMMER }) {
                val stand = ladeStand()
                kandidaten.filter { it.laeuftBeiTageslichtMitLog(weckzeit, stand) }
            } else {
                kandidaten
            }

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

            // Auto-Aus schon JETZT auf die Bridge - siehe HueRuleUseCase.vorabAutoAusZiele.
            // Best-effort wie im Weckzeit-Pfad: ein Fehler kippt die Rampe nicht.
            if (minutenBisWecker != null && succeeded > 0) {
                try {
                    lightUseCase.scheduleBridgeAutoOff(
                        HueRuleUseCase.vorabAutoAusZiele(sunriseRules, minutenBisWecker, fruehAusFuer(sunriseRules, weckzeit)),
                        shiftName
                    ).onFailure { errors.add("Vorab-Auto-Aus fehlgeschlagen: ${it.message}") }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(LogTags.HUE_USECASE, "Failed to schedule pre-alarm auto-off", e)
                    errors.add("Vorab-Auto-Aus fehlgeschlagen: ${e.message}")
                }
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
    fun getPreAlarmSunriseLeadMinutes(
        rules: List<HueSchedule>,
        shiftName: String,
        weckzeit: Long?,
        stand: TageslichtStand?
    ): Int? {
        return try {
            matchingPreAlarmSunriseRules(rules, shiftName, weckzeit, stand)
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
     *
     * Tageslicht (#140): mit [weckzeit] UND [stand] faellt eine Regel heraus, die zur Weckzeit
     * entfiele - ohne Vorlauf kein Worker, ohne Worker keine halbe Rampe. Fehlt eines: kein Filter.
     * Bewusst still (laeuft in der Planer-Schleife je Wecker); geloggt wird beim Feuern.
     */
    private fun matchingPreAlarmSunriseRules(
        rules: List<HueSchedule>,
        shiftName: String,
        weckzeit: Long?,
        stand: TageslichtStand?
    ): List<HueSchedule> {
        return rules.filter { rule ->
            val sunrise = rule.sunrise
            rule.enabled &&
                sunrise != null &&
                sunrise.enabled &&
                sunrise.startBeforeAlarm &&
                rule.passtAufSchicht(shiftName) &&
                (weckzeit == null || stand == null || rule.laeuftBeiTageslicht(weckzeit, stand))
        }
    }

    // Szenen-Ziele sind hier ausgeschlossen: eine Rampe erzeugt den Lichtzustand ueber die
    // Zeit, eine Szene bringt ihn fertig mit. validateRule() lehnt die Kombination bereits
    // ab - dieser Filter ist die zweite Linie fuer Bestandsdaten und kuenftige Editor-Fehler.
    private fun sunriseTargets(rule: HueSchedule): List<Pair<String, Boolean>> = rule.lightActions
        .filter { !it.isScene }
        .map { it.targetId to it.isGroup }
        .filter { it.first.isNotBlank() }
        .distinct()

    /**
     * Runs the sunrise ramp on every target of [rule] via the light use case.
     */
    suspend fun runSunriseForRule(rule: HueSchedule, sunrise: SunriseConfig): SunriseRunResult {
        val targets = sunriseTargets(rule)

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
        val targets = sunriseTargets(rule)

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
                transitionTime = HueConstants.Lights.SLOW_TRANSITION_TIME
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
