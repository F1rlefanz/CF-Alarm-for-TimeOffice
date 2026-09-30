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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    KALENDER_NICHT_ERREICHBAR,
    CALENDAR_PARTIALLY_UNAVAILABLE,
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
    kalenderNichtErreichbar: Boolean,
    unavailableCalendarCount: Int,
    errorMessage: String?,
    eventCount: Int,
    shiftConfigLoaded: Boolean,
    enabledShiftTypeCount: Int,
    recognizedShiftCount: Int
): NoShiftReason = when {
    !hasSelectedCalendars -> NoShiftReason.NO_CALENDAR_SELECTED
    !calendarAuthorizationValid -> NoShiftReason.AUTHORIZATION_LOST
    // VOR Fehlermeldung und leerer Terminliste: offline ist die Liste zwangslaeufig leer, und
    // sobald die Snackbar den Fehler geraeumt hat, hiesse es sonst "im gewaehlten Kalender steht
    // nichts" - eine Behauptung ueber den Dienstplan, die niemand pruefen konnte.
    kalenderNichtErreichbar -> NoShiftReason.KALENDER_NICHT_ERREICHBAR
    // NACH der Autorisierung, VOR allem Weiteren: ist ein Kalender nicht abrufbar, halten die
    // Vollstaendigkeits-Sperren jeden Alarm-Sync an. Jede Ursache darunter (keine Termine, kein
    // Muster) waere dann eine Folge davon, keine eigene Erklaerung - und wuerde den Nutzer an der
    // falschen Stelle suchen lassen.
    unavailableCalendarCount > 0 -> NoShiftReason.CALENDAR_PARTIALLY_UNAVAILABLE
    !errorMessage.isNullOrBlank() -> NoShiftReason.LOAD_ERROR
    eventCount == 0 -> NoShiftReason.NO_EVENTS
    !shiftConfigLoaded -> NoShiftReason.SHIFT_CONFIG_NOT_LOADED
    enabledShiftTypeCount == 0 -> NoShiftReason.NO_SHIFT_TYPES
    recognizedShiftCount == 0 -> NoShiftReason.NO_PATTERN_MATCH
    else -> NoShiftReason.ONLY_PAST_SHIFTS
}

/**
 * Der Zusatz fuer die Master-Pause. Bewusst kurz und ohne den Weg zurueck: den nennt die
 * Alarm-Status-Karte unmittelbar darunter (`ALARM_STATUS_PAUSIERT_AUSWEG`) - derselbe Zustand
 * soll nicht zweimal ausfuehrlich auf demselben Bildschirm stehen.
 */
internal const val NO_SHIFT_HINWEIS_PAUSIERT: String =
    "Hinweis: Alles ist pausiert — es wird kein Wecker gestellt."

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
 * Genauso die Master-Pause: auch sie haelt die Erkennung nicht an, sondern nur alles, was danach
 * kaeme.
 *
 * ES STEHT IMMER HOECHSTENS EIN ZUSATZ DA, und die Master-Pause hat Vorrang: sie ist der
 * umfassendere Zustand (sie schaltet zusaetzlich Dimmer, "Nicht stoeren", Hue und die 6h-Wartung
 * ab), und zwei Hinweise nebeneinander liessen offen, welcher der wirksame ist.
 */
