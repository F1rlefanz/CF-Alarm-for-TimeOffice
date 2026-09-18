package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel.SchnellstartVorlage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Fenster-Editor muss JEDE Blockposition anbieten, die das Modell kennt - dieselbe
 * Zusicherung wie fuer die Anker (`DimmerFensterEditorAnkerTest`): eine Position, die die
 * Nachtdienst-Vorlage setzt und der Editor nicht zeigt, waere dort nicht abwaehlbar.
 */
class DimmerBlockpositionEditorTest {

    @Test
    fun `der Editor bietet jede Blockposition des Modells an`() {
        assertEquals(Blockposition.entries.toSet(), BLOCKPOSITIONEN.toSet())
        assertEquals("keine Position doppelt", Blockposition.entries.size, BLOCKPOSITIONEN.size)
    }

    @Test
    fun `jedes Fenster der Nachtdienst-Vorlage ist im Editor bedienbar`() {
        val regel = DimmerRulesViewModel.baueVorlagenRegel(SchnellstartVorlage.NACHTDIENST_RHYTHMUS, "x", "ND")!!
        regel.windows.forEach { w ->
            assertTrue(w.blockPositionen.isNotEmpty())
            assertTrue(w.blockPositionen.all { it in BLOCKPOSITIONEN })
        }
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
