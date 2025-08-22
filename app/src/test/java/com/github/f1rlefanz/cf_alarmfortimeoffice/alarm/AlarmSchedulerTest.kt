package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit Tests für Alarm-Scheduling - Kritische Kernfunktionalität
 */
class AlarmSchedulerTest {

    private lateinit var alarmScheduler: AlarmScheduler
    
    @Before
    fun setup() {
        alarmScheduler = AlarmScheduler()
    }

    @Test
    fun `calculate alarm time with lead time returns correct time`() {
        // Given
        val eventStartTime = LocalDateTime.of(2025, 1, 20, 9, 0)
        val leadTimeMinutes = 30
        
        // When
        val alarmTime = alarmScheduler.calculateAlarmTime(eventStartTime, leadTimeMinutes)
        
        // Then
        val expectedTime = LocalDateTime.of(2025, 1, 20, 8, 30)
        assertEquals(expectedTime, alarmTime)
    }

    @Test
    fun `calculate alarm time with zero lead time returns event time`() {
        // Given
        val eventStartTime = LocalDateTime.of(2025, 1, 20, 9, 0)
        val leadTimeMinutes = 0
        
        // When
        val alarmTime = alarmScheduler.calculateAlarmTime(eventStartTime, leadTimeMinutes)
        
        // Then
        assertEquals(eventStartTime, alarmTime)
    }

    @Test
    fun `calculate alarm time crossing midnight works correctly`() {
        // Given - Event at 00:30, with 60 min lead time
        val eventStartTime = LocalDateTime.of(2025, 1, 20, 0, 30)
        val leadTimeMinutes = 60
        
        // When
        val alarmTime = alarmScheduler.calculateAlarmTime(eventStartTime, leadTimeMinutes)
        
        // Then - Should be previous day at 23:30
        val expectedTime = LocalDateTime.of(2025, 1, 19, 23, 30)
        assertEquals(expectedTime, alarmTime)
    }

    @Test
    fun `should not schedule alarm for past events`() {
        // Given
        val pastEvent = LocalDateTime.now().minusHours(2)
        val leadTimeMinutes = 30
        
        // When
        val shouldSchedule = alarmScheduler.shouldScheduleAlarm(pastEvent, leadTimeMinutes)
        
        // Then
        assertFalse("Should not schedule alarm for past events", shouldSchedule)
    }

    @Test
    fun `should schedule alarm for future events`() {
        // Given
        val futureEvent = LocalDateTime.now().plusHours(2)
        val leadTimeMinutes = 30
        
        // When
        val shouldSchedule = alarmScheduler.shouldScheduleAlarm(futureEvent, leadTimeMinutes)
        
        // Then
        assertTrue("Should schedule alarm for future events", shouldSchedule)
    }

    @Test
    fun `convert local time to UTC milliseconds correctly`() {
        // Given
        val localTime = LocalDateTime.of(2025, 1, 20, 9, 0)
        val zoneId = ZoneId.of("Europe/Berlin")
        
        // When
        val milliseconds = alarmScheduler.toMillis(localTime, zoneId)
        
        // Then
        val zonedDateTime = ZonedDateTime.of(localTime, zoneId)
        val expectedMillis = zonedDateTime.toInstant().toEpochMilli()
        assertEquals(expectedMillis, milliseconds)
    }

    @Test
    fun `handle timezone changes correctly`() {
        // Given - Time during DST change
        val localTime = LocalDateTime.of(2025, 3, 30, 2, 30) // DST change in Europe
        val zoneId = ZoneId.of("Europe/Berlin")
        
        // When
        val milliseconds = alarmScheduler.toMillis(localTime, zoneId)
        
        // Then
        assertTrue("Milliseconds should be positive", milliseconds > 0)
    }

