package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Echte Unit-Tests für [ShiftDefinition], insbesondere die Wortgrenzen-Regex in
 * `matchesKeywords()`.
 *
 * HINWEIS zu Umlauten (per javac/java auf einer isolierten Testdatei nachgerechnet,
 * NICHT geraten – zuletzt am 10.08.2026): Java-Regex zählt ohne
 * `Pattern.UNICODE_CHARACTER_CLASS` nur `[a-zA-Z0-9_]` als Wortzeichen – Umlaute und 'ß'
 * zählen also NICHT dazu. Für Muster, die MITTEN im Wort einen Umlaut haben ("Früh"),
 * ist das folgenlos: dass "Früh" nicht in "Frühschicht" matcht, liegt an 'h' und 's'
 * (beides Wortzeichen) direkt aneinander, nicht am 'ü'.
 *
 * Für Muster, die mit einem Umlaut oder 'ß' BEGINNEN oder ENDEN, drehte `\b` die
 * Semantik dagegen komplett um – gemessen: `\büd\b` traf "üd" NICHT, dafür aber "xüd";
 * `\bgruß\b` traf "gruß anna" NICHT, dafür "grußformel". Deshalb formuliert
 * [ShiftDefinition] die Wortgrenzen seit v1.22.2 selbst über Lookarounds auf
 * Unicode-Kategorien statt über `\b`. Die Tests unten halten BEIDE Seiten fest: die
 * Umlaut-Fälle treffen jetzt richtig, und ein einzelner Buchstabe trifft weiterhin
 * nicht innerhalb eines Wortes.
 */
class ShiftDefinitionTest {

    private fun definition(keywords: List<String>) = ShiftDefinition(
        id = "test",
        name = "Test",
        keywords = keywords,
        alarmTime = LocalTime.of(6, 0)
    )

    // ---- matchesKeywords: Basis-Wortgrenzen-Verhalten ----

    @Test
    fun `matchesKeywords findet exaktes Match des gesamten Titels`() {
        assertTrue(definition(listOf("F")).matchesKeywords("F"))
    }

    @Test
    fun `matchesKeywords ist case-insensitive`() {
        assertTrue(definition(listOf("f")).matchesKeywords("F"))
        assertTrue(definition(listOf("F")).matchesKeywords("f"))
    }

    @Test
    fun `matchesKeywords matcht Keyword als eigenstaendiges Wort umgeben von Leerzeichen`() {
        assertTrue(definition(listOf("F")).matchesKeywords("Meeting F"))
        assertTrue(definition(listOf("F")).matchesKeywords("F Meeting"))
    }

    @Test
    fun `matchesKeywords lehnt Teilstring-Treffer in laengerem Wort ab`() {
        // "F" darf NICHT in "Fortbildung" matchen (kein Wortende nach 'F')
        assertFalse(definition(listOf("F")).matchesKeywords("Fortbildung"))
    }

    @Test
    fun `matchesKeywords S matcht nicht als Teilstring von S2`() {
        // \bS\b in "s2": nach 'S' folgt '2' (Wortzeichen) -> keine Wortgrenze -> kein Match
        assertFalse(definition(listOf("S")).matchesKeywords("S2"))
    }

    @Test
    fun `matchesKeywords S2 matcht nicht als Teilstring von S`() {
        assertFalse(definition(listOf("S2")).matchesKeywords("S"))
    }

    @Test
    fun `matchesKeywords S2 matcht exakt bei Titel S2`() {
        assertTrue(definition(listOf("S2")).matchesKeywords("S2"))
    }

    @Test
    fun `matchesKeywords erkennt Keyword getrennt durch Satzzeichen als eigenes Wort`() {
        // Satzzeichen (Bindestrich, Slash) sind Nicht-Wortzeichen -> bilden gueltige Wortgrenzen
        assertTrue(definition(listOf("F")).matchesKeywords("F-Schicht"))
        assertTrue(definition(listOf("F")).matchesKeywords("F/Spaetdienst"))
    }

