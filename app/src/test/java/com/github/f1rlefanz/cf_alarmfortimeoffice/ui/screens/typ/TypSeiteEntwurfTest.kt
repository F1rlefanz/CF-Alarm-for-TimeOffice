package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.TypEntwurf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Die Rechnung hinter der Typ-Seite (Umbau #187), ohne Compose: ein Entwurf fasst die
 * Konfiguration nicht an, „Speichern“ schreibt genau einmal und GENAU den einen Typ, und ein
 * verschwundener Typ entsteht nie neu. Das Gegenstueck zur Festlegung des Betreuers: je Tastendruck
 * waere `updateShiftConfig()` ein Alarm-Sync - geschrieben wird nur ueber [TypSeiteSpeicherung].
 */
class TypSeiteEntwurfTest {

    private val frueh = ShiftDefinition(
        id = "id-frueh", name = "Frueh", keywords = listOf("F", "IMCF"), alarmTime = LocalTime.of(5, 30)
    )
    private val spaet = ShiftDefinition(
        id = "id-spaet", name = "Spaet", keywords = listOf("S"), alarmTime = LocalTime.of(11, 0)
    )
    private val urlaub = ShiftDefinition(
        id = "id-urlaub", name = "Urlaub", keywords = listOf("U"), alarmTime = LocalTime.of(6, 0),
        weckerAn = false
    )
    private val config = ShiftConfig(definitions = listOf(frueh, spaet, urlaub))

    /** Zaehlt jeden Schreibvorgang - jeder entspricht einem `updateShiftConfig()` samt Alarm-Sync. */
    private class Schreiber {
        val geschrieben = mutableListOf<ShiftConfig>()
        val speicherung = TypSeiteSpeicherung { geschrieben += it }
    }

    @Test
    fun `ein Entwurf ohne Speichern laesst die Konfiguration unveraendert`() {
        val schreiber = Schreiber()
        val ausgang = TypEntwurf.aus(frueh)

        // Alles, was der Nutzer auf der Seite tun kann, ohne "Speichern" zu tippen.
        var entwurf = ausgang
        entwurf = entwurf.copy(name = "Frueher als frueh")
        entwurf = entwurf.copy(muster = entwurf.muster + "FD")
        entwurf = entwurf.copy(weckzeitText = "04:15")
        entwurf = entwurf.copy(weckerAn = false, isSilent = true, kategorie = ShiftKategorie.URLAUB)

        assertTrue(TypSeiteEntwurf.hatAenderungen(ausgang, entwurf))
        assertTrue("Nichts darf geschrieben werden", schreiber.geschrieben.isEmpty())

        // Auch das blosse Ausrechnen des Ergebnisses veraendert die gespeicherte Konfiguration nicht.
        TypSeiteEntwurf.speichernIn(config, frueh.id, entwurf)
        assertEquals(listOf(frueh, spaet, urlaub), config.definitions)
    }

    @Test
    fun `Speichern schreibt genau einmal und ersetzt nur den einen Typ`() {
        val schreiber = Schreiber()
        val entwurf = TypEntwurf.aus(spaet).copy(name = "Spaetdienst", weckzeitText = "10:45")

        val erstes = schreiber.speicherung.speichere(config, spaet.id, entwurf)
        val zweites = schreiber.speicherung.speichere(config, spaet.id, entwurf) // Doppeltipp

        assertEquals(SpeicherErgebnis.GESCHRIEBEN, erstes)
        assertEquals(SpeicherErgebnis.SCHON_GESCHRIEBEN, zweites)
        assertEquals("genau ein updateShiftConfig()", 1, schreiber.geschrieben.size)

        val neu = schreiber.geschrieben.single()
        assertEquals("Reihenfolge und Anzahl bleiben - die Erkennung nimmt den ersten Treffer",
            listOf("id-frueh", "id-spaet", "id-urlaub"), neu.definitions.map { it.id })
        assertEquals("Die Nachbarn bleiben unberuehrt", frueh, neu.definitions[0])
        assertEquals(urlaub, neu.definitions[2])
        assertEquals("Spaetdienst", neu.definitions[1].name)
        assertEquals(LocalTime.of(10, 45), neu.definitions[1].alarmTime)
        assertEquals("Was das Formular nicht aendert, bleibt", listOf("S"), neu.definitions[1].keywords)
        assertEquals(config.autoAlarmEnabled, neu.autoAlarmEnabled)
        assertEquals(config.ohneKalendereintragWie, neu.ohneKalendereintragWie)
    }

    @Test
    fun `ein verschwundener Typ wird beim Speichern nicht neu angelegt`() {
        val schreiber = Schreiber()
        val entwurf = TypEntwurf.aus(spaet).copy(name = "Spaetdienst")
        // Zuruecksetzen/Import haben den Typ entfernt, waehrend die Seite offen war.
        val ohneSpaet = ShiftConfig(definitions = listOf(frueh, urlaub))

        assertNull(TypSeiteEntwurf.speichernIn(ohneSpaet, spaet.id, entwurf))
        assertEquals(
            SpeicherErgebnis.TYP_FEHLT,
            schreiber.speicherung.speichere(ohneSpaet, spaet.id, entwurf)
        )
        assertTrue("Es wird nichts geschrieben, erst recht nichts angehaengt", schreiber.geschrieben.isEmpty())
    }

    @Test
    fun `ohne gelesene Konfiguration wird nichts geschrieben`() {
        val schreiber = Schreiber()
        val entwurf = TypEntwurf.aus(frueh).copy(name = "X")
        assertEquals(
            SpeicherErgebnis.KEINE_KONFIGURATION,
            schreiber.speicherung.speichere(null, frueh.id, entwurf)
        )
        assertTrue(schreiber.geschrieben.isEmpty())
    }

    @Test
    fun `ein nicht speicherbares Formular schreibt nichts und sperrt ein spaeteres Speichern nicht`() {
        val schreiber = Schreiber()
        val leererName = TypEntwurf.aus(frueh).copy(name = " ")
        val ungueltigeZeit = TypEntwurf.aus(frueh).copy(weckzeitText = "05:")

        assertEquals(SpeicherErgebnis.NICHT_SPEICHERBAR, schreiber.speicherung.speichere(config, frueh.id, leererName))
        assertEquals(SpeicherErgebnis.NICHT_SPEICHERBAR, schreiber.speicherung.speichere(config, frueh.id, ungueltigeZeit))
        assertTrue(schreiber.geschrieben.isEmpty())

        // Ein abgelehnter Versuch verbraucht das einmalige Schreiben nicht.
        val gueltig = TypEntwurf.aus(frueh).copy(name = "Frueher")
        assertEquals(SpeicherErgebnis.GESCHRIEBEN, schreiber.speicherung.speichere(config, frueh.id, gueltig))
        assertEquals(1, schreiber.geschrieben.size)
    }

    @Test
    fun `Aenderungen werden am Stand beim Oeffnen gemessen - zurueckgetippt ist nichts geaendert`() {
        val ausgang = TypEntwurf.aus(frueh)
        assertFalse("Frisch geoeffnet: keine Rueckfrage", TypSeiteEntwurf.hatAenderungen(ausgang, ausgang))

        val geaendert = ausgang.copy(name = "Frueh2")
        assertTrue(TypSeiteEntwurf.hatAenderungen(ausgang, geaendert))
        assertFalse(
            "Wieder auf den alten Namen gesetzt: nichts zu verwerfen",
            TypSeiteEntwurf.hatAenderungen(ausgang, geaendert.copy(name = "Frueh"))
        )
    }

    @Test
    fun `ein Typ ohne Muster bekommt ein leeres Feld und ist erst mit einem Muster speicherbar`() {
        val ignorieren = ShiftDefinition(id = "id-i", name = "Egal", keywords = emptyList(), alarmTime = LocalTime.of(6, 0))
            .mitKategorie(ShiftKategorie.IGNORIEREN)
        val entwurf = TypEntwurf.aus(ignorieren)
        assertEquals(listOf(""), entwurf.muster)
        assertFalse(entwurf.speicherbar)
        assertTrue(entwurf.copy(muster = listOf("X")).speicherbar)
    }

    @Test
    fun `der Entwurf uebersteht Speichern und Wiederherstellen unveraendert`() {
        // rememberSaveable legt ihn ueber Drehen, Dunkelmodus und Schriftgroesse in ein Bundle.
        ShiftKategorie.entries.forEach { kategorie ->
            val entwurf = TypEntwurf(
                name = "Nacht ${kategorie.schluessel}",
                muster = listOf("N", "", "NACHT"),
                weckzeitText = "04:",
                isEnabled = false,
                isSilent = true,
                weckerAn = false,
                kategorie = kategorie
            )
            assertEquals(kategorie.name, entwurf, TypEntwurf.ausSpeicherListe(entwurf.alsSpeicherListe()))
        }
    }

    @Test
    fun `ein unlesbarer gespeicherter Entwurf wird verworfen statt zu einem falschen Entwurf`() {
        assertNull(TypEntwurf.ausSpeicherListe(emptyList()))
        assertNull(TypEntwurf.ausSpeicherListe(listOf("Name", "kein-Liste", "05:30", true, false, true, "dienst")))
        // Eine unbekannte Kategorie faellt auf Dienst, wie beim Lesen eines Typs ohne Kategorie.
        val unbekannt = TypEntwurf.ausSpeicherListe(listOf("N", listOf("N"), "05:30", true, false, true, "gibt-es-nicht"))
        assertEquals(ShiftKategorie.DIENST, unbekannt?.kategorie)
        assertNotEquals(null, unbekannt)
    }
}
