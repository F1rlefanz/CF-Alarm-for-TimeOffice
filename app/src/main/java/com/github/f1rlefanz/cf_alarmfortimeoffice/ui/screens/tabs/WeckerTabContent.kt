package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.AlarmStatusHeader
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.DatePickerDialog
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TagFreigabeUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Auswahlmoeglichkeiten fuer die Schlummer-Dauer-Dropdown - deckt die ueblichen Werte ab. */
private val SNOOZE_MINUTES_OPTIONS = listOf(3, 5, 10, 15)

/**
 * Anlaufzeiten des sanften Weckton-Anstiegs, in Sekunden.
 *
 * Die Liste endet bei 120 s, weil
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs.MAX_ANSTIEG_SEKUNDEN] dort endet - ein laengerer
 * Anlauf ist kein sanfterer Wecker mehr, sondern ein spaeterer.
 */
private val ANSTIEG_SEKUNDEN_OPTIONS = listOf(10, 15, 30, 45, 60, 90, 120)

/**
 * Startlautstaerken in Prozent der am Geraet eingestellten Alarm-Lautstaerke.
 *
 * Kein Wert unter 5 %: darunter ist der Anfang auf den meisten Geraeten schlicht nicht mehr zu
 * hoeren, und ein Wecker, dessen erste Sekunden wirkungslos verstreichen, ist kein sanfter
 * Wecker, sondern ein kuerzerer.
 */
private val ANSTIEG_START_PROZENT_OPTIONS = listOf(5, 10, 15, 25, 40, 60)

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

/**
 * Kurzfassung der Abgrenzung "Ueberspringen" gegen "Tag freigeben" - immer sichtbar.
 *
 * WARUM DIE ERKLAERUNG SEIN MUSS: Bis v1.31.0 gab es nur das Ueberspringen, und die Oberflaeche
 * sagte nirgends, dass es AUSSCHLIESSLICH den Wecker betrifft. Am 24.08.2026 hat der Nutzer
 * deshalb einen Tag, an dem sein Chef ihm freigegeben hatte, per Ueberspringen behandelt - der
 * Wecker blieb korrekt stumm, und um 14:48 Uhr ging punktgenau zum Schichtbeginn "Nicht stoeren"
 * an. Das war kein Fehler, sondern die dokumentierte Absicht (ein uebersprungener Wecker aendert
 * nichts daran, dass der Dienst stattfindet) - nur konnte das aus der Oberflaeche niemand wissen.
 * Zwei Gesten, die sich fast gleich anfuehlen und verschieden wirken, brauchen den Unterschied
 * an genau der Stelle, an der man sich entscheidet.
 */
internal const val FREIGEBEN_HINWEIS_KURZ: String =
    "„Überspringen“ betrifft nur den Wecker, „Tag freigeben“ den ganzen Dienst."

/** Der ausfuehrliche Teil - nach der Hausform: was faellt weg, was kommt wieder, was bleibt. */
internal const val FREIGEBEN_HINWEIS_DETAIL: String =
    "Überspringen lässt genau einen Weckruf aus. Der Dienst bleibt bestehen: „Nicht stören“ und " +
        "das Dimmen richten sich weiter nach der Schicht — gedacht für den Morgen, an dem du " +
        "ohnehin wach bist.\n\n" +
        "Tag freigeben streicht den Dienst selbst. Für diesen Kalendertag wird kein Wecker " +
        "gestellt, und weder „Nicht stören“ noch das Dimmen richten sich noch nach der " +
        "Schicht — der Tag zählt ab dann als freier Tag, mit allem, was für dich an freien Tagen " +
        "gilt (deine Regel für freie Tage). Gedacht für den Tag, an dem du nicht arbeitest, " +
        "obwohl er im Dienstplan steht.\n\n" +
        "Der Termin im Kalender bleibt in beiden Fällen unangetastet. Nimmst du eine Freigabe " +
        "zurück, legt die App den Wecker aus dem Dienstplan neu an."

/** Gesamttext - damit ein Test auf beide Haelften zeigen kann, ohne sie zu duplizieren. */
internal const val FREIGEBEN_HINWEIS: String = FREIGEBEN_HINWEIS_KURZ + "\n\n" + FREIGEBEN_HINWEIS_DETAIL

/**
 * PURE, TESTBAR: Ist der Abschnitt "Tag freigeben" ueberhaupt zu zeigen?
 *
 * Das zweite Kriterium ist tragend und dieselbe Falle wie beim Ueberspringen bis v1.26.2: eine
 * Freigabe LOESCHT die Wecker des Tages. War es der einzige, gibt es danach keinen naechsten
 * Wecker mehr - ohne `freieTage.isNotEmpty()` verschwaende der Abschnitt samt dem einzigen Weg
 * zurueck, obwohl der Zustand ausdruecklich als umkehrbar angeboten wird.
 */
