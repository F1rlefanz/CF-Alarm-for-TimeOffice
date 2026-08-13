package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Haelt fest, dass die rollende Dimm-Tick-Kette NICHT abreisst, solange der Dimmer ueberhaupt an ist
 * ([DimScheduleUseCase.fallbackTick]).
 *
 * Vorher cancelte `scheduleNextTransition()` den Alarm, sobald keine Fenstergrenze mehr in der
 * Zukunft lag - danach konnte sich die Kette nicht selbst wiederbeleben. Real: nach einer
 * Urlaubswoche ohne Schichten ist die Fensterliste leer; der spaeter eintreffende Dienstplan wird
 * von Aufrufern synchronisiert (CalendarViewModel/ShiftViewModel/CalendarPreAlarmRefreshWorker),
 * die Dimmer/DND NICHT nacharmieren.
 *
 * Die BEDEUTUNG einer leeren Fensterliste ("heute wird nicht gedimmt", z. B.
 * Nachtdienst-Unterdrueckung) bleibt davon unberuehrt - es wird nur spaeter noch einmal
 * nachgesehen.
 */
class DimScheduleTickChainTest {

    private val now = 1_770_000_000_000L

    @Test
    fun `Ohne aktive Quelle bleibt die Kette self-cleaning`() {
        assertNull(
            DimScheduleUseCase.fallbackTick(now, alarmReadFailed = false, anySourceEnabled = false)
        )
    }

    @Test
    fun `Aktive Quelle ohne Fenster bekommt einen Keep-alive-Tick`() {
        val next = DimScheduleUseCase.fallbackTick(now, alarmReadFailed = false, anySourceEnabled = true)
        assertEquals(now + 6 * 60 * 60_000L, next)
    }

    @Test
    fun `Ein Lesefehler des Alarm-Bestands fuehrt zu einem kurzen Retry statt zum Kettenabbruch`() {
        val next = DimScheduleUseCase.fallbackTick(now, alarmReadFailed = true, anySourceEnabled = true)
        assertEquals(now + 15 * 60_000L, next)
    }
}
