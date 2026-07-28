package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndShiftSpanResolver.AlarmSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit-Tests der reinen Zeitmathematik des "Waehrend der Dienstzeit"-Triggers ([DndShiftSpanResolver]).
 */
class DndShiftSpanResolverTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `Schicht mit bekannter Start- und Endzeit ergibt genau die Kalender-Spanne`() {
        val start = ep(2026, 1, 15, 6, 0)
        val end = ep(2026, 1, 15, 14, 0)
        val slot = AlarmSlot(shiftName = "Fruehschicht", shiftStartTime = start, shiftEndTime = end)

        val spans = DndShiftSpanResolver.buildShiftSpans(listOf(slot), excludedShifts = emptySet())

        assertEquals(1, spans.size)
        assertEquals(start, spans[0].first)
        assertEquals(end, spans[0].last)
    }

    @Test
    fun `Ueber Mitternacht laufende Schicht ergibt eine durchgehende Spanne`() {
        val start = ep(2026, 1, 15, 22, 0)
        val end = ep(2026, 1, 16, 6, 0)
        val slot = AlarmSlot(shiftName = "Nachtschicht", shiftStartTime = start, shiftEndTime = end)

        val spans = DndShiftSpanResolver.buildShiftSpans(listOf(slot), excludedShifts = emptySet())

        assertEquals(1, spans.size)
        assertEquals(start, spans[0].first)
        assertEquals(end, spans[0].last)
        assertTrue(spans[0].contains(ep(2026, 1, 16, 0, 0)))
    }

    @Test
    fun `Ausgeschlossene Schicht liefert kein Fenster`() {
        val slot = AlarmSlot(
            shiftName = "Rufbereitschaft",
            shiftStartTime = ep(2026, 1, 15, 8, 0),
            shiftEndTime = ep(2026, 1, 15, 16, 0)
        )

        val spans = DndShiftSpanResolver.buildShiftSpans(listOf(slot), excludedShifts = setOf("Rufbereitschaft"))

        assertTrue(spans.isEmpty())
    }

    @Test
    fun `Unbekannte Start- oder Endzeit (0) liefert kein Fenster`() {
        val onlyEnd = AlarmSlot(shiftName = "Manuell", shiftStartTime = 0, shiftEndTime = ep(2026, 1, 15, 16, 0))
        val onlyStart = AlarmSlot(shiftName = "Manuell", shiftStartTime = ep(2026, 1, 15, 8, 0), shiftEndTime = 0)
        val neither = AlarmSlot(shiftName = "Manuell", shiftStartTime = 0, shiftEndTime = 0)

        val spans = DndShiftSpanResolver.buildShiftSpans(
            listOf(onlyEnd, onlyStart, neither),
            excludedShifts = emptySet()
        )

        assertTrue(spans.isEmpty())
    }

    @Test
    fun `Endzeit vor oder gleich Startzeit liefert kein Fenster`() {
        val invalid = AlarmSlot(
            shiftName = "Kaputt",
            shiftStartTime = ep(2026, 1, 15, 16, 0),
            shiftEndTime = ep(2026, 1, 15, 8, 0)
        )

        val spans = DndShiftSpanResolver.buildShiftSpans(listOf(invalid), excludedShifts = emptySet())

        assertTrue(spans.isEmpty())
    }

    @Test
    fun `Mehrere Schichten liefern mehrere unabhaengige Spannen`() {
        val a = AlarmSlot("Fruehschicht", ep(2026, 1, 15, 6, 0), ep(2026, 1, 15, 14, 0))
        val b = AlarmSlot("Spaetschicht", ep(2026, 1, 16, 14, 0), ep(2026, 1, 16, 22, 0))

        val spans = DndShiftSpanResolver.buildShiftSpans(listOf(a, b), excludedShifts = emptySet())

        assertEquals(2, spans.size)
    }
}
