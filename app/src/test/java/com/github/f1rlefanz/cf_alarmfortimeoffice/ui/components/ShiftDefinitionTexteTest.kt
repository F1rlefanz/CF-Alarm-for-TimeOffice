package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #142: Die Krankmeldungs-Texte im Schicht-Editor und auf der Schicht-Karte duerfen nur zusagen,
 * was ein Krankmeldungs-Typ wirklich tut - den Tag fuer Wecker aus dem Dienstplan freigeben,
 * eigene Wecker unangetastet lassen. Er hat keine Weckzeit und spannt keine Schicht auf; ein Text,
 * der von "Nicht stören" oder einer Weckzeit spricht, verspraeche etwas, das es nicht gibt.
 */
class ShiftDefinitionTexteTest {

    @Test
    fun `Der Krankmeldungs-Hinweis verspricht nur, was gilt`() {
        val text = ShiftDefinitionTexte.KRANKMELDUNG_HINWEIS
        assertTrue(
            "Ohne diesen Satz muss der Nutzer fuerchten, dass auch manuelle Wecker entfallen",
            text.contains("Eigene Wecker bleiben")
        )
        assertTrue(text.contains("kein Wecker aus dem Dienstplan"))
        assertFalse(
            "Ein Krankmeldungs-Typ steuert \"Nicht stören\" nicht selbst",
            text.contains("Nicht stören")
        )
    }

    @Test
    fun `Statuszeilen einer Krankmeldung nennen keine Weckzeit`() {
        listOf(
            ShiftDefinitionTexte.KRANKMELDUNG_STATUS,
            ShiftDefinitionTexte.KRANKMELDUNG_AUSGESCHALTET,
            ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS
        ).forEach { text ->
            assertFalse(text, text.contains("Alarm"))
            assertFalse(text, text.contains("Weckzeit"))
            assertFalse(text, text.contains("Nicht stören"))
        }
        assertTrue(ShiftDefinitionTexte.KRANKMELDUNG_AUSGESCHALTET.contains("keinen Tag frei"))
        assertTrue(ShiftDefinitionTexte.KRANKMELDUNG_AKTIVIERT_HINWEIS.contains("keinen Tag frei"))
    }

    @Test
    fun `Loeschrueckfrage einer Krankmeldung nennt den Namen und die zurueckkehrenden Wecker`() {
        val text = ShiftDefinitionTexte.krankmeldungLoeschenText("Krank")
        assertTrue(text.startsWith("\"Krank\" wird mit allen Mustern gelöscht."))
        assertTrue(text.contains("bekommen ihre Wecker aus dem Dienstplan zurück"))
        assertFalse(
            "Ein Krankmeldungs-Typ hat keine Weckzeit, die mit geloescht wuerde",
            text.contains("Weckzeit")
        )
    }

    @Test
    fun `Die Ein-Buchstaben-Warnung nennt bei einer Krankmeldung den fehlenden Wecker`() {
        val krank = ShiftDefinitionTexte.einBuchstabeWarnung("K", isSickDay = true)!!
        assertTrue(krank.contains("Diensttag frei"))
        assertTrue(krank.contains("kein Wecker aus dem Dienstplan"))
        assertFalse("Bei Krank weckt ein fremder Termin nicht, er schweigt", krank.contains("weckt dich"))

        val schicht = ShiftDefinitionTexte.einBuchstabeWarnung("F", isSickDay = false)!!
        assertTrue(schicht.contains("weckt dich dann an freien Tagen"))
    }

    /**
     * Seit #150 trifft ein Muster aus reinen Zeichen ("+" fuer frei) nur den GANZEN Titel - nie
     * "Kino mit +" oder "x + y". Die Warnung behauptete das Gegenteil, in Fehlerfarbe, und genau
     * unter dem Kuerzel, das die App seit #150 selbst vorschlaegt. Sie gilt nur, wo die Erkennung
     * mit Wortgrenzen arbeitet: ein einzelner Buchstabe oder eine einzelne Ziffer.
     */
    @Test
    fun `Die Ein-Buchstaben-Warnung erscheint nur bei einem Buchstaben oder einer Ziffer`() {
        for (krank in listOf(false, true)) {
            assertNotNull(ShiftDefinitionTexte.einBuchstabeWarnung("F", krank))
            assertNotNull(ShiftDefinitionTexte.einBuchstabeWarnung(" 7 ", krank))
            assertNull("'+' trifft nur den ganzen Titel", ShiftDefinitionTexte.einBuchstabeWarnung("+", krank))
            assertNull(ShiftDefinitionTexte.einBuchstabeWarnung(" - ", krank))
            assertNull(ShiftDefinitionTexte.einBuchstabeWarnung("FR", krank))
            assertNull(ShiftDefinitionTexte.einBuchstabeWarnung("", krank))
        }
    }
}
