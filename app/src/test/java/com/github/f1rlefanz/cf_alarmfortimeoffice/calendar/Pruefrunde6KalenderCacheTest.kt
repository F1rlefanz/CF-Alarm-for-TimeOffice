package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.KalenderEventAbruf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 *
 * Seit #51 gehoert auch das FENSTER zur Frage, die ein Eintrag beantwortet (unten).
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

    private fun abruf(vararg ids: String, fensterTage: Int = 14, horizontEnde: Long = 1_000L) =
        KalenderEventAbruf(ids.map { event(it) }, fensterTage, horizontEnde)

    @Test
    fun `ein Eintrag ueberlebt den Stundenwechsel`() = runTest {
        cache.put("cal-a", abruf("e1"))

        jetzt = jetzt.plusMinutes(2) // 11:01 - andere Stunde, aber weit innerhalb der TTL

        assertEquals(
            "Der Stundenanteil im Schluessel machte jeden Eintrag ab der naechsten vollen Stunde unauffindbar",
            listOf("e1"),
            cache.get("cal-a", 14)?.events?.map { it.id }
        )
    }

    @Test
    fun `ein zweiter put ersetzt den Eintrag, statt eine Karteileiche zu hinterlassen`() = runTest {
        cache.put("cal-a", abruf("e1"))

        jetzt = jetzt.plusMinutes(2) // andere Stunde

        cache.put("cal-a", abruf("e2"))

        assertEquals(listOf("e2"), cache.get("cal-a", 14)?.events?.map { it.id })
        assertTrue(
            "Pro Kalender genau EIN Eintrag - frueher sammelte sich je Stunde ein unauffindbarer dazu",
            cache.getCacheStats().contains("1 total")
        )
    }

    @Test
    fun `nach Ablauf der TTL ist der Eintrag weg`() = runTest {
        cache.put("cal-a", abruf("e1"))

        jetzt = jetzt.plusMinutes(CalendarEventCache.TTL_MINUTES + 1)

        assertNull(cache.get("cal-a", 14))
    }

    @Test
    fun `die Statistik zaehlt abgelaufene Eintraege wirklich`() = runTest {
        cache.put("cal-a", abruf("e1"))

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

    /**
     * #51: Ein Eintrag beantwortet "alle Events der naechsten N Tage" fuer GENAU sein N. Stellt der
     * Nutzer von 14 auf 56 Tage, darf ein 14-Tage-Eintrag innerhalb der TTL nicht als 56-Tage-Liste
     * zurueckkommen - der Sync hielte die kuerzere Liste sonst fuer das neue Fenster.
     */
    @Test
    fun `ein Eintrag mit anderem Fenster ist ein Fehltreffer`() = runTest {
        cache.put("cal-a", abruf("e1", fensterTage = 14))

        assertNull("Anderes Fenster = Fehltreffer", cache.get("cal-a", 56))
        assertNotNull("Dasselbe Fenster trifft weiter", cache.get("cal-a", 14))
    }

    /**
     * Ein Treffer reicht nur so weit wie sein URSPRUENGLICHER Abruf - nicht "jetzt + N". Das
     * Abruf-Ende kommt deshalb aus dem Eintrag, auch Minuten spaeter.
     */
    @Test
    fun `ein Treffer traegt das Abruf-Ende seines urspruenglichen Abrufs`() = runTest {
        cache.put("cal-a", abruf("e1", horizontEnde = 42_000L))

        jetzt = jetzt.plusMinutes(10)

        assertEquals(42_000L, cache.get("cal-a", 14)?.horizontEnde)
    }
}
