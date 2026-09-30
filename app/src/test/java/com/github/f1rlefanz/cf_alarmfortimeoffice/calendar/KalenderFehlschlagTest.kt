package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Die Einstufung eines gescheiterten Kalenderabrufs - Vordergrund (CalendarViewModel) und 6h-Wartung
 * teilen sie. Die Faelle stammen aus CalendarViewModelTest (dort lag die Einstufung bis v1.43.6);
 * die Begruendungen je Fall stehen an den Tests.
 */
class KalenderFehlschlagTest {

    @Test
    fun `die Art eines Fehlschlags`() {
        assertEquals(FehlschlagArt.NETZ, fehlschlagArt(AppError.NetworkError("No internet connection")))
        assertEquals(
            FehlschlagArt.KALENDER_FEHLT,
            fehlschlagArt(AppError.PermissionError(message = "Kalender nicht gefunden oder nicht mehr freigegeben"))
        )
        assertEquals(FehlschlagArt.ANMELDUNG, fehlschlagArt(AppError.AuthenticationError("401")))
        assertEquals(
            FehlschlagArt.ANMELDUNG,
            fehlschlagArt(Exception("Calendar events require authorization. Please sign in."))
        )
        assertEquals(FehlschlagArt.ANMELDUNG, fehlschlagArt(null))
    }

    // ------------------------------------------------ Welcher Fehlschlag gilt als netzbedingt

    @Test
    fun `NetworkError aus dem Kalenderabruf ist netzbedingt`() {
        // So kam es am Fairphone an: CalendarRepository bildet UnknownHostException auf
        // NetworkError("No internet connection") ab - OHNE die Ursache mitzugeben.
        assertTrue(
            istNetzbedingterFehlschlag(AppError.NetworkError("No internet connection"))
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
        assertTrue(istNetzbedingterFehlschlag(kette))
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
            assertFalse("$fehler", istNetzbedingterFehlschlag(fehler))
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
                istNetzbedingterFehlschlag(fehler)
            )
        }
    }
}
