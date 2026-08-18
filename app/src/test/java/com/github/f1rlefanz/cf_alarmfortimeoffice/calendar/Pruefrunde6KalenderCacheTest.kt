package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Pruefrunde 6, Befund 10: der Cache-Schluessel trug die aktuelle Stunde.
 *
 * Geschrieben wurde unter der Schreibstunde, gelesen unter der Lesestunde - ab der naechsten vollen
 * Stunde war ein Eintrag unter keinem gelesenen Schluessel mehr auffindbar. Die im Kommentar
 * zugesicherte "6h TTL" gab es damit nie (effektiv 0-60 Minuten), die Ablauf-Zweige konnten nie
 * greifen, und Eintraege alter Stunden blieben als Karteileichen liegen.
 *
 * Die Tests halten BEIDE Richtungen fest: der Eintrag muss einen Stundenwechsel ueberleben - und
 * die TTL muss deutlich unter dem 6h-Wartungsintervall bleiben, sonst liefe die 6h-Wartung in
 * einen Cache-Treffer und saehe Dienstplan-Aenderungen gar nicht mehr.
 */
class Pruefrunde6KalenderCacheTest {

    /** Kurz vor dem Stundenwechsel, damit +2 Minuten wirklich eine andere Stunde ergeben. */
    private var jetzt: LocalDateTime = LocalDateTime.of(2026, 8, 18, 10, 59)

    private val cache = CalendarEventCache { jetzt }

    private fun event(id: String) = CalendarEvent(
        id = id,
        title = "Frühschicht",
        startTime = LocalDateTime.of(2026, 8, 19, 6, 0),
        endTime = LocalDateTime.of(2026, 8, 19, 14, 0),
        calendarId = "cal-a"
    )

    @Test
    fun `ein Eintrag ueberlebt den Stundenwechsel`() = runTest {
        cache.put("cal-a", listOf(event("e1")))

        jetzt = jetzt.plusMinutes(2) // 11:01 - andere Stunde, aber weit innerhalb der TTL

        assertTrue(
            "Der Stundenanteil im Schluessel machte jeden Eintrag ab der naechsten vollen Stunde unauffindbar",
            cache.isCached("cal-a")
        )
        assertEquals(listOf("e1"), cache.get("cal-a")?.map { it.id })
    }

    @Test
    fun `ein zweiter put ersetzt den Eintrag, statt eine Karteileiche zu hinterlassen`() = runTest {
        cache.put("cal-a", listOf(event("e1")))

        jetzt = jetzt.plusMinutes(2) // andere Stunde

        cache.put("cal-a", listOf(event("e2")))

        assertEquals(listOf("e2"), cache.get("cal-a")?.map { it.id })
        assertTrue(
            "Pro Kalender genau EIN Eintrag - frueher sammelte sich je Stunde ein unauffindbarer dazu",
            cache.getCacheStats().contains("1 total")
        )
    }

    @Test
    fun `nach Ablauf der TTL ist der Eintrag weg`() = runTest {
        cache.put("cal-a", listOf(event("e1")))

        jetzt = jetzt.plusMinutes(CalendarEventCache.TTL_MINUTES + 1)

        assertFalse(cache.isCached("cal-a"))
        assertNull(cache.get("cal-a"))
    }

    @Test
    fun `die Statistik zaehlt abgelaufene Eintraege wirklich`() = runTest {
        cache.put("cal-a", listOf(event("e1")))

        jetzt = jetzt.plusMinutes(CalendarEventCache.TTL_MINUTES + 1)

        assertTrue(
            "Die Statistik meldete strukturell immer '0 expired' - eine Diagnostik, die den Defekt gerade nicht anzeigt",
            cache.getCacheStats().contains("1 expired")
        )
    }

    @Test
    fun `die TTL bleibt deutlich unter dem 6h-Wartungsintervall`() {
        assertTrue(
            "Bei einer TTL nahe 6h liefe die 6h-Wartung (ohne forceRefresh) in einen Cache-Treffer " +
                "und saehe Dienstplan-Aenderungen und -Streichungen nicht mehr - der Cache waere " +
                "dann die Wahrheit, aus der syncAlarms() loescht",
            CalendarEventCache.TTL_MINUTES <= 60L
        )
    }
}
