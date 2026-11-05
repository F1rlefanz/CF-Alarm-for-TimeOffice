package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.OhneEintragTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.ShiftDefinitionTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.ShiftConfigTexte
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gegenpruefung Etappe 1: die Texte rund um die Schichttyp-Liste sagen nur zu, was in Etappe 1
 * wirklich gilt, und nennen Schalter mit ihrer heutigen Beschriftung.
 */
class SchichttypTexteTest {

    /** Der Editor bietet in Etappe 1 nur den Wecker; Dimmen, DND und Licht kommen mit Etappe 3/4. */
    @Test
    fun `Der Einordnen-Hinweis verspricht am Typ nur den Wecker`() {
        val text = EinordnenTexte.KATEGORIE_HINWEIS
        assertTrue(text, text.startsWith("Die Kategorie sortiert nur."))
        assertTrue(text, text.contains("Wecker"))
        listOf("Dimmen", "Nicht stören", "Licht").forEach { assertFalse("$it: $text", text.contains(it)) }
    }

    /**
     * Eine komplett leere Eventliste erreicht den Sync nie (Leerlisten-Sperren, #173/#175) - der
     * Text darf fuer diesen Fall keinen Wecker zusagen.
     */
    @Test
    fun `Die Erklaerung der festen Zeile nennt den komplett leeren Kalender`() {
        val text = OhneEintragTexte.ERKLAERUNG
        // Pflichttext BILDSCHIRME.md B3o (Satzanfang)
        assertTrue(text, text.startsWith("Feste Zeile: gilt für jeden Tag, an dem im Kalender gar nichts steht"))
        assertTrue(text, text.contains("kein einziger Termin"))
        assertTrue(text, text.contains("keinen Wecker"))
    }

    /** Seit #174 heisst der Schalter „Stummer Wecker“; der Reset nimmt auch die feste Zeile mit. */
    @Test
    fun `Reset-Rueckfrage und Stumm-Symbol nennen den Schalter wie der Editor`() {
        listOf(ShiftConfigTexte.RESET_RUECKFRAGE, ShiftConfigTexte.STUMM_SYMBOL).forEach {
            assertFalse(it, it.contains("Stille Schicht"))
        }
        assertTrue(ShiftConfigTexte.STUMM_SYMBOL.startsWith(ShiftDefinitionTexte.STUMMER_WECKER_TITEL))
        val reset = ShiftConfigTexte.RESET_RUECKFRAGE
        assertTrue(reset, reset.contains("„${ShiftDefinitionTexte.STUMMER_WECKER_TITEL}“"))
        assertTrue(reset, reset.contains("„${ShiftDefinitionTexte.WECKER_AN_TITEL}“"))
        assertTrue(reset, reset.contains("Kategorien"))
        assertTrue(reset, reset.contains("„${OhneEintragTexte.TITEL}“"))
    }
}
