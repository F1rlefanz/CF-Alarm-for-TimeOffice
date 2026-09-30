package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Deckt CalendarViewModel.resolveCalendarAuthorizationOutcome() ab - die pure Funktion,
 * die aus loadEventsForSelectedCalendars() ausgelagert wurde, um genau den dort
 * dokumentierten realen Bug testbar zu machen: calendarAuthorizationValid durfte nicht
 * mehr bedingungslos true sein, wenn JEDER ausgewaehlte Kalender fehlschlaegt - sonst
 * bleibt der "Kalender-Zugriff erneuern"-Recovery-Button versteckt, waehrend die App
 * eine leere (und damit von "du hast frei" nicht unterscheidbare) Schichtliste zeigt.
 *
 * Und die Gegenrichtung (30.09.2026, am Fairphone): scheitern alle Kalender nur an der
 * VERBINDUNG, ist das kein Zugriffsverlust. Die App meldete im Flugmodus "Kalender-Autorisierung
 * verloren", obwohl das Token gueltig war - der angebotene Knopf konnte daran nichts aendern.
 */
class CalendarViewModelTest {

    private val ANMELDUNG = setOf(FehlschlagArt.ANMELDUNG)
    private val NETZ = setOf(FehlschlagArt.NETZ)
    private val FEHLT = setOf(FehlschlagArt.KALENDER_FEHLT)

