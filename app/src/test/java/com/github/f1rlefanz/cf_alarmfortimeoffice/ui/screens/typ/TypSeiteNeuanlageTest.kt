package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Die Neuanlage ueber die Typ-Seite (Umbau #190), ohne Compose: WELCHER Schreibweg, wie oft, und was
 * die Seite dem Nutzer sagt. Der ganze Ablauf mit dem echten ShiftViewModel steht in `EinordnenAblaufTest`.
 */
class TypSeiteNeuanlageTest {

    private val frueh = ShiftDefinition(
        id = "id-frueh", name = "Frueh", keywords = listOf("F"), alarmTime = LocalTime.of(5, 30)
    )
    private val config = ShiftConfig(definitions = listOf(frueh))
    private val vorlage = ShiftDefinition(
        id = "neu", name = "AD1", keywords = listOf("AD1"), alarmTime = LocalTime.of(6, 0)
    )

    /** Zeichnet beide Schreibwege auf: `updateShiftConfig` und `legeTypAusCodeAn`. */
    private class Schreiber {
        val konfigurationen = mutableListOf<ShiftConfig>()
        val ausCode = mutableListOf<Pair<String, ShiftDefinition>>()
        val speicherung = TypSeiteSpeicherung { konfigurationen += it }
        val legeAusCodeAn: (String, ShiftDefinition) -> Unit = { code, typ -> ausCode += code to typ }
    }

    @Test
    fun `mit Fund-Code geht Erstellen ueber legeTypAusCodeAn - sonst wird hinten angehaengt - und je Seite nur einmal`() {
        // Mit Fund-Code: der Weg, der den Code auch bei anderen Typen abraeumt und isEnabled zurueckstellt.
        val ausFund = Schreiber()
        val entwurf = TypEntwurf.aus(vorlage).copy(name = "Einsatz")
        assertEquals(
            SpeicherErgebnis.GESCHRIEBEN,
            ausFund.speicherung.erstelle(config, vorlage, "AD1", entwurf, ausFund.legeAusCodeAn)
        )
        assertEquals(
            "Doppeltipp: nichts ein zweites Mal",
            SpeicherErgebnis.SCHON_GESCHRIEBEN,
            ausFund.speicherung.erstelle(config, vorlage, "AD1", entwurf, ausFund.legeAusCodeAn)
        )
        assertEquals("AD1", ausFund.ausCode.single().first)
        assertEquals("neu", ausFund.ausCode.single().second.id)
        assertEquals("Einsatz", ausFund.ausCode.single().second.name)
        assertTrue("der andere Weg bleibt unberuehrt", ausFund.konfigurationen.isEmpty())

        // Ohne Fund-Code (das „+“): angehaengt, Reihenfolge bleibt - die Erkennung nimmt den ersten Treffer.
        val plus = Schreiber()
        assertEquals(
            SpeicherErgebnis.GESCHRIEBEN,
            plus.speicherung.erstelle(config, vorlage, null, entwurf, plus.legeAusCodeAn)
        )
        assertEquals(listOf("id-frueh", "neu"), plus.konfigurationen.single().definitions.map { it.id })
        assertEquals(frueh, plus.konfigurationen.single().definitions.first())
        assertTrue(plus.ausCode.isEmpty())
    }

    @Test
    fun `Erstellen schreibt nichts ohne Konfiguration, bei unvollstaendigem Formular und bei vergebener id`() {
        val schreiber = Schreiber()
        val leer = ShiftDefinition(id = "leer", name = "", keywords = emptyList(), alarmTime = LocalTime.of(6, 0))
        val ohneName = TypEntwurf.aus(leer)

        assertEquals(
            SpeicherErgebnis.KEINE_KONFIGURATION,
            schreiber.speicherung.erstelle(null, vorlage, "AD1", TypEntwurf.aus(vorlage), schreiber.legeAusCodeAn)
        )
        assertEquals(
            "Name und Muster fehlen",
            SpeicherErgebnis.NICHT_SPEICHERBAR,
            schreiber.speicherung.erstelle(config, leer, null, ohneName, schreiber.legeAusCodeAn)
        )
        assertEquals(
            "Die id steht schon in der Konfiguration",
            SpeicherErgebnis.SCHON_GESCHRIEBEN,
            schreiber.speicherung.erstelle(config, frueh, null, TypEntwurf.aus(frueh), schreiber.legeAusCodeAn)
        )
        assertTrue(schreiber.konfigurationen.isEmpty() && schreiber.ausCode.isEmpty())

        // Ein abgelehnter Versuch verbraucht das einmalige Schreiben nicht.
        assertEquals(
            SpeicherErgebnis.GESCHRIEBEN,
            schreiber.speicherung.erstelle(config, vorlage, null, TypEntwurf.aus(vorlage), schreiber.legeAusCodeAn)
        )
    }

    @Test
    fun `die leere Vorlage des Plus ergibt dieselben Vorgaben wie der Dialog davor und ist erst mit Name und Muster speicherbar`() {
        val leer = ShiftEditFormular.leererTyp { "neu" }

        // Der Dialog hatte keine Vorlage: TypEntwurf.aus(null). Dieselben Vorgaben, nur mit einer id.
        assertEquals(TypEntwurf.aus(null), TypEntwurf.aus(leer))
        assertEquals("neu", leer.id)
        assertEquals(ShiftKategorie.DIENST, leer.kategorie)
        assertFalse("ohne Name und Muster ist „Erstellen“ gesperrt", TypEntwurf.aus(leer).speicherbar)
        assertTrue(TypEntwurf.aus(leer).copy(name = "Zwischendienst", muster = listOf("ZD")).speicherbar)
    }

    @Test
    fun `der Banner nennt den Code und die gerade gewaehlte Kategorie - und behauptet keine fertige Anlage`() {
        val text = TypSeiteTexte.banner("AD1", ShiftKategorie.RUFBEREITSCHAFT).text

        assertTrue(text, text.startsWith("Neuer Typ aus dem Kalender-Code AD1 als „Rufbereitschaft“."))
        // Festlegung des Betreuers: darunter ist in Etappe 2 nur der Wecker einstellbar - kein Dimmen, kein Licht.
        assertTrue(text, text.contains("Alles darunter ist eine Voreinstellung"))
        assertFalse(text, text.contains("Dimm", ignoreCase = true))
        assertFalse(text, text.contains("Licht", ignoreCase = true))
        // Textwahrheit: gespeichert wird erst mit „Erstellen“, also steht da nicht „Neu angelegt“.
        assertFalse(text, text.contains("Neu angelegt"))
        assertTrue(text, text.contains("Angelegt ist der Typ erst mit „Erstellen“."))
    }

    @Test
    fun `die Rueckfrage der Neuanlage sagt, dass nichts angelegt wird, und nennt bei einem Fund, wo der Code bleibt`() {
        val ausFund = TypSeiteTexte.neuerTypVerwerfenText("AD1")
        val ohneFund = TypSeiteTexte.neuerTypVerwerfenText(null)

        assertTrue(ausFund, ausFund.contains("noch nicht angelegt"))
        assertTrue(ausFund, ausFund.contains("„AD1“ bleibt unter „Im Kalender gefunden, noch nicht eingeordnet“"))
        assertTrue(ohneFund, ohneFund.contains("noch nicht angelegt"))
        assertFalse("Ohne Fund gibt es keinen Code, der bleiben koennte", ohneFund.contains("bleibt"))
        // Die Rueckfrage eines bestehenden Typs spricht von einem GESPEICHERTEN Typ - hier gibt es keinen.
        assertFalse(ausFund.contains("gespeicherte Typ"))
    }
}
