package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pruefrunde 6, Befund 8: eine bei `maxResults` abgeschnittene Eventliste galt als VOLLSTAENDIG.
 *
 * Bis v1.27.0 holte der Kalender-Abruf genau eine Seite (`setMaxResults(50)`) und forderte
 * `nextPageToken` nicht einmal an. Die gekuerzte Liste war von einer vollstaendigen nicht zu
 * unterscheiden und erreichte ueber `CalendarFetchOutcome.isComplete == true` die loeschenden
 * Konsumenten - und dort heisst "kein Event mit dieser id" gleichbedeutend "Termin geloescht":
 * `syncAlarms()` cancelt den Systemalarm, loescht ihn aus Repository und Direct-Boot-Spiegel und
 * meldet dem Nutzer "Schicht entfernt", obwohl der Termin unveraendert im Kalender steht.
 *
 * [collectAllPages] ist die Stelle, an der das entschieden wird, und die einzige, die sich ohne
 * Netz pruefen laesst - der Rest von `CalendarRepository` ist Google-API-Verdrahtung.
 */
class Pruefrunde6KalenderSeitenTest {

    @Test
    fun `alle Seiten werden eingesammelt, nicht nur die erste`() = runTest {
        val pages = listOf(
            (1..50).map { "e$it" },
            (51..100).map { "e$it" },
            (101..107).map { "e$it" }
        )
        var calls = 0

        val result = collectAllPages(maxPages = 10, label = "Testkalender") { pageToken ->
            val index = pageToken?.removePrefix("seite-")?.toInt() ?: 0
            calls++
            ApiPage(
                items = pages[index],
                nextPageToken = if (index + 1 < pages.size) "seite-${index + 1}" else null
            )
        }

        assertEquals("Die Seitenkette muss zu Ende gelesen werden", 3, calls)
        assertEquals(107, result.size)
        assertEquals(
            "Gerade die spaeten Termine fielen frueher weg - genau ihre Wecker wurden geloescht",
            "e107",
            result.last()
        )
    }

    @Test
    fun `eine offene Seitenkette wirft, statt still zu kuerzen`() = runTest {
        var calls = 0

        try {
            collectAllPages(maxPages = 3, label = "Kalender abc12345...") { _ ->
                calls++
                ApiPage(items = listOf("e$calls"), nextPageToken = "es-gibt-immer-noch-mehr")
            }
            fail(
                "Eine nicht zu Ende lesbare Seitenkette darf kein Erfolg sein: das Ergebnis waere " +
                    "isComplete == true, und das ist in dieser App die Erlaubnis zu loeschen"
            )
        } catch (e: AppError.CalendarAccessError) {
            assertTrue(
                "Die Meldung muss den betroffenen Kalender benennen - sonst kann der Nutzer nichts tun",
                e.message.contains("Kalender abc12345")
            )
        }

        assertEquals("Die Notbremse muss genau nach maxPages Seiten greifen", 3, calls)
    }

    @Test
    fun `genau maxPages Seiten mit sauberem Ende sind kein Fehler`() = runTest {
        var calls = 0

        val result = collectAllPages(maxPages = 3, label = "Testkalender") { _ ->
            calls++
            ApiPage(
                items = listOf("e$calls"),
                nextPageToken = if (calls < 3) "seite-$calls" else null
            )
        }

        assertEquals(3, calls)
        assertEquals(3, result.size)
    }

    @Test
    fun `ein leerer nextPageToken beendet die Kette, statt sie zu wiederholen`() = runTest {
        var calls = 0

        val result = collectAllPages(maxPages = 5, label = "Testkalender") { _ ->
            calls++
            ApiPage(items = listOf("e$calls"), nextPageToken = "")
        }

        assertEquals("Ein leerer Token ist kein Token - sonst laeuft die Schleife auf der Stelle", 1, calls)
        assertEquals(1, result.size)
    }
}
