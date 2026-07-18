package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel

/**
 * Hue Settings Screen - Bridge and Rules Management
 *
 * HILT MIGRATION: Now receives HueViewModel directly instead of ViewModelFactory
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueSettingsScreen(
    hueViewModel: HueViewModel,
    onNavigateBack: () -> Unit,
    onEditRule: (String) -> Unit,
    onCreateNewRule: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by hueViewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        hueViewModel.refreshRules()
        hueViewModel.refreshLightTargets()
    }

    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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

            // Banner entfernt - siehe HueTabContent: die BridgeStatusCard direkt darunter sagt
            // dasselbe (rote Karte, Fehler-Icon, "Nicht verbunden"). Die Folge steht jetzt dort.

            item {
                BridgeStatusCard(
                    connectionInfo = uiState.bridgeConnectionInfo,
                    onValidate = { hueViewModel.validateBridgeConnection() },
                    onTest = { hueViewModel.runLightTest() },
                    onForgetBridge = { hueViewModel.forgetBridge() }
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
    onTest: () -> Unit,
    onForgetBridge: () -> Unit
) {
    // UX FEATURE (B): confirmation dialog before actually disconnecting/forgetting the bridge.
    var showForgetDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connectionInfo?.isConnected == true)
                MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    tint = if (connectionInfo?.isConnected == true) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Philips Hue Bridge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (connectionInfo?.isConnected == true) "Verbunden mit ${connectionInfo.bridgeIp}" else "Nicht verbunden",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (connectionInfo?.isConnected != true) {
                        Text(
                            "Lichtaktionen für Alarme könnten ausfallen.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactOutlinedButton(
                    onClick = onValidate,
                    text = "Prüfen",
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f)
                )
                CompactOutlinedButton(
                    onClick = onTest,
                    text = "Test",
                    icon = Icons.Default.FlashOn,
                    modifier = Modifier.weight(1f)
                )
            }

            // UX FEATURE (B): "Verbindung trennen / Bridge vergessen". Only offered once a
            // bridge was actually paired (bridgeIp present) - nothing to forget otherwise.
            if (connectionInfo?.bridgeIp != null) {
                OutlinedButton(
                    onClick = { showForgetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Verbindung trennen / Bridge vergessen")
                }
            }
        }
    }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text("Bridge vergessen?") },
            text = {
                Text(
                    "Die Verbindung zur Hue-Bridge wird getrennt. Um die App wieder mit der " +
                        "Bridge zu nutzen, muss sie erneut gekoppelt werden (Link-Taste drücken). " +
                        "Ihre gespeicherten Regeln bleiben erhalten."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetDialog = false
                        onForgetBridge()
                    }
                ) {
                    Text("Trennen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
            
            // Hier war "Bearbeiten" zu "Bea/rbei/ten" zerfallen: zwei weight(1f)-Buttons plus
            // IconButton lassen je ~116dp, davon gehen 48dp allein für den Material3-Innenabstand
            // ab. CompactOutlinedButton nimmt den zurück und lässt nur eine Zeile zu.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactOutlinedButton(
                    onClick = onEdit,
                    text = "Bearbeiten",
                    icon = Icons.Default.Edit,
                    modifier = Modifier.weight(1f)
                )
                CompactOutlinedButton(
                    onClick = onTest,
                    text = "Test",
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
