package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.DatePickerDialog
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.uhrBeimFortsetzen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.ANDERER_TAG_KNOPF
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.Abschnittstitel
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.KlangKarte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.NICHT_WECKEN_NUR_NAECHSTER
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.TagDetails
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.TagKarte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.TagesAktionen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.WeckerZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.alsSkipLage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.leerzustandText
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.weckerZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.zustandsHinweisText
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtWirkung
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TagFreigabeUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.baueWeckerTage
import java.time.LocalDate
import java.time.ZoneId

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

/**
 * Der Wecker-Tab als Tagesliste (Issue #139, Variante A).
 *
 * REIHENFOLGE: Zustandshinweis (hoechstens einer) -> Liste der gestellten Wecker -> „Wie der Wecker
 * klingt“ -> „Automatik“. Zuerst steht, was als Naechstes passiert; Einstellungen darunter. Die
 * Schichttypen sind Einrichtung und stehen seit #139 in den Einstellungen.
 *
 * Die Liste ist eine `Column` mit `key(datum)` statt einer `LazyColumn`: sie steht in einer
 * scrollenden Spalte (eine `LazyColumn` darin bekaeme unendliche Hoehe), und mehr als die Tage der
 * Kalender-Vorausschau werden es nie.
 *
 * @param masterPausePaused bewusst OHNE Default (Anzeigepfad, Skill cfalarm-ui-und-navigation).
 * @param wirkungen Schichtname -> Wirkung, aus `ShiftViewModel.schichtFolgen` (siehe
 *   `weckerWirkungen`).
 */
