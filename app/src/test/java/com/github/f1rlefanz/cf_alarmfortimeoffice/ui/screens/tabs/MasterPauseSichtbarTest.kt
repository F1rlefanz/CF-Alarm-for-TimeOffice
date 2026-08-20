package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ALARM_STATUS_PAUSIERT_AUSWEG
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ALARM_STATUS_PAUSIERT_TEXT
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.AlarmStatusZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.alarmStatusZustand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRUEFRUNDE 8, BEFUND 10: Die Master-Pause war nur an EINER Stelle sichtbar - dem Schalter ganz
 * unten im Einstellungen-Tab.
 *
 * Der gemeldete Ablauf: Pause vor dem Urlaub eingeschaltet, danach wie ueblich Home- und
 * Wecker-Tab geprueft. Home zeigte die naechste Schicht (die Erkennung laeuft unabhaengig
 * weiter) und darunter "Keine aktiven Alarme" OHNE Grund; der Wecker-Tab bestaetigte
 * "Automatische Alarme: AN" - mitsamt der in diesem Zustand FALSCHEN Beschreibung
 * "Deaktivieren loescht sofort alle bereits gesetzten Wecker" (geloescht sind sie laengst).
 * Daraus schliesst jeder, die Wecker entstuenden noch. Tatsaechlich weist der Backstop in
 * `syncAlarms()` jeden Sync ab und die 6h-Wartung ist gecancelt: die App laeuft aus diesem
 * Zustand NIE von allein heraus.
 *
 * Diese Tests halten fest, dass der Zustand dort ankommt, wo der Nutzer nachsieht - und dass er
 * nicht dreimal auf demselben Bildschirm steht.
 */
class MasterPauseSichtbarTest {

    // ---- Alarm-Status-Karte (Home UND Wecker) -------------------------------------------------

    @Test
    fun `pausiert schlaegt jeden anderen Zweig des Alarm-Status`() {
        // Auch mit noch angezeigtem Alarm-Bestand: pause() hat ALLE Alarme geloescht, ein
        // stehengebliebener UI-Zustand ist danach kein Versprechen mehr. "3 aktive Alarme" waere
        // hier eine Weckzeit, die nie gestellt wird - die gefaehrlichste Anzeige, die eine
        // Wecker-App haben kann.
        assertEquals(
            AlarmStatusZustand.PAUSIERT,
            alarmStatusZustand(masterPausePaused = true, hasActiveAlarms = true, isLoading = false)
        )
        assertEquals(
            AlarmStatusZustand.PAUSIERT,
            alarmStatusZustand(masterPausePaused = true, hasActiveAlarms = false, isLoading = true)
        )
    }

    @Test
    fun `ohne Pause bleiben die bisherigen drei Zweige unveraendert`() {
        assertEquals(
            AlarmStatusZustand.AKTIVE_ALARME,
            alarmStatusZustand(masterPausePaused = false, hasActiveAlarms = true, isLoading = true)
        )
        assertEquals(
            AlarmStatusZustand.LAEDT,
            alarmStatusZustand(masterPausePaused = false, hasActiveAlarms = false, isLoading = true)
        )
        assertEquals(
            AlarmStatusZustand.KEINE_ALARME,
            alarmStatusZustand(masterPausePaused = false, hasActiveAlarms = false, isLoading = false)
        )
    }

    @Test
    fun `der Alarm-Status nennt Folge UND Ausweg`() {
        // Die Folge: es klingelt nichts. Ohne diesen Satz bleibt "Keine aktiven Alarme" ein
        // Zustand ohne Ursache.
        assertTrue(ALARM_STATUS_PAUSIERT_TEXT.contains("pausiert"))
        assertTrue(ALARM_STATUS_PAUSIERT_TEXT.contains("kein Wecker"))
        // Der Ausweg nennt die Schalterbeschriftung WORTGLEICH - nach ihr sucht der Nutzer,
        // eine Positionsangabe waere wertlos.
        assertTrue(ALARM_STATUS_PAUSIERT_AUSWEG.contains("Hintergrunddienste pausieren"))
        // Kein Fachbegriff aus dem Code.
        assertFalse(ALARM_STATUS_PAUSIERT_TEXT.contains("Master"))
        assertFalse(ALARM_STATUS_PAUSIERT_AUSWEG.contains("Sync"))
    }

    // ---- Wecker-Tab: der Schalter "Automatische Alarme" ---------------------------------------

