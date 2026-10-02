package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KalenderVorausschauPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/** Titel der Karte - als Konstante, damit Hinweistexte ihn wortgleich nennen koennen. */
internal const val KALENDER_VORAUSSCHAU_TITEL = "Kalender-Vorausschau"

/** Der Knopf am Zahlenfeld. */
internal const val KALENDER_VORAUSSCHAU_UEBERNEHMEN = "Übernehmen"

/**
 * REINE FUNKTION: der Satz, der den eingestellten Zustand ABLESBAR macht. `null` = noch nicht
 * gelesen - dann wird keine Zahl behauptet.
 */
internal fun kalenderVorausschauBeschreibung(tage: Int?): String =
    if (tage == null) {
        "Wie weit CF-Alarm im Kalender nach Schichten sucht."
    } else {
        "CF-Alarm liest die nächsten $tage Tage aus deinem Kalender und stellt für die " +
            "erkannten Schichten darin Wecker."
    }

/**
 * Der Hinweis zur Wirkung einer Aenderung. Er sagt nur, was der Code wirklich tut: beim Verkuerzen
 * bleiben Wecker hinter dem neuen Fenster stehen (`AlarmUseCase.syncAlarms()` Schritt 1,
 * `BootAlarmValidation`), und nach dem Speichern laedt `CalendarViewModel` neu.
 */
internal const val KALENDER_VORAUSSCHAU_HINWEIS =
    "Nach einer Änderung werden die Termine neu geladen. Beim Verkürzen bleiben bereits " +
        "gestellte Wecker dahinter erhalten."

/**
 * Einstellungskarte fuer die Kalender-Vorausschau (#51): Schnellwahl (2/4/6/8 Wochen) plus freies
 * Zahlenfeld mit "Übernehmen".
 *
 * WARUM KEIN SLIDER UND KEIN SPEICHERN PRO TASTENDRUCK: jede Aenderung kostet einen Kalenderabruf
 * und einen Alarm-Sync. Ein Slider oder ein Feld, das bei "2" (auf dem Weg zu "28") schon speichert,
 * loeste mehrere davon aus - mit Zwischenwerten, die der Nutzer nie wollte.
 *
 * Ungueltiges wird mit Text ABGELEHNT ([meldung]), nicht still auf die Grenze gesetzt.
 *
 * Zustandslos: Wert und Meldung kommen vom Aufrufer (`KalenderVorausschauViewModel`).
 *
 * @param onUebernehmen liefert `true`, wenn die Eingabe angenommen wurde - dann wird das Feld geleert.
 */
@Composable
fun KalenderVorausschauKarte(
    tage: Int?,
    meldung: String?,
    onChip: (Int) -> Unit,
    onUebernehmen: (String) -> Boolean,
    onEingabeGeaendert: () -> Unit
) {
    var eingabe by rememberSaveable { mutableStateOf("") }

    fun uebernehmen() {
        if (onUebernehmen(eingabe)) eingabe = ""
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DateRange,
                    // dekorativ: der Titel daneben sagt es bereits
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(KALENDER_VORAUSSCHAU_TITEL, style = MaterialTheme.typography.titleMedium)
                    Text(
                        kalenderVorausschauBeschreibung(tage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KalenderVorausschauPrefs.SCHNELLWAHL_TAGE.forEach { wert ->
                    FilterChip(
                        selected = tage == wert,
                        onClick = { onChip(wert) },
                        label = { Text("$wert Tage") }
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = eingabe,
                    onValueChange = {
                        eingabe = it
                        onEingabeGeaendert()
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = {
                        Text("Tage (${KalenderVorausschauPrefs.MIN_TAGE}–${KalenderVorausschauPrefs.MAX_TAGE})")
                    },
                    isError = meldung != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { uebernehmen() })
                )
                CompactButton(
                    onClick = { uebernehmen() },
                    text = KALENDER_VORAUSSCHAU_UEBERNEHMEN,
                    enabled = eingabe.isNotBlank()
                )
            }

            if (meldung != null) {
                Text(
                    meldung,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                KALENDER_VORAUSSCHAU_HINWEIS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
