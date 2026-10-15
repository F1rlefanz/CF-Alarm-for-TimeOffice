package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.TagFreigabeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.kurzesDatum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * #142: „Dienst fällt aus“ fuer einen Zeitraum. Die reinen Umrechnungen und Texte stehen oben,
 * damit sie als JVM-Test pruefbar sind; der Composable setzt nur zusammen.
 */

/**
 * #142: Material3-Datepicker liefern UTC-Mitternacht. Umrechnung IMMER ueber [ZoneOffset.UTC],
 * nie ueber `ZoneId.systemDefault()`: in einer Zone westlich von UTC (New York) waere UTC-Mitternacht
 * noch der VORTAG - freigegeben wuerde ein anderer Tag, als der Nutzer angetippt hat.
 */
internal fun ausPickerMillis(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/** Gegenrichtung zu [ausPickerMillis] - fuer die Vorauswahl. */
internal fun inPickerMillis(datum: LocalDate): Long =
    datum.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * #142: Tage von [von] bis [bis] einschliesslich - dieselbe Zaehlung wie
 * `TagFreigabeUseCase.freigebenZeitraum`, sonst liesse der Dialog einen Zeitraum zu, den der
 * UseCase abweist (oder umgekehrt).
 */
private fun anzahlTage(von: LocalDate, bis: LocalDate): Long = ChronoUnit.DAYS.between(von, bis) + 1

/**
 * PURE, TESTBAR: darf „Freigeben“ getippt werden? Beginn ist Pflicht, ein fehlendes Ende heisst
 * „nur dieser Tag“. Die Obergrenze prueft erst diese Funktion: `SelectableDates` sieht immer nur
 * EINEN Tag, nie den Beginn des Zeitraums (Plan #142, Machbarkeit 3).
 */
internal fun zeitraumGueltig(von: LocalDate?, bis: LocalDate?): Boolean {
    if (von == null) return false
    val ende = bis ?: von
    return !ende.isBefore(von) && anzahlTage(von, ende) <= TagFreigabeUseCase.MAX_ZEITRAUM_TAGE
}

/**
 * PURE, TESTBAR: die Zeile unter dem Kalender. Sie sagt, was „Freigeben“ jetzt tut - oder warum
 * der Knopf gesperrt ist (ein ausgegrauter Knopf ohne Grund daneben waere ein toter Knopf).
 */
internal fun zeitraumZusammenfassung(von: LocalDate?, bis: LocalDate?): String {
    if (von == null || (bis != null && bis.isBefore(von))) {
        return "Wähle den ersten Tag, bei mehreren Tagen auch den letzten."
    }
    val ende = bis ?: von
    val tage = anzahlTage(von, ende)
    return when {
        tage > TagFreigabeUseCase.MAX_ZEITRAUM_TAGE ->
            "Höchstens ${TagFreigabeUseCase.MAX_ZEITRAUM_TAGE} Tage auf einmal."
        tage == 1L -> "${kurzesDatum(von)}: kein Wecker aus dem Dienstplan, eigene Wecker bleiben."
        else -> "${kurzesDatum(von)} – ${kurzesDatum(ende)} ($tage Tage): kein Wecker aus dem " +
            "Dienstplan, eigene Wecker bleiben."
    }
}

/**
 * #142: Auswahl eines Zeitraums fuer „Dienst fällt aus“ - Beginn Pflicht, Ende optional (= nur
 * ein Tag), frueheste Auswahl heute, hoechstens [TagFreigabeUseCase.MAX_ZEITRAUM_TAGE] Tage.
 *
 * Ein eigener Dialog statt des geteilten `DatePickerDialog` aus `ui.components`: den nutzt der
 * manuelle Wecker weiter fuer EINEN Tag. Zurueck schliesst den Dialog von selbst
 * (`onDismissRequest`, ein `Dialog` ist ein eigenes Fenster) - ein neuer `NavigationState` entsteht
 * nicht.
 *
 * VOLLBILD statt des Material3-`DatePickerDialog`: der setzt seiner Flaeche in Material3 1.4.0
 * `requiredWidth(360.dp)` (DatePickerDialog.android.kt), bei 320 dp (Fairphone mit grosser
 * Anzeige) ragte er 20 dp je Seite aus dem Fenster - Sonntagsspalte, Titelanfang und der rechte
 * Rand von „Freigeben“ abgeschnitten. Das Raster selbst braucht 7 x 40 dp plus 2 x 12 dp Rand und
 * passt. Material empfiehlt fuer den Bereichs-Picker ohnehin den Vollbild-Dialog.
 *
 * Kein Tag vor heute: eine Freigabe in der Vergangenheit raeumte der Store beim naechsten Lesen
 * sofort weg, der Tap bliebe sichtbar wirkungslos (Hergang cfalarm-wecker-und-boot/reference/
 * tag-freigeben.md).
 *
 * @param beginn vorausgewaehlter erster Tag (der angetippte Tag bzw. heute).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DienstFaelltAusDialog(
    beginn: LocalDate,
    heute: LocalDate,
    onFreigeben: (von: LocalDate, bis: LocalDate) -> Unit,
    onAbbrechen: () -> Unit
) {
    val auswaehlbar = remember(heute) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !ausPickerMillis(utcTimeMillis).isBefore(heute)

            override fun isSelectableYear(year: Int): Boolean = year >= heute.year
        }
    }
    val zustand = rememberDateRangePickerState(
        initialSelectedStartDateMillis = inPickerMillis(maxOf(beginn, heute)),
        initialSelectedEndDateMillis = null,
        selectableDates = auswaehlbar
    )
    // In der Komposition gelesen: Zusammenfassung und Knopf folgen jeder Auswahl sofort.
    val von = zustand.selectedStartDateMillis?.let(::ausPickerMillis)
    val bis = zustand.selectedEndDateMillis?.let(::ausPickerMillis)
    val gueltig = zeitraumGueltig(von, bis)

    Dialog(
        onDismissRequest = onAbbrechen,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                DateRangePicker(
                    state = zustand,
                    modifier = Modifier.weight(1f),
                    title = {
                        Text(
                            "Dienst fällt aus",
                            modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                        )
                    },
                    showModeToggle = false
                )
                Text(
                    zeitraumZusammenfassung(von, bis),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                KnopfZeile(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    TextButton(onClick = onAbbrechen) { Text("Abbrechen") }
                    TextButton(
                        onClick = { if (von != null && gueltig) onFreigeben(von, bis ?: von) },
                        enabled = gueltig
                    ) { Text("Freigeben") }
                }
            }
        }
    }
}
