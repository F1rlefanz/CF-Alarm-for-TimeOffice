package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Ausfall
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailableNotifier.Companion.meldung
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.KALENDER_NICHT_GEFUNDEN_TEXT
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.KALENDER_NICHT_GEFUNDEN_TITEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Was die Hintergrund-Warnung SAGT - je nachdem, wie der Abruf ausging.
 *
 * Sie ist das Einzige, was der Nutzer sieht, ohne die App zu oeffnen, und sie schickt ihn in die
 * App. Dort muss er finden, was sie ankuendigt: dieselbe Bezeichnung und denselben Ausweg. Seit
 * auch der TOTALAUSFALL gemeldet wird (30.09.2026), gibt es dafuer drei Lagen mit verschiedenen
 * Anzeigen in der App - ein einziger Text passte nicht mehr auf alle:
 *  - einzelne Kalender gescheitert: die Karte "Kalender" nennt sie und bietet "Aus Auswahl
 *    entfernen" an.
 *  - alle fehlen (404/403): die Uebersicht sagt "Kalender nicht gefunden".
 *  - alle scheitern anders (Anmeldung, abgeschnittene Liste, unbekannt): die Karte zeigt die
 *    Anmeldung ("Neu anmelden") - ein Entfernen-Knopf steht dort NICHT.
 */
class CalendarUnavailableNotifierTextTest {

    @Test
    fun `fehlen alle Kalender, heisst die Meldung wie die Karte in der Uebersicht`() {
        // Ueber die Schichtgrenze hinweg: die Konstante liegt in ui/ und darf aus alarm/ nicht
        // gelesen werden (tools/invarianten/pruefe_code.py) - deshalb haelt dieser Test beide
        // Stellen wortgleich.
        for (anzahl in listOf(1, 2)) {
            assertEquals(KALENDER_NICHT_GEFUNDEN_TITEL, meldung(Ausfall.ALLE_NICHT_GEFUNDEN, anzahl).titel)
        }
        assertTrue(
            "gelöscht oder nicht mehr freigegeben" in meldung(Ausfall.ALLE_NICHT_GEFUNDEN, 1).text
        )
    }

    @Test
    fun `jede Meldung nennt die Folge und wo es weitergeht`() {
        for (ausfall in Ausfall.entries) {
            for (anzahl in listOf(1, 2)) {
                val text = meldung(ausfall, anzahl).text
                assertTrue("$ausfall/$anzahl: Folge fehlt", "keine neuen Wecker" in text)
                assertTrue("$ausfall/$anzahl: Bestand fehlt", "bereits gestellten bleiben" in text)
                assertTrue("$ausfall/$anzahl: Ort fehlt", "System-Status unter \"Kalender\"" in text)
            }
        }
    }

    /**
     * Beim TOTALAUSFALL bietet die Meldung das Entfernen nicht an: fehlt der einzige Kalender,
     * waere es eine Abwahl, die alle Wecker der naechsten zwei Wochen raeumt (die App fragt
     * deshalb vorher und bietet zuerst "Anderen Kalender waehlen" an). Und scheitern alle aus
     * einem anderen Grund, steht in der App gar kein Entfernen-Knopf - der Text versprach dann
     * einen Weg, den es nicht gibt.
     */
    @Test
    fun `nur bei einzelnen Kalendern bietet die Meldung das Entfernen an`() {
        assertTrue("entfernen" in meldung(Ausfall.EINZELNE, 1).text)
        for (anzahl in listOf(1, 2)) {
            assertFalse("entfernen" in meldung(Ausfall.ALLE_NICHT_GEFUNDEN, anzahl).text)
            assertFalse("entfernen" in meldung(Ausfall.ALLE_NICHT_ABRUFBAR, anzahl).text)
        }
    }

    @Test
    fun `scheitern alle aus anderem Grund, behauptet die Meldung weder Fehlen noch Anmeldung`() {
        // Die Ursache ist dort gerade NICHT bekannt - eine abgeschnittene Terminliste ist weder
        // ein geloeschter Kalender noch ein Anmeldeproblem.
        for (anzahl in listOf(1, 2)) {
            val m = meldung(Ausfall.ALLE_NICHT_ABRUFBAR, anzahl)
            assertEquals("Kalender nicht abrufbar", m.titel)
            assertFalse("gefunden" in m.text)
            assertFalse("anmelden" in m.text.lowercase())
        }
    }

    @Test
    fun `mehrere betroffene Kalender stehen im Plural`() {
        assertEquals("Ein Kalender ist nicht mehr abrufbar", meldung(Ausfall.EINZELNE, 1).titel)
        assertEquals("2 Kalender sind nicht mehr abrufbar", meldung(Ausfall.EINZELNE, 2).titel)
        assertTrue("lässt er sich" in meldung(Ausfall.EINZELNE, 1).text)
        assertTrue("lassen sie sich" in meldung(Ausfall.EINZELNE, 2).text)
    }

    /**
     * Die Karte "Kalender" nennt einen betroffenen Kalender nur, wenn Google ihn noch in der
     * Kalenderliste fuehrt - ein geloeschter oder nicht mehr freigegebener steht dort nicht mehr,
     * dann steht nur die Anzahl da (unavailableCalendarDetails). Gerade das ist der typische
     * dauerhafte Anlass; ein Versprechen "dort steht, welcher" waere dann gebrochen (Review
     * 30.09.2026, am Emulator gesehen). Der Ausweg "Aus Auswahl entfernen" funktioniert trotzdem.
     */
    @Test
    fun `bei einzelnen Kalendern verspricht die Meldung keinen Namen`() {
        for (anzahl in listOf(1, 2)) {
            val text = meldung(Ausfall.EINZELNE, anzahl).text
            assertFalse(text, "welche" in text)
        }
    }

    /**
     * Beim Totalausfall scheitern in DIESEM Lauf alle ausgewaehlten Kalender - "keinen" stimmt
     * also fuer jede Anzahl, auch wenn die Entprellung erst einen Teil davon meldet. Und es ist
     * wortgleich mit der Karte der Uebersicht (KALENDER_NICHT_GEFUNDEN_TEXT), die denselben
     * Zustand beschreibt.
     */
    @Test
    fun `beim Totalausfall haengt der Text nicht an der Anzahl`() {
        for (ausfall in listOf(Ausfall.ALLE_NICHT_GEFUNDEN, Ausfall.ALLE_NICHT_ABRUFBAR)) {
            assertEquals(meldung(ausfall, 1), meldung(ausfall, 2))
            assertTrue("keinen ausgewählten Kalender" in meldung(ausfall, 1).text)
        }
        val gefunden = "Google findet keinen ausgewählten Kalender mehr"
        assertTrue(meldung(Ausfall.ALLE_NICHT_GEFUNDEN, 1).text.startsWith(gefunden))
        assertTrue(KALENDER_NICHT_GEFUNDEN_TEXT.startsWith(gefunden))
    }
}
