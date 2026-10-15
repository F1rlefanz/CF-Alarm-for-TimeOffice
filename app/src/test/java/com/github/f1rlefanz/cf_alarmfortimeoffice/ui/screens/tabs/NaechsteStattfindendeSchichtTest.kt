package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * #142: "Nächste Schicht" nennt die naechste Schicht, die STATTFINDET - eine Schicht an einem
 * freigegebenen Tag (eigene Freigabe oder Krank laut Kalender) faellt aus und wird uebersprungen.
 * Anker ist die Weckzeit, nicht der Schichtbeginn.
 */
class NaechsteStattfindendeSchichtTest {

    private val jetzt = LocalDateTime.of(2026, 10, 15, 13, 0)

    private fun schicht(name: String, beginn: LocalDateTime, wecker: LocalDateTime) = ShiftInfo(
        id = name,
        shiftType = ShiftType(name = name, displayName = name),
        startTime = beginn,
        endTime = beginn.plusHours(8),
        alarmTime = wecker
    )

    private val frueh17 = schicht("F17", LocalDateTime.of(2026, 10, 17, 6, 0), LocalDateTime.of(2026, 10, 17, 5, 30))
    private val frueh18 = schicht("F18", LocalDateTime.of(2026, 10, 18, 6, 0), LocalDateTime.of(2026, 10, 18, 5, 30))

    @Test
    fun `ohne Freigabe ist es die naechste kuenftige Schicht`() {
        assertEquals("F17", naechsteStattfindendeSchicht(listOf(frueh18, frueh17), emptySet(), jetzt)?.id)
    }

    @Test
    fun `eine Schicht am freigegebenen Tag wird uebersprungen`() {
        assertEquals(
            "F18",
            naechsteStattfindendeSchicht(listOf(frueh17, frueh18), setOf(LocalDate.of(2026, 10, 17)), jetzt)?.id
        )
    }

    @Test
    fun `der Anker ist die Weckzeit - eine Nachtschicht mit Wecker am Vortag faellt mit dem Vortag aus`() {
        // Beginn Sa 00:30, Wecker Fr 23:30: freigegeben ist der FREITAG.
        val nacht = schicht("N", LocalDateTime.of(2026, 10, 17, 0, 30), LocalDateTime.of(2026, 10, 16, 23, 30))
        assertEquals("F18", naechsteStattfindendeSchicht(listOf(nacht, frueh18), setOf(LocalDate.of(2026, 10, 16)), jetzt)?.id)
        assertEquals("N", naechsteStattfindendeSchicht(listOf(nacht, frueh18), setOf(LocalDate.of(2026, 10, 17)), jetzt)?.id)
    }

    @Test
    fun `fallen alle kommenden Schichten aus, gibt es keine`() {
        assertNull(
            naechsteStattfindendeSchicht(
                listOf(frueh17, frueh18),
                setOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)),
                jetzt
            )
        )
    }
}