internal fun freigabeAbschnittSichtbar(
    naechsterAlarmTag: LocalDate?,
    freieTage: List<LocalDate>
): Boolean = naechsterAlarmTag != null || freieTage.isNotEmpty()

/** Anzeigeform eines freigegebenen Tages, z. B. "Mo, 24.08.2026". */
internal fun formatiereFreienTag(datum: LocalDate): String =
    datum.format(DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", Locale.GERMAN))

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
    tagFreigabeState: TagFreigabeUiState,
    snoozeMinutes: Int,
    wecktonAnstieg: WecktonAnstieg,
    masterPausePaused: Boolean,
    onUpdateShiftConfig: (ShiftConfig) -> Unit,
    onSkipNextAlarm: () -> Unit,
    onCancelSkip: () -> Unit,
    onTagFreigeben: (LocalDate) -> Unit,
    onFreigabeZuruecknehmen: (LocalDate) -> Unit,
    onShowShiftConfig: () -> Unit,
    onSnoozeMinutesChange: (Int) -> Unit,
    onAnstiegAktivChange: (Boolean) -> Unit,
    onAnstiegSekundenChange: (Int) -> Unit,
    onAnstiegStartProzentChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        
        // Auto-Alarm Switch
        //
        // Farbe und Erhebung ausdruecklich gesetzt, obwohl die Compose-Vorgabe hier dasselbe
        // Weiss liefert: die Status-Karte darunter setzt beides seit jeher selbst, und zwei
        // Inhaltskarten direkt untereinander duerfen nicht aus zwei verschiedenen Quellen
        // stammen - sonst verschiebt eine kuenftige Aenderung an einer der beiden Stellen
        // stillschweigend nur die halbe Liste.
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

                HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_MEDIUM))

                WecktonAnstiegAbschnitt(
                    anstieg = wecktonAnstieg,
                    onAktivChange = onAnstiegAktivChange,
                    onSekundenChange = onAnstiegSekundenChange,
                    onStartProzentChange = onAnstiegStartProzentChange
                )
            }
        }

        // Enhanced Alarm Status Card mit Skip-Funktionalität
        EnhancedAlarmStatusCard(
            alarmState = alarmState,
            skipState = skipState,
            tagFreigabeState = tagFreigabeState,
            masterPausePaused = masterPausePaused,
            onSkipNextAlarm = onSkipNextAlarm,
            onCancelSkip = onCancelSkip,
            onTagFreigeben = onTagFreigeben,
            onFreigabeZuruecknehmen = onFreigabeZuruecknehmen
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
    tagFreigabeState: TagFreigabeUiState,
    masterPausePaused: Boolean,
    onSkipNextAlarm: () -> Unit,
    onCancelSkip: () -> Unit,
    onTagFreigeben: (LocalDate) -> Unit,
    onFreigabeZuruecknehmen: (LocalDate) -> Unit
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

            TagFreigabeAbschnitt(
                naechsterAlarmTag = alarmState.nextAlarmDate,
                tagFreigabeState = tagFreigabeState,
                ueberspringenLaeuft = skipState.isLoading,
                onTagFreigeben = onTagFreigeben,
                onFreigabeZuruecknehmen = onFreigabeZuruecknehmen
            )
        }
    }
}

/**
 * "Tag freigeben": zweite Aktion neben dem Ueberspringen, plus die Liste der freigegebenen Tage
 * und die Erklaerung, wofuer die beiden Gesten jeweils da sind.
 *
 * Der Hauptknopf gibt den Tag des NAECHSTEN Weckers frei (der haeufige Fall: der Chef sagt am
 * Vorabend Bescheid); das Kalender-Symbol daneben oeffnet die vorhandene Datumsauswahl fuer jeden
 * anderen Tag.
 */
