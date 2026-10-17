package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Umbau E3 (#172): welcher Ruf-Termin seinen Wecker an einen Dienst am selben Tag abgibt.
 * Der Tag ist das Datum des Termin-BEGINNS, nicht das der Weckzeit.
 */
class RufUebernahmeTest {

    private fun typ(id: String, kategorie: ShiftKategorie, weckzeit: LocalTime = LocalTime.of(5, 30)) =
        ShiftDefinition(id = id, name = id, keywords = listOf(id), alarmTime = weckzeit).mitKategorie(kategorie)

    private val ruf = typ("AD1", ShiftKategorie.RUFBEREITSCHAFT, LocalTime.of(6, 0))
    private val frueh = typ("F", ShiftKategorie.DIENST)

    private fun treffer(
        typ: ShiftDefinition,
        eventId: String,
        beginn: LocalDateTime,
        ende: LocalDateTime = beginn.plusHours(8),
        ganztaegig: Boolean = false,
        weckzeit: LocalDateTime = beginn.toLocalDate().atTime(typ.alarmTime)
    ) = ShiftMatch(
        shiftDefinition = typ,
        calendarEvent = CalendarEvent(
            id = eventId,
            title = typ.keywords.first(),
            startTime = beginn,
            endTime = ende,
            calendarId = "test",
            isAllDay = ganztaegig
        ),
        calculatedAlarmTime = weckzeit
    )

    /** Ganztaegig wie aus dem Repository: 00:00 des Datums bis 23:59. */
    private fun rufGanztaegig(eventId: String, tag: Int) = treffer(
        ruf, eventId,
        beginn = LocalDateTime.of(2035, 6, tag, 0, 0),
        ende = LocalDateTime.of(2035, 6, tag, 23, 59),
        ganztaegig = true
    )

    @Test
    fun `Dienst am selben Tag uebernimmt den Ruf, an anderen Tagen nicht`() {
        val ergebnis = uebernommeneRufTermine(
            listOf(
                treffer(ruf, "ruf-1", LocalDateTime.of(2035, 6, 1, 7, 0)),
                treffer(frueh, "f-1", LocalDateTime.of(2035, 6, 1, 6, 0)),
                treffer(ruf, "ruf-2", LocalDateTime.of(2035, 6, 2, 7, 0)),
                treffer(frueh, "f-3", LocalDateTime.of(2035, 6, 3, 6, 0))
            )
        )
        assertEquals(setOf("ruf-1"), ergebnis)
    }

    @Test
    fun `ganztaegiger Ruftermin zaehlt zu seinem Datum`() {
        val ergebnis = uebernommeneRufTermine(
            listOf(
                rufGanztaegig("ruf-1", 1),
                treffer(frueh, "f-1", LocalDateTime.of(2035, 6, 1, 13, 0)),
                rufGanztaegig("ruf-2", 2)
            )
        )
        assertEquals(setOf("ruf-1"), ergebnis)
    }

    @Test
    fun `Nachtdienst zaehlt zum Tag seines Beginns, nicht zum Tag seiner Weckzeit oder seines Endes`() {
        // N beginnt am 2. um 00:30, Weckzeit 23:30 am 1. (Engine rechnet einen Tag zurueck), Ende
        // 2. 08:30. Ein zweiter N beginnt am 3. um 22:00 und endet am 4. Ruf am 1., 2. und 4.
        val nacht = typ("N", ShiftKategorie.DIENST, LocalTime.of(23, 30))
        val ergebnis = uebernommeneRufTermine(
            listOf(
                rufGanztaegig("ruf-1", 1),
                rufGanztaegig("ruf-2", 2),
                rufGanztaegig("ruf-4", 4),
                treffer(
                    nacht, "n-2", LocalDateTime.of(2035, 6, 2, 0, 30),
                    weckzeit = LocalDateTime.of(2035, 6, 1, 23, 30)
                ),
                treffer(
                    nacht, "n-3", LocalDateTime.of(2035, 6, 3, 22, 0),
                    ende = LocalDateTime.of(2035, 6, 4, 6, 0),
                    weckzeit = LocalDateTime.of(2035, 6, 3, 21, 0)
                )
            )
        )
        // Nur der Ruf am Beginntag des Nachtdienstes - nicht der am Weckzeit-Tag (1.), nicht der am
        // Ende-Tag (4.).
        assertEquals(setOf("ruf-2"), ergebnis)
    }

    @Test
    fun `Frei, Urlaub, Fortbildung und eine zweite Rufbereitschaft uebernehmen nicht`() {
        val zweiterRuf = typ("AD", ShiftKategorie.RUFBEREITSCHAFT)
        val ergebnis = uebernommeneRufTermine(
            listOf(
                rufGanztaegig("ruf-1", 1),
                treffer(typ("X", ShiftKategorie.FREI), "frei-1", LocalDateTime.of(2035, 6, 1, 0, 0)),
                rufGanztaegig("ruf-2", 2),
                treffer(typ("U", ShiftKategorie.URLAUB), "urlaub-2", LocalDateTime.of(2035, 6, 2, 0, 0)),
                rufGanztaegig("ruf-3", 3),
                treffer(typ("FBE", ShiftKategorie.FORTBILDUNG), "fb-3", LocalDateTime.of(2035, 6, 3, 8, 0)),
                rufGanztaegig("ruf-4", 4),
                treffer(zweiterRuf, "ad-4", LocalDateTime.of(2035, 6, 4, 7, 0))
            )
        )
        assertEquals(emptySet<String>(), ergebnis)
    }

    @Test
    fun `ein Dienst mit Wecker aus uebernimmt trotzdem - der Tag folgt dem Dienst-Typ`() {
        val ohneWecker = frueh.copy(weckerAn = false)
        val ergebnis = uebernommeneRufTermine(
            listOf(rufGanztaegig("ruf-1", 1), treffer(ohneWecker, "f-1", LocalDateTime.of(2035, 6, 1, 6, 0)))
        )
        assertEquals(setOf("ruf-1"), ergebnis)
    }
}
