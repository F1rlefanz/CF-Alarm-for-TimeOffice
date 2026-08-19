package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.rememberLocalNetworkPermissionGate
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.ActionConfigCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.AutoOffCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.RuleBasicInfoCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.RulePreviewCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.ShiftPatternCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.SunriseConfigCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.TargetSelectionCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel

/**
 * Saver fuer die Lampen-/Gruppen-Auswahl.
 *
 * Ein `Set` ist nicht garantiert bundle-faehig (es haengt an der konkreten Implementierung, die
 * gerade in der Variable steckt); eine Liste von Strings ist es immer. Deshalb der Umweg ueber
 * [listSaver] statt sich auf den autoSaver zu verlassen.
 */
private val StringSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() }
)

/**
 * Die einzige netzbeduerftige Aktion dieses Bildschirms: "Regel testen" schaltet echte Lampen.
 * Enum statt Lambda, damit die Absicht einen Activity-Neuaufbau waehrend des offenen
 * Berechtigungsdialogs ueberlebt - siehe [rememberLocalNetworkPermissionGate].
 */
private enum class HueRuleConfigNetzAktion { RULE_TEST }

/**
 * Hue Regel-Konfiguration Screen - Deutsche Version
 *
 * HILT MIGRATION: Now receives HueViewModel and ShiftViewModel directly instead of ViewModelFactory
 *
 * AUFTEILUNG (v1.24.0): Die sieben Karten liegen im Unterpaket `cards/`, die geteilten Hilfen
 * (Farb-Presets, Farbdarstellung, [MIN_TOUCH_TARGET], Musterbeschriftung) in
 * `HueRuleConfigHelpers.kt`. Hier bleiben Formularzustand, Regel-Aufbau und Validierung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueRuleConfigScreen(
    ruleId: String?,
    hueViewModel: HueViewModel,
    shiftViewModel: ShiftViewModel,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // collectAsStateWithLifecycle statt collectAsState: beide Flows speisen ausschliesslich diesen
    // Bildschirm. Das Sammeln pausiert damit unterhalb von STARTED - im Hintergrund gibt es hier
    // nichts nachzuhalten, und ein `stateIn(WhileSubscribed)`-Timeout weiter unten kann ueberhaupt
    // erst ablaufen. Kein Seiteneffekt haengt daran (die einmaligen Meldungen laufen ueber den
    // LaunchedEffect auf `userMessages`, den das nicht beruehrt).
    val uiState by hueViewModel.uiState.collectAsStateWithLifecycle()
    val shiftState by shiftViewModel.uiState.collectAsStateWithLifecycle()
    val toastContext = LocalContext.current

    // Surfaces one-shot messages from the ViewModel (e.g. the "Auto-Aus verkürzt" hint after
    // "Regel testen") as a Toast.
    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Form state
    //
    // Durchgaengig rememberSaveable, NICHT remember: MainActivity ist weder auf eine Orientierung
    // festgenagelt (kein screenOrientation) noch faengt sie Konfigurationswechsel ab (kein
    // configChanges) - jede Rotation zerstoert die Activity und baut sie neu auf. Mit `remember`
    // waren damit alle 17 Formularfelder (Name, Schichtmuster, Lampenauswahl, Farbe, Auto-Aus,
    // Sunrise-Parameter) kommentarlos weg, sobald das Geraet beim Tippen gedreht/abgelegt wurde -
    // ebenso bei Prozesstod im Hintergrund und bei "Aktivitaeten nicht behalten".
    var ruleName by rememberSaveable { mutableStateOf("") }
    var selectedShiftPattern by rememberSaveable { mutableStateOf("") }
    var selectedLightIds by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(setOf()) }
    var selectedGroupIds by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(setOf()) }
    var targetOn by rememberSaveable { mutableStateOf(true) }
    var targetBrightness by rememberSaveable { mutableIntStateOf(128) }
    var isEnabled by rememberSaveable { mutableStateOf(true) }

    // Color state
    var colorMode by rememberSaveable { mutableStateOf(ColorMode.NONE) }
    var colorKelvin by rememberSaveable { mutableIntStateOf(2700) }
    var colorPreset by rememberSaveable { mutableStateOf(HueColorConverter.ColorPreset.RED) }

    // Auto-off state (turn the lights off again after N minutes)
    var autoOffEnabled by rememberSaveable { mutableStateOf(false) }
    var autoOffMinutes by rememberSaveable { mutableIntStateOf(30) }

    // Sunrise state
    var sunriseEnabled by rememberSaveable { mutableStateOf(false) }
    var sunriseDurationMinutes by rememberSaveable { mutableIntStateOf(15) }
    var sunriseStartKelvin by rememberSaveable { mutableIntStateOf(2000) }
    var sunriseEndKelvin by rememberSaveable { mutableIntStateOf(4000) }
    var sunriseEndBrightness by rememberSaveable { mutableIntStateOf(254) }
    var sunriseStartBeforeAlarm by rememberSaveable { mutableStateOf(true) }

    // Validation state
    var showValidationErrors by rememberSaveable { mutableStateOf(false) }

    // Merkt sich, fuer welche Regel das Formular schon einmal aus der gespeicherten Regel gefuellt
    // wurde - und muss deshalb selbst saveable sein. Ohne diesen Waechter waere rememberSaveable
    // oben wirkungslos: Nach einer Rotation laedt LaunchedEffect(ruleId) die Regel erneut, und der
    // LaunchedEffect(uiState.editingRule) darunter wuerde die gerade wiederhergestellten,
    // ungespeicherten Eingaben mit den ALTEN Werten aus der Regel ueberschreiben.
    var initializedForRuleId by rememberSaveable { mutableStateOf<String?>(null) }

    // Load rule for editing if ruleId is provided
    LaunchedEffect(ruleId) {
        if (ruleId != null) {
            hueViewModel.loadRuleForEditing(ruleId)
        } else {
            hueViewModel.clearEditingRule()
        }
    }

    // Auto-refresh light targets when screen opens (only if not already loaded)
    LaunchedEffect(Unit) {
        if (uiState.lightTargets.lights.isEmpty() && uiState.lightTargets.groups.isEmpty()) {
            hueViewModel.refreshLightTargets()
        }
    }

    // Initialize form fields when editing rule is loaded
    LaunchedEffect(uiState.editingRule) {
        uiState.editingRule?.let { rule ->
            // Nur beim ERSTEN Laden dieser Regel fuellen (siehe initializedForRuleId oben).
            if (initializedForRuleId == rule.id) return@LaunchedEffect
            initializedForRuleId = rule.id

            ruleName = rule.name
            selectedShiftPattern = rule.shiftPattern
            isEnabled = rule.enabled

            // Extract values from first time range and action
            val firstTimeRange = rule.timeRanges.firstOrNull()
            if (firstTimeRange != null) {
                val firstAction = firstTimeRange.actions.firstOrNull()
                if (firstAction != null) {
                    targetOn = firstAction.on ?: true
                    targetBrightness = firstAction.brightness ?: 128

                    // Restore the color mode from whichever color field was persisted.
                    when {
                        firstAction.colorTemperature != null -> {
                            colorMode = ColorMode.WHITE
                            colorKelvin = HueColorConverter.hueMiredsToKelvin(firstAction.colorTemperature)
                        }
                        firstAction.hue != null -> {
                            colorMode = ColorMode.COLOR
                            colorPreset = nearestPreset(firstAction.hue)
                        }
                        else -> colorMode = ColorMode.NONE
                    }

                    // Restore auto-off (duration in minutes)
                    firstAction.duration?.let { d ->
                        autoOffEnabled = d > 0
                        if (d > 0) autoOffMinutes = d
                    }
                }

                // Separate lights and groups from actions
                val lightIds = mutableSetOf<String>()
                val groupIds = mutableSetOf<String>()

                firstTimeRange.actions.forEach { action ->
                    when (action.targetType) {
                        TargetType.LIGHT -> lightIds.add(action.targetId)
                        TargetType.GROUP -> groupIds.add(action.targetId)
                        else -> {} // Handle other types if needed
                    }
                }

                selectedLightIds = lightIds
                selectedGroupIds = groupIds
            }

            // Restore sunrise configuration
            rule.sunrise?.let { sunrise ->
                sunriseEnabled = sunrise.enabled
                sunriseDurationMinutes = sunrise.durationMinutes
                sunriseStartKelvin = sunrise.startKelvin
                sunriseEndKelvin = sunrise.endKelvin
                sunriseEndBrightness = sunrise.endBrightness
                sunriseStartBeforeAlarm = sunrise.startBeforeAlarm
            }
        }
    }

    // Clear editing rule when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            hueViewModel.clearEditingRule()
        }
    }

    // Get available shift patterns from ShiftConfig
    val availableShiftPatterns = remember(shiftState.currentShiftConfig) {
        shiftState.currentShiftConfig?.definitions?.filter { it.isEnabled }?.map { it.name } ?: emptyList()
    }

    // Builds the light actions for the current form state. A sunrise rule only needs its
    // targets (color/brightness come from the SunriseConfig); otherwise the selected color
    // mode (white temperature or preset color) is baked into each action.
    //
    // UX FIX (D): auto-off is now independent of sunriseEnabled - a sunrise rule always ends
    // up "on" (its ramp reaches the configured end brightness/temperature), so it can equally
    // be auto-switched-off again afterwards. Only color/brightness stay sunrise-exclusive
    // (the SunriseConfig owns those), the `duration` field does not.
    //
    // ZIELNAME WIRD MITGESCHRIEBEN: `targetId` ist eine BRIDGE-LOKALE Nummer. Sie steht so in der
    // Exportdatei und im Android-Backup und zeigt auf einer anderen Bridge ins Leere. Der Name ist
    // der einzige Anker, an dem sich eine Aktion dort wiederfinden laesst
    // (`HueTargetReconciler`). Der Rueckfall auf den bereits gespeicherten Namen ist kein Beiwerk:
    // Beim Bearbeiten einer Regel, deren IDs auf DIESER Bridge unbekannt sind, liefert die
    // Bridge-Liste keinen Namen - ein blosses `bridgeName` wuerde beim Speichern genau den Anker
    // loeschen, der die Regel noch retten kann.
    fun buildActions(): List<HueLightAction> {
        val lightNames = uiState.lightTargets.lights.associate { it.id to it.name }
        val groupNames = uiState.lightTargets.groups.associate { it.id to it.name }
        val storedNames = uiState.editingRule?.lightActions
            ?.mapNotNull { action -> action.targetName?.let { action.targetId to it } }
            ?.toMap()
            .orEmpty()

        val colorTemp: Int? = if (!sunriseEnabled && targetOn && colorMode == ColorMode.WHITE) {
            HueColorConverter.kelvinToHueMireds(colorKelvin)
        } else null
        val presetColor = if (!sunriseEnabled && targetOn && colorMode == ColorMode.COLOR) {
            HueColorConverter.getPresetColor(colorPreset)
        } else null
        val effectiveOn = sunriseEnabled || targetOn
        val autoOffDuration = if (effectiveOn && autoOffEnabled) autoOffMinutes else null

        val actions = mutableListOf<HueLightAction>()
        fun addAction(id: String, type: TargetType, isGroup: Boolean) {
            actions.add(
                HueLightAction(
                    targetType = type,
                    targetId = id,
                    targetName = (if (isGroup) groupNames[id] else lightNames[id]) ?: storedNames[id],
                    actionType = if (effectiveOn) ActionType.TURN_ON else ActionType.TURN_OFF,
                    on = if (sunriseEnabled) true else targetOn,
                    brightness = if (sunriseEnabled || !targetOn) null else targetBrightness,
                    hue = presetColor?.hue,
                    saturation = presetColor?.saturation,
                    colorTemperature = colorTemp,
                    duration = autoOffDuration,
                    isGroup = isGroup
                )
            )
        }
        selectedLightIds.forEach { addAction(it, TargetType.LIGHT, false) }
        selectedGroupIds.forEach { addAction(it, TargetType.GROUP, true) }
        return actions
    }

    fun buildRule(id: String): HueScheduleRule {
        val timeRange = HueTimeRange(actions = buildActions())
        return HueScheduleRule(
            id = id,
            name = ruleName,
            shiftPattern = selectedShiftPattern,
            enabled = isEnabled,
            timeRanges = listOf(timeRange),
            sunrise = if (sunriseEnabled) {
                SunriseConfig(
                    enabled = true,
                    durationMinutes = sunriseDurationMinutes,
                    startKelvin = sunriseStartKelvin,
                    endKelvin = sunriseEndKelvin,
                    endBrightness = sunriseEndBrightness,
                    startBeforeAlarm = sunriseStartBeforeAlarm
                )
            } else null
        )
    }

    // Der Regeltest schaltet echte Lampen und braucht deshalb ab Android 17 den lokalen
    // Netzwerkzugriff. Ohne dieses Tor scheiterte er mit einer generischen Netzwerkmeldung, ohne
    // dass je der Systemdialog erschien. Die Testregel wird bewusst ERST HIER gebaut, aus dem
    // aktuellen Formularzustand: Der ist rememberSaveable, ueberlebt also den Activity-Neuaufbau
    // waehrend des Dialogs - eine vorher gebaute Regel waere danach weg.
    val gate = rememberLocalNetworkPermissionGate<HueRuleConfigNetzAktion>(
        onMessage = { hueViewModel.setError(it) }
    ) { _, _ ->
        val testRule = buildRule("test_${System.currentTimeMillis()}")
            .copy(name = ruleName.ifBlank { "Test-Regel" })
        hueViewModel.testRuleExecution(testRule)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ruleId != null) "Regel bearbeiten" else "Neue Regel",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (validateForm(ruleName, selectedShiftPattern, selectedLightIds, selectedGroupIds)) {
                                val rule = buildRule(ruleId ?: HueScheduleRule.generateId())

                                if (ruleId != null) {
                                    hueViewModel.updateRule(rule)
                                } else {
                                    hueViewModel.createRule(rule)
                                }
                                onSaveComplete()
                            } else {
                                showValidationErrors = true
                            }
                        }
                    ) {
                        Text("Speichern", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.error?.let { error ->
                item {
                    ErrorMessage(message = error, onDismiss = { hueViewModel.clearError() })
                }
            }

            item {
                RuleBasicInfoCard(
                    ruleName = ruleName,
                    onRuleNameChange = { ruleName = it },
                    isEnabled = isEnabled,
                    onEnabledChange = { isEnabled = it },
                    showValidationErrors = showValidationErrors
                )
            }

            item {
                ShiftPatternCard(
                    selectedShiftPattern = selectedShiftPattern,
                    onShiftPatternChange = { selectedShiftPattern = it },
                    availableShiftPatterns = availableShiftPatterns,
                    showValidationErrors = showValidationErrors
                )
            }

            item {
                TargetSelectionCard(
                    lightTargets = uiState.lightTargets,
                    selectedLightIds = selectedLightIds,
                    selectedGroupIds = selectedGroupIds,
                    onLightSelectionChange = { selectedLightIds = it },
                    onGroupSelectionChange = { selectedGroupIds = it },
                    onRefreshTargets = { hueViewModel.refreshLightTargets() },
                    showValidationErrors = showValidationErrors,
                    // Nur die Ziele DIESER Regel. Bei einer neuen Regel gibt es keine.
                    unresolvedTargets = if (ruleId == null) emptyList()
                    else uiState.unresolvedTargets.filter { it.ruleId == ruleId }
                )
            }

            // Manuelle Einschalt-Config und Sunrise-Lichtwecker sind zwei gleichrangige, sich
            // gegenseitig ausschliessende Wege, wie die Regel das Licht ansteuert - je eine eigene
            // Karte, kein Oberbegriff darueber. Ist Sunrise an, ueberschreibt er Farbe/Helligkeit
            // ohnehin; dann blenden wir die manuelle Karte ganz aus, statt sie zu einer blossen
            // Hinweiskarte zu entkernen.
            if (!sunriseEnabled) {
                item {
                    ActionConfigCard(
                        targetOn = targetOn,
                        targetBrightness = targetBrightness,
                        colorMode = colorMode,
                        colorKelvin = colorKelvin,
                        colorPreset = colorPreset,
                        onTargetOnChange = { targetOn = it },
                        onTargetBrightnessChange = { targetBrightness = it },
                        onColorModeChange = { colorMode = it },
                        onColorKelvinChange = { colorKelvin = it },
                        onColorPresetChange = { colorPreset = it }
                    )
                }
            }

            item {
                SunriseConfigCard(
                    enabled = sunriseEnabled,
                    durationMinutes = sunriseDurationMinutes,
                    startKelvin = sunriseStartKelvin,
                    endKelvin = sunriseEndKelvin,
                    endBrightness = sunriseEndBrightness,
                    startBeforeAlarm = sunriseStartBeforeAlarm,
                    onEnabledChange = { sunriseEnabled = it },
                    onDurationChange = { sunriseDurationMinutes = it },
                    onStartKelvinChange = { sunriseStartKelvin = it },
                    onEndKelvinChange = { sunriseEndKelvin = it },
                    onEndBrightnessChange = { sunriseEndBrightness = it },
                    onStartBeforeAlarmChange = { sunriseStartBeforeAlarm = it }
                )
            }

            // Auto-Aus ist ein querschnittliches Verhalten: es betrifft, was auch immer die
            // Lichter angeschaltet hat - die manuelle Aktion ODER den Sunrise. Deshalb eine
            // eigene Karte hinter beiden, nicht in "Aktionskonfiguration" eingebettet (das las
            // sich, als gehoerte es nur zum manuellen Einschalten). Nur sichtbar, wenn die Regel
            // die Lichter ueberhaupt anschaltet - dieselbe Bedingung wie `effectiveOn` in
            // buildActions(); bei einer reinen Ausschalt-Regel gibt es nichts nachzuschalten.
            if (sunriseEnabled || targetOn) {
                item {
                    AutoOffCard(
                        autoOffEnabled = autoOffEnabled,
                        autoOffMinutes = autoOffMinutes,
                        sunriseActive = sunriseEnabled,
                        onAutoOffEnabledChange = { autoOffEnabled = it },
                        onAutoOffMinutesChange = { autoOffMinutes = it }
                    )
                }
            }

            item {
                RulePreviewCard(
                    ruleName = ruleName,
                    selectedShiftPattern = selectedShiftPattern,
                    selectedLightIds = selectedLightIds,
                    selectedGroupIds = selectedGroupIds,
                    targetOn = targetOn,
                    targetBrightness = targetBrightness,
                    isEnabled = isEnabled,
                    colorMode = colorMode,
                    colorKelvin = colorKelvin,
                    colorPreset = colorPreset,
                    autoOffEnabled = autoOffEnabled,
                    autoOffMinutes = autoOffMinutes,
                    sunriseEnabled = sunriseEnabled,
                    sunriseDurationMinutes = sunriseDurationMinutes,
                    sunriseStartBeforeAlarm = sunriseStartBeforeAlarm,
                    onTestRule = { gate(HueRuleConfigNetzAktion.RULE_TEST) }
                )
            }
        }

        if (uiState.isLoading) {
            LoadingScreen()
        }
    }
}

private fun validateForm(
    ruleName: String,
    selectedShiftPattern: String,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>
): Boolean {
    return ruleName.isNotBlank() &&
            selectedShiftPattern.isNotBlank() &&
            (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty())
}
