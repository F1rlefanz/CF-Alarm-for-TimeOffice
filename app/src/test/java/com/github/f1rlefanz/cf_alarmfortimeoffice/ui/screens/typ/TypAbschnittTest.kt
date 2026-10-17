package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Die Rechnung hinter der Karte „Typ“ (Umbau #188), ohne Compose: ein Chip-Klick auf × nimmt nur
 * dieses Muster heraus, „+“ mit einem Duplikat aendert nichts, und ein Kategoriewechsel behaelt
 * alles andere und setzt erst beim Speichern `isOnCall`/`isSickDay`.
 */
class TypAbschnittTest {

    private val frueh = ShiftDefinition(
        id = "id-frueh", name = "Frueh", keywords = listOf("F", "IMCF", "FD"), alarmTime = LocalTime.of(5, 30),
        isSilent = true
    )
    private val spaet = ShiftDefinition(
        id = "id-spaet", name = "Spaet", keywords = listOf("S"), alarmTime = LocalTime.of(11, 0)
    )
    private val config = ShiftConfig(definitions = listOf(frueh, spaet))

    private val ausgang = TypEntwurf.aus(frueh)

    // --- × ---

    @Test
    fun `x nimmt nur dieses Muster heraus und laesst den Rest stehen`() {
        val neu = TypAbschnittLogik.musterEntfernen(ausgang, "IMCF")

        assertEquals(listOf("F", "FD"), neu.muster)
        assertEquals("Alles andere bleibt", ausgang.copy(muster = listOf("F", "FD")), neu)
        // Auch beim Speichern: nur das Muster fehlt, Weckzeit, Name und stummer Wecker bleiben.
        val gespeichert = TypSeiteEntwurf.speichernIn(config, frueh.id, neu)!!.definitions.first { it.id == frueh.id }
        assertEquals(listOf("F", "FD"), gespeichert.keywords)
        assertEquals(LocalTime.of(5, 30), gespeichert.alarmTime)
        assertTrue(gespeichert.isSilent)
        assertEquals("Der Nachbar bleibt unberuehrt", spaet, TypSeiteEntwurf.speichernIn(config, frueh.id, neu)!!.definitions[1])
    }

    @Test
    fun `x auf ein Muster, das es nicht gibt, aendert nichts`() {
        assertSame(ausgang, TypAbschnittLogik.musterEntfernen(ausgang, "gibt-es-nicht"))
        // Nur der genaue Wortlaut trifft: „f“ ist nicht „F“.
        assertSame(ausgang, TypAbschnittLogik.musterEntfernen(ausgang, "f"))
    }

    // --- + ---

    @Test
    fun `plus mit einem Duplikat aendert nichts - Gross und klein egal, getrimmt`() {
        listOf("F", "f", " F ", "imcf", "Fd", "  FD").forEach { eingabe ->
            assertSame(eingabe, ausgang, TypAbschnittLogik.musterHinzufuegen(ausgang, eingabe))
            assertTrue(eingabe, TypAbschnittLogik.istDoppelt(ausgang, eingabe))
        }
    }

    @Test
    fun `plus mit einem leeren Muster aendert nichts`() {
        listOf("", " ", "   ").forEach { eingabe ->
            assertSame("'$eingabe'", ausgang, TypAbschnittLogik.musterHinzufuegen(ausgang, eingabe))
            assertFalse("'$eingabe'", TypAbschnittLogik.istDoppelt(ausgang, eingabe))
        }
    }

    @Test
    fun `plus haengt ein neues Muster getrimmt hinten an`() {
        val neu = TypAbschnittLogik.musterHinzufuegen(ausgang, "  IMCF2 ")

        assertEquals(listOf("F", "IMCF", "FD", "IMCF2"), neu.muster)
        assertEquals("Nur die Muster aendern sich", ausgang.copy(muster = neu.muster), neu)
    }

    @Test
    fun `der Entwurf fasst die Konfiguration nicht an - erst Speichern schreibt`() {
        val geschrieben = mutableListOf<ShiftConfig>()
        val speicherung = TypSeiteSpeicherung { geschrieben += it }

        val entwurf = TypAbschnittLogik.musterEntfernen(TypAbschnittLogik.musterHinzufuegen(ausgang, "NEU"), "F")

        assertTrue("Muster hinzufuegen und entfernen schreibt nichts", geschrieben.isEmpty())
        assertEquals("Die gespeicherte Konfiguration bleibt, wie sie ist", listOf(frueh, spaet), config.definitions)
        assertEquals(SpeicherErgebnis.GESCHRIEBEN, speicherung.speichere(config, frueh.id, entwurf))
        assertEquals(listOf("IMCF", "FD", "NEU"), geschrieben.single().definitions.first { it.id == frueh.id }.keywords)
    }

    // --- Typ ohne Muster (Platzhalter) ---

    @Test
    fun `ein Typ ohne Muster traegt den Platzhalter - hinzufuegen und entfernen ist dann wieder derselbe Stand`() {
        val ohneMuster = TypEntwurf.aus(frueh.copy(keywords = emptyList()))
        assertEquals(listOf(""), ohneMuster.muster)
        assertTrue("Der Platzhalter ist kein Chip", TypAbschnittLogik.sichtbareMuster(ohneMuster).isEmpty())

        val mitX = TypAbschnittLogik.musterHinzufuegen(ohneMuster, "X")
        assertEquals("Der Platzhalter weicht dem ersten Muster", listOf("X"), mitX.muster)
        assertTrue(mitX.speicherbar)

        val wieder = TypAbschnittLogik.musterEntfernen(mitX, "X")
        assertEquals(listOf(""), wieder.muster)
        assertFalse("Ohne Muster ist der Typ nicht speicherbar", wieder.speicherbar)
        assertFalse(
            "Hinzugefuegt und wieder entfernt: nichts zu verwerfen, keine Rueckfrage",
            TypSeiteEntwurf.hatAenderungen(ohneMuster, wieder)
        )
    }

    @Test
    fun `das letzte Muster zu entfernen macht den Typ unspeicherbar`() {
        val nurS = TypEntwurf.aus(spaet)
        val leer = TypAbschnittLogik.musterEntfernen(nurS, "S")

        assertTrue(TypAbschnittLogik.sichtbareMuster(leer).isEmpty())
        assertFalse(leer.speicherbar)
        assertNull("Gespeichert wird nichts", TypSeiteEntwurf.speichernIn(config, spaet.id, leer))
    }

    // --- Text im „+“-Feld ---

    @Test
    fun `ein noch nicht hinzugefuegtes Muster im Feld wird beim Speichern nicht verschluckt`() {
        val kandidat = TypAbschnittLogik.mitEingabe(ausgang, " FD3 ")

        assertEquals(listOf("F", "IMCF", "FD", "FD3"), kandidat.muster)
        assertTrue("Der Text im Feld zaehlt als Aenderung (Rueckfrage beim Zurueck)", TypSeiteEntwurf.hatAenderungen(ausgang, kandidat))
        // Ein Duplikat und ein leeres Feld sind keine Aenderung.
        assertSame(ausgang, TypAbschnittLogik.mitEingabe(ausgang, "f"))
        assertSame(ausgang, TypAbschnittLogik.mitEingabe(ausgang, ""))
        assertFalse(TypSeiteEntwurf.hatAenderungen(ausgang, TypAbschnittLogik.mitEingabe(ausgang, "F")))
    }

    @Test
    fun `die Meldung Ein Typ braucht mindestens ein Muster folgt demselben Stand wie Speichern`() {
        val ohneMuster = TypEntwurf.aus(frueh.copy(keywords = emptyList()))

        assertTrue("Nichts da, nichts im Feld: die Meldung erklaert das graue Speichern",
            TypAbschnittLogik.fehltMuster(ohneMuster, ""))
        assertFalse("Text im Feld wird mitgespeichert - dann fehlt kein Muster",
            TypAbschnittLogik.fehltMuster(ohneMuster, "FD"))
        assertTrue("Leerzeichen im Feld ersetzen kein Muster", TypAbschnittLogik.fehltMuster(ohneMuster, "   "))
        // Dieselbe Rechnung wie „Speichern“: genau dann speicherbar, wenn kein Muster fehlt.
        listOf("", "FD", "  ").forEach { eingabe ->
            assertEquals("'$eingabe'", !TypAbschnittLogik.fehltMuster(ohneMuster, eingabe),
                TypAbschnittLogik.mitEingabe(ohneMuster, eingabe).speicherbar)
        }
    }

    // --- Kategorie ---

    @Test
    fun `ein Kategoriewechsel behaelt Muster Weckzeit und Schalter und setzt beim Speichern isOnCall und isSickDay`() {
        val gesetzt = frueh.copy(weckerAn = false).mitKategorie(ShiftKategorie.RUFBEREITSCHAFT)
        assertTrue("Ausgangslage: Rufbereitschaft", gesetzt.isOnCall)
        val configRuf = ShiftConfig(definitions = listOf(gesetzt, spaet))

        ShiftKategorie.entries.forEach { ziel ->
            val entwurf = TypAbschnittLogik.kategorieWechseln(TypEntwurf.aus(gesetzt), ziel)

            // Im Entwurf aendert sich nur die Kategorie.
            assertEquals(ziel.name, TypEntwurf.aus(gesetzt).copy(kategorie = ziel), entwurf)

            val neu = TypSeiteEntwurf.speichernIn(configRuf, gesetzt.id, entwurf)!!.definitions.first { it.id == gesetzt.id }
            assertEquals(ziel.name, ziel, neu.kategorie)
            assertEquals("${ziel.name}: isSickDay", ziel == ShiftKategorie.KRANK, neu.isSickDay)
            assertEquals("${ziel.name}: isOnCall", ziel == ShiftKategorie.RUFBEREITSCHAFT, neu.isOnCall)
            assertEquals("${ziel.name}: Muster", listOf("F", "IMCF", "FD"), neu.keywords)
            assertEquals("${ziel.name}: Weckzeit", LocalTime.of(5, 30), neu.alarmTime)
            assertFalse("${ziel.name}: Wecker aus bleibt aus", neu.weckerAn)
            assertTrue("${ziel.name}: stummer Wecker bleibt", neu.isSilent)
        }
    }

    @Test
    fun `ein Wechsel zu Krank und zurueck laesst nie beide Flags stehen`() {
        val krank = TypSeiteEntwurf.speichernIn(config, frueh.id, TypAbschnittLogik.kategorieWechseln(ausgang, ShiftKategorie.KRANK))!!
        val krankTyp = krank.definitions.first { it.id == frueh.id }
        assertTrue(krankTyp.isSickDay)
        assertFalse(krankTyp.isOnCall)

        val zurueck = TypSeiteEntwurf.speichernIn(krank, frueh.id, TypAbschnittLogik.kategorieWechseln(TypEntwurf.aus(krankTyp), ShiftKategorie.DIENST))!!
        val dienstTyp = zurueck.definitions.first { it.id == frueh.id }
        assertFalse(dienstTyp.isSickDay)
        assertFalse(dienstTyp.isOnCall)
        assertEquals(LocalTime.of(5, 30), dienstTyp.alarmTime)
    }

    // --- Warnung ---

    @Test
    fun `die Ein-Buchstaben-Warnung erscheint, wenn ein Chip oder das Feld einen Buchstaben hat`() {
        // Chips F, IMCF, FD: F ist ein einzelner Buchstabe, der Chip loest die Warnung aus.
        assertEquals(ShiftDefinitionTexte.EIN_BUCHSTABE_SCHICHT, TypAbschnittLogik.musterWarnung(ausgang, ""))

        val ohneEinzeln = TypEntwurf.aus(frueh.copy(keywords = listOf("IMCF", "FD")))
        assertNull(TypAbschnittLogik.musterWarnung(ohneEinzeln, ""))
        assertNull("Zwei Buchstaben im Feld warnen nicht", TypAbschnittLogik.musterWarnung(ohneEinzeln, "FD"))
        assertEquals(
            "Auch das noch nicht hinzugefuegte Muster im Feld warnt",
            ShiftDefinitionTexte.EIN_BUCHSTABE_SCHICHT, TypAbschnittLogik.musterWarnung(ohneEinzeln, "K")
        )
        // Ein Zeichen wie „+“ trifft nur den ganzen Titel (#150): keine Warnung.
        assertNull(TypAbschnittLogik.musterWarnung(TypEntwurf.aus(frueh.copy(keywords = listOf("+"))), "+"))
    }

    @Test
    fun `die Warnung folgt Kategorie und Wecker-Schalter`() {
        val krank = TypEntwurf.aus(frueh.copy(keywords = listOf("K"))).copy(kategorie = ShiftKategorie.KRANK)
        assertEquals(ShiftDefinitionTexte.EIN_BUCHSTABE_KRANK, TypAbschnittLogik.musterWarnung(krank, ""))

        val weckerAus = TypEntwurf.aus(frueh.copy(keywords = listOf("F"))).copy(weckerAn = false)
        assertEquals(ShiftDefinitionTexte.EIN_BUCHSTABE_OHNE_WECKER, TypAbschnittLogik.musterWarnung(weckerAus, ""))
    }

    // --- Texte ---

    @Test
    fun `die Pflichttexte der Karte stehen wortgleich wie in BILDSCHIRME B3`() {
        assertEquals("Typ", TypAbschnittTexte.ABSCHNITT)
        assertEquals("Name", TypAbschnittTexte.NAME)
        assertEquals("Muster im Kalender – alle Codes, die zu diesem Typ gehören", TypAbschnittTexte.MUSTER_TITEL)
        assertEquals("Kategorie", TypAbschnittTexte.KATEGORIE)
        assertEquals("Muster IMCF entfernen", TypAbschnittTexte.musterEntfernen("IMCF"))
    }

    @Test
    fun `die Einleitung der Kategorie-Wahl nennt den Typ und sagt nur, was gilt`() {
        val text = KategorieWahlTexte.einleitung("Frühschicht")

        // „Deine Einstellungen bleiben“ gilt, weil der Wechsel alles ausser der Kategorie behaelt (SPEC E2,
        // Test `ein Kategoriewechsel behaelt ...`).
        assertEquals("Kategorie für „Frühschicht“ ändern. Deine Einstellungen bleiben, wie sie sind.", text)
    }
}
