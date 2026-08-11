package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Haelt den Fix an CalendarViewModel.loadMoreEvents() fest: Nachgeladen wird ein PRAEFIX der
 * Vereinigung aller ausgewaehlten Kalender (offset = 0, maxEvents = bereits geladen + limit),
 * und das Ergebnis wird nach id dedupliziert + neu sortiert.
 *
 * Der Bug davor: Der Erst-Ladevorgang holt PRO Kalender die ersten 10 Events und setzt
 * eventOffset = Listenlaenge. loadMoreEvents() gab diesen Offset aber an
 * getCalendarEventsLazy(ALLE Kalender) weiter - und das schneidet aus der SORTIERTEN
 * VEREINIGUNG. Bei mehr als einem Kalender ist "je Kalender die ersten 10" kein Praefix der
 * Vereinigung, also lieferte die Nachlade-Seite bereits angezeigte Events erneut (doppelte
 * LazyColumn-Keys -> IllegalArgumentException "Key ... was already used" -> Crash; ueber den
 * CalendarStateHolder erkennt ShiftViewModel dieselbe Schicht zweimal) und liess einen Block
 * dazwischen komplett aus.
 *
 * [sliceUnionPage] bildet exakt nachweisbar nach, was CalendarUseCase.getCalendarEventsLazy()
 * tut (Vereinigung -> sortedBy startTime -> subList(offset, offset+maxEvents)), damit das
 * Szenario ohne Android-/Netzwerk-Raender pruefbar bleibt.
 */
class CalendarViewModelLoadMoreEventsTest {

    // Kalender A: 30 Termine, stuendlich ab 06.08.2026 00:00 (letzter am 07.08. 05:00)
    private val calendarA: List<CalendarEvent> = (0 until 30).map { i ->
        event(id = "A$i", calendarId = "cal-a", start = LocalDateTime.of(2026, 8, 6, 0, 0).plusHours(i.toLong()))
    }

    // Kalender B: 30 Termine, deren FRUEHESTER nach dem spaetesten aus A liegt.
    // Genau der reale Fall (TimeOffice-Dienstplanfeed + privater Kalender mit spaeteren Terminen).
    private val calendarB: List<CalendarEvent> = (0 until 30).map { j ->
        event(id = "B$j", calendarId = "cal-b", start = LocalDateTime.of(2026, 8, 8, 0, 0).plusHours(j.toLong()))
    }

    private val allCalendars = listOf(calendarA, calendarB)

    /** Erst-Ladevorgang: PRO Kalender die ersten 10 Events (initialPageSize = 10). */
    private val initiallyLoaded: List<CalendarEvent> =
        (calendarA.take(10) + calendarB.take(10)).sortedBy { it.startTime }

    @Test
    fun `Praefix-Nachladen liefert keine doppelten Event-Ids`() {
        val page = sliceUnionPage(
            offset = 0,
            maxEvents = CalendarViewModel.resolveLoadMoreWindow(
                alreadyLoaded = initiallyLoaded.size,
                limit = 50
            )
        )

        val merged = CalendarViewModel.mergeMoreEvents(
            currentEvents = initiallyLoaded,
            pageEvents = page,
            totalEvents = unionSorted().size
        )

        val duplicateIds = merged.events.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        assertTrue(
            "Nach dem Nachladen darf keine Event-Id doppelt vorkommen - doppelte Keys " +
                "lassen die LazyColumn abstuerzen. Doppelt: ${duplicateIds.keys}",
            duplicateIds.isEmpty()
        )
    }

    @Test
    fun `Praefix-Nachladen laesst keinen Block aus`() {
        val page = sliceUnionPage(
            offset = 0,
            maxEvents = CalendarViewModel.resolveLoadMoreWindow(
                alreadyLoaded = initiallyLoaded.size,
                limit = 50
            )
        )

        val merged = CalendarViewModel.mergeMoreEvents(
            currentEvents = initiallyLoaded,
            pageEvents = page,
            totalEvents = unionSorted().size
        )

        // A[10..19] waren mit der alten Semantik das Loch: sie stehen im
        // Vereinigungs-Praefix VOR dem alten Slice-Start und wurden nie geladen.
        val missing = (10 until 20).map { "A$it" }.filterNot { id ->
            merged.events.any { it.id == id }
        }
        assertTrue("Diese Events fehlen komplett in der Liste: $missing", missing.isEmpty())
        assertEquals(
            "Ein Praefix, das alles Angezeigte plus 50 abdeckt, muss hier den gesamten " +
                "Bestand enthalten",
            unionSorted().size,
            merged.events.size
        )
    }

    @Test
    fun `alte Offset-Semantik erzeugte nachweislich Duplikate und ein Loch`() {
        // Charakterisierung des behobenen Bugs: offset = Listenlaenge (20), maxEvents = limit.
        val altePage = sliceUnionPage(offset = initiallyLoaded.size, maxEvents = 50)
        val naivAngehaengt = initiallyLoaded + altePage

        val duplicateIds = naivAngehaengt.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        assertEquals(
            "Die alte Semantik lieferte genau die 10 bereits angezeigten B-Events erneut",
            (0 until 10).map { "B$it" }.toSet(),
            duplicateIds.keys
        )

        val fehlend = (10 until 20).map { "A$it" }.filterNot { id ->
            naivAngehaengt.any { it.id == id }
        }
        assertEquals(
            "Gleichzeitig fehlten A[10..19] dauerhaft",
            (10 until 20).map { "A$it" },
            fehlend
        )
    }

