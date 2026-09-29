package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/** Sub-State: Kalender-Laden, Auswahl, Auto-Alarm und Token-Gültigkeit. */
@Immutable
data class CalendarOperationState(
    val calendarsLoading: Boolean = false,
    val autoAlarmEnabled: Boolean = false,
    val hasSelectedCalendars: Boolean = false,
    val eventsLoading: Boolean = false,
    val hasValidToken: Boolean = false,
    val tokenChecked: Boolean = false // GATE: true once the initial token validity check has completed
) {
    val isOperational: Boolean get() =
        !calendarsLoading && autoAlarmEnabled && hasSelectedCalendars && hasValidToken

    val isFullyConfigured: Boolean get() =
        hasSelectedCalendars && autoAlarmEnabled && hasValidToken

    val needsCalendarSelection: Boolean get() =
        !hasSelectedCalendars && !calendarsLoading

    val needsTokenReauthorization: Boolean get() =
        !hasValidToken && !calendarsLoading

    // GATE: signed-in users without a valid calendar token must (re-)authorize before the main UI.
    // Guarded by tokenChecked so we never gate before the initial check has run.
    val needsCalendarAuthorization: Boolean get() =
        tokenChecked && !hasValidToken

    val isReady: Boolean get() =
        isFullyConfigured && !calendarsLoading && !eventsLoading

    companion object {
        val EMPTY = CalendarOperationState()

        fun configured(
            autoAlarmEnabled: Boolean = true,
            hasSelectedCalendars: Boolean = true,
            hasValidToken: Boolean = true
        ) = CalendarOperationState(
            calendarsLoading = false,
            autoAlarmEnabled = autoAlarmEnabled,
            hasSelectedCalendars = hasSelectedCalendars,
            hasValidToken = hasValidToken
        )
    }
}
