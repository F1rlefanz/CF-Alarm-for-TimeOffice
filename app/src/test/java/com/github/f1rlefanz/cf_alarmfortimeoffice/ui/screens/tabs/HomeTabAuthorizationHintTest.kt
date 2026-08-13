package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Erklaertext fuer [NoShiftReason.AUTHORIZATION_LOST] arbeitete mit einer Positionsangabe ("in
 * der Karte darunter"), und die stimmte nicht: direkt unter der Karte "Naechste Schicht" liegt die
 * Alarm-Status-Karte (die in den Wecker-Tab springt), der Knopf "Kalender-Zugriff erneuern" sitzt
 * erst in der Karte "Kalender-Events" danach. Ein Nutzer ohne Kalender-Zugriff - also ohne jeden
 * Wecker - suchte damit an der falschen Stelle weiter.
 *
 * Dieselbe Fehlerklasse wie die CLAUDE.md-Invariante "Der Akku-Onboarding-Screen darf keine
 * Einstellungen versprechen": ein UI-Text darf keinen Ort beschreiben, den es so nicht gibt. Die
 * anderen Gruende nennen deshalb Tab UND Kartenbeschriftung wortgleich mit der echten UI.
 */
class HomeTabAuthorizationHintTest {

    private val text = noShiftExplanation(NoShiftReason.AUTHORIZATION_LOST)

    @Test
    fun `verlorener Zugriff nennt Karte und Knopf statt einer Position`() {
        assertTrue(
            "Die Kartenbeschriftung aus HomeTabContent muss vorkommen: $text",
            text.contains("Kalender-Events")
        )
        assertTrue(
            "Die Knopfbeschriftung aus HomeTabContent muss vorkommen: $text",
            text.contains("Kalender-Zugriff erneuern")
        )
        assertFalse(
            "Keine Positionsangabe - die Karte direkt darunter ist die falsche: $text",
            text.contains("darunter")
        )
    }

    @Test
    fun `der Hinweis bleibt bei ausgeschalteter Automatik der eigentliche Grund`() {
        val ohneAutomatik = noShiftExplanation(
            NoShiftReason.AUTHORIZATION_LOST,
            autoAlarmEnabled = false
        )
        assertTrue(ohneAutomatik.startsWith(text))
        assertTrue(ohneAutomatik.contains("Kalender-Zugriff erneuern"))
    }
}
