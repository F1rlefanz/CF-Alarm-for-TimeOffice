package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.Ausfall
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.AusfallQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.KRANK_KALENDER_ZURUECK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #142: die Texte der Ausfall-Karte ganz oben in der Uebersicht. Jede Ueberschrift nennt, ab wann
 * und bis wann - und nur ein reiner Kalender-Block heisst "krankgemeldet".
 */
class AusfallKarteTextTest {

    private val heute = LocalDate.of(2026, 10, 12) // Mo
    private val morgen = heute.plusDays(1)
    private val freitag = LocalDate.of(2026, 10, 16)

    private fun ausfall(von: LocalDate, bis: LocalDate, quelle: AusfallQuelle, handTage: Set<LocalDate> = emptySet()) =
        Ausfall(von, bis, quelle, handTage)

    @Test
    fun `eigene Freigabe - alle Ueberschriften`() {
        val hand = AusfallQuelle.HAND
        assertEquals("Dienst fällt heute aus", ausfallTitel(ausfall(heute, heute, hand), heute))
        assertEquals("Dienst fällt morgen aus", ausfallTitel(ausfall(morgen, morgen, hand), heute))
        assertEquals("Dienst fällt aus bis Fr 16.10.", ausfallTitel(ausfall(heute, freitag, hand), heute))
        assertEquals("Dienst fällt ab morgen aus, bis Fr 16.10.", ausfallTitel(ausfall(morgen, freitag, hand), heute))
    }

    @Test
    fun `gemischter Block heisst wie eine eigene Freigabe`() {
        assertEquals(
            "Dienst fällt aus bis Fr 16.10.",
            ausfallTitel(ausfall(heute, freitag, AusfallQuelle.GEMISCHT, setOf(heute)), heute)
        )
    }

    @Test
    fun `Kalender - alle Ueberschriften`() {
        val kal = AusfallQuelle.KALENDER
        assertEquals("Heute krankgemeldet (laut Kalender)", ausfallTitel(ausfall(heute, heute, kal), heute))
        assertEquals("Morgen krankgemeldet (laut Kalender)", ausfallTitel(ausfall(morgen, morgen, kal), heute))
        assertEquals("Krankgemeldet bis Fr 16.10. (laut Kalender)", ausfallTitel(ausfall(heute, freitag, kal), heute))
        assertEquals(
            "Ab morgen krankgemeldet, bis Fr 16.10. (laut Kalender)",
            ausfallTitel(ausfall(morgen, freitag, kal), heute)
        )
    }

    @Test
    fun `GEMISCHT nennt den Hinweis zur eigenen Freigabe`() {
        val gemischt = ausfall(heute, freitag, AusfallQuelle.GEMISCHT, setOf(heute, morgen))
        assertTrue(ausfallZuruecknehmbar(gemischt))
        assertEquals(AUSFALL_GEMISCHT_HINWEIS, ausfallHinweis(gemischt))
        assertTrue(AUSFALL_GEMISCHT_HINWEIS.contains("nur deine eigene Freigabe"))
    }

    @Test
    fun `KALENDER bietet kein Zuruecknehmen an`() {
        val kalender = ausfall(heute, freitag, AusfallQuelle.KALENDER)
        assertFalse(ausfallZuruecknehmbar(kalender))
        // Statt des Knopfs: wie der Wecker zurueckkommt - derselbe Satz wie in der Tagesliste.
        assertEquals(KRANK_KALENDER_ZURUECK, ausfallHinweis(kalender))
    }

    @Test
    fun `eigene Freigabe bietet Zuruecknehmen ohne Zusatz an`() {
        val hand = ausfall(heute, freitag, AusfallQuelle.HAND, setOf(heute, freitag))
        assertTrue(ausfallZuruecknehmbar(hand))
        assertNull(ausfallHinweis(hand))
    }

    @Test
    fun `der Kartentext verspricht nur, was gilt`() {
        assertTrue(AUSFALL_TEXT.contains("eigene Wecker bleiben"))
        assertFalse(AUSFALL_TEXT.contains("Nicht stören"))
    }
}
