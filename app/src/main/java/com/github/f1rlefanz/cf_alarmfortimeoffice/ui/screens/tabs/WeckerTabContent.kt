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

/** Beschreibung des Schalters im Normalfall - beschreibt, was ein Umlegen wirklich tut. */
internal const val AUTO_ALARM_BESCHREIBUNG_NORMAL: String =
    "Deaktivieren löscht sofort alle bereits gesetzten Wecker. Aktivieren erstellt sie aus dem " +
        "letzten bekannten Kalenderstand neu."

/**
 * Beschreibung waehrend der Master-Pause.
 *
 * WARUM SIE SEIN MUSS: Der Satz oben ist in diesem Zustand schlicht FALSCH - geloescht sind die
 * Wecker laengst, und "Aktivieren erstellt sie neu" verspricht etwas, das der Master-Pause-
 * Backstop in `syncAlarms()` sofort abweist. Der Schalter selbst steht dabei weiter auf AN
 * (die Pause ruehrt `autoAlarmEnabled` bewusst nicht an), behauptet also von sich aus das
 * Gegenteil der Lage. Deshalb: Ursache, Folge und der Weg zurueck - und der Schalter wird
 * gesperrt (siehe [autoAlarmSchalterBedienbar]), damit hier kein Zustand bedienbar aussieht,
 * der gerade keine Wirkung hat.
 */
internal const val AUTO_ALARM_BESCHREIBUNG_PAUSIERT: String =
    "Ohne Wirkung, solange alles pausiert ist: es sind bereits alle Wecker gelöscht, und es wird " +
        "keiner neu gestellt."

/** PURE, TESTBAR: welcher der beiden Beschreibungstexte unter dem Schalter steht. */
internal fun autoAlarmBeschreibung(masterPausePaused: Boolean): String =
    if (masterPausePaused) AUTO_ALARM_BESCHREIBUNG_PAUSIERT else AUTO_ALARM_BESCHREIBUNG_NORMAL

/**
 * PURE, TESTBAR: darf der Schalter "Automatische Alarme" bedient werden?
 *
 * Zwei voneinander unabhaengige Gruende, ihn zu sperren - und in BEIDEN Faellen steht das WARUM
 * daneben (Beschreibungstext bzw. der Ladezustand), sonst waere es nur ein toter Schalter:
 *  - `shiftConfigGeladen == false`: kurzes Fenster beim Kaltstart, der Tap wuerde wortlos
 *    verpuffen (`onUpdateShiftConfig` wird nie erreicht).
 *  - `masterPausePaused == true`: der Schalter haette keine sichtbare Wirkung.
 */
internal fun autoAlarmSchalterBedienbar(
    shiftConfigGeladen: Boolean,
    masterPausePaused: Boolean
): Boolean = shiftConfigGeladen && !masterPausePaused

@Composable
fun WeckerTabContent(
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    snoozeMinutes: Int,
    masterPausePaused: Boolean,
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
                    description = autoAlarmBeschreibung(masterPausePaused),
                    checked = shiftState.currentShiftConfig?.autoAlarmEnabled ?: false,
                    onCheckedChange = { enabled ->
                        shiftState.currentShiftConfig?.let { config ->
                            onUpdateShiftConfig(config.copy(autoAlarmEnabled = enabled))
                        }
                    },
                    // Zwei Sperrgruende, beide mit sichtbarer Begruendung daneben - siehe
                    // autoAlarmSchalterBedienbar(). Waehrend ShiftViewModel.loadShiftConfig()
                    // noch laedt (kurzes Fenster beim Kaltstart) ist currentShiftConfig null:
                    // der Tap wuerde sonst wortlos verpuffen (checked bleibt an "?: false"
                    // haengen, onCheckedChange erreicht nie onUpdateShiftConfig).
                    enabled = autoAlarmSchalterBedienbar(
                        shiftConfigGeladen = shiftState.currentShiftConfig != null,
                        masterPausePaused = masterPausePaused
                    ),
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
            masterPausePaused = masterPausePaused,
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
    masterPausePaused: Boolean,
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
            AlarmStatusHeader(
                alarmState = alarmState,
                skipState = skipState,
                masterPausePaused = masterPausePaused
            )

            // Ausgang des letzten "Aufheben", falls der uebersprungene MANUELLE Wecker NICHT
            // zurueckkam: Weckzeit inzwischen verstrichen, gesicherter Stand unlesbar, oder das
            // Speichern/Stellen schlug fehl. Steht bewusst AUSSERHALB des Blocks unten: nach so
            // einem Ausgang gibt es womoeglich weder einen aktiven Alarm noch ein aktives
            // Ueberspringen - der Block waere dann ausgeblendet und die Meldung damit unsichtbar.
            skipState.restoreNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.warning
                )
            }

            // Sichtbar, sobald es etwas zu bedienen gibt: entweder ein Alarm zum Ueberspringen
            // ODER ein aktives Ueberspringen zum Aufheben.
            //
            // Das `|| skipState.isNextAlarmSkipped` ist tragend und fehlte bis v1.26.2. Da
            // skipNextAlarm() den Alarm SOFORT aus dem Repository loescht (SKIP-IMMEDIATE-UX),
            // wird hasActiveAlarms `false`, sobald der uebersprungene Alarm der einzige war - und
            // damit verschwand der gesamte Block INKLUSIVE des einzigen "Aufheben"-Knopfes. Der
            // Nutzer hatte dann keinen Weg mehr zurueck, obwohl der Zustand ausdruecklich als
            // umkehrbar angeboten wird.
            if (alarmState.hasActiveAlarms || skipState.isNextAlarmSkipped) {
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