    @Test
    fun `matchesKeywords lehnt Keyword direkt an Ziffer angehaengt ab`() {
        // "12F" - 'F' ist von '2' durch keine Wortgrenze getrennt (beides Wortzeichen)
        assertFalse(definition(listOf("F")).matchesKeywords("12F"))
    }

    @Test
    fun `matchesKeywords findet Treffer wenn irgendein Keyword der Liste passt`() {
        val def = definition(listOf("F", "IMCF"))
        assertTrue(def.matchesKeywords("IMCF Fruehschicht"))
        assertTrue(def.matchesKeywords("Team F"))
        assertFalse(def.matchesKeywords("Geburtstag"))
    }

    @Test
    fun `matchesKeywords mit leerer Keyword-Liste matcht nie`() {
        assertFalse(definition(emptyList()).matchesKeywords("F"))
        assertFalse(definition(emptyList()).matchesKeywords(""))
    }

    @Test
    fun `matchesKeywords mit leerem Titel matcht nicht`() {
        assertFalse(definition(listOf("F")).matchesKeywords(""))
    }

    // ---- Umlaute: 'ü' zaehlt in Java-Regex standardmaessig NICHT als Wortzeichen ----

    @Test
    fun `matchesKeywords Fruehschicht-Keyword als eigenstaendiges Wort mit Leerzeichen`() {
        // "Früh" als Keyword matcht "Früh Schicht" (durch Leerzeichen getrennt)
        assertTrue(definition(listOf("Früh")).matchesKeywords("Früh Schicht"))
    }

    @Test
    fun `matchesKeywords Fruehschicht-Keyword matcht nicht als Praefix von zusammengeschriebenem Wort`() {
        // "Früh" darf NICHT als Teilstring von "Frühschicht" (ein zusammenhängendes Wort) matchen
        assertFalse(definition(listOf("Früh")).matchesKeywords("Frühschicht"))
    }

    @Test
    fun `matchesKeywords Fruehschicht-Keyword matcht bei Satzzeichen-Umrandung`() {
        assertTrue(definition(listOf("Früh")).matchesKeywords("Termin: Früh!"))
    }

    // ---- Muster, die mit Umlaut/ß ANFANGEN oder ENDEN (Fix v1.22.2) ----
    //
    // Mit `\b` war die Semantik hier exakt umgedreht: das Muster traf NUR mitten im Wort und
    // NIEMALS als eigenes Wort. Echte deutsche Schichtbezeichnungen sind davon betroffen, und
    // seit der Schichtname selbst als Muster zaehlt, sagt der Dialogtext dem Nutzer die Erkennung
    // ausdruecklich zu.

    @Test
    fun `matchesKeywords Muster mit fuehrendem Umlaut trifft den gleichnamigen Titel`() {
        assertTrue(definition(listOf("ÜD")).matchesKeywords("ÜD"))
    }

    @Test
    fun `matchesKeywords Muster mit fuehrendem Umlaut trifft als eigenes Wort im Titel`() {
        assertTrue(definition(listOf("ÜD")).matchesKeywords("Station ÜD"))
        assertTrue(definition(listOf("ÜD")).matchesKeywords("Dienstplan: ÜD (8h)"))
    }

    @Test
    fun `matchesKeywords Muster mit fuehrendem Umlaut trifft NICHT innerhalb eines Wortes`() {
        // Die Kehrseite, die `\b` ausgerechnet erlaubte: "üd" in "XÜD" ist kein eigenes Wort.
        assertFalse(definition(listOf("ÜD")).matchesKeywords("XÜD"))
        assertFalse(definition(listOf("ÜD")).matchesKeywords("ÜDX"))
    }

