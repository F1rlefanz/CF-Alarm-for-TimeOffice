package com.github.f1rlefanz.cf_alarmfortimeoffice.di

import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Haelt die Vollstaendigkeits-Semantik des [CalendarStateHolder] fest.
 *
 * WARUM DAS FLAG EXISTIERT: Der Holder ist geteilter Zustand - `CalendarViewModel` schreibt,
 * `ShiftViewModel` liest. Der Leser gibt die Liste an `AlarmUseCase.syncAlarms()` weiter, und
 * dessen Delta-Sync entfernt jeden Alarm, dessen eventId darin fehlt. Geschrieben wurde aber im
 * Normalfall das LAZY-PRAEFIX (pro Kalender die ersten 10 Events); ein Ausschnitt hiess damit
 * "die spaetesten Schichten wurden abgesagt". Bei einem Dienstplan mit mehr als zehn Terminen in
 * 14 Tagen loeschte jede Aenderung an der Schicht-Konfiguration die letzten Wecker.
 */
class CalendarStateHolderTest {

    private fun event(id: String) = CalendarEvent(
        id = id,
        title = "Schicht $id",
        startTime = LocalDateTime.of(2026, 8, 20, 6, 0),
        endTime = LocalDateTime.of(2026, 8, 20, 14, 0),
        calendarId = "cal"
    )

    @Test
    fun `frischer Holder gilt NICHT als vollstaendig`() {
        val holder = CalendarStateHolder()

        assertFalse(
            "Der Startzustand ist eine leere Liste - als 'vollstaendig' gelesen hiesse das " +
                "'keine Schichten' und syncAlarms() wuerde raeumen",
            holder.eventsComplete.value
        )
    }

    @Test
    fun `ein Ausschnitt wird als unvollstaendig ausgewiesen`() {
        val holder = CalendarStateHolder()

        holder.updateEvents(listOf(event("A")), complete = false)

        assertEquals(1, holder.events.value.size)
        assertFalse(holder.eventsComplete.value)
    }

    @Test
    fun `ein vollstaendiger Bestand wird als vollstaendig ausgewiesen`() {
        val holder = CalendarStateHolder()

        holder.updateEvents(listOf(event("A"), event("B")), complete = true)

        assertEquals(2, holder.events.value.size)
        assertTrue(holder.eventsComplete.value)
    }

    @Test
    fun `clearEvents setzt die Vollstaendigkeit zurueck`() {
        val holder = CalendarStateHolder()
        holder.updateEvents(listOf(event("A")), complete = true)

        holder.clearEvents()

        assertTrue(holder.events.value.isEmpty())
        assertFalse(
            "Leer UND vollstaendig ist die gefaehrlichste Kombination: syncAlarms() liest eine " +
                "leere Liste als 'keine Schichten' und loescht alle Alarme",
            holder.eventsComplete.value
        )
    }
}
