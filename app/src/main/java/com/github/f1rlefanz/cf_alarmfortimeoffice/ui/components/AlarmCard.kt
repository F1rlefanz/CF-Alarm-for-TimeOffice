package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.UIText
import java.time.format.DateTimeFormatter

/**
 * PERFORMANCE OPTIMIERTE AlarmCard
 * 
 * Optimierungen:
 * - DateTimeFormatter wird nur einmal erstellt
 * - Stabile Lambda-Callbacks mit remember
 * - Vermeidung von unnötigen String-Formatierungen
 * - Hardcodierte Werte durch Konstanten ersetzt
 */
@Composable
fun AlarmCard(
    nextShift: ShiftMatch?,
    autoAlarmEnabled: Boolean,
    systemAlarmSet: Boolean,
    canScheduleExactAlarms: Boolean,
    alarmStatusMessage: String?,
    onToggleAutoAlarm: () -> Unit,
    onManualSetAlarm: (ShiftMatch) -> Unit,
    onCancelAlarm: () -> Unit,
    modifier: Modifier = Modifier
) {
    // PERFORMANCE: DateTimeFormatter nur einmal erstellen
    val dateFormatter = remember { DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = SpacingConstants.CARD_ELEVATION),
        shape = RoundedCornerShape(SpacingConstants.CARD_CORNER_RADIUS)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
        ) {
            // Header mit Icon und Titel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    Icon(
                        imageVector = if (systemAlarmSet) Icons.Default.Alarm else Icons.Default.AlarmOff,
                        contentDescription = null,
                        tint = if (systemAlarmSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = UIText.ALARM_CONTROL,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Auto-Alarm Toggle
                Switch(
                    checked = autoAlarmEnabled,
                    onCheckedChange = { onToggleAutoAlarm() }
                )
            }

            // Nächste Schicht anzeigen
            if (nextShift != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL)
                ) {
                    Text(
                        text = UIText.NEXT_SHIFT,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = nextShift.shiftDefinition.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    // PERFORMANCE: Format nur wenn sich die Zeit ändert
                    Text(
                        text = remember(nextShift.calculatedAlarmTime) {
                            nextShift.calculatedAlarmTime.format(dateFormatter)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status-Nachricht
            if (!alarmStatusMessage.isNullOrBlank()) {
                Text(
                    text = alarmStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (systemAlarmSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            // Warnungen
            if (!canScheduleExactAlarms) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(SpacingConstants.SPACING_MEDIUM),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = UIText.EXACT_ALARM_DISABLED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                if (systemAlarmSet) {
                    Button(
                        onClick = onCancelAlarm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlarmOff,
                            contentDescription = null,
                            modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                        )
                        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_EXTRA_SMALL))
                        Text(UIText.CANCEL_ALARM)
                    }
                } else if (nextShift != null) {
                    // PERFORMANCE: Stabile Lambda mit remember
                    val onClickSetAlarm = remember(nextShift, onManualSetAlarm) {
                        { onManualSetAlarm(nextShift) }
                    }
                    Button(
                        onClick = onClickSetAlarm,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlarmAdd,
                            contentDescription = null,
                            modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                        )
                        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_EXTRA_SMALL))
                        Text(UIText.SET_ALARM)
                    }
                }
            }
        }
    }
}