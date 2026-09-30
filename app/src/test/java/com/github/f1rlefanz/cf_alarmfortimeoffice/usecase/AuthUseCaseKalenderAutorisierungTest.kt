package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * [AuthUseCase.hasCalendarAuthorization] entscheidet beim App-Start, ob das Gate
 * "Kalender-Zugriff erforderlich" VOR die ganze Oberflaeche tritt.
 *
 * DER FEHLER (gefunden bei der Pruefung des Fixes vom 30.09.2026): das lokale Token gilt 45
 * Minuten; danach refresht getValidToken() - und offline scheitert das mit einer IOException.
 * Das wurde als "keine Autorisierung" gelesen: wer die App ohne Netz oeffnete, landete vor dem
 * Gate, und "Kalender-Zugriff erlauben" scheiterte ebenfalls. Wecker-Tab und Ueberspringen waren
 * bis zur naechsten Verbindung unerreichbar - wegen eines Funklochs.
 */
class AuthUseCaseKalenderAutorisierungTest {

    private suspend fun autorisiert(tokenErgebnis: Result<TokenData>): Result<Boolean> {
        val manager = mock<OAuth2TokenManager>()
        whenever(manager.getValidToken()).thenReturn(tokenErgebnis)
        return AuthUseCase(mock<IAuthDataStoreRepository>(), manager).hasCalendarAuthorization()
    }

    @Test
    fun `gueltiges Token heisst autorisiert`() = runTest {
        val token = TokenData(
            accessToken = "a",
            expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
            scope = "calendar"
        )
        assertEquals(Result.success(true), autorisiert(Result.success(token)))
    }

    @Test
    fun `offline gescheiterter Refresh ist KEIN Beleg fuer fehlende Autorisierung`() = runTest {
        val funkloch = TokenException.RefreshFailed(
            "Token refresh failed",
            TokenException.RefreshFailed("Google refresh failed: NetworkError", IOException("NetworkError"))
        )
        assertEquals(
            "Offline darf das Gate die Oberflaeche nicht sperren",
            Result.success(true),
            autorisiert(Result.failure(funkloch))
        )
    }

    @Test
    fun `fehlendes, entzogenes oder endgueltig abgelehntes Token heisst NICHT autorisiert`() = runTest {
        listOf(
            TokenException.NoTokenAvailable("x"),
            TokenException.ConsentRequired("x"),
            TokenException.AuthorizationExpired("x"),
            TokenException.RefreshFailed("GoogleAuthException: BadAuthentication")
        ).forEach { fehler ->
            assertEquals(
                "${fehler::class.simpleName} muss zum Gate fuehren",
                Result.success(false),
                autorisiert(Result.failure(fehler))
            )
        }
    }
}
