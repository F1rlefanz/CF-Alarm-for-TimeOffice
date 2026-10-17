package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Die Rechnung der Karte „Wecker“ (Umbau #189), ohne Compose. Die Abnahme: bei „Wecker aus“ sind
 * Weckzeit und „Stummer Wecker“ gesperrt, und ihre Werte bleiben beim Speichern erhalten (SPEC E2) -
 * gelaufen wird der echte Schreibweg der Seite ([TypSeiteEntwurf.speichernIn]).
 */
class WeckerAbschnittTest {

    private val frueh = ShiftDefinition(
        id = "id-frueh", name = "Frueh", keywords = listOf("F"), alarmTime = LocalTime.of(5, 30), isSilent = true
    )
    private val config = ShiftConfig(definitions = listOf(frueh))

    private fun gespeichert(entwurf: TypEntwurf): ShiftDefinition =
        TypSeiteEntwurf.speichernIn(config, frueh.id, entwurf)!!.definitions.single()

    @Test
    fun `Wecker aus sperrt Weckzeit und Stummen Wecker - ihre Werte bleiben beim Speichern`() {
        val an = TypEntwurf.aus(frueh)
        assertFalse("Wecker an: nichts gesperrt", WeckerAbschnittLogik.felderGesperrt(an))

        val aus = an.copy(weckerAn = false)
        assertTrue(WeckerAbschnittLogik.felderGesperrt(aus))

        val typ = gespeichert(aus)
        assertFalse(typ.weckerAn)
        assertEquals("Die Weckzeit bleibt stehen", LocalTime.of(5, 30), typ.alarmTime)
        assertTrue("Der Stumm-Schalter bleibt stehen", typ.isSilent)

        // Wieder einschalten bringt beide Werte zurueck (SPEC E2): nichts ging verloren.
        val wieder = gespeichert(TypEntwurf.aus(typ).copy(weckerAn = true))
        assertEquals(LocalTime.of(5, 30), wieder.alarmTime)
        assertTrue(wieder.isSilent)
    }

    @Test
    fun `Bei Wecker aus sperrt eine kaputte Weckzeit das Speichern nicht und ueberschreibt nichts`() {
        // Das gesperrte Feld kann nur ein Rest von frueher enthalten: "07:" ist ein Zwischenstand.
        val aus = TypEntwurf.aus(frueh).copy(weckerAn = false, weckzeitText = "07:")
        assertFalse("Gesperrt heisst: kein Fehler", WeckerAbschnittLogik.weckzeitFehler(aus))
        assertTrue(aus.speicherbar)
        assertEquals("Die bisherige Zeit, nicht 00:00", LocalTime.of(5, 30), gespeichert(aus).alarmTime)

        // Eine gueltige, getippte Zeit wird dagegen uebernommen - auch bei Wecker aus (Wert bleibt, wie getippt).
        val getippt = aus.copy(weckzeitText = "07:15")
        assertEquals(LocalTime.of(7, 15), gespeichert(getippt).alarmTime)
    }

    @Test
    fun `Bei Wecker an ist eine kaputte Weckzeit ein Fehler und sperrt das Speichern`() {
        val kaputt = TypEntwurf.aus(frueh).copy(weckzeitText = "07:")
        assertTrue(WeckerAbschnittLogik.weckzeitFehler(kaputt))
        assertFalse(kaputt.speicherbar)
        assertNull("Nichts wird geschrieben", TypSeiteEntwurf.speichernIn(config, frueh.id, kaputt))

        assertFalse(WeckerAbschnittLogik.weckzeitFehler(kaputt.copy(weckzeitText = "07:00")))
    }

    @Test
    fun `Das Zeitfeld nimmt Zwischenstaende einer Uhrzeit und lehnt Buchstaben ab`() {
        val entwurf = TypEntwurf.aus(frueh)
        listOf("", "6", "06", "06:", "06:3", "06:30", ":30").forEach { eingabe ->
            assertEquals(eingabe, eingabe, WeckerAbschnittLogik.weckzeitAendern(entwurf, eingabe).weckzeitText)
        }
        listOf("ab:cd", "06:30:", "6.30", "06 30", "06:30 Uhr").forEach { eingabe ->
            assertEquals(
                "$eingabe wird nicht uebernommen",
                entwurf.weckzeitText,
                WeckerAbschnittLogik.weckzeitAendern(entwurf, eingabe).weckzeitText
            )
        }
    }

    @Test
    fun `Die Karte Wecker fehlt genau bei Krank und Ignorieren, der Hinweis folgt dem Schalter`() {
        val ohneKarte = ShiftKategorie.entries.filterNot { WeckerAbschnittLogik.zeigtWeckerKarte(it) }
        assertEquals(listOf(ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN), ohneKarte)

        assertEquals(ShiftDefinitionTexte.WECKER_AN_HINWEIS, WeckerAbschnittLogik.weckerHinweis(true))
        assertEquals(ShiftDefinitionTexte.WECKER_AUS_HINWEIS, WeckerAbschnittLogik.weckerHinweis(false))
    }
}