    @Test
    fun `mergeMoreEvents sortiert das Ergebnis nach startTime`() {
        // Die Vereinigungs-Seite kann Events enthalten, die zeitlich VOR bereits
        // angezeigten liegen (anderer Kalender) - reines Anhaengen waere unsortiert.
        val merged = CalendarViewModel.mergeMoreEvents(
            currentEvents = listOf(calendarB[0]),
            pageEvents = listOf(calendarA[5], calendarA[0]),
            totalEvents = 3
        )

        assertEquals(
            listOf("A0", "A5", "B0"),
            merged.events.map { it.id }
        )
    }

    @Test
    fun `mergeMoreEvents dedupliziert nach id und nimmt die frische Fassung aus der Seite`() {
        val bereitsAngezeigt = calendarA[0]
        val gleicheIdVerschoben = bereitsAngezeigt.copy(title = "Schicht verschoben")

        val merged = CalendarViewModel.mergeMoreEvents(
            currentEvents = listOf(bereitsAngezeigt, calendarB[0]),
            pageEvents = listOf(gleicheIdVerschoben, calendarA[1]),
            totalEvents = 3
        )

        assertEquals(listOf("A0", "A1", "B0"), merged.events.map { it.id })
        assertEquals(
            "Bei gleicher id muss die frische Fassung aus der Nachlade-Seite gewinnen - " +
                "sonst bleibt eine verschobene Schicht mit der alten Uhrzeit stehen",
            "Schicht verschoben",
            merged.events.first().title
        )
        assertEquals(
            "Events, die nur in der bestehenden Liste stehen, bleiben erhalten",
            calendarB[0].title,
            merged.events.last().title
        )
    }

    @Test
    fun `eventOffset ist die Laenge des Ergebnisses und damit wieder ein Vereinigungs-Offset`() {
        val merged = CalendarViewModel.mergeMoreEvents(
            currentEvents = calendarA.take(3),
            pageEvents = calendarA.take(5),
            totalEvents = 30
        )

        assertEquals(5, merged.events.size)
        assertEquals(
            "eventOffset darf nicht offset+Seitengroesse sein (das zaehlte Duplikate mit), " +
                "sondern die tatsaechliche Laenge der Liste",
            5,
            merged.eventOffset
        )
    }

    @Test
    fun `hasMoreEvents kommt aus dem Gesamtbestand, nicht aus der Seitengroesse`() {
        val nochNichtAlles = CalendarViewModel.mergeMoreEvents(
            currentEvents = calendarA.take(10),
            pageEvents = calendarA.take(20),
            totalEvents = 30
        )
        assertTrue(nochNichtAlles.hasMoreEvents)

        val allesDa = CalendarViewModel.mergeMoreEvents(
            currentEvents = calendarA.take(10),
            pageEvents = calendarA,
            totalEvents = 30
        )
        assertFalse(allesDa.hasMoreEvents)

        // Enthaelt die Liste durch veraltete Eintraege sogar mehr als der Bestand,
        // ist trotzdem nichts mehr nachzuladen.
        val mehrAlsBestand = CalendarViewModel.mergeMoreEvents(
            currentEvents = calendarA,
            pageEvents = calendarB.take(2),
            totalEvents = 30
        )
        assertFalse(mehrAlsBestand.hasMoreEvents)
    }

    @Test
    fun `resolveLoadMoreWindow deckt das Angezeigte plus limit ab und klemmt Unsinn`() {
        assertEquals(70, CalendarViewModel.resolveLoadMoreWindow(alreadyLoaded = 20, limit = 50))

        // Ein fehlerhafter Aufrufer darf keine leere Seite anfordern - das saehe wie
        // "keine weiteren Events" aus.
        assertTrue(CalendarViewModel.resolveLoadMoreWindow(alreadyLoaded = -5, limit = 0) >= 1)
        assertEquals(1, CalendarViewModel.resolveLoadMoreWindow(alreadyLoaded = 0, limit = 0))
    }

    // --- Hilfsmittel -------------------------------------------------------------------

    /** Vereinigung aller Kalender, sortiert - so wie getCalendarEventsLazy() intern arbeitet. */
    private fun unionSorted(): List<CalendarEvent> =
        allCalendars.flatten().sortedBy { it.startTime }

    /** Bildet subList(offset, offset+maxEvents) aus der sortierten Vereinigung nach. */
    private fun sliceUnionPage(offset: Int, maxEvents: Int): List<CalendarEvent> {
        val sorted = unionSorted()
        if (offset >= sorted.size) return emptyList()
        return sorted.subList(offset, minOf(offset + maxEvents, sorted.size))
    }

    private fun event(id: String, calendarId: String, start: LocalDateTime) = CalendarEvent(
        id = id,
        title = "Termin $id",
        startTime = start,
        endTime = start.plusHours(1),
        calendarId = calendarId
    )
}
