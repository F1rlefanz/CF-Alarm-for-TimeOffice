package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftEditDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftConfigScreen(
    shiftViewModel: ShiftViewModel,
    onNavigateBack: () -> Unit
) {
    val shiftState by shiftViewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingDefinition by remember { mutableStateOf<ShiftDefinition?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schicht-Konfiguration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "Zurück"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Schicht hinzufügen"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            // Auto-Alarm Switch
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Automatische Alarme",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Alarme automatisch für erkannte Schichten setzen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = shiftState.currentShiftConfig?.autoAlarmEnabled ?: false,
                        onCheckedChange = { enabled ->
                            shiftState.currentShiftConfig?.let { config ->
                                shiftViewModel.updateShiftConfig(
                                    config.copy(autoAlarmEnabled = enabled)
                                )
                            }
                        }
                    )
                }
            }

            // Schichttypen
            Text(
                "Schichttypen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (shiftState.currentShiftConfig?.definitions?.isEmpty() == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(SpacingConstants.ICON_SIZE_XXL),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
                        Text(
                            "Keine Schichttypen definiert",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Füge Schichttypen hinzu, um die automatische Erkennung zu aktivieren",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    items(
                        shiftState.currentShiftConfig?.definitions ?: emptyList(),
                        key = { it.name }
                    ) { definition ->
                        ShiftDefinitionCard(
                            definition = definition,
                            onEdit = { editingDefinition = definition },
                            onDelete = {
                                shiftState.currentShiftConfig?.let { config ->
                                    shiftViewModel.updateShiftConfig(
                                        config.copy(
                                            definitions = config.definitions - definition
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }

            // Reset Button
            Spacer(modifier = Modifier.weight(1f))
            
            OutlinedButton(
                onClick = {
                    // Reset to defaults
                    shiftViewModel.updateShiftConfig(ShiftConfig.getDefaultConfig())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text("Auf Standardwerte zurücksetzen")
            }
        }
    }

    // Add/Edit Dialog
    if (showAddDialog || editingDefinition != null) {
        ShiftEditDialog(
            shift = editingDefinition,
            onSave = { newDefinition ->
                shiftState.currentShiftConfig?.let { config ->
                    val updatedDefinitions = if (editingDefinition != null) {
                        config.definitions.map { 
                            if (it.name == editingDefinition?.name) newDefinition else it 
                        }
                    } else {
                        config.definitions + newDefinition
                    }
                    shiftViewModel.updateShiftConfig(
                        config.copy(definitions = updatedDefinitions)
                    )
                }
                showAddDialog = false
                editingDefinition = null
            },
            onDismiss = {
                showAddDialog = false
                editingDefinition = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShiftDefinitionCard(
    definition: ShiftDefinition,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    definition.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Muster: ${definition.keywords.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Alarm: ${definition.getAlarmTimeFormatted()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Löschen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}


