package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer [ShiftConfig.withCodeAssignedTo] - die Zuordnung eines Kalender-Kuerzels zu einer
 * Schicht (Vorschlags-Karte). Jeder Test hier haelt eine Falle fest, in die der erste Wurf gelaufen
 * war: er ergaenzte nur das Keyword und liess Aktivierung und Eindeutigkeit offen.
 */
class ShiftCodeAssignmentTest {

    private fun def(id: String, name: String, keywords: List<String>, enabled: Boolean = true) =
        ShiftDefinition(
            id = id,
            name = name,
            keywords = keywords,
            alarmTime = java.time.LocalTime.of(5, 30),
            isEnabled = enabled
        )

    private fun config(vararg definitions: ShiftDefinition) =
        ShiftConfig(definitions = definitions.toList())

    @Test
    fun `Kuerzel wird als Erkennungsmuster ergaenzt`() {
        val result = config(def("a", "Fruehdienst", listOf("F")))
            .withCodeAssignedTo("FD1", "a")

        assertTrue(result!!.definitions.first().keywords.contains("FD1"))
    }

    /**
     * DIE WICHTIGSTE FALLE: `ShiftRecognitionEngine` beachtet nur AKTIVIERTE Definitionen. Eine
     * Zuordnung an eine ausgeschaltete Schicht war damit ein Klick, der garantiert nichts tut -
     * und der Nutzer sucht die Ursache dann in der Erkennung, nicht in einem Schalter.
     *
     * Der Fall ist nicht theoretisch: die Vorschlags-Karte bietet ein Kuerzel genau dann an, wenn
     * es von keiner AKTIVIERTEN Definition getroffen wird. Ein Kuerzel, das bei einer
     * ausgeschalteten Schicht liegt, wird also vorgeschlagen.
     */
    @Test
    fun `Zieldefinition wird aktiviert - sonst waere die Zuordnung wirkungslos`() {
        val result = config(def("a", "Nachtdienst", listOf("N"), enabled = false))
            .withCodeAssignedTo("ND", "a")

        assertTrue("Ziel muss aktiviert werden", result!!.definitions.first().isEnabled)
        assertTrue(result.definitions.first().keywords.contains("ND"))
    }

    /**
     * Auch wenn das Kuerzel schon beim Ziel steht, ist die Zuordnung NICHT wirkungslos, solange die
     * Schicht ausgeschaltet ist. Der erste Wurf brach hier per Duplikat-Pruefung ab und machte den
     * Knopf damit dauerhaft wirkungslos - fuer genau den Fall, fuer den die Karte gebaut wurde.
     */
    @Test
    fun `bekanntes Kuerzel bei ausgeschalteter Schicht aktiviert sie trotzdem`() {
        val result = config(def("a", "Fruehdienst", listOf("F"), enabled = false))
            .withCodeAssignedTo("F", "a")

        assertTrue(result!!.definitions.first().isEnabled)
        assertEquals(listOf("F"), result.definitions.first().keywords)
    }

    /**
     * Zwei Besitzer desselben Kuerzels waeren eine stille, von der LISTENREIHENFOLGE abhaengige
     * Entscheidung darueber, wann geweckt wird - `findDefinitionFor` nimmt den ersten Treffer. Der
     * Nutzer hat gerade gesagt, zu welcher Schicht das Kuerzel gehoert; das ist die Antwort.
     */
    @Test
    fun `Kuerzel wird bei allen anderen Schichten entfernt`() {
        val result = config(
            def("a", "Fruehdienst", listOf("F", "IMCF")),
            def("b", "Spaetdienst", listOf("S", "IMCF"))
        ).withCodeAssignedTo("IMCF", "b")

        assertEquals(listOf("F"), result!!.definitions.first { it.id == "a" }.keywords)
        assertEquals(listOf("S", "IMCF"), result.definitions.first { it.id == "b" }.keywords)
    }

    /** Gross-/Kleinschreibung darf keinen zweiten Besitzer erzeugen. */
    @Test
    fun `Entfernen bei anderen ignoriert Gross- und Kleinschreibung`() {
        val result = config(
            def("a", "Fruehdienst", listOf("fd")),
            def("b", "Spaetdienst", listOf("S"))
        ).withCodeAssignedTo("FD", "b")

        assertTrue(result!!.definitions.first { it.id == "a" }.keywords.isEmpty())
    }

    /** Nur die Zieldefinition wird aktiviert - eine fremde ausgeschaltete bleibt ausgeschaltet. */
    @Test
    fun `andere Schichten werden nicht mit aktiviert`() {
        val result = config(
            def("a", "Fruehdienst", listOf("F"), enabled = false),
            def("b", "Spaetdienst", listOf("S"), enabled = false)
        ).withCodeAssignedTo("S2", "b")

        assertTrue(result!!.definitions.first { it.id == "a" }.isEnabled.not())
        assertTrue(result.definitions.first { it.id == "b" }.isEnabled)
    }

    @Test
    fun `leeres Kuerzel und unbekannte Definition aendern nichts`() {
        val base = config(def("a", "Fruehdienst", listOf("F")))

        assertNull(base.withCodeAssignedTo("   ", "a"))
        assertNull(base.withCodeAssignedTo("FD", "gibtsnicht"))
    }

    /** Steht schon alles so, gibt es keine ueberfluessige Schreiboperation (und keinen Sync). */
    @Test
    fun `keine Aenderung wenn alles schon so steht`() {
        val base = config(def("a", "Fruehdienst", listOf("F"), enabled = true))

        assertNull(base.withCodeAssignedTo("F", "a"))
    }

    /** Rundum-Probe: nach der Zuordnung findet die Erkennung das Kuerzel auch wirklich. */
    @Test
    fun `nach der Zuordnung trifft findDefinitionFor das Kuerzel`() {
        val result = config(
            def("a", "Fruehdienst", listOf("F"), enabled = false),
            def("b", "Spaetdienst", listOf("S"))
        ).withCodeAssignedTo("IMCF", "a")!!

        assertEquals("Fruehdienst", result.findDefinitionFor("IMCF")?.name)
    }
}
