package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/** Sub-State: Kalender-Laden, Auswahl und Token-Gültigkeit. */
@Immutable
data class CalendarOperationState(
    val calendarsLoading: Boolean = false,
    val hasSelectedCalendars: Boolean = false,
    val hasValidToken: Boolean = false,
    val tokenChecked: Boolean = false // GATE: true once the initial token validity check has completed
) {
    val needsTokenReauthorization: Boolean get() =
        !hasValidToken && !calendarsLoading

    // GATE: signed-in users without a valid calendar token must (re-)authorize before the main UI.
    // Guarded by tokenChecked so we never gate before the initial check has run.
    val needsCalendarAuthorization: Boolean get() =
        tokenChecked && !hasValidToken

    companion object {
        val EMPTY = CalendarOperationState()
    }
}
