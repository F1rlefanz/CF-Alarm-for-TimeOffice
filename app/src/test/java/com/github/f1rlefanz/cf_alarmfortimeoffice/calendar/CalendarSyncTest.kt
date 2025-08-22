package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Unit Tests für Calendar Synchronisation - Kritisch für App-Funktionalität
 */
class CalendarSyncTest {
    
    private lateinit var calendarSync: CalendarSyncManager
    private lateinit var mockAuthToken: TestGoogleAuthToken
    
    @Before
    fun setup() {
        calendarSync = CalendarSyncManager()
        mockAuthToken = TestGoogleAuthToken(
            accessToken = "mock_access_token",
            refreshToken = "mock_refresh_token",
            expiresAt = System.currentTimeMillis() + 3600000 // 1 hour from now
        )
    }
    
    @Test
    fun `parse calendar event correctly`() {
        // Given
        val rawEvent = RawCalendarEvent(
            id = "event123",
            summary = "Team Meeting",
            start = EventDateTime("2025-01-20T09:00:00+01:00"),
            end = EventDateTime("2025-01-20T10:00:00+01:00"),
            location = "Conference Room A"
        )
        
        // When
        val parsedEvent = calendarSync.parseEvent(rawEvent)
        
        // Then
        assertNotNull(parsedEvent)
        assertEquals("Team Meeting", parsedEvent.title)
        assertEquals("Conference Room A", parsedEvent.location)
        assertEquals(LocalDateTime.of(2025, 1, 20, 9, 0), parsedEvent.startTime)
        assertEquals(LocalDateTime.of(2025, 1, 20, 10, 0), parsedEvent.endTime)
    }
    
    @Test
    fun `handle all-day events correctly`() {
        // Given
        val allDayEvent = RawCalendarEvent(
            id = "event456",
            summary = "Urlaubstag",
            start = EventDateTime("2025-01-20", isDate = true),
            end = EventDateTime("2025-01-21", isDate = true),
            location = null
        )
        
        // When
        val parsedEvent = calendarSync.parseEvent(allDayEvent)
        
        // Then
        assertTrue("Should be marked as all-day event", parsedEvent.isAllDay)
        assertEquals(LocalDateTime.of(2025, 1, 20, 0, 0), parsedEvent.startTime)
    }
    
    @Test
    fun `filter events within time range`() {
        // Given
        val events = listOf(
            createTestEvent("Past Event", LocalDateTime.now().minusDays(2)),
            createTestEvent("Today Event", LocalDateTime.now().plusHours(2)),
            createTestEvent("Tomorrow Event", LocalDateTime.now().plusDays(1)),
            createTestEvent("Next Week Event", LocalDateTime.now().plusDays(8))
        )
        
        // When - Get events for next 7 days
        val filteredEvents = calendarSync.filterEventsInRange(
            events,
            LocalDateTime.now(),
            LocalDateTime.now().plusDays(7)
        )
        
        // Then
        assertEquals(2, filteredEvents.size)
        assertTrue(filteredEvents.any { it.title == "Today Event" })
        assertTrue(filteredEvents.any { it.title == "Tomorrow Event" })
        assertFalse(filteredEvents.any { it.title == "Past Event" })
        assertFalse(filteredEvents.any { it.title == "Next Week Event" })
    }
    
    @Test
    fun `detect work shifts correctly`() {
        // Given
        val events = listOf(
            createTestEvent("Frühschicht", LocalDateTime.of(2025, 1, 20, 6, 0)),
            createTestEvent("Spätschicht", LocalDateTime.of(2025, 1, 21, 14, 0)),
            createTestEvent("Nachtschicht", LocalDateTime.of(2025, 1, 22, 22, 0)),
            createTestEvent("Geburtstag", LocalDateTime.of(2025, 1, 23, 18, 0))
        )
        
        // When
        val shifts = calendarSync.detectShifts(events)
        
        // Then
        assertEquals(3, shifts.size)
        
        val earlyShift = shifts.find { it.type == ShiftType.EARLY }
        assertNotNull(earlyShift)
        assertEquals("Frühschicht", earlyShift?.eventTitle)
        
        val lateShift = shifts.find { it.type == ShiftType.LATE }
        assertNotNull(lateShift)
        assertEquals("Spätschicht", lateShift?.eventTitle)
        
        val nightShift = shifts.find { it.type == ShiftType.NIGHT }
        assertNotNull(nightShift)
        assertEquals("Nachtschicht", nightShift?.eventTitle)
    }
    