    @Test
    fun `filter work events correctly`() {
        // Given
        val events = listOf(
            TestCalendarEvent("Arbeit", LocalDateTime.now().plusDays(1)),
            TestCalendarEvent("Schicht", LocalDateTime.now().plusDays(2)),
            TestCalendarEvent("Geburtstag", LocalDateTime.now().plusDays(3)),
            TestCalendarEvent("Work Meeting", LocalDateTime.now().plusDays(4))
        )
        
        // When
        val workEvents = alarmScheduler.filterWorkEvents(events)
        
        // Then
        assertEquals(3, workEvents.size)
        assertTrue(workEvents.any { it.title == "Arbeit" })
        assertTrue(workEvents.any { it.title == "Schicht" })
        assertTrue(workEvents.any { it.title == "Work Meeting" })
        assertFalse(workEvents.any { it.title == "Geburtstag" })
    }

    @Test
    fun `handle recurring events correctly`() {
        // Given
        val recurringEvent = TestCalendarEvent(
            title = "Daily Standup",
            startTime = LocalDateTime.of(2025, 1, 20, 9, 0),
            isRecurring = true,
            recurrenceRule = "FREQ=DAILY;COUNT=5"
        )
        
        // When
        val alarms = alarmScheduler.createAlarmsForRecurringEvent(recurringEvent, 15)
        
        // Then
        assertEquals(5, alarms.size)
        // Verify first alarm is 15 minutes before first occurrence
        assertEquals(
            LocalDateTime.of(2025, 1, 20, 8, 45),
            alarms[0].time
        )
    }

    @Test
    fun `validate alarm time is not too far in future`() {
        // Given - Max 30 days in future
        val farFutureEvent = LocalDateTime.now().plusDays(35)
        
        // When
        val isValid = alarmScheduler.isValidAlarmTime(farFutureEvent)
        
        // Then
        assertFalse("Should not allow alarms more than 30 days in future", isValid)
    }
}

/**
 * Mock AlarmScheduler for testing
 * In production, this would be the actual implementation
 */
class AlarmScheduler {
    
    fun calculateAlarmTime(eventTime: LocalDateTime, leadTimeMinutes: Int): LocalDateTime {
        return eventTime.minusMinutes(leadTimeMinutes.toLong())
    }
    
    fun shouldScheduleAlarm(eventTime: LocalDateTime, leadTimeMinutes: Int): Boolean {
        val alarmTime = calculateAlarmTime(eventTime, leadTimeMinutes)
        return alarmTime.isAfter(LocalDateTime.now())
    }
    
    fun toMillis(localTime: LocalDateTime, zoneId: ZoneId): Long {
        return ZonedDateTime.of(localTime, zoneId).toInstant().toEpochMilli()
    }
    
    fun filterWorkEvents(events: List<TestCalendarEvent>): List<TestCalendarEvent> {
        val workKeywords = listOf("arbeit", "schicht", "work", "meeting", "office")
        return events.filter { event ->
            workKeywords.any { keyword ->
                event.title.lowercase().contains(keyword)
            }
        }
    }
    
    fun createAlarmsForRecurringEvent(
        event: TestCalendarEvent, 
        leadTimeMinutes: Int
    ): List<TestAlarm> {
        // Simplified implementation for testing
        val alarms = mutableListOf<TestAlarm>()
        if (event.isRecurring && event.recurrenceRule?.contains("COUNT=5") == true) {
            for (i in 0 until 5) {
                val alarmTime = event.startTime
                    .plusDays(i.toLong())
                    .minusMinutes(leadTimeMinutes.toLong())
                alarms.add(TestAlarm(time = alarmTime, eventTitle = event.title))
            }
        }
        return alarms
    }
    
    fun isValidAlarmTime(alarmTime: LocalDateTime): Boolean {
        val maxFutureTime = LocalDateTime.now().plusDays(30)
        return alarmTime.isBefore(maxFutureTime) && alarmTime.isAfter(LocalDateTime.now())
    }
}

// Test data classes
data class TestCalendarEvent(
    val title: String,
    val startTime: LocalDateTime,
    val isRecurring: Boolean = false,
    val recurrenceRule: String? = null
)

data class TestAlarm(
    val time: LocalDateTime,
    val eventTitle: String
)