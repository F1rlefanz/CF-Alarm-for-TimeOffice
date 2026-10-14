package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SchlummerEingabe
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

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

/** Karte „Wie der Wecker klingt“: Schlummer-Dauer, sanfter Anstieg, Probehören. */
@Composable
internal fun KlangKarte(
    snoozeMinutes: Int,
    wecktonAnstieg: WecktonAnstieg,
    probeZustand: ProbeZustand,
    onSnoozeMinutesChange: (Int) -> Unit,
    onAnstiegAktivChange: (Boolean) -> Unit,
    onAnstiegSekundenChange: (Int) -> Unit,
    onAnstiegStartProzentChange: (Int) -> Unit,
    onProbeStart: () -> Unit,
    onProbeStopp: () -> Unit
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
            SchlummerFeld(snoozeMinutes = snoozeMinutes, onSnoozeMinutesChange = onSnoozeMinutesChange)

            HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_MEDIUM))

            WecktonAnstiegAbschnitt(
                anstieg = wecktonAnstieg,
                onAktivChange = onAnstiegAktivChange,
                onSekundenChange = onAnstiegSekundenChange,
                onStartProzentChange = onAnstiegStartProzentChange
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_MEDIUM))

            Probehoeren(zustand = probeZustand, onStart = onProbeStart, onStopp = onProbeStopp)
        }
    }
}

/**
 * Schlummer-Dauer als Zahlenfeld (1 bis 30, Grenzen aus `AlarmPrefs`). Wirkt auf beide
 * Snooze-Ausloeser (Vollbild-Knopf, Benachrichtigungs-Knopf) gleichermassen.
 *
 * Uebernommen wird beim Verlassen des Feldes und bei „Fertig“ - nicht bei jedem Tastendruck: auf
 * dem Weg von "1" zu "15" laege sonst kurz eine 1-Minuten-Schlummerzeit im Speicher. Was nicht
 * passt, korrigiert [SchlummerEingabe.pruefen]: eine leere Eingabe oder keine Zahl behaelt den
 * bisherigen Wert, eine zu kleine oder zu grosse Zahl wird auf 1 bzw. 30 gesetzt. Der kurze
 * Hinweis sagt, welcher Wert jetzt gilt.
 *
 * LAYOUT (Emulator, 14.10.2026): Bereich und Korrektur standen als `supportingText` im Feld und
 * brachen in dessen schmaler Spalte auf zwei bis drei Zeilen. Sie stehen jetzt in voller
 * Kartenbreite UNTER der Zeile; das Feld ist nur so breit wie drei Ziffern plus "Min" und waechst
 * mit der Schriftgroesse.
 */
