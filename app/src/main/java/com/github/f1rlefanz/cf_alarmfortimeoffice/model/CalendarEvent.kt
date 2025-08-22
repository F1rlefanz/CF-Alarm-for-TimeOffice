package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import java.time.LocalDateTime

/**
 * IMMUTABLE Calendar Event Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation prevents unnecessary recompositions in Compose
 * ✅ Strukturelle Gleichheit für effiziente distinctUntilChanged() in Flows
 * ✅ Memory-efficient data class für GC-Optimierung
 */
@Immutable
data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val calendarId: String,
    val isAllDay: Boolean = false
)
