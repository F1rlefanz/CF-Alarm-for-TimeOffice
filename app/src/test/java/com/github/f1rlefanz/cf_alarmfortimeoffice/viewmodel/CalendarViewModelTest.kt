package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deckt CalendarViewModel.resolveCalendarAuthorizationOutcome() ab - die pure Funktion,
 * die aus loadEventsForSelectedCalendars() ausgelagert wurde, um genau den dort
 * dokumentierten realen Bug testbar zu machen: calendarAuthorizationValid durfte nicht
 * mehr bedingungslos true sein, wenn JEDER ausgewaehlte Kalender fehlschlaegt - sonst
 * bleibt der "Kalender-Zugriff erneuern"-Recovery-Button versteckt, waehrend die App
 * eine leere (und damit von "du hast frei" nicht unterscheidbare) Schichtliste zeigt.
 */
class CalendarViewModelTest {

    @Test
    fun `alle Kalender fehlgeschlagen - Autorisierung gilt als ungueltig, nicht hart als gueltig`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 3,
            totalSelectedCalendars = 3
        )

        assertTrue(
            "everythingFailed muss erkannt werden, wenn ALLE Kalender scheitern",
            outcome.everythingFailed
        )
        assertFalse(
            "authStillValid darf NICHT hart auf true stehen, wenn jeder Kalender an einem " +
                "toten Token gescheitert ist - das versteckt den Recovery-Button",
            outcome.authStillValid
        )
    }

    @Test
    fun `mindestens ein Kalender erfolgreich - Autorisierung bleibt gueltig`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 2,
            totalSelectedCalendars = 3
        )

        assertFalse(
            "ein Teilerfolg ist kein everythingFailed - ein geloeschter/nicht mehr " +
                "freigegebener Einzelkalender darf die gesamte Anmeldung nicht in Frage stellen",
            outcome.everythingFailed
        )
        assertTrue(outcome.authStillValid)
    }

    @Test
    fun `kein Kalender fehlgeschlagen - Autorisierung bleibt gueltig`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 0,
            totalSelectedCalendars = 3
        )

        assertFalse(outcome.everythingFailed)
        assertTrue(outcome.authStillValid)
    }

    @Test
    fun `failedCalendars 0 bei 0 ausgewaehlten Kalendern ist kein everythingFailed`() {
        // Randfall: failedCalendars > 0 ist Teil der Bedingung, damit 0-von-0 (z.B. durch
        // einen zukuenftigen Aufrufer mit leerer Auswahl) nicht faelschlich als "alles
        // gescheitert" gilt, obwohl schlicht nichts versucht wurde.
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 0,
            totalSelectedCalendars = 0
        )

        assertFalse(outcome.everythingFailed)
        assertTrue(outcome.authStillValid)
    }

    @Test
    fun `genau ein Kalender ausgewaehlt und dieser scheitert - zaehlt als everythingFailed`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 1,
            totalSelectedCalendars = 1
        )

        assertTrue(outcome.everythingFailed)
        assertFalse(outcome.authStillValid)
    }

    @Test
    fun `everythingFailed und authStillValid sind immer exakte Gegenteile`() {
        val scenarios = listOf(
            0 to 0,
            0 to 5,
            1 to 1,
            2 to 5,
            5 to 5
        )

        scenarios.forEach { (failed, total) ->
            val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(failed, total)
            assertEquals(
                "authStillValid muss stets das Gegenteil von everythingFailed sein " +
                    "(failed=$failed, total=$total)",
                !outcome.everythingFailed,
                outcome.authStillValid
            )
        }
    }
}
