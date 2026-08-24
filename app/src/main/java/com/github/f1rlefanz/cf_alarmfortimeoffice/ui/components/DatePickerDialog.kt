package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Date Picker Dialog Component
 * 
 * Ermöglicht die Auswahl eines Datums für manuelle Alarme.
 * Nutzt Material3 DatePickerDialog für konsistente UX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Frueheste waehlbare Tag - `null` heisst "keine Grenze" (bisheriges Verhalten, so nutzen es
     * die manuellen Wecker).
     *
     * WARUM ES DIE GRENZE GIBT: "Tag freigeben" laesst sonst einen Tag in der VERGANGENHEIT
     * waehlen. Der wird beim naechsten Lesen sofort weggeraeumt (Aufraeumgrenze heute-1) - der
     * Nutzer tippt also einen Knopf, und nichts passiert. Ein Bedienelement, das sichtbar nichts
     * tut, ist schlimmer als eines, das gar nicht erst anbietet. Am Emulator aufgefallen
     * (24.08.2026), nachdem versehentlich der 10.08. freigegeben wurde.
     */
    fruehesterTag: LocalDate? = null
) {
    val zone = ZoneId.systemDefault()
    val grenzeMillis = fruehesterTag?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli(),
        selectableDates = object : SelectableDates {
            // Der DatePicker rechnet in UTC-Mitternacht; die Grenze wird deshalb ebenfalls auf
            // den Kalendertag zurueckgerechnet statt roh in Millis verglichen.
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val grenze = grenzeMillis ?: return true
                return Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() >=
                    Instant.ofEpochMilli(grenze).atZone(zone).toLocalDate()
            }

            override fun isSelectableYear(year: Int): Boolean {
                val grenze = fruehesterTag ?: return true
                return year >= grenze.year
            }
        }
    )
    
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedLocalDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        onDateSelected(selectedLocalDate)
                    }
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    ) {
        DatePicker(
            state = datePickerState,
            showModeToggle = false
        )
    }
}