@Composable
private fun SchlummerFeld(snoozeMinutes: Int, onSnoozeMinutesChange: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(snoozeMinutes.toString()) }
    var fokussiert by remember { mutableStateOf(false) }
    var hinweis by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val aktuellerWert by rememberUpdatedState(snoozeMinutes)

    // Den gespeicherten Wert nur ins Feld holen, solange niemand tippt - sonst ueberschriebe die
    // erste Emission nach dem Laden eine gerade begonnene Eingabe.
    LaunchedEffect(snoozeMinutes) {
        if (!fokussiert) text = snoozeMinutes.toString()
    }

    fun uebernehmen() {
        val ergebnis = SchlummerEingabe.pruefen(text, fallback = aktuellerWert)
        text = ergebnis.wert.toString()
        hinweis = ergebnis.hinweis?.let { schlummerHinweisText(it, ergebnis.wert) }
        if (ergebnis.wert != aktuellerWert) onSnoozeMinutesChange(ergebnis.wert)
    }

    val feldBreite = SCHLUMMER_FELD_BREITE * LocalDensity.current.fontScale.coerceAtLeast(1f)

    Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL)) {
        InhaltUndBedienung(
            inhalt = { modifier ->
                TitelUndBeschreibung(
                    titel = "Schlummer-Dauer",
                    beschreibung = "Gilt für Vollbild und Benachrichtigung",
                    modifier = modifier
                )
            }
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { neu ->
                    // Nur Ziffern, hoechstens drei: mehr ergibt nie einen gueltigen Wert, und ein
                    // Feld, das endlos waechst, sprengt die Zeile.
                    text = neu.filter { it.isDigit() }.take(3)
                    hinweis = null
                },
                singleLine = true,
                suffix = { Text("Min") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                // „Fertig“ gibt nur den Fokus ab - uebernommen wird EINMAL, im Fokusverlust darunter.
                // Ein zweites uebernehmen() hier liefe auf dem schon korrigierten Wert und loeschte
                // den Hinweis wieder, bevor ihn jemand sieht.
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .width(feldBreite)
                    .semantics { contentDescription = "Schlummer-Dauer in Minuten" }
                    .onFocusChanged { zustand ->
                        if (fokussiert && !zustand.isFocused) uebernehmen()
                        fokussiert = zustand.isFocused
                    }
            )
        }
        // Bereich bzw. Korrektur in voller Breite; die Korrektur wird vorgelesen, sobald sie erscheint.
        Text(
            hinweis ?: SCHLUMMER_BEREICH,
            style = MaterialTheme.typography.bodySmall,
            color = if (hinweis != null) MaterialTheme.colorScheme.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/**
 * Breite des Schlummer-Felds bei Schrift 1,0: Innenabstand (2 x 16 dp) plus drei Ziffern und "Min".
 * Mit der Schriftgroesse skaliert - bei 1,3 waeren es sonst abgeschnittene Ziffern.
 */
private val SCHLUMMER_FELD_BREITE = 96.dp

/**
 * Der sanfte Weckton-Anstieg: Schalter plus die beiden Werte, die ihn beschreiben.
 *
 * ## Warum daneben KEINE Ton- oder Lautstaerkeauswahl steht
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
 * „Probehören“ - spielt den Weckton mit dem gespeicherten Anstieg (`WecktonProbe`).
 *
 * STOPP BEIM VERLASSEN: Die Probe gehoert einem Prozess-Singleton, nicht diesem Bildschirm, und
 * endet von selbst erst nach Anlaufzeit + 5 s. Wer die App verlaesst (ON_STOP) oder den Tab
 * wechselt (onDispose), will keinen Weckton im Hintergrund - und der Text oben verspricht genau
 * das. Ein `stop()` ohne laufende Probe ist harmlos.
 */
@Composable
private fun Probehoeren(zustand: ProbeZustand, onStart: () -> Unit, onStopp: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val stopp by rememberUpdatedState(onStopp)
    DisposableEffect(lifecycleOwner) {
        val beobachter = LifecycleEventObserver { _, ereignis ->
            if (ereignis == Lifecycle.Event.ON_STOP) stopp()
        }
        lifecycleOwner.lifecycle.addObserver(beobachter)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(beobachter)
            stopp()
        }
    }

    val laeuft = zustand as? ProbeZustand.Laeuft
    Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
        InhaltUndBedienung(
            inhalt = { modifier ->
                // bodySmall wie die Beschreibung von „Sanft lauter werden“ darueber (SwitchRow).
                Text(
                    laeuft?.let { probeLaeuftText(it) } ?: PROBE_BESCHREIBUNG,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = modifier
                )
            }
        ) {
            // Ein Knopf, der seinen Zustand zeigt: waehrend der Probe heisst er „Stopp“.
            if (laeuft != null) {
                Button(onClick = onStopp) { Text("Stopp") }
            } else {
                OutlinedButton(onClick = onStart) { Text("Probehören") }
            }
        }
        (zustand as? ProbeZustand.Aus)?.fehler?.let { fehler ->
            Text(
                probeFehlerText(fehler),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.warning
            )
        }
    }
}
