package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleExecutionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleValidationResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * UseCase for Hue Rule operations with shift integration
 * Implements business logic layer with validation and rule engine
 */
@Singleton
class HueRuleUseCase @Inject constructor(
    private val configRepository: IHueConfigRepository,
    private val lightUseCase: IHueLightUseCaseAdvanced
) : IHueRuleUseCase {
    
    companion object {
        private const val MAX_RULES_PER_SHIFT = 10
        private const val MIN_RULE_NAME_LENGTH = 3
        private const val MAX_RULE_NAME_LENGTH = 50

        /** Shortened ramp duration used when previewing a sunrise via "Regel testen". */
        private const val SUNRISE_TEST_DURATION_MINUTES = 1

        /**
         * Shortened auto-off delay used when previewing a rule's auto-off via "Regel testen".
         * Kept short ("zügig") so the tester sees lights go on and back off within the preview,
         * instead of waiting for the real configured duration (which can be many minutes).
         */
        private const val AUTO_OFF_TEST_DURATION_SECONDS = 20
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
            
            // Filter rules matching the shift pattern (only enabled rules)
            val matchingRules = allRules.filter { rule ->
                // Skip rules the user has disabled in the rule editor
                rule.enabled &&
                // Check if rule matches shift pattern
                (rule.shiftPattern.equals(shiftPattern, ignoreCase = true) ||
                rule.shiftPattern.equals(shift.shiftDefinition.name, ignoreCase = true) ||
                rule.shiftPattern.equals("ALL", ignoreCase = true)) // Universal rules
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
                    // SUNRISE: a sunrise rule overrides the plain on/off/brightness actions.
                    val sunrise = rule.sunrise
                    if (sunrise?.enabled == true) {
                        val sunriseResult = if (sunrise.startBeforeAlarm) {
                            // The ramp was started earlier by the pre-alarm worker. Snap to the
                            // end state now so the alarm ALWAYS lights up, even if that worker
                            // never ran (device off/offline). Idempotent if the ramp completed.
                            Logger.d(LogTags.HUE_USECASE, "Rule ${rule.name}: finalizing pre-alarm sunrise at alarm time")
                            finalizeSunriseForRule(rule, sunrise)
                        } else {
                            // Sunrise starts at the alarm: run the full ramp now.
                            runSunriseForRule(rule, sunrise)
                        }
                        totalActions += sunriseResult.attempted
                        successfulActions += sunriseResult.succeeded
                        errors.addAll(sunriseResult.errors)
                        continue
                    }

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

    override suspend fun executeSunrisePreAlarm(shiftName: String): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "🌅 Executing PRE-ALARM sunrise ramps for shift: $shiftName")

        return try {
            val sunriseRules = matchingPreAlarmSunriseRules(shiftName)

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

    override suspend fun getPreAlarmSunriseLeadMinutes(shiftName: String): Int? {
        return try {
            matchingPreAlarmSunriseRules(shiftName)
                .mapNotNull { it.sunrise?.durationMinutes }
                .maxOrNull()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to compute pre-alarm sunrise lead minutes", e)
            null
        }
    }

    override suspend fun getAutoOffActions(shiftName: String): List<AutoOffTarget> {
        return try {
            val all = getAllRules().getOrElse { error ->
                Logger.e(LogTags.HUE_USECASE, "Failed to load rules for auto-off", error)
                return emptyList()
            }
            all.asSequence()
                .filter { rule ->
                    rule.enabled &&
                        rule.sunrise?.enabled != true && // sunrise reaches its own end state
                        (rule.shiftPattern.equals(shiftName, ignoreCase = true) ||
                            rule.shiftPattern.equals("ALL", ignoreCase = true))
                }
                .flatMap { rule -> rule.lightActions.asSequence() }
                .filter { action -> action.on == true && (action.duration ?: 0) > 0 && action.targetId.isNotBlank() }
                .map { action -> AutoOffTarget(action.targetId, action.isGroup, action.duration!!) }
                .distinct()
                .toList()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to compute auto-off actions", e)
            emptyList()
        }
    }

    /**
     * Enabled "start before alarm" sunrise rules whose shift pattern matches [shiftName]
     * (or the universal "ALL" pattern).
     */
    private suspend fun matchingPreAlarmSunriseRules(shiftName: String): List<HueSchedule> {
        val all = getAllRules().getOrElse { error ->
            Logger.e(LogTags.HUE_USECASE, "Failed to load rules for pre-alarm sunrise", error)
            return emptyList()
        }
        return all.filter { rule ->
            val sunrise = rule.sunrise
            rule.enabled &&
                sunrise != null &&
                sunrise.enabled &&
                sunrise.startBeforeAlarm &&
                (rule.shiftPattern.equals(shiftName, ignoreCase = true) ||
                    rule.shiftPattern.equals("ALL", ignoreCase = true))
        }
    }

    /**
     * Runs the sunrise ramp on every target of [rule] via the light use case.
     */
    private suspend fun runSunriseForRule(rule: HueSchedule, sunrise: SunriseConfig): SunriseRunResult {
        val targets = rule.lightActions
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
    private suspend fun finalizeSunriseForRule(rule: HueSchedule, sunrise: SunriseConfig): SunriseRunResult {
        val targets = rule.lightActions
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
    private data class SunriseRunResult(
        val attempted: Int,
        val succeeded: Int,
        val errors: List<String>
    )

    /**
     * Converts a HueSchedule rule to executable LightAction list
     */
    private fun convertRuleToLightActions(rule: HueSchedule): Result<List<LightAction>> {
        return try {
            val actions = mutableListOf<LightAction>()
            
            // Convert each light action in the rule
            rule.lightActions.forEach { ruleAction ->
                // Resolve color: prefer the explicit hue/sat fields, else fall back to
                // the action's HueColor (so rules built with a color picker still work).
                val resolvedHue = ruleAction.hue ?: ruleAction.color?.hue
                val resolvedSaturation = ruleAction.saturation ?: ruleAction.color?.saturation

                // Color-mode exclusivity: the Hue bridge accepts only ONE color mode per
                // call. When a color temperature is set it wins over hue/saturation.
                val useColorTemperature = ruleAction.colorTemperature != null

                val lightAction = LightAction(
                    targetId = ruleAction.targetId,
                    isGroup = ruleAction.isGroup,
                    on = ruleAction.on,
                    brightness = ruleAction.brightness,
                    hue = if (useColorTemperature) null else resolvedHue,
                    saturation = if (useColorTemperature) null else resolvedSaturation,
                    colorTemperature = ruleAction.colorTemperature,
                    transitionTime = ruleAction.transitionTime,
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

            action.colorTemperature?.let { ct ->
                if (ct < HueConstants.Lights.MIN_COLOR_TEMPERATURE || ct > HueConstants.Lights.MAX_COLOR_TEMPERATURE) {
                    errors.add("Color temperature must be between ${HueConstants.Lights.MIN_COLOR_TEMPERATURE} and ${HueConstants.Lights.MAX_COLOR_TEMPERATURE} mireds")
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
    
    override suspend fun executeRuleNow(rule: HueSchedule): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "▶️ Executing rule now (preview): ${rule.name}")

        return try {
            val sunrise = rule.sunrise
            if (sunrise?.enabled == true) {
                // Demo the ramp over a short, observable duration instead of the full time.
                val testSunrise = sunrise.copy(durationMinutes = SUNRISE_TEST_DURATION_MINUTES)
                val result = runSunriseForRule(rule, testSunrise)
                Result.success(
                    RuleExecutionResult(
                        rulesExecuted = 1,
                        actionsExecuted = result.attempted,
                        successfulActions = result.succeeded,
                        errors = result.errors
                    )
                )
            } else {
                val actions = convertRuleToLightActions(rule).getOrElse { return Result.failure(it) }
                if (actions.isEmpty()) {
                    return Result.success(RuleExecutionResult(0, 0, 0, emptyList()))
                }

                // If the rule configures an auto-off, demo it too: lights on now, OFF again
                // after a short, observable delay (the real alarm still uses the configured
                // duration - only the preview is shortened).
                val hasAutoOff = rule.lightActions.any { it.on == true && (it.duration ?: 0) > 0 }

                if (hasAutoOff) {
                    lightUseCase.executeActionsWithAutoRevert(
                        actions = actions,
                        revertAfter = AUTO_OFF_TEST_DURATION_SECONDS.seconds
                    ).fold(
                        onSuccess = { batch ->
                            Result.success(
                                RuleExecutionResult(
                                    rulesExecuted = 1,
                                    actionsExecuted = batch.totalActions,
                                    successfulActions = batch.successfulActions,
                                    errors = batch.failedActions.mapNotNull { it.error },
                                    autoOffTestNote = "Test: Auto-Aus verkürzt auf ~${AUTO_OFF_TEST_DURATION_SECONDS} s " +
                                        "(die echte Regel nutzt die konfigurierte Zeit)"
                                )
                            )
                        },
                        onFailure = { Result.failure(it) }
                    )
                } else {
                    lightUseCase.executeBatchLightActions(actions).fold(
                        onSuccess = { batch ->
                            Result.success(
                                RuleExecutionResult(
                                    rulesExecuted = 1,
                                    actionsExecuted = batch.totalActions,
                                    successfulActions = batch.successfulActions,
                                    errors = batch.failedActions.mapNotNull { it.error }
                                )
                            )
                        },
                        onFailure = { Result.failure(it) }
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to execute rule now", e)
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