    @Test
    fun `der Schalter behauptet waehrend der Pause nicht mehr das Loeschen`() {
        val normal = autoAlarmBeschreibung(masterPausePaused = false)
        val pausiert = autoAlarmBeschreibung(masterPausePaused = true)

        assertEquals(AUTO_ALARM_BESCHREIBUNG_NORMAL, normal)
        assertNotEquals(normal, pausiert)
        // Der alte Satz ist in diesem Zustand schlicht falsch - er darf nicht stehenbleiben.
        assertFalse(pausiert.contains("Deaktivieren löscht sofort"))
        assertTrue(pausiert.contains("pausiert"))
        // WIDERLEGTE ANNAHME: hier stand einmal, der Weg zurueck muesse AUCH in dieser
        // Beschreibung stehen ("sonst ist es nur eine Sackgasse"). Die Pruefung dieses Fixes hat
        // gezeigt, dass er damit im selben Scroll-Bereich zweimal nahezu wortgleich steht - die
        // Karte direkt darunter (AlarmStatusHeader) traegt ihn bereits. Die Beschreibung sagt
        // deshalb nur noch, dass der Schalter gerade wirkungslos ist; genau EINE Stelle je
        // Bildschirm nennt den Ausweg.
        assertFalse(pausiert.contains("Hintergrunddienste pausieren"))
    }

    @Test
    fun `der Schalter ist waehrend der Pause gesperrt - und nur dann zusaetzlich zum Ladefall`() {
        // Gesperrt, weil er keine sichtbare Wirkung haette; das WARUM steht als Beschreibung
        // daneben (siehe Test darueber), es ist also kein toter Schalter.
        assertFalse(
            autoAlarmSchalterBedienbar(shiftConfigGeladen = true, masterPausePaused = true)
        )
        // Der bestehende Ladefall bleibt unveraendert.
        assertFalse(
            autoAlarmSchalterBedienbar(shiftConfigGeladen = false, masterPausePaused = false)
        )
        assertTrue(
            autoAlarmSchalterBedienbar(shiftConfigGeladen = true, masterPausePaused = false)
        )
    }

    // ---- Home-Tab: "Keine Schicht erkannt" ----------------------------------------------------

    @Test
    fun `die Naechste-Schicht-Karte erwaehnt die Pause`() {
        val ohne = noShiftExplanation(NoShiftReason.NO_EVENTS)
        val mit = noShiftExplanation(NoShiftReason.NO_EVENTS, masterPausePaused = true)

        assertFalse(ohne.contains("pausiert"))
        assertTrue(mit.startsWith(ohne))
        assertTrue(mit.contains(NO_SHIFT_HINWEIS_PAUSIERT))
    }

    @Test
    fun `es steht hoechstens EIN Zusatz da - die Pause hat Vorrang`() {
        // Beide Zustaende gleichzeitig sind moeglich: die Master-Pause ruehrt autoAlarmEnabled
        // bewusst nicht an. Zwei Hinweise nebeneinander liessen offen, welcher der wirksame ist -
        // und die Pause ist der umfassendere (sie schaltet zusaetzlich Dimmer, "Nicht stoeren",
        // Hue und die 6h-Wartung ab).
        val beides = noShiftExplanation(
            NoShiftReason.NO_EVENTS,
            autoAlarmEnabled = false,
            masterPausePaused = true
        )
        assertTrue(beides.contains(NO_SHIFT_HINWEIS_PAUSIERT))
        assertFalse(beides.contains("Automatische Alarme sind derzeit ausgeschaltet"))
        assertEquals(1, beides.count { it == '\n' })
    }

    @Test
    fun `ohne Pause bleibt der bestehende Automatik-Hinweis erhalten`() {
        val nurAutomatikAus = noShiftExplanation(NoShiftReason.NO_EVENTS, autoAlarmEnabled = false)
        assertTrue(nurAutomatikAus.contains("Automatische Alarme sind derzeit ausgeschaltet"))
    }

    // ---- Status-Tab: die Karte ----------------------------------------------------------------

    @Test
    fun `die Statuskarte nennt Folge und Ausweg in Nutzersprache`() {
        // FOLGE: kein Wecker - und ausdruecklich auch nicht fuer die Schichten, die Home
        // weiterhin anzeigt. Genau an dieser weiterlaufenden Anzeige ist der Zustand bisher
        // unbemerkt geblieben.
        assertTrue(ALLES_PAUSIERT_TEXT.contains("kein Wecker"))
        assertTrue(ALLES_PAUSIERT_TEXT.contains("weiterhin anzeigt"))
        // AUSWEG: hier auf der Karte oder ueber den Schalter, dessen Beschriftung wortgleich
        // genannt wird.
        assertTrue(ALLES_PAUSIERT_TEXT.contains("Hintergrunddienste pausieren"))
        assertTrue(ALLES_PAUSIERT_AKTION.isNotBlank())
        // Keine Fachbegriffe.
        for (text in listOf(ALLES_PAUSIERT_TITEL, ALLES_PAUSIERT_TEXT, ALLES_PAUSIERT_AKTION)) {
            assertFalse(text.contains("Master"))
            assertFalse(text.contains("Sync"))
            assertFalse(text.contains("Backstop"))
        }
    }
}
