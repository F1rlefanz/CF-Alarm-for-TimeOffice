package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndOnCallCutoffResolver.AlarmSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Unit-Tests der reinen Zeitmathematik der Rufbereitschaft-DND-Schiene ([DndOnCallCutoffResolver]).
 */
class DndOnCallCutoffResolverTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun ep(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // --- cutoffInstants ---

    @Test
    fun `On-Call-Schicht liefert Cutoff auf demselben Kalendertag wie der Schichtbeginn`() {
        val slot = AlarmSlot(shiftName = "AD1", shiftStartTime = ep(2026, 1, 15, 0, 30))

        val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
            listOf(slot), onCallShifts = setOf("AD1"), cutoffMinutes = 5 * 60, zone = zone
        )

        assertEquals(1, cutoffs.size)
        assertEquals(ep(2026, 1, 15, 5, 0), cutoffs[0])
    }

    @Test
    fun `Schicht nicht in onCallShifts liefert keinen Cutoff`() {
        val slot = AlarmSlot(shiftName = "Fruehschicht", shiftStartTime = ep(2026, 1, 15, 6, 0))

        val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
            listOf(slot), onCallShifts = setOf("AD1"), cutoffMinutes = 5 * 60, zone = zone
        )

        assertTrue(cutoffs.isEmpty())
    }

    @Test
    fun `shiftStartTime 0 liefert keinen Cutoff`() {
        val slot = AlarmSlot(shiftName = "AD1", shiftStartTime = 0L)

        val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
            listOf(slot), onCallShifts = setOf("AD1"), cutoffMinutes = 5 * 60, zone = zone
        )

        assertTrue(cutoffs.isEmpty())
    }

    @Test
    fun `Mehrere On-Call-Tage liefern mehrere unabhaengige Cutoffs`() {
        val a = AlarmSlot("AD1", ep(2026, 1, 15, 0, 30))
        val b = AlarmSlot("AD1", ep(2026, 1, 20, 3, 0))

        val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
            listOf(a, b), onCallShifts = setOf("AD1"), cutoffMinutes = 5 * 60, zone = zone
        )

        assertEquals(2, cutoffs.size)
        assertTrue(cutoffs.contains(ep(2026, 1, 15, 5, 0)))
        assertTrue(cutoffs.contains(ep(2026, 1, 20, 5, 0)))
    }

    // --- clip ---

    @Test
    fun `Fenster wird auf den Cutoff geklippt wenn der Cutoff innerhalb liegt`() {
        val window = ep(2026, 1, 14, 22, 0)..ep(2026, 1, 15, 7, 0)
        val cutoff = ep(2026, 1, 15, 5, 0)

        val clipped = DndOnCallCutoffResolver.clip(listOf(window), listOf(cutoff))

        assertEquals(1, clipped.size)
        assertEquals(window.first, clipped[0].first)
        assertEquals(cutoff, clipped[0].last)
    }

    @Test
    fun `Cutoff ausserhalb des Fensters veraendert das Fenster nicht`() {
        val window = ep(2026, 1, 15, 6, 0)..ep(2026, 1, 15, 14, 0)
        // Cutoff liegt vor Fensterbeginn - keine Ueberschneidung.
        val cutoff = ep(2026, 1, 15, 5, 0)

        val clipped = DndOnCallCutoffResolver.clip(listOf(window), listOf(cutoff))

        assertEquals(1, clipped.size)
        assertEquals(window, clipped[0])
    }

    @Test
    fun `Cutoff nach Fensterende veraendert das Fenster nicht`() {
        val window = ep(2026, 1, 15, 1, 0)..ep(2026, 1, 15, 4, 0)
        val cutoff = ep(2026, 1, 15, 5, 0)

        val clipped = DndOnCallCutoffResolver.clip(listOf(window), listOf(cutoff))

        assertEquals(1, clipped.size)
        assertEquals(window, clipped[0])
    }

    @Test
    fun `Ohne Cutoffs bleiben alle Fenster unveraendert`() {
        val window = ep(2026, 1, 14, 22, 0)..ep(2026, 1, 15, 7, 0)

        val clipped = DndOnCallCutoffResolver.clip(listOf(window), emptyList())

        assertEquals(listOf(window), clipped)
    }

    @Test
    fun `Mehrere Fenster werden unabhaengig voneinander geklippt`() {
        val nightOne = ep(2026, 1, 14, 22, 0)..ep(2026, 1, 15, 7, 0)
        val nightTwo = ep(2026, 1, 19, 22, 0)..ep(2026, 1, 20, 7, 0)
        val cutoffOne = ep(2026, 1, 15, 5, 0)
        val cutoffTwo = ep(2026, 1, 20, 5, 30)

        val clipped = DndOnCallCutoffResolver.clip(
            listOf(nightOne, nightTwo),
            listOf(cutoffOne, cutoffTwo)
        )

        assertEquals(2, clipped.size)
        assertEquals(cutoffOne, clipped[0].last)
        assertEquals(cutoffTwo, clipped[1].last)
    }

    @Test
    fun `Mehrere Fenster am selben Tag werden vom selben Cutoff unabhaengig geklippt`() {
        // Zwei sich nicht ueberschneidende Fenster derselben Nacht (z. B. "Folgt Dimmer" plus
        // "Waehrend der Dienstzeit"), beide enden nach dem Cutoff.
        val windowA = ep(2026, 1, 14, 22, 0)..ep(2026, 1, 15, 6, 30)
        val windowB = ep(2026, 1, 15, 0, 0)..ep(2026, 1, 15, 7, 0)
        val cutoff = ep(2026, 1, 15, 5, 0)

        val clipped = DndOnCallCutoffResolver.clip(listOf(windowA, windowB), listOf(cutoff))

        assertEquals(2, clipped.size)
        assertEquals(cutoff, clipped[0].last)
        assertEquals(cutoff, clipped[1].last)
    }
}
