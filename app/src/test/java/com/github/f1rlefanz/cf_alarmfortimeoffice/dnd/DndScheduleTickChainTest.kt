package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pendant zu `DimScheduleTickChainTest` fuer die eigene DND-Tick-Kette
 * ([DndScheduleUseCase.fallbackTick], eigener Request-Code, bewusst unabhaengig vom Dimmer).
 *
 * Betroffener Realfall: nur "Waehrend der Dienstzeit" aktiv, eine Urlaubswoche ohne Alarme ->
 * Fensterliste leer -> der letzte Tick cancelte den Alarm. Der neue Dienstplan wurde danach von
 * Aufrufern synchronisiert, die DND nicht nacharmieren; "Waehrend der Dienstzeit" blieb bis zum
 * naechsten Reboot wirkungslos.
 */
class DndScheduleTickChainTest {

    private val now = 1_770_000_000_000L

    @Test
    fun `Ohne aktive Fenster-Quelle bleibt die Kette self-cleaning`() {
        assertNull(
            DndScheduleUseCase.fallbackTick(now, alarmReadFailed = false, anySourceEnabled = false)
        )
    }

    @Test
    fun `Aktive Fenster-Quelle ohne Fenster bekommt einen Keep-alive-Tick`() {
        val next = DndScheduleUseCase.fallbackTick(now, alarmReadFailed = false, anySourceEnabled = true)
        assertEquals(now + 6 * 60 * 60_000L, next)
    }

    @Test
    fun `Ein Lesefehler des Alarm-Bestands fuehrt zu einem kurzen Retry statt zum Kettenabbruch`() {
        val next = DndScheduleUseCase.fallbackTick(now, alarmReadFailed = true, anySourceEnabled = true)
        assertEquals(now + 15 * 60_000L, next)
    }
}