@Composable
private fun TagFreigabeAbschnitt(
    naechsterAlarmTag: LocalDate?,
    tagFreigabeState: TagFreigabeUiState,
    /**
     * Laeuft gerade ein Ueberspringen? Dann sind die Knoepfe hier GESPERRT.
     *
     * Beide Vorgaenge teilen sich im ViewModel eine Wiedereintrittssperre (`skipVorgangLaeuft`) -
     * ein Tap waehrenddessen kehrt wortlos zurueck. Ein Knopf, der bedienbar aussieht und still
     * nichts tut, ist schlimmer als ein ausgegrauter: der Nutzer haelt die Freigabe fuer gesetzt.
     */
    ueberspringenLaeuft: Boolean,
    onTagFreigeben: (LocalDate) -> Unit,
    onFreigabeZuruecknehmen: (LocalDate) -> Unit
) {
    val gesperrt = tagFreigabeState.isLoading || ueberspringenLaeuft
    if (!freigabeAbschnittSichtbar(naechsterAlarmTag, tagFreigabeState.freieTage)) return

    var datumsauswahlOffen by rememberSaveable { mutableStateOf(false) }

    HorizontalDivider()

    Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Dienst fällt aus? Tag freigeben:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            // Zwei Bedienelemente teilen sich hier eine Zeile mit dem Text - deshalb der
            // CompactButton: ein normaler Button braeuchte mehr Innenabstand, als auf 360dp
            // uebrig ist, und Compose braeche die Beschriftung mitten im Wort.
            CompactButton(
                onClick = { naechsterAlarmTag?.let(onTagFreigeben) },
                text = "Freigeben",
                icon = Icons.Default.BeachAccess,
                enabled = naechsterAlarmTag != null &&
                    naechsterAlarmTag !in tagFreigabeState.freieTage &&
                    !gesperrt
            )

            IconButton(
                onClick = { datumsauswahlOffen = true },
                enabled = !gesperrt
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    // NICHT dekorativ: dieses Symbol steht ohne Beschriftung fuer sich.
                    contentDescription = "Anderen Tag freigeben"
                )
            }
        }

        naechsterAlarmTag?.let { tag ->
            Text(
                "Betrifft ${formatiereFreienTag(tag)} — den Tag des nächsten Weckers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Ausgang des letzten Vorgangs, sofern er nicht einfach aufging - dieselbe Rolle wie
        // skipState.restoreNotice: der schlechteste Fall darf nicht stumm sein.
        tagFreigabeState.hinweis?.let { hinweis ->
            Text(
                hinweis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.warning
            )
        }

        tagFreigabeState.freieTage.forEach { tag ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.BeachAccess,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.warning,
                    // dekorativ: der Text daneben sagt es bereits
                    contentDescription = null
                )
                Text(
                    "${formatiereFreienTag(tag)} — freigegeben: kein Wecker, kein Dienst",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                CompactOutlinedButton(
                    onClick = { onFreigabeZuruecknehmen(tag) },
                    text = "Aufheben",
                    enabled = !gesperrt
                )
            }
        }

        UeberspringenOderFreigebenHinweis()
    }

    if (datumsauswahlOffen) {
        DatePickerDialog(
            selectedDate = naechsterAlarmTag ?: LocalDate.now(),
            onDateSelected = { gewaehlt ->
                datumsauswahlOffen = false
                onTagFreigeben(gewaehlt)
            },
            onDismiss = { datumsauswahlOffen = false },
            // Kein Tag in der Vergangenheit: die Freigabe wuerde beim naechsten Lesen sofort
            // weggeraeumt, der Tap bliebe wirkungslos. Begruendung an fruehesterTag.
            fruehesterTag = LocalDate.now()
        )
    }
}

/**
 * Erklaert den Unterschied zwischen "Ueberspringen" und "Tag freigeben".
 *
 * Aufbau wie `SchichterkennungsHinweis()` im ShiftConfigScreen, und aus denselben Gruenden:
 * der Kurzsatz bleibt immer sichtbar (der volle Text fuellt bei grosser Systemschrift die halbe
 * Seite), der Umschalter sagt, WAS er zeigt (nicht "Details" oder "i"), und `rememberSaveable`
 * verhindert, dass eine Drehung ihn wieder zuklappt.
 */
@Composable
private fun UeberspringenOderFreigebenHinweis() {
    var ausgeklappt by rememberSaveable { mutableStateOf(false) }

    // `surfaceVariant` ist in der hellen CSJR-Palette derselbe Farbwert wie `background`
    // (beides `OffWhite`, siehe Theme.kt) - eine Karte in dieser Farbe hat auf dem Seiten-
    // hintergrund gar keine sichtbare Flaeche mehr. Ein Hinweis, den man nicht als Hinweis
    // erkennt, ist keiner. Deshalb weisse Flaeche und ein Rand aus der Marken-Randfarbe:
    // dezent abgesetzt von den Inhaltskarten, aber vorhanden. Im dunklen Schema war es nie
    // kaputt (`DarkSurfaceVariant` != `DarkBg`) - die Regel gilt trotzdem fuer beide.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Info,
                // dekorativ: der Text daneben traegt die Aussage
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (ausgeklappt) FREIGEBEN_HINWEIS else FREIGEBEN_HINWEIS_KURZ,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { ausgeklappt = !ausgeklappt }) {
                    Text(
                        if (ausgeklappt) "Weniger anzeigen" else "Wann was benutzen?",
                        style = MaterialTheme.typography.labelLarge
                    )
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
    AuswahlRow(
        titel = "Schlummer-Dauer",
        beschreibung = "Gilt für Vollbild und Benachrichtigung",
        optionen = SNOOZE_MINUTES_OPTIONS,
        beschriftung = { "$it Min" },
        aktuellerWert = snoozeMinutes,
        aenderungsBeschreibung = "Schlummer-Dauer ändern",
        onAuswahl = onSnoozeMinutesChange
    )
}

