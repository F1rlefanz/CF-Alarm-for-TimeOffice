package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition

/**
 * Shift Selector Dialog Component
 * 
 * Ermöglicht die Auswahl einer Schichtdefinition aus den user-konfigurierten Optionen.
 * Zeigt Name, Alarmzeit und Keywords für bessere Identifikation.
 */
@Composable
fun ShiftSelectorDialog(
    availableShifts: List<ShiftDefinition>,
    selectedShift: ShiftDefinition?,
    onShiftSelected: (ShiftDefinition) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Schicht auswählen")
        },
        text = {
            if (availableShifts.isEmpty()) {
                // Fallback wenn keine Schichten konfiguriert
                Text(
                    text = "Keine Schichtmuster konfiguriert. Bitte konfigurieren Sie zuerst Ihre Schichtmuster in den Einstellungen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableShifts) { shift ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onShiftSelected(shift) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedShift?.id == shift.id) 
                                    MaterialTheme.colorScheme.primaryContainer
                                else 
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = shift.name, // User-definierter Name
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Alarm um ${shift.getAlarmTimeFormatted()}", // User-definierte Zeit
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // Zeige Keywords für bessere Identifikation
                                    if (shift.keywords.isNotEmpty()) {
                                        Text(
                                            text = "Keywords: ${shift.keywords.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                if (selectedShift?.id == shift.id) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        tint = MaterialTheme.colorScheme.primary,
                                        contentDescription = "Ausgewählt"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        },
        dismissButton = if (availableShifts.isEmpty()) {
            {
                TextButton(
                    onClick = { 
                        // TODO: Navigation zu Shift Configuration Screen
                        onDismiss()
                    }
                ) {
                    Text("Einstellungen")
                }
            }
        } else null
    )
}
