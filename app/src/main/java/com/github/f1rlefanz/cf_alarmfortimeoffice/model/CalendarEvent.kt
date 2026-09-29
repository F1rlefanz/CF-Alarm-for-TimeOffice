package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import java.time.LocalDateTime

/** Ein Termin aus den ausgewaehlten Kalendern. */
@Immutable
data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val calendarId: String,
    val isAllDay: Boolean = false
)