/**
 * Der sanfte Weckton-Anstieg: Schalter plus die beiden Werte, die ihn beschreiben.
 *
 * ## Warum es diesen Abschnitt gibt - und warum daneben KEINE Ton- oder Lautstaerkeauswahl steht
 *
 * Weckton und Lautstaerke kommen vollstaendig aus den Android-Einstellungen: der Ton ist der
 * Standard-Alarmton des Geraets, die Lautstaerke der Alarm-Regler. Eine eigene Auswahl daneben
 * waere eine zweite Wahrheit ohne Gewinn - und eine Stelle, an der die App leiser sein kann, als
 * der Nutzer glaubt. Das EINE, was Android nicht anbietet, ist ein sanfter Anstieg. Nur der steht
 * hier.
 *
 * ## Warum die beiden Werte nur bei eingeschaltetem Anstieg erscheinen
 *
 * Ausgeschaltet beschreiben sie nichts, was passiert. Ein bedienbares Dropdown, das folgenlos
 * bleibt, ist genau die Art Oberflaeche, die man einmal einstellt und danach fuer wirksam haelt.
 */
@Composable
private fun WecktonAnstiegAbschnitt(
    anstieg: WecktonAnstieg,
    onAktivChange: (Boolean) -> Unit,
    onSekundenChange: (Int) -> Unit,
    onStartProzentChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
    ) {
        SwitchRow(
            title = "Sanft lauter werden",
            // Nennt beide Halbwahrheiten, auf die man sonst kommt: dass die App eine eigene
            // Lautstaerke haette, und dass sie die des Geraets veraendert. Beides ist falsch -
            // der Anstieg endet bei dem, was am Alarm-Regler eingestellt ist.
            description = "Der Wecker beginnt leise und wird bis zu deiner eingestellten " +
                "Alarm-Lautstärke lauter. Die Lautstärke des Geräts bleibt unverändert.",
            checked = anstieg.aktiv,
            onCheckedChange = onAktivChange,
            titleStyle = MaterialTheme.typography.titleMedium
        )

        if (anstieg.aktiv) {
            AuswahlRow(
                titel = "Anlaufzeit",
                beschreibung = "Bis zur vollen Lautstärke",
                optionen = ANSTIEG_SEKUNDEN_OPTIONS,
                beschriftung = { "$it s" },
                aktuellerWert = anstieg.sekunden,
                aenderungsBeschreibung = "Anlaufzeit ändern",
                onAuswahl = onSekundenChange
            )

            AuswahlRow(
                titel = "Startlautstärke",
                beschreibung = "Anteil deiner Alarm-Lautstärke am Anfang",
                optionen = ANSTIEG_START_PROZENT_OPTIONS,
                beschriftung = { "$it %" },
                aktuellerWert = anstieg.startProzent,
                aenderungsBeschreibung = "Startlautstärke ändern",
                onAuswahl = onStartProzentChange
            )
        }
    }
}

/**
 * Beschriftete Zeile mit Auswahlknopf rechts - eine Bauart fuer alle drei Wecker-Einstellungen.
 *
 * Zusammengefasst, als der Anstieg die zweite und dritte Zeile dieser Form brauchte: dieselbe
 * Row, dasselbe Dropdown, dieselbe Barrierefreiheits-Falle dreimal nebeneinander waere dreimal
 * die Gelegenheit, sie an einer Stelle zu vergessen.
 *
 * @param aktuellerWert wird auch dann angezeigt, wenn er nicht in [optionen] steht - der Wert kann
 *   aus einem Import oder einer aelteren Version stammen. Ihn stillschweigend als eine der
 *   Optionen darzustellen, waere eine Anzeige, die luegt.
 */
@Composable
private fun AuswahlRow(
    titel: String,
    beschreibung: String,
    optionen: List<Int>,
    beschriftung: (Int) -> String,
    aktuellerWert: Int,
    aenderungsBeschreibung: String,
    onAuswahl: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.titleMedium)
            Text(
                beschreibung,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(beschriftung(aktuellerWert))
                // Nicht dekorativ: die Beschriftung des Knopfes ist nur der Wert - die
                // Ueberschrift steht daneben in einer eigenen Spalte und wird nicht mitgelesen.
                // Ohne diese Beschreibung meldet der Screenreader den Knopf als blosses "5 Min",
                // ohne zu sagen, was er aendert.
                Icon(Icons.Default.ArrowDropDown, contentDescription = aenderungsBeschreibung)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                optionen.forEach { wert ->
                    DropdownMenuItem(
                        text = { Text(beschriftung(wert)) },
                        onClick = {
                            onAuswahl(wert)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
