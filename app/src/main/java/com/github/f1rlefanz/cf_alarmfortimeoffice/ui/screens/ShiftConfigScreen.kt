package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ShiftEditDialog
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftConfigScreen(
    shiftViewModel: ShiftViewModel,
    onNavigateBack: () -> Unit
) {
    val shiftState by shiftViewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingDefinition by remember { mutableStateOf<ShiftDefinition?>(null) }

    // Beide destruktiven Aktionen dieses Screens laufen ueber eine Rueckfrage. Ohne sie war ein
    // Fehlgriff nicht wiederherstellbar: `updateShiftConfig()` persistiert sofort UND ruft
    // `triggerAlarmCreationFromConfigUpdate()`, das die System-Alarme neu setzt bzw. den Alarm
    // der geloeschten Schicht cancelt. Es gibt kein Undo, und "Auf Standardwerte zuruecksetzen"
    // holt nur die Standardwerte zurueck, nicht die selbst getippten Muster/Weckzeiten.
    var pendingDelete by remember { mutableStateOf<ShiftDefinition?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Schicht-Konfiguration",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
            // Schichttypen
            Text(
                "Schichttypen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Die Standard-Muster sind die Kuerzel EINER Station. Wer auf einer anderen Station
            // arbeitet, wird ohne diesen Hinweis nicht erkannt und bekommt gar keinen Wecker -
            // sichtbar an genau der Stelle, an der man es aendert, statt es ihn erst nach dem
            // Verschlafen herausfinden zu lassen.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                    Text(
                        "Erkannt wird über die Muster, nicht über den Schichtnamen allein: ein " +
                            "Muster muss im Titel deines Kalendertermins als eigenes Wort " +
                            "vorkommen. Die Standardmuster (IMCF, IMCS, IMCN, IMCZ) sind die " +
                            "Kürzel einer bestimmten Station – arbeitest du auf einer anderen, " +
                            "trage hier deine eigenen Kürzel ein. Ohne passendes Muster wird " +
                            "keine Schicht erkannt und es klingelt kein Wecker.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (shiftState.currentShiftConfig?.definitions?.isEmpty() == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(SpacingConstants.ICON_SIZE_XXL),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))
                        Text(
                            "Keine Schichttypen definiert",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Füge Schichttypen hinzu, um die automatische Erkennung zu aktivieren",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    items(
                        shiftState.currentShiftConfig?.definitions ?: emptyList(),
                        key = { it.id }
                    ) { definition ->
                        ShiftDefinitionCard(
                            definition = definition,
                            onEdit = { editingDefinition = definition },
                            onDelete = { pendingDelete = definition }
                        )
                    }
                }
            }

            // Reset Button
            Spacer(modifier = Modifier.weight(1f))
            
            OutlinedButton(
                onClick = { showResetConfirmation = true },
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
                            if (it.id == editingDefinition?.id) newDefinition else it
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

    // Rueckfrage vor dem Loeschen einer Schicht: der Muelleimer sitzt am rechten Rand einer
    // Karte, deren restliche Flaeche "Bearbeiten" ist - ein Fehlgriff ist strukturell
    // wahrscheinlich, und er nimmt Muster, Weckzeit UND den bereits gesetzten Alarm mit.
    pendingDelete?.let { definition ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Schicht löschen?") },
            text = {
                Text(
                    "\"${definition.name}\" wird mit allen Mustern und der Weckzeit " +
                        "${definition.getAlarmTimeFormatted()} gelöscht. Der zugehörige Wecker " +
                        "entfällt damit sofort. Das lässt sich nicht rückgängig machen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        shiftState.currentShiftConfig?.let { config ->
                            shiftViewModel.updateShiftConfig(
                                config.copy(definitions = config.definitions - definition)
                            )
                        }
                        pendingDelete = null
                    }
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Rueckfrage vor dem Zuruecksetzen: der Knopf ist ganzbreit und sitzt am unteren Rand,
    // direkt dort, wo beim Scrollen getippt wird.
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Auf Standardwerte zurücksetzen?") },
            text = {
                Text(
                    "Alle Schichtdefinitionen werden durch die Standardwerte ersetzt – eigene " +
                        "Namen, Erkennungsmuster, Weckzeiten und die Einstellung \"Stille " +
                        "Schicht\" gehen verloren. Alle kommenden Wecker werden mit den " +
                        "Standard-Weckzeiten neu gesetzt. Das lässt sich nicht rückgängig machen."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        shiftViewModel.updateShiftConfig(ShiftConfig.getDefaultConfig())
                        showResetConfirmation = false
                    }
                ) {
                    Text("Zurücksetzen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Abbrechen")
                }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Alarm: ${definition.getAlarmTimeFormatted()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (definition.isSilent) {
                        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Stille Schicht - kein Ton/Vibration/Vollbild",
                            modifier = Modifier.size(SpacingConstants.ICON_SIZE_SMALL),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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