    @Test
    fun `matchesKeywords ausgeschriebene Umlaut-Schichtbezeichnung trifft`() {
        assertTrue(definition(listOf("Übergabedienst")).matchesKeywords("Übergabedienst"))
        assertTrue(
            definition(listOf("Ärztlicher Dienst")).matchesKeywords("Dienstplan: Ärztlicher Dienst")
        )
    }

    @Test
    fun `matchesKeywords Muster mit abschliessendem ss trifft als eigenes Wort`() {
        // Gemessen: `\bgruß\b` traf "gruß anna" NICHT, dafuer aber "grußformel".
        assertTrue(definition(listOf("Gruß")).matchesKeywords("Gruß Anna"))
        assertFalse(definition(listOf("Gruß")).matchesKeywords("Grußformel"))
    }

    @Test
    fun `matchesKeywords Umlaut-Schichtname zaehlt ebenfalls als Muster`() {
        // Der [name] ist seit v1.22.0 ein zusaetzliches Muster - fuer umlaut-initiale Namen war
        // diese Zusage bis v1.22.2 nicht einloesbar.
        val def = ShiftDefinition(
            id = "uebergabe",
            name = "Übergabedienst",
            keywords = listOf("IMCZ"),
            alarmTime = LocalTime.of(7, 0)
        )

        assertTrue(def.matchesKeywords("Übergabedienst"))
        assertTrue(def.matchesKeywords("Station 3: Übergabedienst"))
        assertFalse(def.matchesKeywords("Übergabedienstplan"))
    }

    // ---- getAlarmTimeFormatted / getAlarmLocalTime ----

    @Test
    fun `getAlarmTimeFormatted formatiert mit fuehrenden Nullen`() {
        val def = definition(listOf("F")).copy(alarmTime = LocalTime.of(5, 5))
        assertEquals("05:05", def.getAlarmTimeFormatted())
    }

    @Test
    fun `getAlarmTimeFormatted formatiert Mitternacht als 00-00`() {
        val def = definition(listOf("F")).copy(alarmTime = LocalTime.of(0, 0))
        assertEquals("00:00", def.getAlarmTimeFormatted())
    }

    @Test
    fun `getAlarmTimeFormatted formatiert kurz vor Mitternacht`() {
        val def = definition(listOf("F")).copy(alarmTime = LocalTime.of(23, 59))
        assertEquals("23:59", def.getAlarmTimeFormatted())
    }

    @Test
    fun `getAlarmLocalTime liefert exakt die konfigurierte alarmTime zurueck`() {
        val time = LocalTime.of(14, 30)
        val def = definition(listOf("F")).copy(alarmTime = time)
        assertEquals(time, def.getAlarmLocalTime())
    }

    // ---- isEnabled (Default-Wert, keine eigene Logik aber Vertragswert wichtig) ----

    @Test
    fun `isEnabled ist standardmaessig true`() {
        val def = ShiftDefinition(id = "x", name = "X", keywords = listOf("X"), alarmTime = LocalTime.of(6, 0))
        assertTrue(def.isEnabled)
    }

    @Test
    fun `isEnabled kann explizit auf false gesetzt werden`() {
        val def = ShiftDefinition(
            id = "x", name = "X", keywords = listOf("X"),
            alarmTime = LocalTime.of(6, 0), isEnabled = false
        )
        assertFalse(def.isEnabled)
    }

    // ---- isSilent ("Stille Schicht", Feature D - Default-Wert fuer Bestandskompatibilitaet) ----

    @Test
    fun `isSilent ist standardmaessig false`() {
        val def = ShiftDefinition(id = "x", name = "X", keywords = listOf("X"), alarmTime = LocalTime.of(6, 0))
        assertFalse(def.isSilent)
    }

    @Test
    fun `isSilent kann explizit auf true gesetzt werden`() {
        val def = ShiftDefinition(
            id = "oncall", name = "AD1", keywords = listOf("AD1"),
            alarmTime = LocalTime.of(5, 0), isSilent = true
        )
        assertTrue(def.isSilent)
    }
}
