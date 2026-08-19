package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

/**
 * Die Wortgrenzen-Muster duerfen nur EINMAL kompiliert werden (Pruefrunde 7).
 *
 * [ShiftDefinition.matchesKeywords] wird in der verschachtelten Schleife von
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine] aufgerufen - einmal je
 * Termin x Definition x Muster - und direkt danach ein zweites Mal fuer dieselben Muster aus
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester]. Vorher baute jeder dieser
 * Aufrufe den Regex neu (`"...".toRegex()` kompiliert ein frisches Pattern), was bei 14 Tagen
 * Dienstplan mehrere hundert Kompilate pro Durchlauf ergab.
 *
 * Wichtig ist beides: dass der Vorrat greift UND dass er am Ergebnis nichts aendert - ein
 * Muster-Vorrat, der einen Treffer falsch beantwortet, waere ein Wecker zu viel oder zu wenig.
 */
class ShiftDefinitionPatternCacheTest {

    @Before
    fun setUp() {
        WordBoundaryPatterns.resetForTest()
    }

    private val fruehschicht = ShiftDefinition(
        id = "fs",
        name = "Fruehschicht",
        keywords = listOf("FS"),
        alarmTime = LocalTime.of(5, 30)
    )

    @Test
    fun `dasselbe Muster wird nur einmal kompiliert`() {
        // Ein Titel OHNE Treffer, damit beide Muster geprueft werden: erst das Keyword "fs",
        // danach der Definitionsname als zusaetzliches Muster.
        repeat(50) {
            assertFalse(fruehschicht.matchesKeywords("Zahnarzt Dr. Mueller"))
        }

        assertEquals(
            "Zwei verschiedene Muster (Keyword + Name) duerfen zusammen nur zwei Kompilate " +
                "erzeugen, egal wie oft geprueft wird",
            2,
            WordBoundaryPatterns.compilationCount
        )
    }

    @Test
    fun `der Muster-Vorrat aendert die Treffer nicht`() {
        // Zweimal dieselbe Pruefung: der zweite Durchgang laeuft garantiert aus dem Vorrat.
        repeat(2) {
            assertTrue("Keyword als eigenstaendiges Wort trifft", fruehschicht.matchesKeywords("Dienst FS heute"))
            assertTrue("Der Definitionsname trifft ebenfalls", fruehschicht.matchesKeywords("Fruehschicht"))
            assertFalse("Mitten im Wort trifft weiterhin nicht", fruehschicht.matchesKeywords("FSX-Termin"))
            assertFalse("Leerer Titel trifft nie", fruehschicht.matchesKeywords("   "))
        }
    }

    /**
     * Zwei Definitionen mit demselben Muster teilen sich den Eintrag - der Vorrat haengt am
     * Muster, nicht an der Instanz. Sonst brachte er nichts, denn die Konfiguration wird bei jedem
     * Read neu deserialisiert.
     */
    @Test
    fun `gleiches Muster in zwei Definitionen kompiliert nur einmal`() {
        val zweite = fruehschicht.copy(id = "fs2", name = "FS")

        assertTrue(fruehschicht.matchesKeywords("Dienst FS"))
        assertTrue(zweite.matchesKeywords("Dienst FS"))

        assertEquals(1, WordBoundaryPatterns.compilationCount)
    }
}
