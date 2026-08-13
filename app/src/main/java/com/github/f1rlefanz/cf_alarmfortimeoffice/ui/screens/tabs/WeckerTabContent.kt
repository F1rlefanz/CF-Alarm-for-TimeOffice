package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.AlarmStatusHeader
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState

/** Auswahlmoeglichkeiten fuer die Schlummer-Dauer-Dropdown - deckt die ueblichen Werte ab. */
private val SNOOZE_MINUTES_OPTIONS = listOf(3, 5, 10, 15)

@Composable
fun WeckerTabContent(
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    snoozeMinutes: Int,
    onUpdateShiftConfig: (ShiftConfig) -> Unit,
    onSkipNextAlarm: () -> Unit,
    onCancelSkip: () -> Unit,
    onShowShiftConfig: () -> Unit,
    onSnoozeMinutesChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        Text(
            "Wecker",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Auto-Alarm Switch
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD)
            ) {
                SwitchRow(
                    title = "Automatische Alarme",
                    description = "Deaktivieren löscht sofort alle bereits gesetzten Wecker. Aktivieren erstellt sie aus dem letzten bekannten Kalenderstand neu.",
                    checked = shiftState.currentShiftConfig?.autoAlarmEnabled ?: false,
                    onCheckedChange = { enabled ->
                        shiftState.currentShiftConfig?.let { config ->
                            onUpdateShiftConfig(config.copy(autoAlarmEnabled = enabled))
                        }
                    },
                    // Waehrend ShiftViewModel.loadShiftConfig() noch laedt (kurzes Fenster beim
                    // Kaltstart) ist currentShiftConfig null - der Tap wuerde sonst wortlos
                    // verpuffen (checked bleibt an "?: false" haengen, onCheckedChange erreicht
                    // nie onUpdateShiftConfig), ohne dass der Nutzer erkennt, dass sein Toggle
                    // wirkungslos war. Deaktiviert statt stumm zu ignorieren.
                    enabled = shiftState.currentShiftConfig != null,
                    titleStyle = MaterialTheme.typography.titleMedium
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_MEDIUM))

                SnoozeMinutesRow(
                    snoozeMinutes = snoozeMinutes,
                    onSnoozeMinutesChange = onSnoozeMinutesChange
                )
            }
        }

        // Enhanced Alarm Status Card mit Skip-Funktionalität
        EnhancedAlarmStatusCard(
            alarmState = alarmState,
            skipState = skipState,
            onSkipNextAlarm = onSkipNextAlarm,
            onCancelSkip = onCancelSkip
        )

        // Schichttypen verwalten
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowShiftConfig
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Work,
                    // dekorativ: Text daneben sagt es bereits ("Schichttypen verwalten")
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Schichttypen verwalten",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Definiere Schichttypen und Erkennungsmuster",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    // dekorativ: reines Weiter-Zeichen, die ganze Karte ist das bedienbare
                    // Element und traegt ihre Beschriftung selbst
                    Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
private fun EnhancedAlarmStatusCard(
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    onSkipNextAlarm: () -> Unit,
    onCancelSkip: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row (bestehend)
            AlarmStatusHeader(alarmState = alarmState, skipState = skipState)

            // Skip-Funktionalität (nur wenn Alarme vorhanden)
            if (alarmState.hasActiveAlarms) {
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (skipState.isNextAlarmSkipped) {
                        // Skip ist aktiv
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                tint = MaterialTheme.colorScheme.warning,
                                modifier = Modifier.size(20.dp),
                                // dekorativ: Text daneben sagt es bereits ("Nächster Alarm wird
                                // übersprungen")
                                contentDescription = null
                            )
                            Text(
                                "Nächster Alarm wird übersprungen",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        OutlinedButton(
                            onClick = onCancelSkip,
                            enabled = !skipState.isLoading,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (skipState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Aufheben")
                            }
                        }
                    } else {
                        // Skip nicht aktiv
                        Text(
                            "Nächsten Alarm einmalig überspringen:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = onSkipNextAlarm,
                            enabled = alarmState.nextAlarmTime != null && !skipState.isLoading
                        ) {
                            if (skipState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    Icons.Default.SkipNext,
                                    modifier = Modifier.size(16.dp),
                                    // dekorativ: die Beschriftung im selben Button sagt es bereits
                                    // ("Überspringen")
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Überspringen")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Auswahl der Schlummer-Dauer. Wirkt auf beide Snooze-Ausloeser (Vollbild-Button, Notification-
 * Button) gleichermassen - siehe [com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs].
 */
@Composable
private fun SnoozeMinutesRow(
    snoozeMinutes: Int,
    onSnoozeMinutesChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Schlummer-Dauer",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Gilt für den Vollbild- und den Benachrichtigungs-Schlummer-Knopf",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("$snoozeMinutes Min")
                // Nicht dekorativ: die Beschriftung des Knopfes ist nur "5 Min" - die Ueberschrift
                // "Schlummer-Dauer" steht daneben in einer eigenen Spalte und wird nicht
                // mitgelesen. Ohne diese Beschreibung meldet der Screenreader den Knopf als
                // blosses "5 Min", ohne zu sagen, was er aendert.
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Schlummer-Dauer ändern")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SNOOZE_MINUTES_OPTIONS.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text("$minutes Min") },
                        onClick = {
                            onSnoozeMinutesChange(minutes)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
