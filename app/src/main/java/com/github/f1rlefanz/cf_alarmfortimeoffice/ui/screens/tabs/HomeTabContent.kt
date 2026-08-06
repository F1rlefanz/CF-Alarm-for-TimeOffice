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
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.AlarmStatusHeader
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * PURE, TESTBAR: Warum die Karte "Naechste Schicht" gerade keine Schicht anzeigen kann.
 *
 * Der else-Zweig zeigte fuer mindestens sechs voellig verschiedene Zustaende denselben,
 * ursachenlosen Satz "Keine Schicht erkannt" - ein Nutzer konnte daraus nicht ablesen, ob der
 * Kalender, die Anmeldung oder seine Erkennungsmuster das Problem sind. Alle unterscheidenden
 * Felder liegen im Composable ohnehin schon als Parameter vor.
 */
internal enum class NoShiftReason {
    NO_CALENDAR_SELECTED,
    AUTHORIZATION_LOST,
    LOAD_ERROR,
    NO_EVENTS,
    SHIFT_CONFIG_NOT_LOADED,
    NO_SHIFT_TYPES,
    NO_PATTERN_MATCH,
    ONLY_PAST_SHIFTS
}

/**
 * PURE, TESTBAR: Leitet den Grund aus den vorhandenen UI-Zustaenden ab.
 *
 * Reihenfolge = von der grundlegendsten Ursache zur speziellsten: Ohne Kalender ist alles andere
 * belanglos, ohne Autorisierung koennen keine Termine kommen, usw. Der letzte Fall ist bewusst
 * kein "unbekannt": Sind Schichten erkannt, aber keine kuenftige dabei, liegen sie in der
 * Vergangenheit ([ShiftUiState.upcomingShift] filtert auf `startTime.isAfter(now)`).
 */
internal fun noShiftReason(
    hasSelectedCalendars: Boolean,
    calendarAuthorizationValid: Boolean,
    errorMessage: String?,
    eventCount: Int,
    shiftConfigLoaded: Boolean,
    enabledShiftTypeCount: Int,
    recognizedShiftCount: Int
): NoShiftReason = when {
    !hasSelectedCalendars -> NoShiftReason.NO_CALENDAR_SELECTED
    !calendarAuthorizationValid -> NoShiftReason.AUTHORIZATION_LOST
    !errorMessage.isNullOrBlank() -> NoShiftReason.LOAD_ERROR
    eventCount == 0 -> NoShiftReason.NO_EVENTS
    !shiftConfigLoaded -> NoShiftReason.SHIFT_CONFIG_NOT_LOADED
    enabledShiftTypeCount == 0 -> NoShiftReason.NO_SHIFT_TYPES
    recognizedShiftCount == 0 -> NoShiftReason.NO_PATTERN_MATCH
    else -> NoShiftReason.ONLY_PAST_SHIFTS
}

/**
 * PURE, TESTBAR: Formuliert Grund + Handlungsschritt.
 *
 * Bei [NoShiftReason.NO_PATTERN_MATCH] werden die tatsaechlich geladenen Termintitel genannt - das
 * ist der Fall, in dem der Nutzer seine Stichwoerter mit dem vergleichen muss, was im Kalender
 * wirklich steht (der gemeldete Konfigurationsfall eines Kollegen auf einer anderen Station).
 *
 * Der Hinweis auf ausgeschaltete Automatik-Alarme ist bewusst ein ZUSATZ und kein eigener Grund:
 * Die Schichterkennung laeuft unabhaengig von `autoAlarmEnabled` weiter (ShiftViewModel
 * .observeCalendarEvents), der Schalter erklaert also nie, warum keine Schicht erkannt wurde.
 */
internal fun noShiftExplanation(
    reason: NoShiftReason,
    errorMessage: String? = null,
    sampleEventTitles: List<String> = emptyList(),
    autoAlarmEnabled: Boolean = true
): String {
    val core = when (reason) {
        NoShiftReason.NO_CALENDAR_SELECTED ->
            // "Kalender wählen" ist der Knopf, den der Status-Tab in genau diesem Zustand anbietet
            // (StatusTabContent, Karte "Kalender") - dorthin zeigen, nicht auf einen erfundenen Weg.
            "Noch kein Kalender ausgewählt — im Status-Tab unter \"Kalender\" den Dienstplan-Kalender wählen."
        NoShiftReason.AUTHORIZATION_LOST ->
            "Kalender-Zugriff abgelaufen — erneuere ihn in der Karte darunter."
        NoShiftReason.LOAD_ERROR ->
            "Termine konnten nicht geladen werden: ${errorMessage?.takeIf { it.isNotBlank() } ?: "unbekannter Fehler"}"
        NoShiftReason.NO_EVENTS ->
            "Keine Termine in den nächsten 14 Tagen — im gewählten Kalender steht nichts."
        NoShiftReason.SHIFT_CONFIG_NOT_LOADED ->
            "Schichttypen werden noch geladen."
        NoShiftReason.NO_SHIFT_TYPES ->
            "Keine aktiven Schichttypen — lege sie im Wecker-Tab unter \"Schichttypen verwalten\" an."
        NoShiftReason.NO_PATTERN_MATCH -> buildString {
            append("Termine gefunden, aber kein Erkennungsmuster passt")
            if (sampleEventTitles.isNotEmpty()) {
                append(" (im Kalender steht: ")
                append(sampleEventTitles.joinToString(", "))
                append(")")
            }
            append(". Erkennungsmuster im Wecker-Tab unter \"Schichttypen verwalten\" prüfen.")
        }
        NoShiftReason.ONLY_PAST_SHIFTS ->
            "Alle erkannten Schichten liegen bereits in der Vergangenheit."
    }
    return if (autoAlarmEnabled) {
        core
    } else {
        "$core\nHinweis: Automatische Alarme sind derzeit ausgeschaltet."
    }
}

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
                        // Nicht nur "Keine Schicht erkannt", sondern WARUM: der Satz allein galt fuer
                        // sechs verschiedene Ursachen und liess den Nutzer ohne Anhaltspunkt zurueck.
                        val shiftConfig = shiftState.currentShiftConfig
                        val reason = noShiftReason(
                            hasSelectedCalendars = calendarState.selectedCalendarIds.isNotEmpty(),
                            calendarAuthorizationValid = calendarState.calendarAuthorizationValid,
                            errorMessage = calendarState.error ?: shiftState.error,
                            eventCount = calendarState.events.size,
                            shiftConfigLoaded = shiftConfig != null,
                            enabledShiftTypeCount = shiftConfig?.definitions?.count { it.isEnabled } ?: 0,
                            recognizedShiftCount = shiftState.recognizedShifts.size
                        )
                        Text(
                            "Keine Schicht erkannt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            noShiftExplanation(
                                reason = reason,
                                errorMessage = calendarState.error ?: shiftState.error,
                                // Nur eine kleine Kostprobe: die Karte soll erklaeren, nicht den
                                // Kalender abbilden (dafuer gibt es "Antippen fuer Details").
                                sampleEventTitles = calendarState.events
                                    .map { it.title }
                                    .distinct()
                                    .take(3),
                                autoAlarmEnabled = shiftConfig?.autoAlarmEnabled != false
                            ),
                            style = MaterialTheme.typography.bodySmall,
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
            AlarmStatusHeader(
                alarmState = alarmState,
                skipState = skipState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Default.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            )
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
