package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Datumsauswahl fuer manuelle Wecker.
 *
 * #142: der Picker rechnet in UTC-Mitternacht - Umrechnung deshalb in BEIDE Richtungen ueber
 * [ZoneOffset.UTC]. Vorher ueber `ZoneId.systemDefault()`: in Berlin lag die Vorauswahl auf
 * 22:00/23:00 UTC des Vortags, der Picker zeigte den VORTAG vorausgewaehlt; westlich von UTC
 * lieferte die Bestaetigung den Vortag des angetippten Tages. „Dienst fällt aus“ nutzt seit #142
 * einen eigenen Dialog (`DienstFaelltAusDialog`).
 *
 * #142: eigener `Dialog` statt Material3-`DatePickerDialog` - der erzwingt in Material3 1.4.0
 * `requiredWidth(360.dp)` und ragte bei 320 dp ueber den Fensterrand (Sonntagsspalte und
 * „OK“ abgeschnitten). Hier hoechstens 360 dp, sonst die volle Fensterbreite; das Raster braucht
 * 7 x 40 dp plus 2 x 12 dp Rand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
        ) {
            Column {
                DatePicker(
                    state = datePickerState,
                    showModeToggle = false
                )
                KnopfZeile(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Abbrechen")
                    }
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                val selectedLocalDate = Instant.ofEpochMilli(millis)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                onDateSelected(selectedLocalDate)
                            }
                        }
                    ) {
                        Text("OK")
                    }
                }
            }
        }
    }
}
