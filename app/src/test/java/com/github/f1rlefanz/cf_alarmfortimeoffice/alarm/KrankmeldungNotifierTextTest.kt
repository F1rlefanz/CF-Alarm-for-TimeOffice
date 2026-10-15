package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * #142: die Texte der Krankmeldung ([KrankmeldungNotifier.meldung]) - wortgleich mit dem Plan.
 * Der 20.10.2025 ist ein Montag.
 */
class KrankmeldungNotifierTextTest {

    private val montag = LocalDate.of(2025, 10, 20)

    @Test
    fun `Ein Tag mit einem Wecker nennt die Uhrzeit`() {
        val m = KrankmeldungNotifier.meldung(listOf(montag), listOf(LocalTime.of(4, 45)))

        assertEquals("Krankmeldung erkannt: Mo 20.10.", m.titel)
        assertEquals("Der Wecker um 04:45 entfällt. Eigene Wecker bleiben.", m.text)
    }

    @Test
    fun `Mehrere Tage nennen die Spanne und die Zahl der Wecker`() {
        val tage = listOf(montag, montag.plusDays(1), montag.plusDays(2))
        // Drei Fruehdienste zur selben Uhrzeit sind DREI Wecker, nicht einer.
        val weckzeiten = List(3) { LocalTime.of(4, 45) }

        val m = KrankmeldungNotifier.meldung(tage, weckzeiten)

        assertEquals("Krankmeldung erkannt: Mo 20.10. – Mi 22.10.", m.titel)
        assertEquals("3 Wecker entfallen. Eigene Wecker bleiben.", m.text)
    }

    @Test
    fun `Ohne Wecker sagt die Meldung, dass keiner aus dem Dienstplan kommt`() {
        assertEquals(
            "Kein Wecker aus dem Dienstplan an diesem Tag.",
            KrankmeldungNotifier.meldung(listOf(montag), emptyList()).text
        )
        assertEquals(
            "Kein Wecker aus dem Dienstplan an diesen Tagen.",
            KrankmeldungNotifier.meldung(listOf(montag, montag.plusDays(1)), emptyList()).text
        )
    }

    @Test
    fun `Nicht zusammenhaengende Tage werden aufgezaehlt`() {
        assertEquals(
            "Krankmeldung erkannt: Mo 20.10., Mi 22.10.",
            KrankmeldungNotifier.meldung(listOf(montag.plusDays(2), montag), emptyList()).titel
        )
        val vierTage = listOf(0L, 2L, 4L, 6L).map { montag.plusDays(it) }
        assertEquals(
            "Krankmeldung erkannt: Mo 20.10., Mi 22.10., Fr 24.10. und weitere",
            KrankmeldungNotifier.meldung(vierTage, emptyList()).titel
        )
    }
}
