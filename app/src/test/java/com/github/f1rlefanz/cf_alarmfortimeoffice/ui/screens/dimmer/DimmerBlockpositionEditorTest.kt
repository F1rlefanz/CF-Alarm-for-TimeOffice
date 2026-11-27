package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.DimmenAbschnittLogik
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die Auswahl der Blockpositionen (`DimTimeControls.kt`, seit #221 vom Regel-Editor und vom Abschnitt
 * „Bildschirm dimmen“ der Typ-Seite geteilt) muss JEDE Blockposition anbieten, die das Modell kennt - dieselbe
 * Zusicherung wie fuer die Anker (`DimmerFensterEditorAnkerTest`): eine Position, die ein gespeichertes Fenster
 * traegt und die Auswahl nicht zeigt, waere dort nicht abwaehlbar.
 */
class DimmerBlockpositionEditorTest {

    @Test
    fun `der Editor bietet jede Blockposition des Modells an`() {
        assertEquals(Blockposition.entries.toSet(), BLOCKPOSITIONEN.toSet())
        assertEquals("keine Position doppelt", Blockposition.entries.size, BLOCKPOSITIONEN.size)
    }

    @Test
    fun `die Typ-Seite bietet Alle und danach jede Blockposition an`() {
        assertEquals(listOf<Blockposition?>(null) + BLOCKPOSITIONEN, DimmenAbschnittLogik.CHIPS)
    }

    @Test
    fun `Umschalten fuegt hinzu und entfernt`() {
        val nurErster = setOf(Blockposition.ERSTER)
        assertEquals(setOf(Blockposition.ERSTER, Blockposition.LETZTER), blockpositionUmschalten(nurErster, Blockposition.LETZTER))
        assertEquals(setOf(Blockposition.ERSTER), blockpositionUmschalten(setOf(Blockposition.ERSTER, Blockposition.LETZTER), Blockposition.LETZTER))
    }

    /** Ein Fenster ohne Position gaelte nirgends, stuende aber in der Regel - das letzte Haekchen bleibt. */
    @Test
    fun `das letzte Haekchen laesst sich nicht abwaehlen`() {
        val nurErster = setOf(Blockposition.ERSTER)
        assertEquals(nurErster, blockpositionUmschalten(nurErster, Blockposition.ERSTER))
    }
}
