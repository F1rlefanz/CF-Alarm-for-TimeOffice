package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Text der Kalender-Karte im Teilerfolg-Fall.
 *
 * WARUM GETESTET: Diese Zeile ist die einzige Stelle, an der ein Zustand sichtbar wird, der sonst
 * ausschliesslich im Log steht - ein dauerhaft nicht abrufbarer Kalender haelt jeden Alarm-Sync an,
 * die bestehenden Wecker laufen aus, und nichts waechst nach. Sagt der Text nicht, WELCHER Kalender
 * betroffen ist und WAS daraus folgt, ist er wertlos.
 *
 * Der Kalendername kommt aus der geladenen Kalenderliste, und die ist PAGINIERT (20 pro Seite) -
 * der Eintrag kann also fehlen. Dass dann die Anzahl statt eines geratenen Namens erscheint, ist
 * die eigentliche Zusicherung dieser Tests.
 */
class UnavailableCalendarDetailsTest {

    @Test
    fun `ein bekannter Kalender wird beim Namen genannt`() {
        val text = unavailableCalendarDetails(
            unavailableIds = setOf("id-dienstplan"),
            namesById = mapOf("id-dienstplan" to "Dienstplan", "id-privat" to "Privat")
        )

        assertTrue("Der Name muss im Text stehen, sonst laesst er sich nicht abwaehlen", text.contains("Dienstplan"))
        assertFalse("Der funktionierende Kalender gehoert nicht in die Warnung", text.contains("Privat"))
    }

    @Test
    fun `mehrere bekannte Kalender werden alle genannt`() {
        val text = unavailableCalendarDetails(
            unavailableIds = setOf("a", "b"),
            namesById = mapOf("a" to "Dienstplan", "b" to "Bereitschaft")
        )

        assertTrue(text.contains("Dienstplan"))
        assertTrue(text.contains("Bereitschaft"))
        assertTrue("Die Anzahl gehoert dazu, wenn es mehrere sind", text.contains("2"))
    }

    @Test
    fun `unbekannte ID nennt die Anzahl statt eines geratenen Namens`() {
        // Der Fall ist real und nicht konstruiert: die Kalenderliste laedt seitenweise, ein
        // ausgewaehlter Kalender kann noch gar nicht darin stehen. Lieber vage und wahr als
        // konkret und falsch.
        val text = unavailableCalendarDetails(
            unavailableIds = setOf("nicht-in-der-liste"),
            namesById = mapOf("id-privat" to "Privat")
        )

        assertFalse("Kein Rohwert der ID im Nutzertext", text.contains("nicht-in-der-liste"))
        assertFalse(text.contains("Privat"))
        assertTrue(text.contains("nicht abrufbar"))
    }

    @Test
    fun `teilweise bekannte Namen fallen auf die Anzahl zurueck`() {
        // Zwei betroffene, aber nur einer ist aufloesbar: einen zu nennen und den anderen
        // wegzulassen waere irrefuehrend - der Nutzer wuerde einen Kalender abwaehlen und sich
        // wundern, dass die Meldung bleibt.
        val text = unavailableCalendarDetails(
            unavailableIds = setOf("a", "unbekannt"),
            namesById = mapOf("a" to "Dienstplan")
        )

        assertFalse("Nicht nur den einen bekannten nennen", text.contains("Dienstplan"))
        assertTrue(text.contains("2"))
    }

    @Test
    fun `der Text nennt die Folge und beruhigt ueber die bestehenden Wecker`() {
        val text = unavailableCalendarDetails(
            unavailableIds = setOf("a"),
            namesById = mapOf("a" to "Dienstplan")
        )

        assertTrue(
            "Ohne die Folge ist die Meldung nur ein Zustand, mit dem niemand etwas anfangen kann",
            text.contains("keine neuen Wecker")
        )
        assertTrue(
            "Sonst liest sich die Karte, als seien die gestellten Wecker bereits weg",
            text.contains("bestehende bleiben")
        )
    }
}