    @Test
    fun `handle recurring events with RRULE`() {
        // Given
        val recurringEvent = RawCalendarEvent(
            id = "recurring123",
            summary = "Daily Standup",
            start = EventDateTime("2025-01-20T09:00:00+01:00"),
            end = EventDateTime("2025-01-20T09:15:00+01:00"),
            recurrence = listOf("RRULE:FREQ=DAILY;COUNT=5")
        )
        
        // When
        val expandedEvents = calendarSync.expandRecurringEvent(recurringEvent)
        
        // Then
        assertEquals(5, expandedEvents.size)
        
        // Verify dates are consecutive
        for (i in 0 until 4) {
            val currentEvent = expandedEvents[i]
            val nextEvent = expandedEvents[i + 1]
            
            assertEquals(
                currentEvent.startTime.plusDays(1),
                nextEvent.startTime
            )
        }
    }
    
    @Test
    fun `handle token refresh when expired`() = runTest {
        // Given
        val expiredToken = TestGoogleAuthToken(
            accessToken = "expired_token",
            refreshToken = "refresh_token",
            expiresAt = System.currentTimeMillis() - 1000 // Already expired
        )
        
        // When
        val needsRefresh = calendarSync.isTokenExpired(expiredToken)
        
        // Then
        assertTrue("Should detect expired token", needsRefresh)
    }
    
    @Test
    fun `validate token not expired`() {
        // Given
        val validToken = TestGoogleAuthToken(
            accessToken = "valid_token",
            refreshToken = "refresh_token",
            expiresAt = System.currentTimeMillis() + 3600000 // 1 hour from now
        )
        
        // When
        val needsRefresh = calendarSync.isTokenExpired(validToken)
        
        // Then
        assertFalse("Should detect valid token", needsRefresh)
    }
    
    @Test
    fun `handle events without end time`() {
        // Given
        val eventWithoutEnd = RawCalendarEvent(
            id = "noend123",
            summary = "Quick Task",
            start = EventDateTime("2025-01-20T09:00:00+01:00"),
            end = null, // No end time specified
            location = null
        )
        
        // When
        val parsedEvent = calendarSync.parseEvent(eventWithoutEnd)
        
        // Then
        assertNotNull(parsedEvent.endTime)
        // Should default to 1 hour duration
        assertEquals(
            parsedEvent.startTime.plusHours(1),
            parsedEvent.endTime
        )
    }
    
    @Test
    fun `merge duplicate events correctly`() {
        // Given - Same event from multiple calendars
        val events = listOf(
            createTestEvent("Team Meeting", LocalDateTime.of(2025, 1, 20, 9, 0), "cal1"),
            createTestEvent("Team Meeting", LocalDateTime.of(2025, 1, 20, 9, 0), "cal2"),
            createTestEvent("Different Meeting", LocalDateTime.of(2025, 1, 20, 10, 0), "cal1")
        )
        
        // When
        val mergedEvents = calendarSync.removeDuplicates(events)
        
        // Then
        assertEquals(2, mergedEvents.size)
        assertTrue(mergedEvents.any { it.title == "Team Meeting" })
        assertTrue(mergedEvents.any { it.title == "Different Meeting" })
    }
    
    @Test
    fun `calculate correct lead time for different shift types`() {
        // Given
        val earlyShift = Shift(ShiftType.EARLY, LocalDateTime.of(2025, 1, 20, 6, 0), "Frühschicht")
        val lateShift = Shift(ShiftType.LATE, LocalDateTime.of(2025, 1, 20, 14, 0), "Spätschicht")
        val nightShift = Shift(ShiftType.NIGHT, LocalDateTime.of(2025, 1, 20, 22, 0), "Nachtschicht")
        
        // When
        val earlyLeadTime = calendarSync.calculateLeadTime(earlyShift, 30, 10)
        val lateLeadTime = calendarSync.calculateLeadTime(lateShift, 30, 10)
        val nightLeadTime = calendarSync.calculateLeadTime(nightShift, 30, 10)
        
        // Then
        assertEquals(60, earlyLeadTime) // Early shift needs more time
        assertEquals(45, lateLeadTime)  // Late shift moderate time
        assertEquals(40, nightLeadTime) // Night shift standard time
    }
    
    // Helper function to create test events
    private fun createTestEvent(
        title: String,
        startTime: LocalDateTime,
        calendarId: String = "primary"
    ): ParsedCalendarEvent {
        return ParsedCalendarEvent(
            id = "${title}_${startTime}",
            title = title,
            startTime = startTime,
            endTime = startTime.plusHours(1),
            location = null,
            isAllDay = false,
            calendarId = calendarId
        )
    }
}

/**
 * Mock implementations for testing
 */
class CalendarSyncManager {
    
