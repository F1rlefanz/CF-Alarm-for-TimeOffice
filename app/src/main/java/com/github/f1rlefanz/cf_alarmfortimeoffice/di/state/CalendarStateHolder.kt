// app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/di/state/CalendarStateHolder.kt

package com.github.f1rlefanz.cf_alarmfortimeoffice.di.state

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized State Holder for Calendar-related data
 * 
 * PURPOSE:
 * - Single Source of Truth for calendar state across ViewModels
 * - Decouples ViewModels from each other (no direct VM-to-VM dependencies)
 * - Thread-safe state management through StateFlow
 */
@Singleton
class CalendarStateHolder @Inject constructor() {
    
    // Calendar Events
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()
    
    // Available Calendars
    private val _availableCalendars = MutableStateFlow<List<AndroidCalendar>>(emptyList())
    val availableCalendars: StateFlow<List<AndroidCalendar>> = _availableCalendars.asStateFlow()

    // Selected Calendar IDs
    private val _selectedCalendarIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedCalendarIds: StateFlow<Set<String>> = _selectedCalendarIds.asStateFlow()
    
    // Loading State (nur Events; ein isLoadingCalendars-Flag existierte, wurde aber nie
    // gelesen oder gesetzt -> als toter Code entfernt, Audit).
    private val _isLoadingEvents = MutableStateFlow(false)
    val isLoadingEvents: StateFlow<Boolean> = _isLoadingEvents.asStateFlow()

    // Update Functions
    fun updateEvents(events: List<CalendarEvent>) {
        _events.value = events
    }

    fun updateSelectedCalendarIds(ids: Set<String>) {
        _selectedCalendarIds.value = ids
    }

    fun updateAvailableCalendars(calendars: List<AndroidCalendar>) {
        _availableCalendars.value = calendars
    }

    fun setLoadingEvents(isLoading: Boolean) {
        _isLoadingEvents.value = isLoading
    }

    // Utility Functions
    fun clearEvents() {
        _events.value = emptyList()
    }

    fun hasSelectedCalendars(): Boolean = _selectedCalendarIds.value.isNotEmpty()
}