package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import java.time.LocalDateTime

/**
 * Represents a matched shift from calendar events.
 * 
 * Contains the shift definition, the matching calendar event, and the calculated alarm time.
 * Access event properties directly via calendarEvent (e.g., calendarEvent.title, calendarEvent.startTime).
 */
data class ShiftMatch(
    val shiftDefinition: ShiftDefinition,
    val calendarEvent: CalendarEvent,
    val calculatedAlarmTime: LocalDateTime
)
