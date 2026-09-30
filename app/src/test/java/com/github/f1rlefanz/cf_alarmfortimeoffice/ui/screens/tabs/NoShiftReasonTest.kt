package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deckt HomeTabContents pure Erklaerfunktionen ab.
 *
 * Vorher stand im else-Zweig der "Naechste Schicht"-Karte EIN konstanter Satz ("Keine Schicht
 * erkannt") fuer sechs voellig verschiedene Ursachen - ein Nutzer (gemeldet: ein Kollege auf einer
 * anderen Station, dessen Erkennungsmuster nicht passten) konnte daraus nicht ablesen, ob der
 * Kalender, die Anmeldung oder seine Stichwoerter das Problem sind. Diese Tests halten die
 * Rangfolge der Ursachen und die Nennung der echten Termintitel fest.
 */
class NoShiftReasonTest {

    private fun reason(
        hasSelectedCalendars: Boolean = true,
        calendarAuthorizationValid: Boolean = true,
        kalenderNichtErreichbar: Boolean = false,
        unavailableCalendarCount: Int = 0,
        errorMessage: String? = null,
        eventCount: Int = 5,
        shiftConfigLoaded: Boolean = true,
        enabledShiftTypeCount: Int = 5,
        recognizedShiftCount: Int = 2
    ) = noShiftReason(
        hasSelectedCalendars = hasSelectedCalendars,
        calendarAuthorizationValid = calendarAuthorizationValid,
        kalenderNichtErreichbar = kalenderNichtErreichbar,
        unavailableCalendarCount = unavailableCalendarCount,
        errorMessage = errorMessage,
        eventCount = eventCount,
        shiftConfigLoaded = shiftConfigLoaded,
        enabledShiftTypeCount = enabledShiftTypeCount,
        recognizedShiftCount = recognizedShiftCount
    )

    @Test
    fun `nicht abrufbarer Kalender schlaegt Fehlermeldung und leere Terminliste`() {
        // Die Vollstaendigkeits-Sperren halten in dieser Lage jeden Alarm-Sync an. "Keine Termine"
        // waere dann eine FOLGE davon und wuerde den Nutzer im Kalender suchen lassen, statt bei
        // dem einen Kalender, der nicht antwortet.
        assertEquals(
            NoShiftReason.CALENDAR_PARTIALLY_UNAVAILABLE,
            reason(unavailableCalendarCount = 1, errorMessage = "irgendwas", eventCount = 0)
        )
    }

    @Test
    fun `verlorene Autorisierung schlaegt den nicht abrufbaren Kalender`() {
        // Der Totalausfall ALLER Kalender laeuft ueber calendarAuthorizationValid und hat seine
        // eigene, handlungsfaehige Meldung ("neu anmelden"). Er darf nicht als Teilerfolg
        // erscheinen - sonst bekaeme der Nutzer den Rat, einen Kalender abzuwaehlen, obwohl in
        // Wahrheit die Anmeldung haengt.
        assertEquals(
            NoShiftReason.AUTHORIZATION_LOST,
            reason(calendarAuthorizationValid = false, unavailableCalendarCount = 2)
        )
    }

    @Test
    fun `ohne nicht abrufbaren Kalender bleiben die uebrigen Gruende unveraendert`() {
        assertEquals(
            NoShiftReason.NO_EVENTS,
            reason(unavailableCalendarCount = 0, eventCount = 0)
        )
    }

    @Test
    fun `kein Kalender gewaehlt schlaegt jede andere Ursache`() {
        // Auch wenn gleichzeitig Autorisierung fehlt und ein Fehler anliegt: ohne Kalender ist
        // alles andere Folge, nicht Ursache.
        assertEquals(
            NoShiftReason.NO_CALENDAR_SELECTED,
            reason(
                hasSelectedCalendars = false,
                calendarAuthorizationValid = false,
                errorMessage = "irgendwas",
                eventCount = 0
            )
        )
    }

    @Test
    fun `verlorene Autorisierung vor Fehlermeldung und leerer Terminliste`() {
        assertEquals(
            NoShiftReason.AUTHORIZATION_LOST,
            reason(calendarAuthorizationValid = false, errorMessage = "401", eventCount = 0)
        )
    }

    @Test
    fun `Fehlermeldung wird genannt statt verschluckt`() {
        assertEquals(NoShiftReason.LOAD_ERROR, reason(errorMessage = "Netzwerkfehler"))

        val text = noShiftExplanation(NoShiftReason.LOAD_ERROR, errorMessage = "Netzwerkfehler")
        assertTrue("Der konkrete Fehler muss im Text auftauchen: $text", text.contains("Netzwerkfehler"))
    }

    @Test
    fun `leere Fehlermeldung gilt nicht als Fehler`() {
        // calendarState.error kann ein leerer String sein - das darf keine leere Fehlerzeile
        // erzeugen, sondern muss zur naechsten Ursache durchfallen.
        assertEquals(NoShiftReason.NO_EVENTS, reason(errorMessage = "   ", eventCount = 0))
    }

    @Test
    fun `keine Termine`() {
        assertEquals(NoShiftReason.NO_EVENTS, reason(eventCount = 0))
    }

