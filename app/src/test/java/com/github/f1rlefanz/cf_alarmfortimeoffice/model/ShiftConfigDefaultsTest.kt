package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt fest, was die Standardkonfiguration auf einem ECHTEN Dienstplan-Kalender erkennt.
 *
 * WARUM DIESE TESTS EXISTIEREN — ein am Geraet nachgewiesener Rueckschritt (10.08.2026):
 * Ein Aufraeumschritt hatte die einbuchstabigen Standard-Keywords "F"/"S"/"N" aus den Defaults
 * entfernt, mit der Begruendung, ein privater Termin "Kino mit F" koenne dadurch einen Wecker um
 * 05:30 erzeugen. Am Emulator gegen den echten Dienstplan-Feed sank die Erkennung daraufhin von
 * 4 auf 1 Schicht: die real vorkommenden Titel sind kurze Codes ("F", "IMCF", "AD1", "FBE", "+"),
 * und der Eintrag "F" traf kein Muster mehr. Ergebnis: fuer eine echte Fruehschicht gab es keinen
 * Wecker.
 *
 * Die Begruendung war ein Verwechslungsfehler zwischen zwei verschiedenen Funktionen:
 *  - [ShiftConfig.findDefinitionFor] ordnet einem BESTEHENDEN Alarm eine Definition zu und
 *    arbeitet unscharf per `contains` OHNE Wortgrenzen. Dort ist ein einzelner Buchstabe
 *    tatsaechlich gefaehrlich ("S" steckt in "Nachtschicht") — deshalb gilt dort
 *    [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH] = 2. Das bleibt so.
 *  - [ShiftDefinition.matchesKeywords] erkennt Schichten in Kalendertiteln und arbeitet mit
 *    Wortgrenzen (`\b`). Dort trifft "F" nur ein alleinstehendes F — nicht "Fruehschicht", nicht
 *    "Fortbildung".
 *
 * Abwaegung, bewusst so entschieden: eine nicht erkannte Schicht heisst KEIN WECKER. Fuer einen
 * ueberzaehligen Wecker gibt es "Naechsten Alarm ueberspringen"; fuer einen verschlafenen gibt es
 * nichts. Das Restrisiko ("Kino mit F" in einem AUSGEWAEHLTEN Kalender) ist eng, sichtbar und vom
 * Nutzer abstellbar — er waehlt selbst, welche Kalender ueberwacht werden, und kann das Keyword
 * entfernen.
 */
class ShiftConfigDefaultsTest {

    private val defaults = ShiftConfig.getDefaultConfig()

    private fun recognize(title: String): ShiftDefinition? =
        defaults.definitions.firstOrNull { it.matchesKeywords(title) }

    /** Die kurzen Codes, die real im Dienstplan stehen, MUESSEN treffen. */
    @Test
    fun `einbuchstabige Dienstplan-Codes werden erkannt`() {
        assertEquals("Frühschicht", recognize("F")?.name)
        assertEquals("Spätschicht", recognize("S")?.name)
        assertEquals("Nachtschicht", recognize("N")?.name)
    }

    /** Die Stationskuerzel dieser Station bleiben erhalten. */
    @Test
    fun `Stationskuerzel werden weiterhin erkannt`() {
        assertEquals("Frühschicht", recognize("IMCF")?.name)
        assertEquals("Spätschicht", recognize("IMCS")?.name)
        assertEquals("Nachtschicht", recognize("IMCN")?.name)
        assertEquals("Zwischendienst", recognize("IMCZ")?.name)
        assertEquals("S2", recognize("S2")?.name)
    }

    /**
     * Generische, mehrbuchstabige Muster fuer Kollegen auf anderen Stationen — der eigentliche
     * Gewinn des Aufraeumschritts, der erhalten bleibt.
     */
    @Test
    fun `generische Dienstbezeichnungen werden erkannt`() {
        assertEquals("Frühschicht", recognize("Frühdienst")?.name)
        assertEquals("Spätschicht", recognize("Spätdienst")?.name)
        assertEquals("Nachtschicht", recognize("Nachtdienst")?.name)
        assertEquals("Zwischendienst", recognize("ZD")?.name)
    }

    /**
     * Der Definitionsname selbst zaehlt als Muster: ein Termin, der wortwoertlich so heisst,
     * trifft auch ohne eigenes Keyword.
     */
    @Test
    fun `Definitionsname zaehlt als Muster`() {
        assertEquals("Frühschicht", recognize("Frühschicht")?.name)
        assertEquals("Zwischendienst", recognize("Zwischendienst")?.name)
    }

    /**
     * DIE GEGENPROBE, die die einbuchstabigen Keywords ueberhaupt vertretbar macht: Wortgrenzen.
     * Ein einzelner Buchstabe darf NICHT als Bestandteil eines laengeren Wortes treffen.
     */
    @Test
    fun `einzelner Buchstabe trifft nicht innerhalb eines Wortes`() {
        assertNull("'Fortbildung' darf nicht ueber das Keyword 'F' treffen", recognize("Fortbildung"))
        assertNull("'Sommerfest' darf nicht ueber das Keyword 'S' treffen", recognize("Sommerfest"))
        assertNull("'Notaufnahme' darf nicht ueber das Keyword 'N' treffen", recognize("Notaufnahme"))
    }

    /** Titel, die keine Schicht sind, bleiben unerkannt — auch die real vorkommenden. */
    @Test
    fun `fremde Titel bleiben unerkannt`() {
        assertNull(recognize("FBE"))
        assertNull(recognize("+"))
        assertNull(recognize(""))
        assertNull(recognize("Urlaub"))
    }

    /**
     * `AD1` (Rufbereitschaft) ist in den Defaults bewusst NICHT enthalten — die Schicht muss der
     * Nutzer selbst anlegen, weil sie eine eigene Weckzeit und das Flag "Stille Schicht" braucht.
     * Dieser Test haelt den Ist-Zustand fest, damit ein spaeteres Hinzufuegen eine bewusste
     * Entscheidung bleibt und nicht unbemerkt passiert.
     */
    @Test
    fun `Rufbereitschaft AD1 ist bewusst nicht in den Defaults`() {
        assertNull(recognize("AD1"))
    }

    /**
     * `findDefinitionFor` bleibt bei der strengeren Regel: dort wird ein bestehender Alarm einer
     * Definition zugeordnet, unscharf per `contains` ohne Wortgrenzen. Ein einzelner Buchstabe
     * darf dort NICHT unscharf greifen, sonst waehlt "Nachtschicht" ueber das "S" der
     * Spaetschicht die falsche Definition — der Bug, aus dem MIN_FUZZY_KEYWORD_LENGTH entstand.
     */
    @Test
    fun `findDefinitionFor bleibt gegen einbuchstabige Teiltreffer geschuetzt`() {
        assertEquals("Nachtschicht", defaults.findDefinitionFor("Nachtschicht")?.name)
        assertEquals("S2", defaults.findDefinitionFor("S2")?.name)
        assertEquals("Zwischendienst", defaults.findDefinitionFor("Zwischendienst")?.name)
        assertTrue(
            "MIN_FUZZY_KEYWORD_LENGTH muss die unscharfe Stufe von einzelnen Buchstaben freihalten",
            ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH >= 2
        )
    }
}
