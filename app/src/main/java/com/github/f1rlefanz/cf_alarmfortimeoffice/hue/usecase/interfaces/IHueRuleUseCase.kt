package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
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
     * Validate rule configuration
     * Business logic validation for rule creation/update
     */
    suspend fun validateRule(rule: HueSchedule): Result<RuleValidationResult>
    
    /**
     * Test rule execution without actually triggering lights
     * Dry-run for rule testing
     */
    suspend fun testRuleExecution(rule: HueSchedule): Result<List<LightAction>>
}

/**
 * Result of rule execution for alarm
 */
data class RuleExecutionResult(
    val rulesExecuted: Int,
    val actionsExecuted: Int,
    val successfulActions: Int,
    val errors: List<String>
)

/**
 * Result of rule validation
 */
data class RuleValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