    @Test
    fun `Schichtkonfiguration noch nicht geladen ist kein Keine-Schichttypen`() {
        assertEquals(
            NoShiftReason.SHIFT_CONFIG_NOT_LOADED,
            reason(shiftConfigLoaded = false, enabledShiftTypeCount = 0)
        )
    }

    @Test
    fun `keine aktiven Schichttypen`() {
        assertEquals(
            NoShiftReason.NO_SHIFT_TYPES,
            reason(enabledShiftTypeCount = 0, recognizedShiftCount = 0)
        )
    }

    @Test
    fun `Termine da aber kein Muster passt nennt die echten Termintitel`() {
        assertEquals(
            NoShiftReason.NO_PATTERN_MATCH,
            reason(eventCount = 3, recognizedShiftCount = 0)
        )

        val text = noShiftExplanation(
            NoShiftReason.NO_PATTERN_MATCH,
            sampleEventTitles = listOf("Dienst A1", "Dienst B2")
        )
        assertTrue("Titel 1 fehlt: $text", text.contains("Dienst A1"))
        assertTrue("Titel 2 fehlt: $text", text.contains("Dienst B2"))
    }

    @Test
    fun `ohne Termintitel bleibt der Satz lesbar`() {
        val text = noShiftExplanation(NoShiftReason.NO_PATTERN_MATCH, sampleEventTitles = emptyList())
        assertTrue("Kein leeres Klammerpaar erwartet: $text", !text.contains("()"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `erkannte Schichten aber keine kuenftige heisst Vergangenheit`() {
        // upcomingShift filtert auf startTime.isAfter(now) - recognizedShifts kann also nicht-leer
        // sein, obwohl oben nichts angezeigt wird. Das ist der letzte Fall, kein "unbekannt".
        assertEquals(NoShiftReason.ONLY_PAST_SHIFTS, reason(recognizedShiftCount = 4))
        assertTrue(
            noShiftExplanation(NoShiftReason.ONLY_PAST_SHIFTS).contains("Vergangenheit")
        )
    }

    @Test
    fun `Automatik-Hinweis ist ein Zusatz und ersetzt den Grund nicht`() {
        val withAutomatic = noShiftExplanation(NoShiftReason.NO_EVENTS, autoAlarmEnabled = true)
        val withoutAutomatic = noShiftExplanation(NoShiftReason.NO_EVENTS, autoAlarmEnabled = false)

        assertTrue(
            "Der eigentliche Grund muss auch bei ausgeschalteter Automatik erhalten bleiben",
            withoutAutomatic.startsWith(withAutomatic)
        )
        assertTrue(withoutAutomatic.contains("Automatische Alarme"))
        assertTrue(
            "Bei eingeschalteter Automatik darf kein Hinweis erscheinen",
            !withAutomatic.contains("Automatische Alarme")
        )
    }

    @Test
    fun `nicht erreichbarer Kalender schlaegt Fehlermeldung und leere Terminliste`() {
        // 30.09.2026, Fairphone im Flugmodus: nach dem Abgleich ist die Terminliste leer. Sobald
        // die Snackbar den Fehler geraeumt hat, hiess es sonst "im gewaehlten Kalender steht
        // nichts" - eine Behauptung ueber den Dienstplan, die niemand geprueft hat.
        assertEquals(
            NoShiftReason.KALENDER_NICHT_ERREICHBAR,
            reason(kalenderNichtErreichbar = true, errorMessage = null, eventCount = 0)
        )
        assertEquals(
            NoShiftReason.KALENDER_NICHT_ERREICHBAR,
            reason(kalenderNichtErreichbar = true, errorMessage = "Keine Internetverbindung", eventCount = 0)
        )
    }

    @Test
    fun `ohne Kalenderauswahl ist auch die Erreichbarkeit belanglos`() {
        assertEquals(
            NoShiftReason.NO_CALENDAR_SELECTED,
            reason(hasSelectedCalendars = false, kalenderNichtErreichbar = true)
        )
    }

    @Test
    fun `nicht erreichbar nennt Karte und Knopf wortgleich und behauptet keinen Zugriffsverlust`() {
        val text = noShiftExplanation(NoShiftReason.KALENDER_NICHT_ERREICHBAR)
        assertTrue("Kartenbeschriftung fehlt: $text", text.contains("\"Kalender-Events\""))
        assertTrue("Knopfbeschriftung fehlt: $text", text.contains("\"Mit Google Kalender abgleichen\""))
        assertTrue("Die Folge fuer die Wecker muss drinstehen: $text", text.contains("Wecker"))
        assertFalse("Keine Positionsangabe: $text", text.contains("darunter"))
        assertFalse(
            "Das ist genau die Fehldiagnose, die behoben wird: $text",
            text.contains("abgelaufen") || text.contains("Autorisierung") || text.contains("erneuern")
        )
    }

    @Test
    fun `jeder Grund hat einen eigenen nicht leeren Text`() {
        val texts = NoShiftReason.entries.map { noShiftExplanation(it, errorMessage = "X") }
        texts.forEach { assertTrue("Leerer Erklaertext", it.isNotBlank()) }
        assertEquals("Zwei Gruende duerfen nicht denselben Text zeigen", texts.size, texts.distinct().size)
    }
}
