package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ManualAlarmUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Manual Alarm Card Component
 * 
 * Ermöglicht Benutzern das manuelle Erstellen von Alarmen nach Schichttausch.
 * Integriert sich nahtlos in das bestehende Card-Design der App.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAlarmCard(
    manualAlarmState: ManualAlarmUiState,
    onSelectDate: (LocalDate) -> Unit,
    onSelectShift: (ShiftDefinition) -> Unit,
    onCreate: () -> Unit,
    onDelete: () -> Unit,
    onClearError: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showShiftSelector by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                manualAlarmState.hasActiveManualAlarm -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (manualAlarmState.hasActiveManualAlarm) Icons.Default.CheckCircle 
                    else Icons.Default.AlarmAdd,
                    modifier = Modifier.size(32.dp),
                    tint = when {
                        manualAlarmState.hasActiveManualAlarm -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    contentDescription = null
                )
                
                Text(
                    text = if (manualAlarmState.hasActiveManualAlarm) 
                        "Manueller Alarm aktiv" 
                    else "Manueller Alarm",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (manualAlarmState.hasActiveManualAlarm) {
                // Aktiver Alarm Anzeige
                manualAlarmState.activeManualAlarm?.let { alarm ->
                    HorizontalDivider()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = alarm.shiftName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Alarm: ${alarm.formattedTime}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        OutlinedButton(
                            onClick = onDelete,
                            enabled = !manualAlarmState.isDeleting,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            if (manualAlarmState.isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Löschen")
                            }
                        }
                    }
                }
            } else {
                // Alarm Erstellung UI
                HorizontalDivider()
                
                // Datum Auswahl
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Datum:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    OutlinedButton(
                        onClick = { showDatePicker = true }
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            modifier = Modifier.size(16.dp),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(formatDate(manualAlarmState.selectedDate))
                    }
                }
                
                // Schicht Auswahl
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Schicht:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    OutlinedButton(
                        onClick = { showShiftSelector = true }
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            modifier = Modifier.size(16.dp),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            manualAlarmState.selectedShift?.name ?: "Schicht wählen"
                        )
                    }
                }
                
                // Berechnete Alarm-Zeit Anzeige
                manualAlarmState.calculatedAlarmTime?.let { alarmTime ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AccessAlarm,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                contentDescription = null
                            )
                            Text(
                                text = "Weckzeit: $alarmTime",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                
                // Error Display
                manualAlarmState.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = error.error ?: "Unbekannter Fehler",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onClearError,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    contentDescription = "Fehler schließen"
                                )
                            }
                        }
                    }
                }
                
                // Erstellen Button
                Button(
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manualAlarmState.selectedShift != null && 
                             manualAlarmState.calculatedAlarmTime != null &&
                             !manualAlarmState.isCreating
                ) {
                    if (manualAlarmState.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Erstelle...")
                    } else {
                        Icon(
                            Icons.Default.AlarmAdd,
                            modifier = Modifier.size(16.dp),
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Alarm erstellen")
                    }
                }
            }
        }
    }
    
    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            selectedDate = manualAlarmState.selectedDate,
            onDateSelected = { date ->
                onSelectDate(date)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
    
    // Shift Selector Dialog
    if (showShiftSelector) {
        ShiftSelectorDialog(
            availableShifts = manualAlarmState.availableShifts,
            selectedShift = manualAlarmState.selectedShift,
            onShiftSelected = { shift ->
                onSelectShift(shift)
                showShiftSelector = false
            },
            onDismiss = { showShiftSelector = false }
        )
    }
}

@Composable
private fun formatDate(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date.isEqual(today) -> "Heute"
        date.isEqual(today.plusDays(1)) -> "Morgen"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("dd.MM"))
        else -> date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}
