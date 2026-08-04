package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeTabContent(
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    onRefresh: () -> Unit,
    onNavigateToWecker: () -> Unit,
    onShowEventList: (() -> Unit)? = null,
    onReauthorize: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL)
            // Zusaetzlicher Freiraum unten, damit der Manueller-Alarm-FAB nicht ueber der letzten
            // Karte schwebt (der FAB liegt ausserhalb des Scaffold-innerPadding).
            .padding(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        // Header mit Refresh-Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Übersicht",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Aktualisieren",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Nächste Schicht Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nächste Schicht",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (shiftState.upcomingShift != null) {
                        Text(
                            shiftState.upcomingShift.shiftType.displayName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
                                .format(shiftState.upcomingShift.startTime),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (calendarState.isLoading || shiftState.isLoading) {
                        // Beim (ersten) Oeffnen synchronisiert die App den Kalender neu; bis die
                        // Events da sind, ist noch keine Schicht erkannt. Das ist ein LADEzustand,
                        // kein Fehler - frueher stand hier sofort "Keine Schicht erkannt" (bzw. ein
                        // Warnsymbol), was beim Aufschlagen fuer einen Sekundenbruchteil aussah, als
                        // sei etwas kaputt. Neutraler Hinweis, solange geladen wird.
                        Text(
                            "Wird geladen …",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Keine Schicht erkannt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Kompakte Alarm-Status Card - Details (inkl. Skip-Funktionalität) leben im Wecker-Tab
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onNavigateToWecker,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AccessAlarm,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_EXTRA_LARGE),
                    tint = when {
                        skipState.isNextAlarmSkipped -> MaterialTheme.colorScheme.warning
                        alarmState.hasActiveAlarms -> MaterialTheme.colorScheme.success
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Alarm-Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (alarmState.hasActiveAlarms) {
                        Text("${alarmState.activeAlarms.size} aktive Alarme")
                        alarmState.nextAlarmTime?.let {
                            Text("Nächster Alarm: $it")
                        }
                    } else if (alarmState.isLoading) {
                        Text(
                            "Wird geladen …",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Keine aktiven Alarme",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }

        // Kalender Events Summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onShowEventList?.invoke() }, // LAZY LOADING: Make card clickable for event list
            colors = CardDefaults.cardColors(
                // PHASE 2 FIX: Show error color if authorization lost
                containerColor = if (!calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty()) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    CardDefaults.cardColors().containerColor
                }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                        tint = if (!calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty()) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        "Kalender-Events",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                HorizontalDivider()
                
                // PHASE 2 FIX: Show authorization error prominently
                if (!calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty()) {
                    Text(
                        "⚠️ Kalender-Autorisierung verloren",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Der Zugriff auf deinen Google Kalender ist abgelaufen. Autorisiere ihn erneut, damit weiterhin Schichtalarme erstellt werden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    onReauthorize?.let { reauthorize ->
                        Button(
                            onClick = reauthorize,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text("Kalender-Zugriff erneuern")
                        }
                    }
                } else if (calendarState.events.isNotEmpty()) {
                    // LAZY LOADING: Show limited events overview in home tab
                    val displayEventCount = minOf(calendarState.events.size, 5) // Show max 5 events in overview
                    Text("${calendarState.events.size} Events in den nächsten 14 Tagen")
                    Text(
                        "${shiftState.recognizedShifts.size} Schichten erkannt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    // Show recognized shifts for today and tomorrow
                    val today = LocalDate.now()
                    val tomorrow = today.plusDays(1)
                    
                    val todayShifts = shiftState.recognizedShifts.filter { 
                        it.startTime.toLocalDate() == today 
                    }
                    val tomorrowShifts = shiftState.recognizedShifts.filter { 
                        it.startTime.toLocalDate() == tomorrow 
                    }
                    
                    if (todayShifts.isNotEmpty() || tomorrowShifts.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (todayShifts.isNotEmpty()) {
                                Text(
                                    "Heute: ${todayShifts.joinToString(", ") { it.shiftType.displayName }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (tomorrowShifts.isNotEmpty()) {
                                Text(
                                    "Morgen: ${tomorrowShifts.joinToString(", ") { it.shiftType.displayName }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    
                    // LAZY LOADING: Show if more events are available
                    if (calendarState.hasMoreEvents) {
                        Text(
                            "Zeige $displayEventCount von ${if (calendarState.totalEvents > 0) calendarState.totalEvents else "mehr"} Events",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    
                    // Clickable hint
                    Text(
                        "Antippen für Details →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "Keine Events geladen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Loading Indicator
        if (calendarState.isLoading || shiftState.isLoading || alarmState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingConstants.SPACING_LARGE),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