@Composable
fun WeckerTabContent(
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    skipState: AlarmSkipUiState,
    tagFreigabeState: TagFreigabeUiState,
    wirkungen: Map<String, SchichtWirkung>,
    vorausschauTage: Int,
    snoozeMinutes: Int,
    wecktonAnstieg: WecktonAnstieg,
    probeZustand: ProbeZustand,
    masterPausePaused: Boolean,
    onUpdateShiftConfig: (ShiftConfig) -> Unit,
    onResumeMasterPause: () -> Unit,
    /** „Nicht wecken“ - mit dem Weckzeitpunkt der Zeile, die der Nutzer gesehen hat. */
    onSkipNextAlarm: (angezeigtesZiel: Long) -> Unit,
    /** „Doch wecken“. */
    onCancelSkip: () -> Unit,
    /** „Dienst fällt aus“. */
    onTagFreigeben: (LocalDate) -> Unit,
    /** „Zurücknehmen“. */
    onFreigabeZuruecknehmen: (LocalDate) -> Unit,
    onSnoozeMinutesChange: (Int) -> Unit,
    onAnstiegAktivChange: (Boolean) -> Unit,
    onAnstiegSekundenChange: (Int) -> Unit,
    onAnstiegStartProzentChange: (Int) -> Unit,
    onProbeStart: () -> Unit,
    onProbeStopp: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val skipLage = skipState.alsSkipLage()
    // Die Liste rechnet bei jeder Aenderung ihrer Eingaben neu - mit der Uhr von DIESEM Moment.
    // Einen Ticker gibt es nicht. Ein Wecker, der klingelt und beendet wird, aendert den Bestand
    // nicht; deshalb zwei weitere Schluessel:
    // - nextRingingAlarmTriggerTime: lehnt AlarmViewModel.skipNextAlarm() ein veraltetes Ziel ab,
    //   erneuert es genau dieses Feld (der Bestand bleibt derselbe). Ohne den Schluessel blieb die
    //   Zeile mit „Nicht wecken“ am verstrichenen Wecker stehen, jeder Tipp wurde erneut abgelehnt,
    //   und der Hinweis „Die Liste zeigt jetzt den nächsten Wecker“ stimmte nicht.
    // - uhrBeimFortsetzen(): wer nach dem Wecken in die App zurueckkehrt, sieht frisch gerechnet.
    // Was dazwischen veralten kann, faengt die Ziel-Nachrechnung in skipNextAlarm() ab (die Zeile
    // reicht ihren Weckzeitpunkt mit).
    val fortgesetzt = uhrBeimFortsetzen()
    val tage = remember(
        alarmState.activeAlarms,
        alarmState.nextRingingAlarmTriggerTime,
        tagFreigabeState.freieTage,
        skipLage,
        wirkungen,
        fortgesetzt
    ) {
        baueWeckerTage(
            alarme = alarmState.activeAlarms,
            freieTage = tagFreigabeState.freieTage,
            skip = skipLage,
            jetzt = System.currentTimeMillis(),
            zone = zone,
            wirkungen = wirkungen
        )
    }
    val heute = LocalDate.now(zone)
    val config = shiftState.currentShiftConfig
    val zustand = weckerZustand(masterPausePaused, config?.autoAlarmEnabled)
    // Beide Vorgaenge teilen sich im ViewModel EINE Wiedereintrittssperre (`skipVorgangLaeuft`):
    // waehrend einer laeuft, kehrt jeder Tap wortlos zurueck. Also sind waehrenddessen ALLE
    // Knoepfe gesperrt, nicht nur der des laufenden Vorgangs.
    val gesperrt = skipState.isLoading || tagFreigabeState.isLoading
    val aktionen = TagesAktionen(
        gesperrt = gesperrt,
        skipLaeuft = skipState.isLoading,
        onNichtWecken = onSkipNextAlarm,
        onDochWecken = onCancelSkip,
        onDienstFaelltAus = onTagFreigeben,
        onZuruecknehmen = onFreigabeZuruecknehmen
    )

    // Der geoeffnete Tag als Epochentag: ein LocalDate laesst sich nicht im Bundle sichern.
    var offenerTag by rememberSaveable { mutableStateOf<Long?>(null) }
    var datumsauswahlOffen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
    ) {
        zustand?.let {
            ZustandsHinweis(
                zustand = it,
                onHandlung = when (it) {
                    WeckerZustand.MASTER_PAUSE -> onResumeMasterPause
                    // Der Hinweis entsteht nur bei geladener Konfiguration (weckerZustand).
                    WeckerZustand.AUTOMATIK_AUS -> {
                        { if (config != null) onUpdateShiftConfig(config.copy(autoAlarmEnabled = true)) }
                    }
                }
            )
        }

        // Ausgang des letzten Vorgangs, sofern er nicht einfach aufging. Steht AUSSERHALB der
        // Liste: nach einem „Nicht wecken“ oder „Dienst fällt aus“ kann es den Tag, an dem
        // getippt wurde, nicht mehr geben - die Meldung verschwaende mit ihm.
        listOfNotNull(skipState.restoreNotice, tagFreigabeState.hinweis).forEach { meldung ->
            Text(
                meldung,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.warning
            )
        }

        Abschnittstitel("Gestellte Wecker")

        if (tage.isEmpty()) {
            Text(
                leerzustandText(zustand, vorausschauTage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tage.forEach { tag ->
                key(tag.datum) {
                    TagKarte(
                        tag = tag,
                        heute = heute,
                        aktionen = aktionen,
                        onOeffnen = { offenerTag = tag.datum.toEpochDay() }
                    )
                }
            }
        }

        if (tage.any { it.eintraege.isNotEmpty() }) {
            Text(
                NICHT_WECKEN_NUR_NAECHSTER,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { datumsauswahlOffen = true }, enabled = !gesperrt) {
            Text(ANDERER_TAG_KNOPF)
        }

        Abschnittstitel("Wie der Wecker klingt")
        KlangKarte(
            snoozeMinutes = snoozeMinutes,
            wecktonAnstieg = wecktonAnstieg,
            probeZustand = probeZustand,
            onSnoozeMinutesChange = onSnoozeMinutesChange,
            onAnstiegAktivChange = onAnstiegAktivChange,
            onAnstiegSekundenChange = onAnstiegSekundenChange,
            onAnstiegStartProzentChange = onAnstiegStartProzentChange,
            onProbeStart = onProbeStart,
            onProbeStopp = onProbeStopp
        )

        Abschnittstitel("Automatik")
        AutomatikKarte(shiftState = shiftState, masterPausePaused = masterPausePaused, onUpdateShiftConfig = onUpdateShiftConfig)
    }

    offenerTag?.let { epochentag ->
        val tag = tage.firstOrNull { it.datum.toEpochDay() == epochentag }
        if (tag != null) {
            TagDetails(
                tag = tag,
                heute = heute,
                wirkungen = wirkungen,
                aktionen = aktionen,
                onSchliessen = { offenerTag = null }
            )
        } else {
            // Der Tag ist aus der Liste verschwunden (Wecker verstrichen, Sync) - nicht wieder
            // aufspringen lassen, falls er spaeter zurueckkommt.
            LaunchedEffect(epochentag) { offenerTag = null }
        }
    }

    if (datumsauswahlOffen) {
        DatePickerDialog(
            selectedDate = heute,
            onDateSelected = { gewaehlt ->
                datumsauswahlOffen = false
                onTagFreigeben(gewaehlt)
            },
            onDismiss = { datumsauswahlOffen = false },
            // Kein Tag in der Vergangenheit: die Freigabe wuerde beim naechsten Lesen sofort
            // weggeraeumt, der Tap bliebe wirkungslos. Begruendung an fruehesterTag.
            fruehesterTag = heute
        )
    }
}

/**
 * Ein Zustand, der die App dauerhaft nicht wecken laesst, steht OBEN - dort, wo der Nutzer
 * nachsieht, nicht nur an seinem Schalter weiter unten. Mit Handlung, damit der Weg zurueck nicht
 * erst gesucht werden muss. Additiv gezeigt (Rand + Farbe), nie per `surfaceVariant`.
 */
@Composable
private fun ZustandsHinweis(zustand: WeckerZustand, onHandlung: () -> Unit) {
    val text = zustandsHinweisText(zustand)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.warning)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                text.titel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.warning
            )
            Text(text.text, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onHandlung) { Text(text.handlung) }
        }
    }
}

/** Der Schalter „Automatische Alarme“ allein - mit seinen beiden Sperrgruenden. */
@Composable
private fun AutomatikKarte(
    shiftState: ShiftUiState,
    masterPausePaused: Boolean,
    onUpdateShiftConfig: (ShiftConfig) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                // autoAlarmSchalterBedienbar().
                enabled = autoAlarmSchalterBedienbar(
                    shiftConfigGeladen = shiftState.currentShiftConfig != null,
                    masterPausePaused = masterPausePaused
                ),
                titleStyle = MaterialTheme.typography.titleMedium
            )
        }
    }
}
