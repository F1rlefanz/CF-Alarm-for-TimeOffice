package com.github.f1rlefanz.cf_alarmfortimeoffice.model.state

import androidx.compose.runtime.Immutable

/**
 * IMMUTABLE Sub-State für Calendar Operations
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ GRANULAR STATE MODELING: Fokussiert auf Calendar-spezifische Operations
 * ✅ Single Responsibility: Nur Calendar Operation States
 * ✅ Loading States: Granular loading indicators
 * ✅ Business Logic: Calendar-specific state logic
 */
@Immutable
data class CalendarOperationState(
    val calendarsLoading: Boolean = false,
    val autoAlarmEnabled: Boolean = false,
    val nextShiftAlarm: String? = null,
    val hasSelectedCalendars: Boolean = false,
    val eventsLoading: Boolean = false
) {
    // Computed properties für Business Logic
    val isOperational: Boolean get() = 
        !calendarsLoading && autoAlarmEnabled && hasSelectedCalendars
    
    val hasUpcomingAlarm: Boolean get() = nextShiftAlarm != null
    
    val isFullyConfigured: Boolean get() = 
        hasSelectedCalendars && autoAlarmEnabled
    
    val needsCalendarSelection: Boolean get() = 
        !hasSelectedCalendars && !calendarsLoading
    
    val isReady: Boolean get() = 
        isFullyConfigured && !calendarsLoading && !eventsLoading
    
    companion object {
        val EMPTY = CalendarOperationState()
        
        fun loading() = CalendarOperationState(calendarsLoading = true)
        
        fun configured(
            autoAlarmEnabled: Boolean = true,
            hasSelectedCalendars: Boolean = true
        ) = CalendarOperationState(
            calendarsLoading = false,
            autoAlarmEnabled = autoAlarmEnabled,
            hasSelectedCalendars = hasSelectedCalendars
        )
        
        fun withAlarm(alarm: String) = CalendarOperationState(
            calendarsLoading = false,
            autoAlarmEnabled = true,
            nextShiftAlarm = alarm,
            hasSelectedCalendars = true
        )
    }
}
