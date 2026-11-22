package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.OhneEintragTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.ShiftDefinitionTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.ShiftConfigTexte
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gegenpruefung Etappe 1, seit #220 mitgefuehrt: die Texte rund um die Schichttyp-Liste sagen nur zu,
 * was wirklich gilt, und nennen Schalter mit ihrer heutigen Beschriftung.
 */
class SchichttypTexteTest {

    /**
     * Seit Etappe 3 (#220) stehen Dimmen und „Nicht stören“ am Typ, der Hinweis nennt sie (SPEC 3.2);
     * Licht kommt erst mit Etappe 4 und wird bis dahin nicht versprochen. Bewusste Verhaltensaenderung
     * gegenueber dem Stand von Etappe 1 („verspricht am Typ nur den Wecker“) - kein Kerntest.
     */
    @Test
    fun `Der Einordnen-Hinweis verspricht am Typ Wecker, Dimmen und Nicht stoeren, noch kein Licht`() {
        val text = EinordnenTexte.KATEGORIE_HINWEIS
        assertTrue(text, text.startsWith("Die Kategorie sortiert nur."))
        listOf("Wecker", "Dimmen", "„Nicht stören“").forEach { assertTrue("$it: $text", text.contains(it)) }
        assertFalse("Licht kommt erst mit Etappe 4: $text", text.contains("Licht"))
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
        // Der Reset verliert BEIDES an der festen Zeile: die Wahl („wie <Typ>“, `getDefaultConfig()` setzt
        // keinen Verweis) UND ihre eigenen Dimm-/DND-Einstellungen (Pruefer-Befund zu #220).
        assertTrue(reset, reset.contains("die Wahl und die eigenen Einstellungen bei „${OhneEintragTexte.TITEL}“"))
        assertTrue(reset, reset.contains("Dimm-Fenster"))
        assertTrue(reset, reset.contains("„Nicht stören“-Schalter"))
    }
}
