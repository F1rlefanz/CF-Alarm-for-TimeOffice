package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import java.time.LocalDateTime

data class ShiftMatch(
    val shiftDefinition: ShiftDefinition,
    val calendarEvent: CalendarEvent,
    val calculatedAlarmTime: LocalDateTime
) {
    val calendarEventTitle: String
        get() = calendarEvent.title
        
    val eventStartTime: LocalDateTime
        get() = calendarEvent.startTime
        
    val alarmTime: java.util.Calendar
        get() = java.util.Calendar.getInstance().apply {
            time = java.util.Date.from(
                calculatedAlarmTime.atZone(java.time.ZoneId.systemDefault()).toInstant()
            )
        }
}
