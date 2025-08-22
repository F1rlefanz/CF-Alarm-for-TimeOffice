package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import java.time.LocalTime
import java.util.*
import kotlin.math.abs

/**
 * UseCase for Hue Rule operations with shift integration
 * Implements business logic layer with validation and rule engine
 */
class HueRuleUseCase(
    private val configRepository: IHueConfigRepository,
    private val lightUseCase: IHueLightUseCase
) : IHueRuleUseCase {
    
    companion object {
        private const val MAX_RULES_PER_SHIFT = 10
        private const val MIN_RULE_NAME_LENGTH = 3
        private const val MAX_RULE_NAME_LENGTH = 50
        private const val MIN_TIME_OFFSET_MINUTES = -60
        private const val MAX_TIME_OFFSET_MINUTES = 60
    }
    
    override suspend fun getAllRules(): Result<List<HueSchedule>> {
        Logger.d(LogTags.HUE_USECASE, "Getting all schedule rules")
        
        return try {
            val rulesResult = configRepository.getScheduleRules()
            
            if (rulesResult.isSuccess) {
                val rules = rulesResult.getOrNull() ?: emptyList()
                Logger.i(LogTags.HUE_USECASE, "Retrieved ${rules.size} schedule rules")
                Result.success(rules)
            } else {
                Logger.w(LogTags.HUE_USECASE, "Failed to get schedule rules", rulesResult.exceptionOrNull())
                rulesResult
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to get all rules", e)
            Result.failure(Exception("Failed to retrieve schedule rules: ${e.message}", e))
        }
    }
    
    override suspend fun createRule(rule: HueSchedule): Result<HueSchedule> {
        Logger.i(LogTags.HUE_USECASE, "Creating new schedule rule: ${rule.name}")
        
        return try {
            // Validate rule
            val validationResult = validateRule(rule)
            if (validationResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Rule validation failed for ${rule.name}")
                return Result.failure(validationResult.exceptionOrNull() ?: Exception("Validation failed"))
            }
            
            // Check for duplicate IDs
            val existingRules = configRepository.getScheduleRules().getOrNull() ?: emptyList()
            if (existingRules.any { it.id == rule.id }) {
                Logger.w(LogTags.HUE_USECASE, "Rule with ID ${rule.id} already exists")
                return Result.failure(IllegalArgumentException("Rule with ID ${rule.id} already exists"))
            }
            
            // Check rule count limit per shift
            val shiftRuleCount = existingRules.count { it.shiftPattern == rule.shiftPattern }
            if (shiftRuleCount >= MAX_RULES_PER_SHIFT) {
                Logger.w(LogTags.HUE_USECASE, "Maximum rules per shift exceeded for ${rule.shiftPattern}")
                return Result.failure(
                    IllegalArgumentException("Maximum of $MAX_RULES_PER_SHIFT rules per shift pattern allowed")
                )
            }
            
            // Create rule with generated ID if needed
            val ruleToSave = if (rule.id.isBlank()) {
                rule.copy(id = generateRuleId())
            } else {
                rule
            }
            
            // Save rule
            val saveResult = configRepository.saveScheduleRule(ruleToSave)
            
            if (saveResult.isSuccess) {
                Logger.i(LogTags.HUE_USECASE, "Successfully created rule: ${ruleToSave.id}")
                Result.success(ruleToSave)
            } else {
                Logger.w(LogTags.HUE_USECASE, "Failed to save rule: ${ruleToSave.id}", saveResult.exceptionOrNull())
                Result.failure(saveResult.exceptionOrNull() ?: Exception("Failed to save rule"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to create rule", e)
            Result.failure(Exception("Failed to create rule: ${e.message}", e))
        }
    }
    
    /**
     * Execute matching rules for a shift - Primary entry point from AlarmReceiver
     * 
     * This method is called from AlarmReceiver when an alarm triggers.
     * It finds applicable rules and executes the corresponding light actions.
     */
    suspend fun executeMatchingRules(shift: ShiftMatch): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "🎨 Executing matching rules for shift: ${shift.shiftDefinition.name}")
        
        return try {
            // Get current time for rule matching
            val currentTime = shift.calculatedAlarmTime.toLocalTime()
            
            // Find applicable rules for this shift
            val applicableRulesResult = findApplicableRules(shift, currentTime)
            
            if (applicableRulesResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Failed to find applicable rules", applicableRulesResult.exceptionOrNull())
                return applicableRulesResult.fold(
                    onSuccess = { Result.success(RuleExecutionResult(0, 0, 0, emptyList())) },
                    onFailure = { Result.failure(it) }
                )
            }
            
            val applicableRules = applicableRulesResult.getOrNull() ?: emptyList()
            
            if (applicableRules.isEmpty()) {
                Logger.i(LogTags.HUE_USECASE, "No applicable rules found for shift ${shift.shiftDefinition.name}")
                return Result.success(RuleExecutionResult(0, 0, 0, emptyList()))
            }
            
            Logger.i(LogTags.HUE_USECASE, "Found ${applicableRules.size} applicable rules, executing...")
            
            // Execute rules for alarm
            executeRulesForAlarm(shift, currentTime)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to execute matching rules", e)
            Result.failure(Exception("Failed to execute matching rules: ${e.message}", e))
        }
    }
    
    override suspend fun findApplicableRules(shift: ShiftMatch, currentTime: LocalTime): Result<List<HueSchedule>> {
        Logger.d(LogTags.HUE_USECASE, "Finding applicable rules for shift: ${shift.shiftDefinition.name} at ${currentTime}")
        
        return try {
            // Get all rules
            val allRulesResult = getAllRules()
            if (allRulesResult.isFailure) {
                return allRulesResult.fold(
                    onSuccess = { Result.success(emptyList()) },
                    onFailure = { Result.failure(it) }
                )
            }
            
            val allRules = allRulesResult.getOrNull() ?: emptyList()
            
            // Extract shift pattern from definition (use name or first keyword)
            val shiftPattern = shift.shiftDefinition.keywords.firstOrNull() 
                ?: shift.shiftDefinition.name
            
            // Filter rules matching the shift pattern
            val matchingRules = allRules.filter { rule ->
                // Check if rule matches shift pattern
                rule.shiftPattern.equals(shiftPattern, ignoreCase = true) ||
                rule.shiftPattern.equals(shift.shiftDefinition.name, ignoreCase = true) ||
                rule.shiftPattern.equals("ALL", ignoreCase = true) // Universal rules
            }
            
            Logger.i(LogTags.HUE_USECASE, "Found ${matchingRules.size} rules matching shift pattern $shiftPattern")
            Result.success(matchingRules)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to find applicable rules", e)
            Result.failure(e)
        }
    }
    
    override suspend fun executeRulesForAlarm(shift: ShiftMatch, alarmTime: LocalTime): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "Executing rules for alarm at ${alarmTime}")
        
        return try {
            val applicableRulesResult = findApplicableRules(shift, alarmTime)
            
            if (applicableRulesResult.isFailure) {
                return Result.failure(applicableRulesResult.exceptionOrNull() ?: Exception("Failed to find rules"))
            }
            
            val applicableRules = applicableRulesResult.getOrNull() ?: emptyList()
            val errors = mutableListOf<String>()
            var totalActions = 0
            var successfulActions = 0
            
            // Execute each rule
            for (rule in applicableRules) {
                try {
                    // Convert rule to light actions
                    val actionsResult = convertRuleToLightActions(rule)
                    
                    if (actionsResult.isFailure) {
                        val error = "Failed to convert rule ${rule.name} to actions: ${actionsResult.exceptionOrNull()?.message}"
                        Logger.w(LogTags.HUE_USECASE, error)
                        errors.add(error)
                        continue
                    }
                    
                    val actions = actionsResult.getOrNull() ?: emptyList()
                    totalActions += actions.size
                    
                    // Execute actions via light use case
                    val batchResult = lightUseCase.executeBatchLightActions(actions)
                    
                    if (batchResult.isSuccess) {
                        batchResult.getOrNull()?.let { result ->
                            successfulActions += result.successfulActions
                            
                            // Add any failed actions to errors
                            result.failedActions.forEach { failedAction ->
                                failedAction.error?.let { error ->
                                    errors.add("Action failed for ${failedAction.targetId}: $error")
                                }
                            }
                            
                            Logger.i(LogTags.HUE_USECASE, "Rule ${rule.name} executed: ${result.successfulActions}/${result.totalActions} actions successful")
                        } ?: run {
                            val error = "Batch execution succeeded but no result returned for rule ${rule.name}"
                            Logger.w(LogTags.HUE_USECASE, error)
                            errors.add(error)
                        }
                        
                    } else {
                        val error = "Failed to execute actions for rule ${rule.name}: ${batchResult.exceptionOrNull()?.message}"
                        Logger.w(LogTags.HUE_USECASE, error)
                        errors.add(error)
                    }
                    
                } catch (e: Exception) {
                    val error = "Exception executing rule ${rule.name}: ${e.message}"
                    Logger.e(LogTags.HUE_USECASE, error, e)
                    errors.add(error)
                }
            }
            
            val result = RuleExecutionResult(
                rulesExecuted = applicableRules.size,
                actionsExecuted = totalActions,
                successfulActions = successfulActions,
                errors = errors
            )
            
            Logger.i(LogTags.HUE_USECASE, "Rule execution complete: ${result.rulesExecuted} rules, ${result.successfulActions}/${result.actionsExecuted} actions successful")
            
            Result.success(result)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to execute rules for alarm", e)
            Result.failure(e)
        }
    }
    
    /**
     * Converts a HueSchedule rule to executable LightAction list
     */
    private suspend fun convertRuleToLightActions(rule: HueSchedule): Result<List<LightAction>> {
        return try {
            val actions = mutableListOf<LightAction>()
            
            // Convert each light action in the rule
            rule.lightActions.forEach { ruleAction ->
                val lightAction = LightAction(
                    targetId = ruleAction.targetId,
                    isGroup = ruleAction.isGroup,
                    on = ruleAction.on,
                    brightness = ruleAction.brightness,
                    hue = ruleAction.hue,
                    saturation = ruleAction.saturation,
                    actionDescription = "Rule: ${rule.name} - ${ruleAction.targetId}"
                )
                actions.add(lightAction)
            }
            
            Logger.d(LogTags.HUE_USECASE, "Converted rule ${rule.name} to ${actions.size} light actions")
            Result.success(actions)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to convert rule to actions", e)
            Result.failure(e)
        }
    }
    
    override suspend fun updateRule(rule: HueSchedule): Result<HueSchedule> {
        Logger.i(LogTags.HUE_USECASE, "Updating schedule rule: ${rule.id}")
        
        return try {
            // Validate rule
            val validationResult = validateRule(rule)
            if (validationResult.isFailure) {
                return Result.failure(validationResult.exceptionOrNull() ?: Exception("Validation failed"))
            }
            
            // Update rule
            val updateResult = configRepository.updateScheduleRule(rule)
            
            if (updateResult.isSuccess) {
                Logger.i(LogTags.HUE_USECASE, "Successfully updated rule: ${rule.id}")
                Result.success(rule)
            } else {
                Logger.w(LogTags.HUE_USECASE, "Failed to update rule: ${rule.id}", updateResult.exceptionOrNull())
                Result.failure(updateResult.exceptionOrNull() ?: Exception("Failed to update rule"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to update rule", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteRule(ruleId: String): Result<Unit> {
        Logger.i(LogTags.HUE_USECASE, "Deleting schedule rule: $ruleId")
        
        return try {
            val deleteResult = configRepository.deleteScheduleRule(ruleId)
            
            if (deleteResult.isSuccess) {
                Logger.i(LogTags.HUE_USECASE, "Successfully deleted rule: $ruleId")
            } else {
                Logger.w(LogTags.HUE_USECASE, "Failed to delete rule: $ruleId", deleteResult.exceptionOrNull())
            }
            
            deleteResult
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to delete rule", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getRule(ruleId: String): Result<HueSchedule> {
        Logger.d(LogTags.HUE_USECASE, "Getting schedule rule: $ruleId")
        
        return try {
            val allRulesResult = getAllRules()
            
            if (allRulesResult.isFailure) {
                return allRulesResult.fold(
                    onSuccess = { Result.failure(Exception("Rule not found: $ruleId")) },
                    onFailure = { Result.failure(it) }
                )
            }
            
            val allRules = allRulesResult.getOrNull() ?: emptyList()
            val rule = allRules.find { it.id == ruleId }
            
            if (rule != null) {
                Logger.d(LogTags.HUE_USECASE, "Found rule: $ruleId")
                Result.success(rule)
            } else {
                Logger.w(LogTags.HUE_USECASE, "Rule not found: $ruleId")
                Result.failure(Exception("Rule not found: $ruleId"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to get rule", e)
            Result.failure(e)
        }
    }
    
    override suspend fun validateRule(rule: HueSchedule): Result<RuleValidationResult> {
        Logger.d(LogTags.HUE_USECASE, "Validating rule: ${rule.id}")
        
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate rule name
        if (rule.name.length < MIN_RULE_NAME_LENGTH) {
            errors.add("Rule name must be at least $MIN_RULE_NAME_LENGTH characters long")
        }
        
        if (rule.name.length > MAX_RULE_NAME_LENGTH) {
            errors.add("Rule name must be at most $MAX_RULE_NAME_LENGTH characters long")
        }
        
        // Validate shift pattern
        if (rule.shiftPattern.isBlank()) {
            errors.add("Shift pattern cannot be empty")
        }
        
        // Validate light actions
        if (rule.lightActions.isEmpty()) {
            errors.add("Rule must have at least one light action")
        }
        
        // Validate individual light actions
        rule.lightActions.forEach { action ->
            if (action.targetId.isBlank()) {
                errors.add("Light action must have a valid target ID")
            }
            
            action.brightness?.let { brightness ->
                if (brightness < 0 || brightness > 255) {
                    errors.add("Brightness must be between 0 and 255")
                }
            }
            
            action.hue?.let { hue ->
                if (hue < 0 || hue > 65535) {
                    errors.add("Hue must be between 0 and 65535")
                }
            }
            
            action.saturation?.let { saturation ->
                if (saturation < 0 || saturation > 255) {
                    errors.add("Saturation must be between 0 and 255")
                }
            }
        }
        
        val result = RuleValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
        
        Logger.d(LogTags.HUE_USECASE, "Rule validation result: ${if (result.isValid) "VALID" else "INVALID"} (${errors.size} errors, ${warnings.size} warnings)")
        
        return Result.success(result)
    }
    
    override suspend fun testRuleExecution(rule: HueSchedule): Result<List<LightAction>> {
        Logger.d(LogTags.HUE_USECASE, "Testing rule execution: ${rule.id}")
        
        return try {
            // Convert rule to actions (dry run)
            val actionsResult = convertRuleToLightActions(rule)
            
            if (actionsResult.isSuccess) {
                val actions = actionsResult.getOrNull() ?: emptyList()
                Logger.i(LogTags.HUE_USECASE, "Rule test successful: ${actions.size} actions would be executed")
                Result.success(actions)
            } else {
                Logger.w(LogTags.HUE_USECASE, "Rule test failed", actionsResult.exceptionOrNull())
                actionsResult
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to test rule execution", e)
            Result.failure(e)
        }
    }
    
    /**
     * Generates a unique rule ID
     */
    private fun generateRuleId(): String {
        return "rule_${UUID.randomUUID().toString().take(8)}_${System.currentTimeMillis()}"
    }
}
