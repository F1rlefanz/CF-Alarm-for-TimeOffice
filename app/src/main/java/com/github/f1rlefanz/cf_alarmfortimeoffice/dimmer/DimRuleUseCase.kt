package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geschäftslogik über den Dimm-Regeln: CRUD plus die Auswahl der passenden Regel je Tag.
 *
 * Auswahl-Reihenfolge (mostspecific-wins, analog `ShiftConfig.findDefinitionFor()` und
 * `HueRuleUseCase.findApplicableRules`):
 *  - Schicht-Tag: exakter Definitionsname → sonst [DimRule.SHIFT_UNIVERSAL] → sonst nichts.
 *  - Freier Tag:  [DimRule.SHIFT_FREE] → sonst [DimRule.SHIFT_UNIVERSAL] → sonst nichts.
 *
 * Eine gefundene Regel mit leerer Fensterliste unterdrückt bewusst das Dimmen (überschreibt so
 * die UNIVERSAL-Regel) – das ist die Nachtdienst-Ausnahme.
 */
@Singleton
class DimRuleUseCase @Inject constructor(
    private val repository: DimRuleRepository
) {
    val rules: Flow<List<DimRule>> = repository.rules

    suspend fun getAllRules(): List<DimRule> = repository.getRules()
    suspend fun saveRule(rule: DimRule) = repository.upsert(rule)
    suspend fun deleteRule(id: String) = repository.delete(id)

    /** Passende Regel für einen erkannten Schicht-Namen, oder null. */
    fun findRuleForShift(shiftName: String, all: List<DimRule>): DimRule? {
        val enabled = all.filter { it.enabled }
        return enabled.firstOrNull { it.shiftPattern.equals(shiftName, ignoreCase = true) }
            ?: enabled.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }

    /** Passende Regel für einen freien Tag (keine erkannte Schicht), oder null. */
    fun findRuleForFreeDay(all: List<DimRule>): DimRule? {
        val enabled = all.filter { it.enabled }
        return enabled.firstOrNull { it.shiftPattern == DimRule.SHIFT_FREE }
            ?: enabled.firstOrNull { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
    }
}