internal fun noShiftExplanation(
    reason: NoShiftReason,
    errorMessage: String? = null,
    sampleEventTitles: List<String> = emptyList(),
    autoAlarmEnabled: Boolean = true,
    masterPausePaused: Boolean = false
): String {
    val core = when (reason) {
        NoShiftReason.NO_CALENDAR_SELECTED ->
            // "Kalender wählen" ist der Knopf, den der Status-Tab in genau diesem Zustand anbietet
            // (StatusTabContent, Karte "Kalender") - dorthin zeigen, nicht auf einen erfundenen Weg.
            "Noch kein Kalender ausgewählt — im Status-Tab unter \"Kalender\" den Dienstplan-Kalender wählen."
        NoShiftReason.AUTHORIZATION_LOST ->
            // KEINE Positionsangabe ("in der Karte darunter"): direkt unter dieser Karte liegt die
            // Alarm-Status-Karte, die stattdessen in den Wecker-Tab springt - der Erneuern-Knopf
            // sitzt erst eine Karte weiter. Wie bei den anderen Gruenden die Beschriftung nennen,
            // die im Screen wirklich steht (Karte "Kalender-Events", Knopf darin).
            "Kalender-Zugriff abgelaufen — in der Karte \"Kalender-Events\" auf " +
                "\"Kalender-Zugriff erneuern\" tippen."
        NoShiftReason.KALENDER_NICHT_ERREICHBAR ->
            // Kein Wort von Zugriff oder Anmeldung - genau diese Fehldiagnose stand hier bis
            // v1.43.4 (Flugmodus = "Kalender-Zugriff abgelaufen"). Knopf und Karte wortgleich.
            "Google Kalender gerade nicht erreichbar — die gestellten Wecker bleiben. Mit Netz " +
                "in der Karte \"Kalender-Events\" auf \"Mit Google Kalender abgleichen\" tippen."
        NoShiftReason.CALENDAR_PARTIALLY_UNAVAILABLE ->
            // Verweist auf die Karte, die den Namen des Kalenders UND den Entfernen-Knopf hat -
            // hier stehen die IDs nicht zur Verfuegung, und ein halber Hinweis waere schlechter
            // als der Weg zur vollstaendigen Auskunft.
            "Ein ausgewählter Kalender ist nicht abrufbar — solange werden keine neuen Wecker " +
                "angelegt. Näheres im Status-Tab unter \"Kalender\"."
        NoShiftReason.LOAD_ERROR ->
            "Termine konnten nicht geladen werden: ${errorMessage?.takeIf { it.isNotBlank() } ?: "unbekannter Fehler"}"
        NoShiftReason.NO_EVENTS ->
            "Keine Termine in den nächsten 14 Tagen — im gewählten Kalender steht nichts."
        NoShiftReason.SHIFT_CONFIG_NOT_LOADED ->
            // NICHT nur "wird geladen": derselbe Zustand entsteht, wenn der Read DAUERHAFT
            // gescheitert ist (vorhandene, aber nicht dekodierbare Konfiguration - das Repository
            // liefert dann ein Result.failure und die Rohdaten liegen als `shift_config_broken`).
            // Diese Karte wurde ausdruecklich gebaut, um den WARUM-Zustand ehrlich zu benennen; ein
            // behaupteter laufender Ladevorgang, der nie endet, ist das Gegenteil davon.
            "Schichttypen sind (noch) nicht lesbar. Bleibt das so, hilft der Status-Tab weiter — " +
                "die Konfiguration liegt dann gesichert vor und wird NICHT überschrieben."
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
    return when {
        masterPausePaused -> "$core\n$NO_SHIFT_HINWEIS_PAUSIERT"
        !autoAlarmEnabled -> "$core\nHinweis: Automatische Alarme sind derzeit ausgeschaltet."
        else -> core
    }
}

@Composable
fun HomeTabContent(
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    masterPausePaused: Boolean,
    onJetztAbgleichen: () -> Unit,
    onNavigateToWecker: () -> Unit,
    onShowEventList: () -> Unit,
    onReauthorize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL)
            // Zusaetzlicher Freiraum unten, damit der Manueller-Alarm-FAB nicht ueber der
            // letzten Karte schwebt (der FAB liegt ausserhalb des Scaffold-innerPadding); 88 dp
            // decken seine 56 dp plus Rand.
            .padding(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
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
                    // dekorativ: Text daneben sagt es bereits ("Nächste Schicht")
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
                        // kein Fehler - neutraler Hinweis, solange geladen wird.
                        Text(
                            "Wird geladen …",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Nicht nur "Keine Schicht erkannt", sondern WARUM: der Satz allein galt fuer
                        // sechs verschiedene Ursachen und liess den Nutzer ohne Anhaltspunkt zurueck.
                        val shiftConfig = shiftState.currentShiftConfig
                        val ladeFehler = calendarState.error ?: shiftState.error
                        val reason = noShiftReason(
                            hasSelectedCalendars = calendarState.selectedCalendarIds.isNotEmpty(),
                            calendarAuthorizationValid = calendarState.calendarAuthorizationValid,
                            kalenderNichtErreichbar = calendarState.kalenderNichtErreichbar,
                            unavailableCalendarCount = calendarState.unavailableCalendarIds.size,
                            errorMessage = ladeFehler,
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
                                errorMessage = ladeFehler,
                                // Nur eine kleine Kostprobe: die Karte soll erklaeren, nicht den
                                // Kalender abbilden (dafuer gibt es "Antippen fuer Details").
                                sampleEventTitles = calendarState.events
                                    .map { it.title }
                                    .distinct()
                                    .take(3),
                                autoAlarmEnabled = shiftConfig?.autoAlarmEnabled != false,
                                masterPausePaused = masterPausePaused
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

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
                masterPausePaused = masterPausePaused,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                trailingContent = {
                    Icon(
                        // dekorativ: reines Weiter-Zeichen, die ganze Karte ist das bedienbare
                        // Element und traegt ihre Beschriftung ueber AlarmStatusHeader selbst
                        Icons.AutoMirrored.Default.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            )
        }

        val zugriffVerloren =
            !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty()
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowEventList,
            colors = CardDefaults.cardColors(
                containerColor = if (zugriffVerloren) {
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
                        // dekorativ: Ueberschrift daneben sagt es bereits ("Kalender-Events"); den
                        // Fehlerzustand nennt der Text darunter ausdruecklich
                        // ("⚠️ Kalender-Autorisierung verloren"), nicht dieses Icon
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                        tint = if (zugriffVerloren) {
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
                
                if (zugriffVerloren) {
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
                    Button(
                        onClick = onReauthorize,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Kalender-Zugriff erneuern")
                    }
                } else if (calendarState.kalenderNichtErreichbar) {
                    // Eigener Zustand statt "Autorisierung verloren" (bis v1.43.4), siehe
                    // CalendarUiState.kalenderNichtErreichbar. Bewusst OHNE Fehlerfarbe: kaputt
                    // ist nichts, die Wecker stehen - es fehlt nur die Verbindung. Der Ausweg ist
                    // derselbe Abgleich wie sonst, mit demselben Namen.
                    Text(
                        "Google Kalender nicht erreichbar",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Die Termine konnten gerade nicht abgerufen werden – meist fehlt die " +
                            "Internetverbindung. Die gestellten Wecker bleiben bestehen.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    KalenderAbgleichKnopf(enabled = !calendarState.isLoading, onClick = onJetztAbgleichen)
                } else if (calendarState.events.isNotEmpty()) {
                    Text("${calendarState.events.size} Events in den nächsten 14 Tagen")
                    Text(
                        "${shiftState.recognizedShifts.size} Schichten erkannt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
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
                    
                    // Die Karte listet selbst keine Events - also keine Anzeigemenge behaupten,
                    // nur auf Nachladbares hinweisen (ui-texte-und-layout.md).
                    if (calendarState.hasMoreEvents) {
                        Text(
                            "Es gibt weitere Termine — in der Terminliste nachladbar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    
                    Text(
                        "Antippen für Details →",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Der Abgleich steht an der Karte, deren Inhalt er erneuert, und traegt Worte
                    // statt eines Symbols in der Kopfzeile (ui-texte-und-layout.md).
                    //
                    // Der Text nennt ausdruecklich den Google Kalender: die App gleicht mit IHM ab,
                    // nicht mit TimeOffice. Sie ist nur die Schnittstelle - was TimeOffice noch
                    // nicht in den Kalender geschrieben hat, kann auch hier nicht auftauchen.
                    // Ohne diesen Satz vermutet man den Fehler in der App statt im Dienstplan.
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL)
                    )
                    KalenderAbgleichKnopf(enabled = !calendarState.isLoading, onClick = onJetztAbgleichen)
                    Text(
                        "Holt die Termine aus deinem Google Kalender und stellt die Wecker " +
                            "danach neu. CF-Alarm liest nur den Kalender — was TimeOffice dort " +
                            "noch nicht eingetragen hat, kann auch hier nicht auftauchen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

/**
 * Der Abgleich mit dem Google Kalender - EIN Vorgang, EIN Name (ui-texte-und-layout.md). Steht in
 * der Karte "Kalender-Events" an zwei Stellen: im Normalfall unter der Zusammenfassung und im
 * Zustand "nicht erreichbar" als Ausweg. Deshalb hier einmal, damit Beschriftung und Aussehen
 * nicht auseinanderlaufen.
 */
@Composable
private fun KalenderAbgleichKnopf(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Refresh,
            // dekorativ: die Beschriftung daneben sagt es vollstaendig
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
        Text("Mit Google Kalender abgleichen")
    }
}
