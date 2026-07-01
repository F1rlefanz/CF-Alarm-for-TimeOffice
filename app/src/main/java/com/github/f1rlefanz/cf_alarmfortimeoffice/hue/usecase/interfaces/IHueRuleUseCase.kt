package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
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
     */
    suspend fun findApplicableRules(
        shift: ShiftMatch, 
        currentTime: LocalTime
    ): Result<List<HueSchedule>>
    
    /**
     * Execute rules for alarm trigger
     * Converts rules to light actions and executes them
     */
    suspend fun executeRulesForAlarm(
        shift: ShiftMatch,
        alarmTime: LocalTime
    ): Result<RuleExecutionResult>

    /**
     * Execute ONLY the pre-alarm sunrise ramps for a shift (rules whose sunrise is enabled
     * and configured to start before the alarm). Called by the pre-alarm worker ahead of
     * the actual alarm; the at-alarm path then skips these rules.
     *
     * Matched by shift name (the value the alarm carries) so the worker doesn't need a
     * full ShiftMatch.
     */
    suspend fun executeSunrisePreAlarm(shiftName: String): Result<RuleExecutionResult>

    /**
     * Lead time in minutes for pre-alarm sunrise: the longest duration among enabled
     * "start before alarm" sunrise rules matching [shiftName], or null if none apply.
     * Used by the scheduler to decide how early to start the ramp.
     */
    suspend fun getPreAlarmSunriseLeadMinutes(shiftName: String): Int?

    /**
     * Auto-off targets for a shift: lights/groups that an enabled rule switches ON with a
     * configured auto-off duration. Used by the scheduler to turn them off again afterwards.
     */
    suspend fun getAutoOffActions(shiftName: String): List<AutoOffTarget>
    
    /**
     * Validate rule configuration
     * Business logic validation for rule creation/update
     */
    suspend fun validateRule(rule: HueSchedule): Result<RuleValidationResult>
    
    /**
     * Test rule execution without actually triggering lights
     * Dry-run for rule testing
     */
    suspend fun testRuleExecution(rule: HueSchedule): Result<List<LightAction>>

    /**
     * Execute a rule immediately for previewing ("Regel testen"): applies the configured
     * lights/color, or — for a sunrise rule — runs a shortened, observable warm→cool ramp.
     */
    suspend fun executeRuleNow(rule: HueSchedule): Result<RuleExecutionResult>
}

/**
 * Result of rule execution for alarm
 *
 * @param autoOffTestNote Set only by the "Regel testen" preview path when the rule has an
 * auto-off configured: a user-facing hint that the preview shortened the auto-off delay
 * (the real alarm still uses the configured duration). Null for the real alarm path.
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

/**
 * A light/group that should be switched off [delayMinutes] after a rule turned it on.
 */
data class AutoOffTarget(
    val targetId: String,
    val isGroup: Boolean,
    val delayMinutes: Int
)