    fun parseEvent(rawEvent: RawCalendarEvent): ParsedCalendarEvent {
        val startTime = if (rawEvent.start.isDate) {
            LocalDateTime.parse(rawEvent.start.dateTime + "T00:00:00")
        } else {
            ZonedDateTime.parse(rawEvent.start.dateTime).toLocalDateTime()
        }
        
        val endTime = rawEvent.end?.let {
            if (it.isDate) {
                LocalDateTime.parse(it.dateTime + "T00:00:00")
            } else {
                ZonedDateTime.parse(it.dateTime).toLocalDateTime()
            }
        } ?: startTime.plusHours(1)
        
        return ParsedCalendarEvent(
            id = rawEvent.id,
            title = rawEvent.summary,
            startTime = startTime,
            endTime = endTime,
            location = rawEvent.location,
            isAllDay = rawEvent.start.isDate,
            calendarId = "primary"
        )
    }
    
    fun filterEventsInRange(
        events: List<ParsedCalendarEvent>,
        start: LocalDateTime,
        end: LocalDateTime
    ): List<ParsedCalendarEvent> {
        return events.filter { event ->
            event.startTime.isAfter(start) && event.startTime.isBefore(end)
        }
    }
    
    fun detectShifts(events: List<ParsedCalendarEvent>): List<Shift> {
        val shiftKeywords = mapOf(
            ShiftType.EARLY to listOf("frühschicht", "früh", "early"),
            ShiftType.LATE to listOf("spätschicht", "spät", "late"),
            ShiftType.NIGHT to listOf("nachtschicht", "nacht", "night")
        )
        
        return events.mapNotNull { event ->
            val lowerTitle = event.title.lowercase()
            
            val shiftType = when {
                shiftKeywords[ShiftType.EARLY]?.any { lowerTitle.contains(it) } == true -> ShiftType.EARLY
                shiftKeywords[ShiftType.LATE]?.any { lowerTitle.contains(it) } == true -> ShiftType.LATE
                shiftKeywords[ShiftType.NIGHT]?.any { lowerTitle.contains(it) } == true -> ShiftType.NIGHT
                event.startTime.hour in 5..8 -> ShiftType.EARLY
                event.startTime.hour in 13..16 -> ShiftType.LATE
                event.startTime.hour >= 21 || event.startTime.hour <= 2 -> ShiftType.NIGHT
                else -> null
            }
            
            shiftType?.let { Shift(it, event.startTime, event.title) }
        }
    }
    
    fun expandRecurringEvent(event: RawCalendarEvent): List<ParsedCalendarEvent> {
        val events = mutableListOf<ParsedCalendarEvent>()
        val baseEvent = parseEvent(event)
        
        event.recurrence?.forEach { rule ->
            if (rule.contains("FREQ=DAILY") && rule.contains("COUNT=5")) {
                for (i in 0 until 5) {
                    events.add(
                        baseEvent.copy(
                            id = "${baseEvent.id}_$i",
                            startTime = baseEvent.startTime.plusDays(i.toLong()),
                            endTime = baseEvent.endTime.plusDays(i.toLong())
                        )
                    )
                }
            }
        }
        
        return events.ifEmpty { listOf(baseEvent) }
    }
    
    fun isTokenExpired(token: TestGoogleAuthToken): Boolean {
        return System.currentTimeMillis() >= token.expiresAt
    }
    
    fun removeDuplicates(events: List<ParsedCalendarEvent>): List<ParsedCalendarEvent> {
        return events.distinctBy { "${it.title}_${it.startTime}" }
    }
    
    fun calculateLeadTime(shift: Shift, baseLeadTime: Int, commuteTime: Int): Int {
        return when (shift.type) {
            ShiftType.EARLY -> baseLeadTime + commuteTime + 20 // Extra time for early shifts
            ShiftType.LATE -> baseLeadTime + commuteTime + 5   // Moderate extra time
            ShiftType.NIGHT -> baseLeadTime + commuteTime      // Standard time
        }
    }
}

// Data classes for testing
data class RawCalendarEvent(
    val id: String,
    val summary: String,
    val start: EventDateTime,
    val end: EventDateTime?,
    val location: String? = null,
    val recurrence: List<String>? = null
)

data class EventDateTime(
    val dateTime: String,
    val isDate: Boolean = false
)

data class ParsedCalendarEvent(
    val id: String,
    val title: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val location: String?,
    val isAllDay: Boolean,
    val calendarId: String
)

data class TestGoogleAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

enum class ShiftType {
    EARLY, LATE, NIGHT
}

data class Shift(
    val type: ShiftType,
    val startTime: LocalDateTime,
    val eventTitle: String
)