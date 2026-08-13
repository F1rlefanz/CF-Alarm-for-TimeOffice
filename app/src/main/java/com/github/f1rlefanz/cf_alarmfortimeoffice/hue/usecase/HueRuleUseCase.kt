package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleExecutionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleValidationResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
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

    /**
     * Die Sonnenaufgangs-Ausfuehrung liegt in einer eigenen Klasse, wird hier aber INTERN
     * erzeugt: kein Hilt-Binding, kein zusaetzlicher Konstruktor-Parameter. Es gibt genau einen
     * Besitzer der Rampe, und das ist dieser UseCase.
     */
    private val sunriseExecutor = HueSunriseExecutor(lightUseCase) { getAllRules() }

    companion object {
        private const val MAX_RULES_PER_SHIFT = 10

        /**
         * Schichtmuster einer Regel, die fuer JEDE Schicht gilt. Seit v1.24.0 bietet der
         * Regel-Editor das als Eintrag "Alle Schichten" an (davor wertete nur der UseCase es
         * aus, ohne dass es je jemand setzen konnte).
         *
         * `internal` statt `private`, weil [HueSunriseExecutor] und die Regel-UI dasselbe Muster
         * auswerten muessen - ein zweites Literal waere eine zweite Wahrheit, und genau daran
         * haengt, ob eine Universal-Regel ihr Auto-Aus behaelt.
         */
        internal const val UNIVERSAL_SHIFT_PATTERN = "ALL"

        // War 3 - eine willkuerliche Schwelle, die nie greifen konnte, weil der Validierungs-
        // Check kaputt war (siehe requireValidRule). Mit der Reparatur wuerde sie ploetzlich
        // scharf: Die UI verlangt seit jeher nur isNotBlank(), und eine Regel namens "FS" fuer
        // die Fruehschicht ist voellig legitim - sie existiert real. Bei 3 waere sie ab sofort
        // nicht mehr speicher- UND nicht mehr bearbeitbar gewesen (updateRule validiert auch).
        // 1 bringt UI und UseCase auf dieselbe Regel: der Name darf nicht leer sein.
        private const val MIN_RULE_NAME_LENGTH = 1
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
            requireValidRule(rule).onFailure { return Result.failure(it) }

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

            // `rule.shiftPattern` ist IMMER ein Definitionsname: die Regel-UI bietet nichts
            // anderes an (HueRuleConfigScreen: definitions.map { it.name }). Ein Vergleich
            // gegen das erste KEYWORD der Definition stand hier mal daneben - er konnte nie
            // etwas Richtiges treffen, aber sehr wohl etwas Falsches: eine Regel mit dem
            // Muster "S" haette auf die Spaetschicht gepasst. Dieselbe Fehlerfamilie wie in
            // ShiftConfig.findDefinitionFor() - einbuchstabige Keywords passen auf zu vieles.
            val shiftName = shift.shiftDefinition.name

            val matchingRules = allRules.filter { rule ->
                // Vom Nutzer deaktivierte Regeln bleiben aussen vor.
                rule.enabled &&
                    (rule.shiftPattern.equals(shiftName, ignoreCase = true) ||
                        rule.shiftPattern.equals(UNIVERSAL_SHIFT_PATTERN, ignoreCase = true))
            }

            Logger.i(LogTags.HUE_USECASE, "Found ${matchingRules.size} rules matching shift '$shiftName'")
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
                            sunriseExecutor.finalizeSunriseForRule(rule, sunrise)
                        } else {
                            // Sunrise starts at the alarm: run the full ramp now.
                            sunriseExecutor.runSunriseForRule(rule, sunrise)
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

            // AUTO-AUS: jetzt, im selben Atemzug, auf der BRIDGE hinterlegen.
            //
            // Genau hier sind die Lampen gerade angegangen - die Bridge ist also nachweislich
            // erreichbar. Damit schaltet sie selbst wieder aus, unabhaengig davon, wo das Handy
            // spaeter ist. (Frueher fuhr ein WorkManager-Job das Auto-Aus; der erreichte die
            // Bridge nur aus dem Heim-WLAN und liess die Lampen an, sobald jemand nach dem
            // Wecken das Haus verliess.)
            //
            // autoOffTargetsOf() besitzt die Verzoegerungsrechnung inkl. Sonnenaufgangs-Versatz
            // als einzige Stelle. Ein zweiter Rechenweg waere eine zweite Wahrheit.
            //
            // Best-effort: ein Fehler darf den Weckvorgang NIEMALS kippen. Er landet in
            // `errors` (und im Log), aber die Regelausfuehrung selbst gilt als erfolgt - das
            // Licht ist ja an.
            try {
                val autoOffTargets = autoOffTargetsOf(applicableRules)
                lightUseCase.scheduleBridgeAutoOff(autoOffTargets, shift.shiftDefinition.name)
                    .onFailure { errors.add("Bridge-Zeitplan fuer Auto-Aus fehlgeschlagen: ${it.message}") }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_USECASE, "Failed to schedule bridge-side auto-off", e)
                errors.add("Bridge-Zeitplan fuer Auto-Aus fehlgeschlagen: ${e.message}")
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

    override suspend fun executeSunrisePreAlarm(shiftName: String): Result<RuleExecutionResult> =
        sunriseExecutor.executeSunrisePreAlarm(shiftName)

    override fun getPreAlarmSunriseLeadMinutes(rules: List<HueSchedule>, shiftName: String): Int? =
        sunriseExecutor.getPreAlarmSunriseLeadMinutes(rules, shiftName)

    /**
     * Auto-Aus-Ziele von BEREITS AUSGEWAEHLTEN Regeln.
     *
     * Bewusst OHNE eigenen Schicht-Filter: die Auswahl gehoert allein [findApplicableRules]
     * (exakter Definitionsname ODER [UNIVERSAL_SHIFT_PATTERN]). Ein zweiter Filter gegen den
     * Schichtnamen waere eine zweite Wahrheit - und wuerde konkret die UNIVERSAL-Regeln wieder
     * wegwerfen, deren `shiftPattern` per Definition NICHT dem Schichtnamen gleicht: sie
     * verloeren ihr Auto-Aus, das Licht blieb an. Diese Funktion besitzt nur den Rechenweg
     * (welche Ziele, welche Verzoegerung inkl. Sonnenaufgangs-Versatz), nicht die Auswahl.
     *
     * (Hier stand bis zu diesem Fix als Begruendung, [findApplicableRules] matche "auch ueber die
     * KEYWORDS einer Schicht" - das tut es seit dem Keyword-Fix in v1.11.0 nicht mehr, siehe
     * den Kommentar dort und `HueSunriseExecutor.matchingPreAlarmSunriseRules`. Die Entscheidung
     * "kein zweiter Filter" war richtig, nur die Begruendung war veraltet - und eine veraltete Begruendung
     * verleitet dazu, das Keyword-Matching "wiederherzustellen", also genau die Fehlerfamilie
     * neu zu bauen, die CLAUDE.md unter "Schicht -> Regel" festhaelt.)
     */
    private fun autoOffTargetsOf(rules: List<HueSchedule>): List<AutoOffTarget> {
        return try {
            rules.asSequence()
                // UX FIX (D): sunrise rules can ALSO configure auto-off now (they reach
                // their own bright end state via the ramp, then this schedules the
                // separate "turn back off after N minutes" job on top of that end state).
                // Only the light actions' explicit `duration` field decides whether
                // auto-off applies - no need to special-case sunrise here anymore.
                .flatMap { rule ->
                    // UX FIX (D-timing): a sunrise ramp that starts AT the alarm time
                    // (startBeforeAlarm == false) only reaches full brightness after
                    // sunrise.durationMinutes. Delay the auto-off by that ramp duration so it
                    // can never fire mid-ramp. For startBeforeAlarm == true the ramp already
                    // ends at the alarm time, so no offset is needed (same as a plain on-rule).
                    val sunriseOffsetMinutes = rule.sunrise
                        ?.takeIf { it.enabled && !it.startBeforeAlarm }
                        ?.durationMinutes ?: 0
                    rule.lightActions.asSequence()
                        .filter { action -> action.on == true && (action.duration ?: 0) > 0 && action.targetId.isNotBlank() }
                        .map { action -> AutoOffTarget(action.targetId, action.isGroup, action.duration!! + sunriseOffsetMinutes) }
                }
                .distinct()
                .toList()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to compute auto-off actions", e)
            emptyList()
        }
    }

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
            requireValidRule(rule).onFailure { return Result.failure(it) }

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
    
    /**
     * Lehnt eine Regel ab, die die Validierung nicht besteht.
     *
     * WARUM ES DAS BRAUCHT: An beiden Aufrufstellen stand `if (validateRule(rule).isFailure)`.
     * Das prüft die falsche Ebene. [validateRule] liefert `Result<RuleValidationResult>` und gibt
     * IMMER `Result.success` zurück, sobald die Prüfung durchgelaufen ist — ob die Regel gültig
     * ist, steht eine Ebene tiefer in `isValid`. Eine ungültige Regel war also ein *erfolgreiches*
     * Result: die Abfrage konnte gar nicht greifen, und die Regel wurde gespeichert, obwohl das
     * Log daneben "INVALID (1 errors)" meldete (Gerätelog 14.07., 14:56:03).
     *
     * `isFailure` bleibt hier trotzdem sinnvoll — aber nur für den Fall, dass die Prüfung selbst
     * scheitert. Beides ist jetzt sauber getrennt.
     *
     * Die konkreten Fehler wandern in die Meldung: "Validation failed" hätte niemandem geholfen,
     * die Ursache steht in [RuleValidationResult.errors].
     */
    private suspend fun requireValidRule(rule: HueSchedule): Result<Unit> {
        val validation = validateRule(rule).getOrElse { error ->
            Logger.w(LogTags.HUE_USECASE, "Rule validation could not run for ${rule.name}", error)
            return Result.failure(error)
        }

        if (!validation.isValid) {
            val reason = validation.errors.joinToString("; ")
            Logger.w(LogTags.HUE_USECASE, "Rule rejected: ${rule.name} - $reason")
            return Result.failure(IllegalArgumentException(reason))
        }

        return Result.success(Unit)
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
    
    /**
     * Fuehrt [rule] sofort aus, damit der Nutzer im Formular sieht, was sie tut.
     *
     * DIE VORSCHAU RAEUMT IMMER HINTER SICH AUF - unabhaengig davon, ob die Regel ein Auto-Aus
     * konfiguriert hat. Vorher haing das am Auto-Aus der Regel, und das steht bei einer neuen
     * Regel auf "aus": Der Vorschau-Knopf schaltete das Licht an und liess es an, ohne Weg
     * zurueck ausser der Hue-App. Eine Vorschau, die den Zustand der Wohnung dauerhaft
     * veraendert, ist keine Vorschau - der Nutzer probiert hier eine Regel aus, er schaltet
     * nicht sein Licht ein. Wer das wieder ans Auto-Aus koppelt, baut genau das zurueck.
     *
     * Die verkuerzten Zeiten gelten NUR hier; die echte Regel nutzt die konfigurierten Werte
     * (bzw. bridge-seitige Zeitplaene, siehe executeRulesForAlarm).
     */
    override suspend fun executeRuleNow(rule: HueSchedule): Result<RuleExecutionResult> {
        Logger.i(LogTags.HUE_USECASE, "▶️ Executing rule now (preview): ${rule.name}")

        return try {
            val sunrise = rule.sunrise
            if (sunrise?.enabled == true) {
                // Demo the ramp over a short, observable duration instead of the full time.
                val testSunrise = sunrise.copy(durationMinutes = SUNRISE_TEST_DURATION_MINUTES)
                val result = sunriseExecutor.runSunriseForRule(rule, testSunrise)

                // Das Aus kommt NACH der (verkuerzten) Rampe, nicht mittendrin: Die Rampe
                // laeuft als native Bridge-Transition ueber SUNRISE_TEST_DURATION_MINUTES -
                // ein Aus nach AUTO_OFF_TEST_DURATION_SECONDS wuerde sie mitten im Aufblenden
                // abwuergen. Derselbe Gedanke wie der sunriseOffset in autoOffTargetsOf(): das
                // Auto-Aus haengt hinten an, es faellt nicht in die Rampe hinein.
                //
                // Nur ein blankes on=true (kein Helligkeit/Farbe): alles andere wuerde gegen
                // die laufende Transition der Bridge arbeiten.
                val onlyOnActions = rule.lightActions
                    .map { it.targetId to it.isGroup }
                    .filter { it.first.isNotBlank() }
                    .distinct()
                    .map { (targetId, isGroup) -> LightAction(targetId = targetId, isGroup = isGroup, on = true) }
                if (onlyOnActions.isNotEmpty()) {
                    lightUseCase.executeActionsWithAutoRevert(
                        actions = onlyOnActions,
                        revertAfter = SUNRISE_TEST_DURATION_MINUTES.minutes + AUTO_OFF_TEST_DURATION_SECONDS.seconds
                    )
                }

                Result.success(
                    RuleExecutionResult(
                        rulesExecuted = 1,
                        actionsExecuted = result.attempted,
                        successfulActions = result.succeeded,
                        errors = result.errors,
                        // Sagt, was der Nutzer gleich sieht - und dass es nicht die echten
                        // Zeiten sind (sonst wirkt die 1-Minuten-Rampe wie ein Fehler).
                        autoOffTestNote = "Test: Sonnenaufgang verkürzt auf $SUNRISE_TEST_DURATION_MINUTES Min, " +
                            "danach gehen die Lichter wieder aus " +
                            "(die echte Regel nutzt die konfigurierte Dauer von ${sunrise.durationMinutes} Min)"
                    )
                )
            } else {
                val actions = convertRuleToLightActions(rule).getOrElse { return Result.failure(it) }
                if (actions.isEmpty()) {
                    return Result.success(RuleExecutionResult(0, 0, 0, emptyList()))
                }

                // Licht an, und nach einer kurzen, beobachtbaren Weile wieder aus. Schaltet die
                // Regel nur AUS, hat executeActionsWithAutoRevert nichts nachzuraeumen - es
                // filtert selbst auf on == true.
                val hasAutoOff = rule.lightActions.any { it.on == true && (it.duration ?: 0) > 0 }
                // ... und dann darf auch die Meldung kein Aus versprechen: eine reine
                // Ausschalt-Regel laesst nichts an, was zurueckzunehmen waere.
                val turnsAnythingOn = actions.any { it.on == true }

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
                                // Der Unterschied ist wichtig: Bei einer Regel MIT Auto-Aus ist
                                // das Ausgehen echtes Verhalten, nur schneller. Bei einer Regel
                                // OHNE ist es reines Aufraeumen der Vorschau - dort wuerde das
                                // Licht sonst anbleiben, und das muss dabeistehen, sonst haelt
                                // der Nutzer das Aus faelschlich fuer die Regel.
                                autoOffTestNote = when {
                                    !turnsAnythingOn -> null
                                    hasAutoOff ->
                                        "Test: Auto-Aus verkürzt auf ~${AUTO_OFF_TEST_DURATION_SECONDS} s " +
                                            "(die echte Regel nutzt die konfigurierte Zeit)"
                                    else ->
                                        "Test: Lichter gehen nach ~${AUTO_OFF_TEST_DURATION_SECONDS} s wieder aus " +
                                            "(nur im Test – die echte Regel lässt sie an)"
                                }
                            )
                        )
                    },
                    onFailure = { Result.failure(it) }
                )
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
