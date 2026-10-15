package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #148: Beim Kaltstart zeigte die Uebersicht ein bis zwei Sekunden lang "Kalender-Autorisierung
 * verloren" samt "Kalender-Zugriff erneuern", obwohl der Token gueltig war. Der Startwert des
 * Merkers war `false` - und `false` hiess fuer die Anzeige "verloren", auch solange noch gar
 * nicht geprueft war. Diese Tests halten die drei Zustaende auseinander.
 */
class CalendarUiStateZugriffTest {

    private val auswahl = setOf("cal-a")

    @Test
    fun `vor dem ersten Abgleich ist der Zugriff NICHT verloren, sondern ungeprueft`() {
        val state = CalendarUiState(selectedCalendarIds = auswahl)

        assertFalse("Ungeprueft ist kein Beleg fuer einen verlorenen Zugriff", state.kalenderZugriffVerloren)
        assertTrue(state.kalenderZugriffUngeprueft)
    }

    @Test
    fun `ein gescheiterter Abgleich meldet den Verlust weiterhin`() {
        val state = CalendarUiState(selectedCalendarIds = auswahl, calendarAuthorizationValid = false)

        assertTrue(state.kalenderZugriffVerloren)
        assertFalse(state.kalenderZugriffUngeprueft)
    }

    @Test
    fun `ein gelungener Abgleich ist weder verloren noch ungeprueft`() {
        val state = CalendarUiState(selectedCalendarIds = auswahl, calendarAuthorizationValid = true)

        assertFalse(state.kalenderZugriffVerloren)
        assertFalse(state.kalenderZugriffUngeprueft)
    }

    @Test
    fun `ohne Auswahl gibt es keinen Zugriff, der verloren sein koennte`() {
        val state = CalendarUiState(selectedCalendarIds = emptySet(), calendarAuthorizationValid = false)

        assertFalse(state.kalenderZugriffVerloren)
        assertFalse(state.kalenderZugriffUngeprueft)
    }
}
