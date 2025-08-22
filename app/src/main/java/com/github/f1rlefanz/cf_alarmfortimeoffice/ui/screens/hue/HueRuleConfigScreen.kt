package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ViewModelFactory
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Hue Regel-Konfiguration Screen - Deutsche Version
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueRuleConfigScreen(
    ruleId: String?,
    viewModelFactory: ViewModelFactory,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hueViewModel: HueViewModel = viewModel(factory = viewModelFactory)
    val shiftViewModel: com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel = viewModel(factory = viewModelFactory)
    val uiState by hueViewModel.uiState.collectAsState()
    val shiftState by shiftViewModel.uiState.collectAsState()
    
    // Form state
    var ruleName by remember { mutableStateOf("") }
    var selectedShiftPattern by remember { mutableStateOf("") }
    var timeOffsetMinutes by remember { mutableIntStateOf(0) }
    var selectedLightIds by remember { mutableStateOf(setOf<String>()) }
    var selectedGroupIds by remember { mutableStateOf(setOf<String>()) }
    var targetOn by remember { mutableStateOf(true) }
    var targetBrightness by remember { mutableIntStateOf(128) }
    var isEnabled by remember { mutableStateOf(true) }
    
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
                timeOffsetMinutes = firstTimeRange.offsetMinutes
                
                val firstAction = firstTimeRange.actions.firstOrNull()
                if (firstAction != null) {
                    targetOn = firstAction.on ?: true
                    targetBrightness = firstAction.brightness ?: 128
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
                                val actions = mutableListOf<HueLightAction>()
                                
                                selectedLightIds.forEach { lightId ->
                                    actions.add(
                                        HueLightAction(
                                            targetType = TargetType.LIGHT,
                                            targetId = lightId,
                                            actionType = if (targetOn) ActionType.TURN_ON else ActionType.TURN_OFF,
                                            on = targetOn,
                                            brightness = if (targetOn) targetBrightness else null
                                        )
                                    )
                                }
                                
                                selectedGroupIds.forEach { groupId ->
                                    actions.add(
                                        HueLightAction(
                                            targetType = TargetType.GROUP,
                                            targetId = groupId,
                                            actionType = if (targetOn) ActionType.TURN_ON else ActionType.TURN_OFF,
                                            on = targetOn,
                                            brightness = if (targetOn) targetBrightness else null,
                                            isGroup = true
                                        )
                                    )
                                }
                                
                                val timeRange = HueTimeRange(
                                    startTime = "00:00",
                                    endTime = "23:59",
                                    relativeTo = TimeReference.ALARM_TIME,
                                    offsetMinutes = timeOffsetMinutes,
                                    actions = actions
                                )
                                
                                val rule = HueScheduleRule(
                                    id = ruleId ?: HueScheduleRule.generateId(),
                                    name = ruleName,
                                    shiftPattern = selectedShiftPattern,
                                    enabled = isEnabled,
                                    timeRanges = listOf(timeRange)
                                )
                                
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
                TimeConfigCard(
                    timeOffsetMinutes = timeOffsetMinutes,
                    onTimeOffsetChange = { timeOffsetMinutes = it }
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
                    targetOn = targetOn,
                    targetBrightness = targetBrightness,
                    onTargetOnChange = { targetOn = it },
                    onTargetBrightnessChange = { targetBrightness = it }
                )
            }
            
            item {
                RulePreviewCard(
                    ruleName = ruleName,
                    selectedShiftPattern = selectedShiftPattern,
                    timeOffsetMinutes = timeOffsetMinutes,
                    selectedLightIds = selectedLightIds,
                    selectedGroupIds = selectedGroupIds,
                    targetOn = targetOn,
                    targetBrightness = targetBrightness,
                    isEnabled = isEnabled,
                    onTestRule = {
                        val actions = mutableListOf<HueLightAction>()
                        
                        selectedLightIds.forEach { lightId ->
                            actions.add(
                                HueLightAction(
                                    targetType = TargetType.LIGHT,
                                    targetId = lightId,
                                    actionType = if (targetOn) ActionType.TURN_ON else ActionType.TURN_OFF,
                                    on = targetOn,
                                    brightness = if (targetOn) targetBrightness else null
                                )
                            )
                        }
                        
                        selectedGroupIds.forEach { groupId ->
                            actions.add(
                                HueLightAction(
                                    targetType = TargetType.GROUP,
                                    targetId = groupId,
                                    actionType = if (targetOn) ActionType.TURN_ON else ActionType.TURN_OFF,
                                    on = targetOn,
                                    brightness = if (targetOn) targetBrightness else null,
                                    isGroup = true
                                )
                            )
                        }
                        
                        val timeRange = HueTimeRange(
                            startTime = "00:00",
                            endTime = "23:59",
                            relativeTo = TimeReference.ALARM_TIME,
                            offsetMinutes = timeOffsetMinutes,
                            actions = actions
                        )
                        
                        val testRule = HueScheduleRule(
                            id = "test_${System.currentTimeMillis()}",
                            name = ruleName.ifBlank { "Test-Regel" },
                            shiftPattern = selectedShiftPattern,
                            enabled = true,
                            timeRanges = listOf(timeRange)
                        )
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
private fun TimeConfigCard(
    timeOffsetMinutes: Int,
    onTimeOffsetChange: (Int) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Zeitkonfiguration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Wann soll diese Regel relativ zu Ihrer Weckzeit ausgeführt werden?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Zeitversatz: ${if (timeOffsetMinutes >= 0) "+" else ""}$timeOffsetMinutes Minuten",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                
                Slider(
                    value = timeOffsetMinutes.toFloat(),
                    onValueChange = { onTimeOffsetChange(it.toInt()) },
                    valueRange = -60f..60f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("60 Min früher", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("60 Min später", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Text(
                    when {
                        timeOffsetMinutes < 0 -> "Ausführung ${-timeOffsetMinutes} Minuten vor Weckzeit"
                        timeOffsetMinutes > 0 -> "Ausführung $timeOffsetMinutes Minuten nach Weckzeit"
                        else -> "Ausführung genau zur Weckzeit"
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
            
            TabRow(selectedTabIndex = selectedTab) {
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

@Composable
private fun ActionConfigCard(
    targetOn: Boolean,
    targetBrightness: Int,
    onTargetOnChange: (Boolean) -> Unit,
    onTargetBrightnessChange: (Int) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Aktionskonfiguration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            }
        }
    }
}

@Composable
private fun RulePreviewCard(
    ruleName: String,
    selectedShiftPattern: String,
    timeOffsetMinutes: Int,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    targetOn: Boolean,
    targetBrightness: Int,
    isEnabled: Boolean,
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
            
            if (timeOffsetMinutes != 0 || selectedShiftPattern.isNotBlank()) {
                val timeText = when {
                    timeOffsetMinutes < 0 -> "${-timeOffsetMinutes} Minuten vor Weckzeit"
                    timeOffsetMinutes > 0 -> "$timeOffsetMinutes Minuten nach Weckzeit"
                    else -> "genau zur Weckzeit"
                }
                Text("Ausführung $timeText", style = MaterialTheme.typography.bodyMedium)
            }
            
            if (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty()) {
                Text(
                    "${if (targetOn) "Einschalten" else "Ausschalten"} von ${selectedLightIds.size} Lichtern und ${selectedGroupIds.size} Gruppen${if (targetOn) " mit ${(targetBrightness * 100 / 254)}% Helligkeit" else ""}",
                    style = MaterialTheme.typography.bodyMedium
                )
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
