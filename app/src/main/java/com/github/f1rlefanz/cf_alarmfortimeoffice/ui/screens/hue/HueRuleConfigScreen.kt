package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TimeReference
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.launch

/**
 * Hue Regel-Konfiguration Screen - Deutsche Version
 *
 * HILT MIGRATION: Now receives HueViewModel and ShiftViewModel directly instead of ViewModelFactory
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
    val uiState by hueViewModel.uiState.collectAsState()
    val shiftState by shiftViewModel.uiState.collectAsState()
    val toastContext = LocalContext.current

    // Surfaces one-shot messages from the ViewModel (e.g. the "Auto-Aus verkürzt" hint after
    // "Regel testen") as a Toast.
    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Form state
    var ruleName by remember { mutableStateOf("") }
    var selectedShiftPattern by remember { mutableStateOf("") }
    var selectedLightIds by remember { mutableStateOf(setOf<String>()) }
    var selectedGroupIds by remember { mutableStateOf(setOf<String>()) }
    var targetOn by remember { mutableStateOf(true) }
    var targetBrightness by remember { mutableIntStateOf(128) }
    var isEnabled by remember { mutableStateOf(true) }

    // Color state
    var colorMode by remember { mutableStateOf(ColorMode.NONE) }
    var colorKelvin by remember { mutableIntStateOf(2700) }
    var colorPreset by remember { mutableStateOf(HueColorConverter.ColorPreset.RED) }

    // Auto-off state (turn the lights off again after N minutes)
    var autoOffEnabled by remember { mutableStateOf(false) }
    var autoOffMinutes by remember { mutableIntStateOf(30) }

    // Sunrise state
    var sunriseEnabled by remember { mutableStateOf(false) }
    var sunriseDurationMinutes by remember { mutableIntStateOf(15) }
    var sunriseStartKelvin by remember { mutableIntStateOf(2000) }
    var sunriseEndKelvin by remember { mutableIntStateOf(4000) }
    var sunriseEndBrightness by remember { mutableIntStateOf(254) }
    var sunriseStartBeforeAlarm by remember { mutableStateOf(true) }

    // Validation state
    var showValidationErrors by remember { mutableStateOf(false) }
    
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
    fun buildActions(): List<HueLightAction> {
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
        val timeRange = HueTimeRange(
            startTime = "00:00",
            endTime = "23:59",
            relativeTo = TimeReference.ALARM_TIME,
            actions = buildActions()
        )
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
                    showValidationErrors = showValidationErrors
                )
            }
            
            item {
                ActionConfigCard(
                    colorConfigEnabled = !sunriseEnabled,
                    targetOn = targetOn,
                    targetBrightness = targetBrightness,
                    colorMode = colorMode,
                    colorKelvin = colorKelvin,
                    colorPreset = colorPreset,
                    autoOffEnabled = autoOffEnabled,
                    autoOffMinutes = autoOffMinutes,
                    onTargetOnChange = { targetOn = it },
                    onTargetBrightnessChange = { targetBrightness = it },
                    onColorModeChange = { colorMode = it },
                    onColorKelvinChange = { colorKelvin = it },
                    onColorPresetChange = { colorPreset = it },
                    onAutoOffEnabledChange = { autoOffEnabled = it },
                    onAutoOffMinutesChange = { autoOffMinutes = it }
                )
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
                    onTestRule = {
                        val testRule = buildRule("test_${System.currentTimeMillis()}")
                            .copy(name = ruleName.ifBlank { "Test-Regel" })
                        hueViewModel.testRuleExecution(testRule)
                    }
                )
            }
        }
        
        if (uiState.isLoading) {
            LoadingScreen()
        }
    }
}

@Composable
private fun RuleBasicInfoCard(
    ruleName: String,
    onRuleNameChange: (String) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    showValidationErrors: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Regel-Informationen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = ruleName,
                onValueChange = onRuleNameChange,
                label = { Text("Regel-Name") },
                placeholder = { Text("z.B. Morgenlicht Frühschicht") },
                modifier = Modifier.fillMaxWidth(),
                isError = showValidationErrors && ruleName.isBlank(),
                supportingText = {
                    if (showValidationErrors && ruleName.isBlank()) {
                        Text("Regel-Name ist erforderlich", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Regel aktivieren", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "Regel wird automatisch ausgeführt, wenn Bedingungen erfüllt sind",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = isEnabled, onCheckedChange = onEnabledChange)
            }
        }
    }
}

@Composable
private fun ShiftPatternCard(
    selectedShiftPattern: String,
    onShiftPatternChange: (String) -> Unit,
    availableShiftPatterns: List<String>,
    showValidationErrors: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Schichtmuster", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Wählen Sie aus, für welches Schichtmuster diese Regel gilt:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (availableShiftPatterns.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            "Keine Schichtmuster konfiguriert",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Bitte konfigurieren Sie zunächst Ihre Schichtmuster in den Einstellungen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.selectableGroup()) {
                    availableShiftPatterns.forEach { pattern ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedShiftPattern == pattern,
                                onClick = { onShiftPatternChange(pattern) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(pattern, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            
            if (showValidationErrors && selectedShiftPattern.isBlank()) {
                Text(
                    "Bitte wählen Sie ein Schichtmuster aus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun TargetSelectionCard(
    lightTargets: com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    onLightSelectionChange: (Set<String>) -> Unit,
    onGroupSelectionChange: (Set<String>) -> Unit,
    onRefreshTargets: () -> Unit,
    showValidationErrors: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zielauswahl", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefreshTargets) {
                    Icon(Icons.Default.Refresh, "Lichter aktualisieren")
                }
            }
            
            Text(
                "Wählen Sie aus, welche Lichter oder Gruppen diese Regel steuern soll:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            var selectedTab by remember { mutableIntStateOf(0) }
            
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Gruppen (${lightTargets.groups.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Lichter (${lightTargets.lights.size})") }
                )
            }
            
            when (selectedTab) {
                0 -> {
                    if (lightTargets.groups.isEmpty()) {
                        Text(
                            "Keine Gruppen gefunden. Erstellen Sie zunächst Gruppen in der Hue-App.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        lightTargets.groups.forEach { group ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedGroupIds.contains(group.id),
                                    onCheckedChange = { isChecked ->
                                        onGroupSelectionChange(
                                            if (isChecked) selectedGroupIds + group.id else selectedGroupIds - group.id
                                        )
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(group.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Gruppe • ${if (group.state.any_on) "An" else "Aus"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (lightTargets.lights.isEmpty()) {
                        Text(
                            "Keine Lichter gefunden. Stellen Sie sicher, dass Ihre Bridge verbunden ist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        lightTargets.lights.forEach { light ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedLightIds.contains(light.id),
                                    onCheckedChange = { isChecked ->
                                        onLightSelectionChange(
                                            if (isChecked) selectedLightIds + light.id else selectedLightIds - light.id
                                        )
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(light.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Licht • ${if (light.state.on) "An" else "Aus"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            if (showValidationErrors && selectedLightIds.isEmpty() && selectedGroupIds.isEmpty()) {
                Text(
                    "Bitte wählen Sie mindestens ein Licht oder eine Gruppe aus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            if (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty()) {
                Text(
                    "Ausgewählt: ${selectedLightIds.size} Lichter, ${selectedGroupIds.size} Gruppen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionConfigCard(
    // UX FIX (D): renamed from "enabled" for clarity - this only gates the manual
    // color/brightness/on-off config, which the Sunrise ramp takes over when active. Auto-off
    // is now independent of this flag (see AutoOffSection below), so it's offered either way.
    colorConfigEnabled: Boolean,
    targetOn: Boolean,
    targetBrightness: Int,
    colorMode: ColorMode,
    colorKelvin: Int,
    colorPreset: HueColorConverter.ColorPreset,
    autoOffEnabled: Boolean,
    autoOffMinutes: Int,
    onTargetOnChange: (Boolean) -> Unit,
    onTargetBrightnessChange: (Int) -> Unit,
    onColorModeChange: (ColorMode) -> Unit,
    onColorKelvinChange: (Int) -> Unit,
    onColorPresetChange: (HueColorConverter.ColorPreset) -> Unit,
    onAutoOffEnabledChange: (Boolean) -> Unit,
    onAutoOffMinutesChange: (Int) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Aktionskonfiguration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (!colorConfigEnabled) {
                Text(
                    "Der Sunrise-Lichtwecker steuert Farbe und Helligkeit dieser Regel. Deaktivieren Sie ihn unten, um hier manuell zu konfigurieren.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // UX FIX (D): auto-off still applies to a sunrise rule (it turns the lights
                // back off some minutes after the ramp finishes), so it's offered here too.
                AutoOffSection(
                    autoOffEnabled = autoOffEnabled,
                    autoOffMinutes = autoOffMinutes,
                    onAutoOffEnabledChange = onAutoOffEnabledChange,
                    onAutoOffMinutesChange = onAutoOffMinutesChange
                )
                return@Column
            }

            Text(
                "Konfigurieren Sie, was passieren soll, wenn diese Regel ausgeführt wird:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (targetOn) "Einschalten" else "Ausschalten",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Lichter ${if (targetOn) "einschalten" else "ausschalten"} bei Regelausführung",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = targetOn, onCheckedChange = onTargetOnChange)
            }

            if (targetOn) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Helligkeit: ${(targetBrightness * 100 / 254)}%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )

                    Slider(
                        value = targetBrightness.toFloat(),
                        onValueChange = { onTargetBrightnessChange(it.toInt()) },
                        valueRange = 1f..254f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("100%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Color mode selector
                Text("Farbe", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = colorMode == ColorMode.NONE,
                        onClick = { onColorModeChange(ColorMode.NONE) },
                        label = { Text("Standard") }
                    )
                    FilterChip(
                        selected = colorMode == ColorMode.WHITE,
                        onClick = { onColorModeChange(ColorMode.WHITE) },
                        label = { Text("Weißton") }
                    )
                    FilterChip(
                        selected = colorMode == ColorMode.COLOR,
                        onClick = { onColorModeChange(ColorMode.COLOR) },
                        label = { Text("Farbe") }
                    )
                }

                when (colorMode) {
                    ColorMode.WHITE -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ColorSwatch(previewColorForKelvin(colorKelvin))
                            Text("Farbtemperatur: $colorKelvin K", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Slider(
                            value = colorKelvin.toFloat(),
                            onValueChange = { onColorKelvinChange(it.toInt()) },
                            valueRange = 2000f..6500f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Warm (2000K)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Kühl (6500K)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ColorMode.COLOR -> {
                        COLOR_PRESETS.chunked(4).forEach { rowPresets ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowPresets.forEach { preset ->
                                    FilterChip(
                                        selected = colorPreset == preset,
                                        onClick = { onColorPresetChange(preset) },
                                        leadingIcon = { ColorSwatch(previewColorForPreset(preset), size = 16) },
                                        label = { Text(presetLabel(preset)) }
                                    )
                                }
                            }
                        }
                    }
                    ColorMode.NONE -> { /* keep current bulb color */ }
                }

                AutoOffSection(
                    autoOffEnabled = autoOffEnabled,
                    autoOffMinutes = autoOffMinutes,
                    onAutoOffEnabledChange = onAutoOffEnabledChange,
                    onAutoOffMinutesChange = onAutoOffMinutesChange
                )
            }
        }
    }
}

