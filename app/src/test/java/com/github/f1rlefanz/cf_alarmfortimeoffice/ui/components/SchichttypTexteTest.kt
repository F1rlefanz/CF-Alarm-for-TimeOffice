package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.OhneEintragTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.ShiftDefinitionTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.ShiftConfigTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.SCHICHTTYPEN_UNTERTITEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gegenpruefung Etappe 1, seit #220 mitgefuehrt: die Texte rund um die Schichttyp-Liste sagen nur zu,
 * was wirklich gilt, und nennen Schalter mit ihrer heutigen Beschriftung.
 */
class SchichttypTexteTest {

    /**
     * Seit Etappe 4 (#254) steht auch das Licht am Typ, der Hinweis ist wieder der ganze Satz der
     * Vorlage (SPEC 3.2). Bewusste Verhaltensaenderung gegenueber Etappe 3 („noch kein Licht“,
     * #256) - kein Kerntest.
     */
    @Test
    fun `Der Einordnen-Hinweis ist der Satz der Vorlage und nennt das Licht am Typ`() {
        assertEquals(
            "Die Kategorie sortiert nur. Wecker, Dimmen, „Nicht stören“ und Licht stellst du danach " +
                "am Typ selbst ein.",
            EinordnenTexte.KATEGORIE_HINWEIS
        )
    }

    /** SPEC 3.6, Prototyp `zeigeEinstellungen`. */
    @Test
    fun `Die Zeile Schichttypen einrichten nennt das Licht pro Typ`() {
        assertEquals("Wecker, Dimmen, „Nicht stören“ und Licht pro Typ", SCHICHTTYPEN_UNTERTITEL)
    }

    /** Seit Etappe 3 steht das Dimmen am Typ: ein Urlaub-Typ ohne Fenster dimmt nicht (#230). */
    @Test
    fun `Die Urlaub-Beschreibung nennt Wecker und Dimmer wie die Vorlage`() {
        assertEquals(
            "Urlaubstage – auf Wunsch ganz ohne Wecker und Dimmer.",
            ShiftDefinitionTexte.kategorieBeschreibung(ShiftKategorie.URLAUB)
        )
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
