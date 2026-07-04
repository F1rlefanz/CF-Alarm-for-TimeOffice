// app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/di/state/CalendarStateHolder.kt

package com.github.f1rlefanz.cf_alarmfortimeoffice.di.state

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized State Holder für die geladenen Kalender-Events.
 *
 * PURPOSE:
 * - Single Source of Truth für die Event-Liste, geteilt zwischen CalendarViewModel und
 *   ShiftViewModel (keine direkten VM-zu-VM-Abhängigkeiten).
 * - Thread-safe über StateFlow.
 *
 * HINWEIS (Audit): Die Kalender-Auswahl und -Verfügbarkeit lebt in [CalendarSelectionRepository]
 * (DataStore). Die frueher hier gespiegelten availableCalendars/selectedCalendarIds waren ein
 * toter Parallel-Zustand (Writer nie aufgerufen) und sind entfernt.
 */
@Singleton
class CalendarStateHolder @Inject constructor() {

    // Calendar Events (der einzige geteilte Zustand)
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    // Loading State (nur Events)
    private val _isLoadingEvents = MutableStateFlow(false)
    val isLoadingEvents: StateFlow<Boolean> = _isLoadingEvents.asStateFlow()

    fun updateEvents(events: List<CalendarEvent>) {
        _events.value = events
    }

    fun setLoadingEvents(isLoading: Boolean) {
        _isLoadingEvents.value = isLoading
    }

    fun clearEvents() {
        _events.value = emptyList()
    }
}