/**
 * "Automatisch ausschalten" (auto-off) toggle + duration slider.
 *
 * UX FIX (D): extracted out of [ActionConfigCard]'s manual-config branch so it can also be
 * shown when a Sunrise ramp is active (a sunrise rule ends up "on" too, at its configured end
 * brightness/temperature, so switching it back off after N minutes is equally meaningful).
 */
@Composable
private fun AutoOffSection(
    autoOffEnabled: Boolean,
    autoOffMinutes: Int,
    onAutoOffEnabledChange: (Boolean) -> Unit,
    onAutoOffMinutesChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(240.dp)) {
            Text("Automatisch ausschalten", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                "Lichter nach einer Weile wieder ausschalten",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = autoOffEnabled, onCheckedChange = onAutoOffEnabledChange)
    }
    if (autoOffEnabled) {
        Text(
            "Ausschalten nach: $autoOffMinutes Minuten",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Slider(
            value = autoOffMinutes.toFloat(),
            onValueChange = { onAutoOffMinutesChange(it.toInt().coerceIn(1, 120)) },
            valueRange = 1f..120f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SunriseConfigCard(
    enabled: Boolean,
    durationMinutes: Int,
    startKelvin: Int,
    endKelvin: Int,
    endBrightness: Int,
    startBeforeAlarm: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onStartKelvinChange: (Int) -> Unit,
    onEndKelvinChange: (Int) -> Unit,
    onEndBrightnessChange: (Int) -> Unit,
    onStartBeforeAlarmChange: (Boolean) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.width(260.dp)) {
                    Text("Sunrise-Lichtwecker", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Sanfter Sonnenaufgang: das Licht fährt von dim-warm auf hell-kühl hoch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                // Gradient preview from start to end temperature
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(previewColorForKelvin(startKelvin), previewColorForKelvin(endKelvin))
                            )
                        )
                )

                Text("Dauer: $durationMinutes Minuten", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Slider(
                    value = durationMinutes.toFloat(),
                    onValueChange = { onDurationChange(it.toInt().coerceIn(1, 90)) },
                    valueRange = 1f..90f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorSwatch(previewColorForKelvin(startKelvin))
                    Text("Start: $startKelvin K (warm)", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = startKelvin.toFloat(),
                    onValueChange = { onStartKelvinChange(it.toInt()) },
                    valueRange = 2000f..6500f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorSwatch(previewColorForKelvin(endKelvin))
                    Text("Ziel: $endKelvin K", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = endKelvin.toFloat(),
                    onValueChange = { onEndKelvinChange(it.toInt()) },
                    valueRange = 2000f..6500f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Ziel-Helligkeit: ${endBrightness * 100 / 254}%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Slider(
                    value = endBrightness.toFloat(),
                    onValueChange = { onEndBrightnessChange(it.toInt()) },
                    valueRange = 1f..254f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Zeitpunkt", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = startBeforeAlarm,
                        onClick = { onStartBeforeAlarmChange(true) },
                        label = { Text("Vor dem Alarm") }
                    )
                    FilterChip(
                        selected = !startBeforeAlarm,
                        onClick = { onStartBeforeAlarmChange(false) },
                        label = { Text("Ab Alarmzeit") }
                    )
                }
                Text(
                    if (startBeforeAlarm) {
                        "Rampe endet zur Weckzeit (startet $durationMinutes Min früher)"
                    } else {
                        "Rampe startet zur Weckzeit"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun RulePreviewCard(
    ruleName: String,
    selectedShiftPattern: String,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    targetOn: Boolean,
    targetBrightness: Int,
    isEnabled: Boolean,
    colorMode: ColorMode,
    colorKelvin: Int,
    colorPreset: HueColorConverter.ColorPreset,
    autoOffEnabled: Boolean,
    autoOffMinutes: Int,
    sunriseEnabled: Boolean,
    sunriseDurationMinutes: Int,
    sunriseStartBeforeAlarm: Boolean,
    onTestRule: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeManager = remember { HueBridgeConnectionManager.getInstance(context) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Regel-Vorschau", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                Button(
                    onClick = {
                        // OPTIMIZATION: Manual health check before rule test
                        scope.launch {
                            bridgeManager.forceHealthCheck()
                        }
                        onTestRule()
                    },
                    enabled = ruleName.isNotBlank() && 
                            selectedShiftPattern.isNotBlank() && 
                            (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty())
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Regel testen")
                }
            }
            
            if (ruleName.isNotBlank()) {
                Text("\"$ruleName\"", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
            
            if (selectedShiftPattern.isNotBlank()) {
                Text("Bei $selectedShiftPattern-Schicht:", style = MaterialTheme.typography.bodyMedium)
            }
            
            if (selectedShiftPattern.isNotBlank()) {
                Text("Ausführung zur Weckzeit", style = MaterialTheme.typography.bodyMedium)
            }
            
            if (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty()) {
                if (sunriseEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            buildString {
                                append("🌅 Sunrise über $sunriseDurationMinutes Min an ${selectedLightIds.size} Lichtern und ${selectedGroupIds.size} Gruppen")
                                append(" (${if (sunriseStartBeforeAlarm) "vor dem Alarm" else "ab Alarmzeit"})")
                                // UX FIX (D): auto-off now also applies to sunrise rules.
                                if (autoOffEnabled) append(" · Auto-Aus nach $autoOffMinutes Min")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (colorMode) {
                            ColorMode.WHITE -> ColorSwatch(previewColorForKelvin(colorKelvin))
                            ColorMode.COLOR -> ColorSwatch(previewColorForPreset(colorPreset))
                            ColorMode.NONE -> {}
                        }
                        Text(
                            buildString {
                                append(if (targetOn) "Einschalten" else "Ausschalten")
                                append(" von ${selectedLightIds.size} Lichtern und ${selectedGroupIds.size} Gruppen")
                                if (targetOn) {
                                    append(" mit ${(targetBrightness * 100 / 254)}% Helligkeit")
                                    when (colorMode) {
                                        ColorMode.WHITE -> append(", $colorKelvin K")
                                        ColorMode.COLOR -> append(", ${presetLabel(colorPreset)}")
                                        ColorMode.NONE -> {}
                                    }
                                    if (autoOffEnabled) append(" · Auto-Aus nach $autoOffMinutes Min")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Text(
                "Status: ${if (isEnabled) "Aktiviert" else "Deaktiviert"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
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

/** Color mode for a Hue rule action: keep current color, set a white temperature, or a preset color. */
private enum class ColorMode { NONE, WHITE, COLOR }

/** Preset colors offered in COLOR mode (the whites live in WHITE/temperature mode). */
private val COLOR_PRESETS = listOf(
    HueColorConverter.ColorPreset.RED,
    HueColorConverter.ColorPreset.ORANGE,
    HueColorConverter.ColorPreset.YELLOW,
    HueColorConverter.ColorPreset.GREEN,
    HueColorConverter.ColorPreset.CYAN,
    HueColorConverter.ColorPreset.BLUE,
    HueColorConverter.ColorPreset.PURPLE,
    HueColorConverter.ColorPreset.PINK
)

private fun presetLabel(preset: HueColorConverter.ColorPreset): String = when (preset) {
    HueColorConverter.ColorPreset.WARM_WHITE -> "Warmweiß"
    HueColorConverter.ColorPreset.COOL_WHITE -> "Kaltweiß"
    HueColorConverter.ColorPreset.RED -> "Rot"
    HueColorConverter.ColorPreset.GREEN -> "Grün"
    HueColorConverter.ColorPreset.BLUE -> "Blau"
    HueColorConverter.ColorPreset.YELLOW -> "Gelb"
    HueColorConverter.ColorPreset.PURPLE -> "Lila"
    HueColorConverter.ColorPreset.ORANGE -> "Orange"
    HueColorConverter.ColorPreset.PINK -> "Pink"
    HueColorConverter.ColorPreset.CYAN -> "Türkis"
}

/**
 * Recovers the closest preset for a stored hue, so an edited COLOR rule round-trips back to
 * its chip. Stored COLOR values come from [COLOR_PRESETS], so the match is exact in practice.
 */
private fun nearestPreset(hue: Int): HueColorConverter.ColorPreset {
    return COLOR_PRESETS.minByOrNull { preset ->
        val presetHue = HueColorConverter.getPresetColor(preset).hue ?: 0
        val diff = kotlin.math.abs(presetHue - hue)
        minOf(diff, 65536 - diff) // circular hue distance
    } ?: HueColorConverter.ColorPreset.RED
}

/** Display color for a preset, via the Hue→RGB conversion utility. */
private fun previewColorForPreset(preset: HueColorConverter.ColorPreset): Color {
    val (r, g, b) = HueColorConverter.hueColorToRgb(HueColorConverter.getPresetColor(preset))
    return Color(r, g, b)
}

/** Approximate display color for a white color temperature (warm↔cool interpolation). */
private fun previewColorForKelvin(kelvin: Int): Color {
    val t = ((kelvin - 2000).toFloat() / (6500 - 2000)).coerceIn(0f, 1f)
    val r = (255 + (201 - 255) * t).toInt()
    val g = (166 + (226 - 166) * t).toInt()
    val b = (87 + (255 - 87) * t).toInt()
    return Color(r, g, b)
}

/** Small round color preview dot. */
@Composable
private fun ColorSwatch(color: Color, size: Int = 20) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}
