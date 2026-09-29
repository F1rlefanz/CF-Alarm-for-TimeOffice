package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.RuleExecutionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.TargetReconcileResult
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
         * Schichtmuster einer Regel, die fuer JEDE Schicht gilt (Editor: "Alle Schichten").
         * `internal` statt `private`, weil [HueSunriseExecutor] und die Regel-UI dasselbe Muster
         * auswerten muessen - ein zweites Literal waere eine zweite Wahrheit.
         * Hergang: Skill cfalarm-hue, reference/hue-api-und-regeln.md
         */
        internal const val UNIVERSAL_SHIFT_PATTERN = "ALL"

        // 1 wie die UI (isNotBlank): eine reale Regel heisst "FS", und updateRule validiert
        // ebenfalls. Hergang: Skill cfalarm-hue, reference/hue-api-und-regeln.md
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
            requireValidRule(rule).onFailure { return Result.failure(it) }

            val existingRules = configRepository.getScheduleRules().getOrNull() ?: emptyList()
            if (existingRules.any { it.id == rule.id }) {
                Logger.w(LogTags.HUE_USECASE, "Rule with ID ${rule.id} already exists")
                return Result.failure(IllegalArgumentException("Rule with ID ${rule.id} already exists"))
            }
            
            val shiftRuleCount = existingRules.count { it.shiftPattern == rule.shiftPattern }
            if (shiftRuleCount >= MAX_RULES_PER_SHIFT) {
                Logger.w(LogTags.HUE_USECASE, "Maximum rules per shift exceeded for ${rule.shiftPattern}")
                return Result.failure(
                    IllegalArgumentException("Maximum of $MAX_RULES_PER_SHIFT rules per shift pattern allowed")
                )
            }
            
            val ruleToSave = if (rule.id.isBlank()) {
                rule.copy(id = generateRuleId())
            } else {
                rule
            }
            
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
            val allRulesResult = getAllRules()
            if (allRulesResult.isFailure) {
                return allRulesResult.fold(
                    onSuccess = { Result.success(emptyList()) },
                    onFailure = { Result.failure(it) }
                )
            }
            
            val allRules = allRulesResult.getOrNull() ?: emptyList()

            // `rule.shiftPattern` ist IMMER ein Definitionsname - exakter Vergleich, nie ueber
            // Keywords. Hergang: Skill cfalarm-hue, reference/hue-api-und-regeln.md
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

                    val actionsResult = convertRuleToLightActions(rule)
                    
                    if (actionsResult.isFailure) {
                        val error = "Failed to convert rule ${rule.name} to actions: ${actionsResult.exceptionOrNull()?.message}"
                        Logger.w(LogTags.HUE_USECASE, error)
                        errors.add(error)
                        continue
                    }
                    
                    val actions = actionsResult.getOrNull() ?: emptyList()
                    totalActions += actions.size
                    
                    val batchResult = lightUseCase.executeBatchLightActions(actions)
                    
                    if (batchResult.isSuccess) {
                        batchResult.getOrNull()?.let { result ->
                            successfulActions += result.successfulActions
                            
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

            // AUTO-AUS: jetzt, im selben Atemzug, auf der BRIDGE hinterlegen - die Lampen sind
            // gerade angegangen, die Bridge ist also erreichbar. autoOffTargetsOf() ist der
            // einzige Rechenweg. Hergang: Skill cfalarm-hue, reference/hue-api-und-regeln.md
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
     * Auto-Aus-Ziele von BEREITS AUSGEWAEHLTEN Regeln - bewusst OHNE eigenen Schicht-Filter
     * (die Auswahl gehoert allein [findApplicableRules]), sonst verloeren UNIVERSAL-Regeln ihr
     * Auto-Aus. Besitzt nur den Rechenweg inkl. Sonnenaufgangs-Versatz.
     * Hergang: Skill cfalarm-hue, reference/hue-api-und-regeln.md
     */
    private fun autoOffTargetsOf(rules: List<HueSchedule>): List<AutoOffTarget> {
        return try {
            rules.asSequence()
                // Only the light actions' `duration` decides whether auto-off applies -
                // sunrise rules included (auto-off then counts from the ramp's end state).
                .flatMap { rule ->
                    // A ramp starting AT the alarm time (startBeforeAlarm == false) is only bright
                    // after durationMinutes - delay the auto-off by that so it never fires mid-ramp.
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
            
            rule.lightActions.forEach { ruleAction ->
                // SZENE: Es faehrt AUSSCHLIESSLICH die Szene mit. Kein on, keine Helligkeit,
                // keine Farbe, keine Uebergangszeit - die Szene bestimmt das alles selbst, und
                // ein zweiter Wert im selben PUT waere eine Zusage, die niemand prueft. Das
                // `on = true` der gespeicherten Regel ist bewusst NUR die Zusage fuer
                // autoOffTargetsOf(), es wird hier nicht durchgereicht.
                if (ruleAction.isScene) {
                    actions.add(
                        LightAction(
                            targetId = ruleAction.targetId,
                            isGroup = true,
                            sceneId = ruleAction.sceneId
                        )
                    )
                    return@forEach
                }

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
                    transitionTime = ruleAction.transitionTime
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
            requireValidRule(rule).onFailure { return Result.failure(it) }

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
     * Lehnt eine Regel ab, die die Validierung nicht besteht. [validateRule] hat zwei Ebenen:
     * `isFailure` = die Prüfung selbst ist gescheitert, `isValid` = die Regel ist gültig.
     * Hergang: Skill cfalarm-hue, reference/hue-api-und-regeln.md
     *
     * Die konkreten Fehler wandern in die Meldung - die Ursache steht in
     * [RuleValidationResult.errors].
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
        
        if (rule.name.length < MIN_RULE_NAME_LENGTH) {
            errors.add("Rule name must be at least $MIN_RULE_NAME_LENGTH characters long")
        }
        
        if (rule.name.length > MAX_RULE_NAME_LENGTH) {
            errors.add("Rule name must be at most $MAX_RULE_NAME_LENGTH characters long")
        }
        
        if (rule.shiftPattern.isBlank()) {
            errors.add("Shift pattern cannot be empty")
        }
        
        if (rule.lightActions.isEmpty()) {
            errors.add("Rule must have at least one light action")
        }
        
        // Sonnenaufgang und Szene schliessen sich aus: die Rampe erzeugt den Lichtzustand ueber
        // die Zeit, die Szene bringt ihn fertig mit. Beides zusammen waeren zwei Wahrheiten fuer
        // denselben Zustand, und welche gewinnt, haenge am Timing zweier Bridge-Aufrufe.
        if (rule.sunrise?.enabled == true && rule.lightActions.any { it.isScene }) {
            errors.add("Sonnenaufgang und Szene schliessen sich aus - waehle eines von beidem")
        }

        rule.lightActions.forEach { action ->
            if (action.targetId.isBlank()) {
                errors.add("Light action must have a valid target ID")
            }

            if (action.isScene) {
                // Eine Szene wird ueber /groups/<id>/action angewendet - ohne Gruppenziel gibt
                // es keinen Endpunkt, an den sie gehen koennte.
                if (!action.isGroup) {
                    errors.add("Ein Szenen-Ziel braucht eine Gruppe (Raum oder Zone)")
                }
                if (action.brightness != null || action.hue != null ||
                    action.saturation != null || action.colorTemperature != null
                ) {
                    errors.add("Eine Szene bestimmt Helligkeit und Farbe selbst")
                }
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
    
    /**
     * Fuehrt [rule] sofort aus, damit der Nutzer im Formular sieht, was sie tut.
     *
     * DIE VORSCHAU RAEUMT IMMER HINTER SICH AUF - unabhaengig davon, ob die Regel ein Auto-Aus
     * konfiguriert hat. Hergang: Skill cfalarm-hue, reference/vorschau-und-lampentest.md
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

                // Das Aus kommt NACH der (verkuerzten) Rampe, nicht mittendrin.
                // Hergang: Skill cfalarm-hue, reference/vorschau-und-lampentest.md
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
                //
                // Eine Szenen-Aktion traegt `on == null` (gesendet wird nur `{"scene": ...}`),
                // schaltet aber sehr wohl Licht an - sie zaehlt hier also mit, sonst behauptet
                // die Meldung "nichts geht an", waehrend der Raum hell wird.
                val turnsAnythingOn = actions.any { it.on == true || it.sceneId != null }

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
                                    actions.any { it.sceneId != null } ->
                                        "Test: Der Raum geht nach ~${AUTO_OFF_TEST_DURATION_SECONDS} s wieder aus " +
                                            "(nur im Test – die echte Regel lässt die Szene an)"
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
     * Ziel-Abgleich nach einem Bridge-Wechsel (Konfigurations-Import, neue Bridge, Neuinstallation
     * mit Android-Backup). Rechenweg und Haltung stecken vollstaendig in [HueTargetReconciler];
     * hier liegen nur Transaktion und Protokoll.
     *
     * Der Abgleich laeuft INNERHALB der Schreib-Transaktion auf dem frisch gelesenen Bestand -
     * eine gleichzeitige Nutzer-Aenderung geht dadurch nicht verloren (siehe
     * [IHueConfigRepository.updateScheduleRules]).
     */
    override suspend fun reconcileTargets(targets: LightTargets): Result<TargetReconcileResult> {
        var outcome: HueTargetReconciler.Outcome? = null

        val write = configRepository.updateScheduleRules { current ->
            HueTargetReconciler.reconcile(current, targets).also { outcome = it }.rules
        }

        write.exceptionOrNull()?.let { error ->
            Logger.w(LogTags.HUE_USECASE, "Ziel-Abgleich nicht moeglich - Regeln bleiben unveraendert", error)
            return Result.failure(error)
        }

        val result = outcome ?: return Result.success(TargetReconcileResult(0, emptyList()))

        if (result.remapped > 0 || result.unresolved.isNotEmpty()) {
            // WARN, nicht DEBUG: Ein Ziel, das ins Leere zeigt, ist Licht, das am Wecktag nicht
            // angeht - und Release-Logs enthalten nur WARN+.
            Logger.w(
                LogTags.HUE_USECASE,
                "🔗 Ziel-Abgleich: ${result.remapped} Ziel(e) ueber den Namen neu zugeordnet, " +
                    "${result.namesRefreshed} Name(n) aktualisiert, " +
                    "${result.unresolved.size} nicht zuordenbar" +
                    if (result.unresolved.isEmpty()) "" else
                        " (${result.unresolved.joinToString { "${it.ruleName}/${it.label}: ${it.reason}" }})"
            )
        } else {
            Logger.d(LogTags.HUE_USECASE, "🔗 Ziel-Abgleich: alle Regel-Ziele auf dieser Bridge bekannt")
        }

        return Result.success(TargetReconcileResult(result.remapped, result.unresolved))
    }

    /**
     * Zieht die Regeln einer UMBENANNTEN Schichtdefinition auf den neuen Namen nach.
     * Liefert die Anzahl der geänderten Regeln, oder einen Fehlschlag.
     *
     * WARUM ES DAS GEBEN MUSS (Prüfrunde 8, Befund 2): [HueSchedule.shiftPattern] bindet über den
     * NAMEN der Definition (siehe den Kommentar in [findApplicableRules]), nicht über deren
     * stabile `id` — der Schichtname ist aber frei änderbar, `ShiftEditDialog` behält dabei die
     * `id`. Eine reine Beschriftungsänderung ("AD1" → "Abrufdienst") legte damit die Hue-Regel
     * dieser Schicht lautlos still: [findApplicableRules] vergleicht gegen
     * `shift.shiftDefinition.name` und findet nichts mehr, das Licht geht zur Weckzeit nicht an —
     * während die Regelliste das Muster unverändert als aktiv anzeigt. Der
     * Sonnenaufgangs-Zweig ([HueSunriseExecutor]) hängt am selben Vergleich und bricht mit.
     *
     * DAS UNIVERSALMUSTER BLEIBT UNBERÜHRT: [UNIVERSAL_SHIFT_PATTERN] meint „alle Schichten",
     * keinen Namen. Mitziehen würde eine Regel, die für jede Schicht gilt, auf genau eine
     * einschränken — der Nutzer verlöre das Licht bei allen übrigen. Deshalb der Ausschluss in
     * [betrifftSchicht]. (Denselben Weg zu Ende gedacht: eine Schicht, die neu „ALL" heißen soll,
     * wird gar nicht erst zum Nachziehen angemeldet — siehe `planeSchichtUmbenennungen` im ShiftViewModel.)
     *
     * `ignoreCase` genau wie beim Suchen — der Vergleich dort ist groß-/kleinschreibungsblind.
     *
     * IDEMPOTENT: Liefert die Transformation eine unveränderte Liste, schreibt
     * `updateScheduleRules` nichts (siehe [IHueConfigRepository.updateScheduleRules]).
     *
     * BEWUSST NICHT IN [IHueRuleUseCase]: Der Aufrufer ist der Schicht-Editor, nicht der
     * Hue-Bereich. Wandert der Nachzug später an eine zweite Stelle, gehört die Signatur ins
     * Interface — solange es genau einen Aufrufer gibt, wäre das nur zusätzliche Fläche.
     */
    suspend fun renameShiftPattern(oldName: String, newName: String): Result<Int> {
        if (oldName.isBlank() || newName.isBlank()) {
            return Result.failure(
                IllegalArgumentException(
                    "Leerer Schichtname - Hue-Regelmuster wird NICHT nachgezogen ('$oldName' -> '$newName')"
                )
            )
        }
        // Rein groß-/kleinschreibungsbedingte Änderungen brechen das Matching nicht.
        if (oldName.equals(newName, ignoreCase = true)) return Result.success(0)

        var migrated = 0
        val write = configRepository.updateScheduleRules { current ->
            // Zurücksetzen, falls die Transaktion den Block je erneut ausführt - sonst zählte
            // ein Wiederholungslauf doppelt.
            migrated = 0
            current.map { rule ->
                if (rule.betrifftSchicht(oldName)) {
                    migrated++
                    rule.copy(shiftPattern = newName)
                } else {
                    rule
                }
            }
        }

        write.exceptionOrNull()?.let { error ->
            // WARN+ landet auch im Release-Log: eine nicht nachgezogene Regel ist Licht, das am
            // Wecktag nicht mehr angeht.
            Logger.e(
                LogTags.HUE_USECASE,
                "❌ Hue-Regelmuster konnte nicht von '$oldName' auf '$newName' nachgezogen werden - " +
                    "Regeln bleiben unveraendert",
                error
            )
            return Result.failure(error)
        }

        if (migrated == 0) return Result.success(0)

        // NACHPRÜFEN statt annehmen: `updateScheduleRules` schreibt in EINER Transaktion, aber der
        // Erfolg sagt nur "geschrieben". Bleibt danach noch ein Altname stehen, hat der Nachzug
        // nicht gegriffen, und die Umbenennung darf sich nicht als vollständig ausgeben.
        val nachher = getAllRules().getOrElse { error ->
            Logger.e(
                LogTags.HUE_USECASE,
                "❌ Hue-Regeln nach dem Nachziehen nicht lesbar - Ergebnis unbestaetigt ('$oldName' -> '$newName')",
                error
            )
            return Result.failure(error)
        }
        val nochAlt = nachher.count { it.betrifftSchicht(oldName) }
        if (nochAlt > 0) {
            val fehler = IllegalStateException(
                "Hue-Regelmuster NICHT vollstaendig nachgezogen ('$oldName' -> '$newName'): " +
                    "$nochAlt Regel(n) tragen weiter den Altnamen"
            )
            Logger.e(LogTags.HUE_USECASE, fehler.message ?: "", fehler)
            return Result.failure(fehler)
        }

        Logger.business(
            LogTags.HUE_USECASE,
            "🔁 $migrated Hue-Regel(n) von '$oldName' auf '$newName' nachgezogen"
        )
        return Result.success(migrated)
    }

    /**
     * Trägt diese Regel den Schichtnamen [shiftName]? Das Universalmuster zählt bewusst NIE mit -
     * es meint keinen Namen (siehe [renameShiftPattern]).
     */
    private fun HueSchedule.betrifftSchicht(shiftName: String): Boolean =
        !shiftPattern.equals(UNIVERSAL_SHIFT_PATTERN, ignoreCase = true) &&
            shiftPattern.equals(shiftName, ignoreCase = true)

    private fun generateRuleId(): String {
        return "rule_${UUID.randomUUID().toString().take(8)}_${System.currentTimeMillis()}"
    }
}
