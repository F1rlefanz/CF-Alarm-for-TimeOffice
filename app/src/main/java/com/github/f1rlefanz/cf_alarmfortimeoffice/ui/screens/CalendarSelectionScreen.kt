package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel

/**
 * CalendarSelectionScreen - REFACTORED für Single Source of Truth
 * 
 * ✅ CODE CLEANUP: Updated deprecated Material Icons
 * ✅ PERFORMANCE: Direct imports from theme package
 * ✅ STATE MANAGEMENT: Single Source of Truth pattern
 * ✅ MEMORY: Eliminated temporary state objects
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSelectionScreen(
    calendarViewModel: CalendarViewModel,
    /**
     * Verlaesst den Bildschirm und setzt den Onboarding-Weg fort (Akku-Freistellung usw.).
     *
     * Hiess bis v1.39.1 `onSave` und der Knopf dazu "Speichern" - beides eine Luege: gespeichert
     * wird bei JEDEM Tipp auf einen Kalender sofort (`toggleCalendarSelection` schreibt durch),
     * dieser Aufruf speichert gar nichts. Siehe Issue #50.
     */
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Startet den Google-Zustimmungsdialog. Noetig, weil "Erneut versuchen" ohne gueltiges
     * Token in einer Sackgasse endet: der Abruf braucht ein Token, das die App bei einem
     * 401/403 gerade korrekt verworfen hat. Nur dieser Weg holt ein neues.
     */
    onRequestAuthorization: () -> Unit
) {
    // collectAsStateWithLifecycle statt collectAsState: reiner Anzeige-Zustand. Die Auswahl selbst
    // schreibt toggleCalendarSelection() ueber das ViewModel ins Repository - am Sammeln hier
    // haengt kein Seiteneffekt, der auch im Hintergrund laufen muesste.
    val calendarState by calendarViewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kalender auswählen") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Abbrechen"
                        )
                    }
                },
                actions = {
                    // "Fertig", nicht "Speichern", und ohne `enabled`-Bedingung: der Knopf
                    // speichert nichts - das ist beim Antippen des Kalenders laengst passiert.
                    // Ausgegraut widersprach er ausserdem der Wirkung: wer den letzten Kalender
                    // abwaehlte, sah einen toten Knopf und durfte glauben, nichts sei uebernommen
                    // worden - die Raeumung war zu dem Zeitpunkt schon gelaufen (Issue #50).
                    // Was der leere Zustand bedeutet, sagt jetzt die Warnkarte in der Liste.
                    //
                    // DAS KALENDER-GATE HAENGT NICHT AN DIESEM KNOPF, auch wenn es bis v1.39.0 so
                    // aussah. Wer ohne Kalender auf "Fertig" tippt, laeuft durch die restlichen
                    // Onboarding-Gates bis Home - dort steht "Noch kein Kalender ausgewählt" samt
                    // Weg zurueck (`HomeTabContent`, `NoShiftReason.NO_CALENDAR_SELECTED`), im
                    // Status-Tab ebenso, und beim naechsten App-Vordergrund schickt
                    // `handleAuthenticationSuccess()` ihn wieder hierher. Der ausgegraute Knopf
                    // hat das nie verhindert, sondern nur den Vorwaertsweg gesperrt - der
                    // Zurueck-Pfeil fuehrte an derselben Stelle vorbei. Wer hier wieder ein
                    // `enabled` ergaenzen will, baut den ausgegrauten Luegner neu und gewinnt
                    // nichts: ein sichtbarer Zustand ist die Sperre, kein toter Knopf.
                    TextButton(onClick = onDone) {
                        Text("Fertig")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
        ) {
            Text(
                "Wähle die Kalender aus, die für Schichtalarme überwacht werden sollen. " +
                        "Änderungen gelten sofort.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (calendarState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (calendarState.availableCalendars.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SpacingConstants.SPACING_LARGE),
                        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
                    ) {
                        // Ohne gueltiges Token ist "keine Kalender gefunden" die falsche
                        // Diagnose - es fehlt schlicht die Freigabe. Und "Erneut versuchen"
                        // waere dann eine Sackgasse: der Abruf braucht genau das Token, das
                        // bei einem 401/403 gerade verworfen wurde. Nur der Zustimmungsdialog
                        // holt ein neues.
                        val needsAuthorization = !calendarState.hasValidToken

                        Text(
                            if (needsAuthorization) "Kalender-Zugriff nicht freigegeben" else "Keine Kalender gefunden",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            calendarState.error
                                ?: if (needsAuthorization) {
                                    "Die App braucht deine Erlaubnis, den Google-Kalender zu lesen."
                                } else {
                                    "Dein Google-Konto enthält keine abrufbaren Kalender. Lege zuerst in Google Kalender einen Kalender an, oder versuche es erneut."
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        OutlinedButton(
                            onClick = {
                                if (needsAuthorization) {
                                    onRequestAuthorization()
                                } else {
                                    calendarViewModel.loadAvailableCalendars(resetPagination = true)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (needsAuthorization) "Kalender-Zugriff erlauben" else "Erneut versuchen")
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    // Der leere Zustand ist kein "noch nichts getan", sondern eine WIRKUNG: die
                    // Abwahl des letzten Kalenders raeumt die Wecker der naechsten zwei Wochen
                    // samt der Dienstzeit-Fenster (`clearAlarmsAfterCalendarDeselection`). Solange
                    // der Knopf das nur durch Ausgrauen andeutete, sagte die Oberflaeche das
                    // Gegenteil dessen, was geschehen war. Als `item` und nicht als Karte ueber
                    // der Liste, damit die LazyColumn das einzige hoehenvariable Kind der Column
                    // bleibt.
                    if (calendarState.selectedCalendarIds.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(SpacingConstants.SPACING_LARGE),
                                    verticalArrangement = Arrangement.spacedBy(
                                        SpacingConstants.SPACING_SMALL
                                    )
                                ) {
                                    Text(
                                        "Kein Kalender ausgewählt",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    // KEINE Vollzugsmeldung. "Bereits gestellte Wecker wurden
                                    // entfernt" stand hier zuerst und waere eine Behauptung ueber
                                    // etwas, das diese Karte nicht weiss: die Raeumung nach einer
                                    // Abwahl hat vier bewusste Fail-safe-Ausstiege
                                    // (`clearAlarmsAfterCalendarDeselection`), bei denen JEDER
                                    // Wecker scharf stehen bleibt. Der Widerspruch wird gemeldet -
                                    // aber im Status-Tab, nicht hier. Und von Hand gestellte
                                    // Wecker bleiben ohnehin (`keepManualAlarms`), ebenso wie es
                                    // bei einer Neuinstallation gar nichts zu entfernen gab.
                                    // Deshalb steht hier, was die Abwahl TUT, nicht was sie
                                    // getan hat.
                                    Text(
                                        "Ohne Kalender erkennt die App keine Schichten und stellt " +
                                                "keine Wecker. Beim Abwählen entfernt sie auch die " +
                                                "bereits gestellten; von Hand gestellte Wecker " +
                                                "bleiben. Klappt das Aufräumen nicht, sagt es der " +
                                                "Status-Tab. Tippe einen Kalender an, um wieder " +
                                                "Schichten zu überwachen.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    items(calendarState.availableCalendars) { calendar ->
                        val isSelected = calendarState.selectedCalendarIds.contains(calendar.id)
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                // SINGLE SOURCE OF TRUTH: Direkte Aktualisierung über ViewModel
                                // Kein lokaler State - alles geht durch Repository
                                calendarViewModel.toggleCalendarSelection(calendar.id)
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(SpacingConstants.SPACING_LARGE),
                                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        calendar.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    calendar.accountName?.let { accountName ->
                                        Text(
                                            accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Ausgewählt",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    
                    // PAGINATION: Load More Button
                    if (calendarState.hasMoreCalendars) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = SpacingConstants.SPACING_MEDIUM),
                                contentAlignment = Alignment.Center
                            ) {
                                if (calendarState.isLoadingMore) {
                                    CircularProgressIndicator()
                                } else {
                                    OutlinedButton(
                                        onClick = { calendarViewModel.loadMoreCalendars() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Weitere Kalender laden")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
