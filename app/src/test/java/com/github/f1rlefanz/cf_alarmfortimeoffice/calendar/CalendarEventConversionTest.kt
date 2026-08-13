package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.google.api.client.util.DateTime
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Tests fuer [CalendarEventConverter] - die Umrechnung der Google-Calendar-Antwort in interne
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent]-Objekte.
 *
 * Hintergrund: ganztaegige Termine liefert Google in `start.date`/`end.date` (dateOnly), deren
 * Epoch-Wert UTC-MITTERNACHT ist und deren Ende EXKLUSIV gemeint ist. Bis v1.22.1 behandelte der
 * Code beide Felder gleich (`dateTime ?: date`, beides ueber `Instant.ofEpochMilli` in der
 * Systemzone) - ein ganztaegiger Dienstplaneintrag begann dadurch in Europe/Berlin um 01:00/02:00
 * statt um 00:00, und sein Ende lag einen ganzen Tag zu weit.
 *
 * Die Zeitzone wird hier bewusst fest auf Europe/Berlin gestellt: nur so ist der Unterschied
 * zwischen "UTC-Mitternacht in der Systemzone" und "lokaler Tagesbeginn" ueberhaupt sichtbar (in
 * einer UTC-Umgebung waeren beide Werte zufaellig gleich und der Test wertlos).
 */
class CalendarEventConversionTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun allDayEvent(startDate: String, endDateExclusive: String?): Event = Event()
        .setId("all-day-1")
        .setSummary("Nachtschicht")
        .setStart(EventDateTime().setDate(DateTime(startDate)))
        .setEnd(endDateExclusive?.let { EventDateTime().setDate(DateTime(it)) })

    @Test
    fun `ganztaegiger Termin beginnt um lokal 00-00 am Tag des Eintrags`() {
        val event = allDayEvent("2026-08-05", "2026-08-06")

        val result = CalendarEventConverter.toCalendarEvent(event, "cal-1")

        requireNotNull(result)
        assertTrue("isAllDay muss gesetzt sein", result.isAllDay)
        assertEquals(LocalDateTime.of(2026, 8, 5, 0, 0), result.startTime)
    }

    @Test
    fun `ganztaegiger Termin endet am LETZTEN Tag, nicht am exklusiven Folgetag`() {
        val event = allDayEvent("2026-08-05", "2026-08-06")

        val result = CalendarEventConverter.toCalendarEvent(event, "cal-1")

        requireNotNull(result)
        assertEquals(LocalDateTime.of(2026, 8, 5, 23, 59), result.endTime)
    }

    @Test
    fun `mehrtaegiger ganztaegiger Termin endet am letzten enthaltenen Tag`() {
        // 05.08. bis 07.08. einschliesslich -> Google liefert end.date = 08.08.
        val event = allDayEvent("2026-08-05", "2026-08-08")

        val result = CalendarEventConverter.toCalendarEvent(event, "cal-1")

        requireNotNull(result)
        assertEquals(LocalDateTime.of(2026, 8, 5, 0, 0), result.startTime)
        assertEquals(LocalDateTime.of(2026, 8, 7, 23, 59), result.endTime)
    }

    @Test
    fun `ganztaegiger Termin ohne Ende gilt als ein Tag`() {
        val event = allDayEvent("2026-08-05", null)

        val result = CalendarEventConverter.toCalendarEvent(event, "cal-1")

        requireNotNull(result)
        assertEquals(LocalDateTime.of(2026, 8, 5, 0, 0), result.startTime)
        assertEquals(LocalDateTime.of(2026, 8, 5, 23, 59), result.endTime)
    }

    @Test
    fun `ganztaegiger Termin auch im Winter am richtigen Tag um 00-00`() {
        // Winterzeit: UTC-Mitternacht landete hier frueher auf 01:00 lokal.
        val event = allDayEvent("2026-01-15", "2026-01-16")

        val result = CalendarEventConverter.toCalendarEvent(event, "cal-1")

        requireNotNull(result)
        assertEquals(LocalDateTime.of(2026, 1, 15, 0, 0), result.startTime)
        assertEquals(LocalDateTime.of(2026, 1, 15, 23, 59), result.endTime)
    }

    @Test
    fun `zeitgebundener Termin bleibt unveraendert in der Systemzone`() {
        val start = DateTime("2026-08-05T14:48:00+02:00")
        val end = DateTime("2026-08-05T22:00:00+02:00")
        val event = Event()
            .setId("timed-1")
            .setSummary("S2")
            .setStart(EventDateTime().setDateTime(start))
            .setEnd(EventDateTime().setDateTime(end))

        val result = CalendarEventConverter.toCalendarEvent(event, "cal-1")

        requireNotNull(result)
        assertFalse("Ein Termin mit Uhrzeit ist nicht ganztaegig", result.isAllDay)
        assertEquals(
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(start.value), ZoneId.systemDefault()),
            result.startTime
        )
        assertEquals(LocalDateTime.of(2026, 8, 5, 14, 48), result.startTime)
        assertEquals(LocalDateTime.of(2026, 8, 5, 22, 0), result.endTime)
    }

    @Test
    fun `Termin ohne jede Zeitangabe wird verworfen`() {
        val event = Event().setId("broken").setSummary("kaputt")

        assertNull(CalendarEventConverter.toCalendarEvent(event, "cal-1"))
    }
}
