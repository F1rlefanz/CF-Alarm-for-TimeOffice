package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ViewModelFactory

/**
 * Hue Settings Screen - Bridge and Rules Management
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueSettingsScreen(
    viewModelFactory: ViewModelFactory,
    onNavigateBack: () -> Unit,
    onEditRule: (String) -> Unit,
    onCreateNewRule: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hueViewModel: HueViewModel = viewModel(factory = viewModelFactory)
    val uiState by hueViewModel.uiState.collectAsState()
    
    LaunchedEffect(Unit) {
        hueViewModel.refreshRules()
        hueViewModel.refreshLightTargets()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hue-Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = onCreateNewRule) {
                        Icon(Icons.Default.Add, "Neue Regel")
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
                    ErrorMessage(
                        message = error,
                        onDismiss = { hueViewModel.clearError() }
                    )
                }
            }
            
            item {
                BridgeStatusCard(
                    connectionInfo = uiState.bridgeConnectionInfo,
                    onValidate = { hueViewModel.validateBridgeConnection() },
                    onTest = { hueViewModel.refreshLightTargets() }
                )
            }
            
            item {
                StatsCard(
                    rulesCount = uiState.scheduleRules.size,
                    enabledCount = uiState.scheduleRules.count { it.enabled },
                    lightsCount = uiState.lightTargets.lights.size,
                    groupsCount = uiState.lightTargets.groups.size
                )
            }
            
            item {
                Text(
                    "Hue-Regeln verwalten",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (uiState.scheduleRules.isEmpty()) {
                item {
                    EmptyRulesCard(onCreateNewRule)
                }
            } else {
                items(uiState.scheduleRules) { rule ->
                    RuleCard(
                        rule = rule,
                        onEdit = { onEditRule(rule.id) },
                        onToggle = { hueViewModel.updateRule(rule.copy(enabled = !rule.enabled)) },
                        onDelete = { hueViewModel.deleteRule(rule.id) },
                        onTest = { hueViewModel.testRuleExecution(rule) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BridgeStatusCard(
    connectionInfo: com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo?,
    onValidate: () -> Unit,
    onTest: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connectionInfo?.isConnected == true) 
                MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (connectionInfo?.isConnected == true) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (connectionInfo?.isConnected == true) Color.Green else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Philips Hue Bridge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (connectionInfo?.isConnected == true) "Verbunden mit ${connectionInfo.bridgeIp}" else "Nicht verbunden",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onValidate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Prüfen")
                }
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.FlashOn, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test")
                }
            }
        }
    }
}

@Composable
private fun StatsCard(rulesCount: Int, enabledCount: Int, lightsCount: Int, groupsCount: Int) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Übersicht", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(Icons.Default.Schedule, "Regeln", "$enabledCount/$rulesCount", "aktiv")
                StatItem(Icons.Default.Lightbulb, "Lichter", lightsCount.toString(), "verfügbar")
                StatItem(Icons.Default.Group, "Gruppen", groupsCount.toString(), "verfügbar")
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    label: String, 
    value: String, 
    subtitle: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyRulesCard(onCreateNewRule: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Lightbulb, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Noch keine Regeln erstellt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Erstellen Sie Ihre erste Hue-Regel, um Ihre Beleuchtung automatisch zu steuern.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onCreateNewRule, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Erste Regel erstellen")
            }
        }
    }
}

@Composable
private fun RuleCard(
    rule: HueSchedule,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        )
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
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Schichtmuster: ${rule.shiftPattern}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${if (rule.enabled) "Aktiv" else "Deaktiviert"} • ${rule.timeRanges.size} Zeitbereich(e)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Bearbeiten")
                }
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Test")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