    @Test
    fun `alle Kalender fehlgeschlagen - Autorisierung gilt als ungueltig, nicht hart als gueltig`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 3,
            totalSelectedCalendars = 3,
            fehlschlagArten = ANMELDUNG
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
        assertFalse(outcome.nichtErreichbar)
    }

    @Test
    fun `mindestens ein Kalender erfolgreich - Autorisierung bleibt gueltig`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 2,
            totalSelectedCalendars = 3,
            fehlschlagArten = ANMELDUNG
        )

        assertFalse(
            "ein Teilerfolg ist kein everythingFailed - ein geloeschter/nicht mehr " +
                "freigegebener Einzelkalender darf die gesamte Anmeldung nicht in Frage stellen",
            outcome.everythingFailed
        )
        assertTrue(outcome.authStillValid)
        assertFalse(outcome.nichtErreichbar)
    }

    @Test
    fun `kein Kalender fehlgeschlagen - Autorisierung bleibt gueltig`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 0,
            totalSelectedCalendars = 3,
            fehlschlagArten = ANMELDUNG
        )

        assertFalse(outcome.everythingFailed)
        assertTrue(outcome.authStillValid)
        assertFalse(outcome.nichtErreichbar)
    }

    @Test
    fun `failedCalendars 0 bei 0 ausgewaehlten Kalendern ist kein everythingFailed`() {
        // Randfall: failedCalendars > 0 ist Teil der Bedingung, damit 0-von-0 (z.B. durch
        // einen zukuenftigen Aufrufer mit leerer Auswahl) nicht faelschlich als "alles
        // gescheitert" gilt, obwohl schlicht nichts versucht wurde.
        listOf(ANMELDUNG, NETZ, FEHLT).forEach { arten ->
            val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
                failedCalendars = 0,
                totalSelectedCalendars = 0,
                fehlschlagArten = arten
            )

            assertFalse(outcome.everythingFailed)
            assertTrue(outcome.authStillValid)
            assertFalse("0 von 0 ist keine Stoerung ($arten)", outcome.nichtErreichbar)
            assertFalse("0 von 0 zeigt nichts als nicht abrufbar ($arten)", outcome.nichtAbrufbareZeigen)
        }
    }

    @Test
    fun `genau ein Kalender ausgewaehlt und dieser scheitert - zaehlt als everythingFailed`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 1,
            totalSelectedCalendars = 1,
            fehlschlagArten = ANMELDUNG
        )

        assertTrue(outcome.everythingFailed)
        assertFalse(outcome.authStillValid)
    }

    @Test
    fun `bei Anmelde-Fehlschlaegen sind everythingFailed und authStillValid exakte Gegenteile`() {
        val scenarios = listOf(
            0 to 0,
            0 to 5,
            1 to 1,
            2 to 5,
            5 to 5
        )

        scenarios.forEach { (failed, total) ->
            val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
                failed,
                total,
                fehlschlagArten = ANMELDUNG
            )
            assertEquals(
                "authStillValid muss stets das Gegenteil von everythingFailed sein " +
                    "(failed=$failed, total=$total)",
                !outcome.everythingFailed,
                outcome.authStillValid
            )
        }
    }

    // ------------------------------------------------ Netz ist kein Zugriffsverlust (30.09.2026)

    @Test
    fun `alle Kalender scheitern nur an der Verbindung - kein Zugriffsverlust, sondern nicht erreichbar`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 1,
            totalSelectedCalendars = 1,
            fehlschlagArten = NETZ
        )

        assertTrue("Der Fehlschlag bleibt ein Fehlschlag - er wird gemeldet", outcome.everythingFailed)
        assertTrue(
            "Offline ist KEIN Beleg fuer einen verlorenen Zugriff - sonst bietet die App " +
                "\"Kalender-Zugriff erneuern\" an, und der Knopf kann nichts reparieren",
            outcome.authStillValid
        )
        assertTrue("Der Zustand muss als eigener sichtbar werden", outcome.nichtErreichbar)
    }

    @Test
    fun `ein Teilerfolg bleibt Teilerfolg, auch wenn der Ausfall netzbedingt ist`() {
        // Der Teilerfolg hat seine eigene Anzeige (unavailableCalendarIds). "Nicht erreichbar"
        // heisst: GAR NICHTS kam an - sonst standen zwei Warnungen fuer dieselbe Lage da.
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 1,
            totalSelectedCalendars = 2,
            fehlschlagArten = NETZ
        )

        assertFalse(outcome.everythingFailed)
        assertTrue(outcome.authStillValid)
        assertFalse(outcome.nichtErreichbar)
    }

    // ------------------------------------------------ Kalender fehlt ist kein Zugriffsverlust

    @Test
    fun `alle Kalender gibt es nicht mehr - kein Zugriffsverlust, sondern nicht abrufbar`() {
        // Geloescht oder nicht mehr freigegeben (404/403): "Kalender-Zugriff erneuern" haette
        // daran nichts geaendert. Die Anzeige fuer nicht abrufbare Kalender nennt den Kalender
        // beim Namen und bietet das Entfernen an (mit Rueckfrage, wenn danach keiner mehr bliebe).
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 1,
            totalSelectedCalendars = 1,
            fehlschlagArten = FEHLT
        )

        assertTrue(outcome.everythingFailed)
        assertTrue("Ein fehlender Kalender belegt keinen verlorenen Zugriff", outcome.authStillValid)
        assertFalse(outcome.nichtErreichbar)
        assertTrue("Die gescheiterten Kalender muessen als nicht abrufbar erscheinen", outcome.nichtAbrufbareZeigen)
    }

    @Test
    fun `fehlt gemischt mit Funkloch - nicht abrufbar, nicht nicht erreichbar`() {
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 2,
            totalSelectedCalendars = 2,
            fehlschlagArten = setOf(FehlschlagArt.NETZ, FehlschlagArt.KALENDER_FEHLT)
        )

        assertTrue(outcome.authStillValid)
        assertFalse(outcome.nichtErreichbar)
        assertTrue(outcome.nichtAbrufbareZeigen)
    }

    @Test
    fun `ein einziger Anmelde-Fehlschlag macht den Totalausfall wieder zum Zugriffsverlust`() {
        // Die Anmeldung zuerst: solange sie haengt, kann der Nutzer auch nicht pruefen, welcher
        // Kalender wirklich fehlt.
        val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
            failedCalendars = 2,
            totalSelectedCalendars = 2,
            fehlschlagArten = setOf(FehlschlagArt.KALENDER_FEHLT, FehlschlagArt.ANMELDUNG)
        )

        assertFalse(outcome.authStillValid)
        assertFalse(outcome.nichtAbrufbareZeigen)
    }

    @Test
    fun `ein Teilerfolg zeigt die gescheiterten Kalender als nicht abrufbar - wie bisher`() {
        listOf(ANMELDUNG, NETZ, FEHLT).forEach { arten ->
            val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
                failedCalendars = 1,
                totalSelectedCalendars = 2,
                fehlschlagArten = arten
            )
            assertTrue("$arten", outcome.nichtAbrufbareZeigen)
            assertTrue("$arten", outcome.authStillValid)
        }
    }

    @Test
    fun `Totalausfall aus Anmeldung oder Funkloch zeigt NICHTS als nicht abrufbar`() {
        // Beide haben ihre eigene Anzeige - zwei Warnungen fuer dieselbe Lage waeren schlechter.
        listOf(ANMELDUNG, NETZ).forEach { arten ->
            val outcome = CalendarViewModel.resolveCalendarAuthorizationOutcome(
                failedCalendars = 2,
                totalSelectedCalendars = 2,
                fehlschlagArten = arten
            )
            assertFalse("$arten", outcome.nichtAbrufbareZeigen)
        }
    }

    @Test
    fun `die Art eines Fehlschlags`() {
        assertEquals(FehlschlagArt.NETZ, CalendarViewModel.fehlschlagArt(AppError.NetworkError("No internet connection")))
        assertEquals(
            FehlschlagArt.KALENDER_FEHLT,
            CalendarViewModel.fehlschlagArt(AppError.PermissionError(message = "Kalender nicht gefunden oder nicht mehr freigegeben"))
        )
        assertEquals(FehlschlagArt.ANMELDUNG, CalendarViewModel.fehlschlagArt(AppError.AuthenticationError("401")))
        assertEquals(
            FehlschlagArt.ANMELDUNG,
            CalendarViewModel.fehlschlagArt(Exception("Calendar events require authorization. Please sign in."))
        )
        assertEquals(FehlschlagArt.ANMELDUNG, CalendarViewModel.fehlschlagArt(null))
    }

    // ------------------------------------------------ Welcher Fehlschlag gilt als netzbedingt

    @Test
    fun `NetworkError aus dem Kalenderabruf ist netzbedingt`() {
        // So kam es am Fairphone an: CalendarRepository bildet UnknownHostException auf
        // NetworkError("No internet connection") ab - OHNE die Ursache mitzugeben.
        assertTrue(
            CalendarViewModel.istNetzbedingterFehlschlag(AppError.NetworkError("No internet connection"))
        )
    }

    @Test
    fun `IOException tief in der Ursachenkette ist netzbedingt`() {
        // Offline mit abgelaufenem Token: GoogleAuthUtil wirft IOException, refresh() wickelt
        // zweimal in RefreshFailed, SafeExecutor noch einmal in einen AppError.
        val kette = AppError.UnknownError(
            "Calendar error",
            TokenException.RefreshFailed(
                "Google refresh failed",
                TokenException.RefreshFailed("inner", IOException("NetworkError"))
            )
        )
        assertTrue(CalendarViewModel.istNetzbedingterFehlschlag(kette))
    }

    @Test
    fun `ein Anmeldefehler bleibt einer, auch wenn eine IOException in seiner Kette steckt`() {
        // GoogleJsonResponseException erbt ueber HttpResponseException von IOException. Reicht
        // das CalendarRepository einmal die Ursache durch ("Ursache nie verwerfen"), faende die
        // Kettensuche darin ein "Funkloch" - und ein 401 verloere den Weg zur Neuanmeldung.
        val antwort = IOException("401 Unauthorized")
        listOf(
            AppError.AuthenticationError("Google Calendar authentication failed", antwort),
            AppError.PermissionError(message = "Kein Zugriff auf diesen Google-Kalender", cause = antwort),
            AppError.CalendarAccessError("Calendar access failed", antwort)
        ).forEach { fehler ->
            assertFalse("$fehler", CalendarViewModel.istNetzbedingterFehlschlag(fehler))
        }
    }

    @Test
    fun `Anmelde- und Berechtigungsfehler sind NICHT netzbedingt`() {
        listOf(
            AppError.AuthenticationError("Google Calendar authentication failed"),
            AppError.PermissionError(message = "Kalender nicht gefunden oder nicht mehr freigegeben"),
            Exception("Calendar events require authorization. Please sign in."),
            AppError.UnknownError("Calendar error", TokenException.RefreshFailed("GoogleAuthException")),
            null
        ).forEach { fehler ->
            assertFalse(
                "$fehler darf nicht als Verbindungsproblem durchgehen - sonst verschwindet der " +
                    "Weg zur Neuanmeldung, wenn sie wirklich noetig ist",
                CalendarViewModel.istNetzbedingterFehlschlag(fehler)
            )
        }
    }
}
