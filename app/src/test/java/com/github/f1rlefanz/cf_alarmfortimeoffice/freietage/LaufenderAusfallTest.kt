package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * #142: der laufende Ausfall fuer die Uebersicht - welcher Block freier Tage "heute oder morgen"
 * betrifft, wie weit er reicht und aus welcher Quelle er stammt.
 */
class LaufenderAusfallTest {

    private val heute = LocalDate.of(2026, 10, 13)
    private fun tag(plus: Long): LocalDate = heute.plusDays(plus)

    @Test
    fun `heute frei - der Block reicht bis zum letzten zusammenhaengenden Tag`() {
        val ausfall = laufenderAusfall(hand = setOf(tag(0), tag(1), tag(2), tag(3), tag(4)), kalender = emptySet(), heute = heute)

        assertEquals(Ausfall(tag(0), tag(4), AusfallQuelle.HAND, setOf(tag(0), tag(1), tag(2), tag(3), tag(4))), ausfall)
    }

    @Test
    fun `nur morgen frei - der Ausfall beginnt morgen`() {
        val ausfall = laufenderAusfall(hand = setOf(tag(1)), kalender = emptySet(), heute = heute)

        assertEquals(tag(1), ausfall?.von)
        assertEquals(tag(1), ausfall?.bis)
    }

    @Test
    fun `eine Luecke beendet den Block`() {
        val ausfall = laufenderAusfall(hand = setOf(tag(0), tag(1), tag(3)), kalender = emptySet(), heute = heute)

        assertEquals(tag(1), ausfall?.bis)
        assertEquals("Tage hinter der Luecke gehoeren nicht zum Block", setOf(tag(0), tag(1)), ausfall?.handTage)
    }

    @Test
    fun `weder heute noch morgen frei - kein Ausfall`() {
        assertNull(laufenderAusfall(hand = setOf(tag(2), tag(3)), kalender = setOf(tag(5)), heute = heute))
        assertNull(laufenderAusfall(hand = emptySet(), kalender = emptySet(), heute = heute))
    }

    @Test
    fun `gestern zaehlt nicht als Beginn`() {
        // Gestern steht dank Rueckschau noch in der Menge - die Karte spricht aber von heute/morgen.
        assertNull(laufenderAusfall(hand = setOf(tag(-1)), kalender = emptySet(), heute = heute))
        assertEquals(tag(0), laufenderAusfall(hand = setOf(tag(-1), tag(0)), kalender = emptySet(), heute = heute)?.von)
    }

    @Test
    fun `Quellen HAND, KALENDER und GEMISCHT, handTage nur aus dem Block`() {
        val nurKalender = laufenderAusfall(hand = setOf(tag(5)), kalender = setOf(tag(0), tag(1)), heute = heute)
        assertEquals(AusfallQuelle.KALENDER, nurKalender?.quelle)
        assertEquals("Eine Hand-Freigabe ausserhalb des Blocks zaehlt nicht", emptySet<LocalDate>(), nurKalender?.handTage)

        // Hand und Kalender aneinander: EIN Block ueber die Vereinigung, gemischt.
        val aneinander = laufenderAusfall(hand = setOf(tag(0)), kalender = setOf(tag(1), tag(2)), heute = heute)
        assertEquals(Ausfall(tag(0), tag(2), AusfallQuelle.GEMISCHT, setOf(tag(0))), aneinander)

        // Ein Tag in BEIDEN Mengen macht den Block ebenfalls gemischt.
        val doppelt = laufenderAusfall(hand = setOf(tag(0)), kalender = setOf(tag(0)), heute = heute)
        assertEquals(AusfallQuelle.GEMISCHT, doppelt?.quelle)
        assertEquals(setOf(tag(0)), doppelt?.handTage)
    }
}
