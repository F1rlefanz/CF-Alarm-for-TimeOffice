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
 * Datumsauswahl fuer manuelle Wecker und "Tag freigeben".
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
      * WARUM: ein Tag in der Vergangenheit wird sofort weggeraeumt - der Knopf taete sichtbar nichts.
      * Hergang cfalarm-wecker-und-boot/reference/tag-freigeben.md.
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
