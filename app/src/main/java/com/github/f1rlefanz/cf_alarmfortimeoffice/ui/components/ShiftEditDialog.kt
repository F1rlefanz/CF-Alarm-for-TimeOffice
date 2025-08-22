package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.LayoutFractions
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.AlarmConstants
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftEditDialog(
    shift: ShiftDefinition?,
    onDismiss: () -> Unit,
    onSave: (ShiftDefinition) -> Unit
) {
    val isNewShift = shift == null
    
    var name by remember { mutableStateOf(shift?.name ?: "") }
    var keywords by remember { mutableStateOf(shift?.keywords ?: listOf("")) }
    var alarmTimeString by remember { 
        mutableStateOf(shift?.alarmTime?.format(DateTimeFormatter.ofPattern("HH:mm")) 
            ?: String.format("%02d:%02d", AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE)) 
    }
    var isEnabled by remember { mutableStateOf(shift?.isEnabled ?: true) }
    
    // Time formatter
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(LayoutFractions.DIALOG_WIDTH)
                .fillMaxHeight(LayoutFractions.DIALOG_HEIGHT)
        ) {
            Column(
                modifier = Modifier
                .fillMaxSize()
                .padding(SpacingConstants.SPACING_EXTRA_LARGE)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isNewShift) "Neue Schichtdefinition" else "Schicht bearbeiten",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
                
                // Content
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
                ) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Schichtname") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = name.isBlank()
                        )
                    }
                    
                    item {
                        Text(
                            text = "Erkennungsmuster",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Geben Sie Textmuster ein, die in Kalenderterminen vorkommen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Pattern inputs
                    keywords.forEachIndexed { index, keyword ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = keyword,
                                    onValueChange = { newValue ->
                                        keywords = keywords.toMutableList().apply {
                                            this[index] = newValue
                                        }
                                    },
                                    label = { Text("Muster ${index + 1}") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    isError = keyword.isBlank()
                                )
                                
                                if (keywords.size > 1) {
                                    IconButton(
                                        onClick = {
                                            keywords = keywords.filterIndexed { i, _ -> i != index }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Remove,
                                            contentDescription = "Muster entfernen",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    item {
                        TextButton(
                            onClick = { keywords = keywords + "" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                            Text("Weiteres Muster hinzufügen")
                        }
                    }
                    
                    item {
                        Text(
                            text = "Weckzeit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = alarmTimeString,
                                onValueChange = { newValue ->
                                    // Validate time format
                                    if (newValue.matches(Regex("^\\d{0,2}:?\\d{0,2}$"))) {
                                        alarmTimeString = newValue
                                    }
                                },
                                label = { Text("Zeit (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = try {
                                    LocalTime.parse(alarmTimeString, timeFormatter)
                                    false
                                } catch (e: Exception) {
                                    true
                                }
                            )
                            
                            Text(
                                text = "Format: 06:30",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Schichtdefinition aktiviert",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { isEnabled = it }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_LARGE))
                
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Abbrechen")
                    }
                    
                    Button(
                        onClick = {
                            val validKeywords = keywords.filter { it.isNotBlank() }
                            val parsedAlarmTime = try {
                                LocalTime.parse(alarmTimeString, timeFormatter)
                            } catch (e: Exception) {
                                null
                            }
                            
                            if (name.isNotBlank() && validKeywords.isNotEmpty() && parsedAlarmTime != null) {
                                onSave(
                                    ShiftDefinition(
                                        id = shift?.id ?: UUID.randomUUID().toString(),
                                        name = name.trim(),
                                        keywords = validKeywords,
                                        alarmTime = parsedAlarmTime,
                                        isEnabled = isEnabled
                                    )
                                )
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && 
                                 keywords.any { it.isNotBlank() } && 
                                 try {
                                     LocalTime.parse(alarmTimeString, timeFormatter)
                                     true
                                 } catch (e: Exception) {
                                     false
                                 }
                    ) {
                        Text(if (isNewShift) "Erstellen" else "Speichern")
                    }
                }
            }
        }
    }
}
