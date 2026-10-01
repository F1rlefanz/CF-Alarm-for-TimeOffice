package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.app.Activity
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.KalenderAutorisierung
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.PendingAuthStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.WartungTokenFehler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Der Refresh ueber den AuthorizationClient muss in DIESELBE Einstufung muenden wie frueher
 * GoogleAuthUtil - die Aufrufer (`WartungTokenFehler`, `AuthUseCase`, `CalendarUseCase`) fragen
 * nur nach einer IOException in der Ursachenkette. Kern ist die gemessene Falle aus dem Spike:
 * offline nach geleertem Cache kommt ein ERFOLG mit `hasResolution = true`.
 */
class OAuth2AuthorizationClientRefreshTest {

    private class FakeTokenRepository(var token: TokenData?) : TokenRepository {
        override suspend fun get(): TokenData? = token
        override suspend fun save(token: TokenData): Result<Unit> {
            this.token = token
            return Result.success(Unit)
        }

        override suspend fun clear(): Result<Unit> {
            token = null
            return Result.success(Unit)
        }

        override fun observe(): Flow<TokenData?> = flowOf(token)
    }

    private object KeinMerker : PendingAuthStore {
        override fun remember(email: String) = Unit
        override fun consume(): String? = null
    }

    private class FakeAutorisierung(
        private val antwort: () -> KalenderAutorisierung.Antwort,
        private val leerenWirft: Exception? = null
    ) : KalenderAutorisierung {
        val protokoll = mutableListOf<String>()
        override fun autorisiere(email: String): KalenderAutorisierung.Antwort {
            protokoll += "autorisiere"
            return antwort()
        }

        override fun leereCache(accessToken: String) {
            protokoll += "leere:$accessToken"
            leerenWirft?.let { throw it }
        }
    }

    private fun abgelaufen() = TokenData(
        accessToken = "alt",
        expiresAt = System.currentTimeMillis() - 60_000L,
        scope = "https://www.googleapis.com/auth/calendar.readonly",
        googleAccountEmail = "nutzer@example.com",
        tokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES
    )

    private fun manager(repo: TokenRepository, auth: KalenderAutorisierung, netz: Boolean) =
        OAuth2TokenManager(mock<Context>(), repo, KeinMerker, auth) { netz }

    private fun token(wert: String) = KalenderAutorisierung.Antwort(wert, hatResolution = false, zustimmungsDialog = null)
    private val resolution = KalenderAutorisierung.Antwort(null, hatResolution = true, zustimmungsDialog = null)

    @Test
    fun `Refresh leert erst den Cache mit dem ALTEN Token und rotiert dann`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())
        val auth = FakeAutorisierung({ token("neu") })

        val ergebnis = manager(repo, auth, netz = true).getValidToken()

        assertEquals("neu", ergebnis.getOrThrow().accessToken)
        assertEquals(listOf("leere:alt", "autorisiere"), auth.protokoll)
        assertEquals(1, repo.token?.rotationCount)
    }

    @Test
    fun `Resolution OFFLINE ist ein Funkloch - voruebergehend, Token bleibt`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())

        val fehler = manager(repo, FakeAutorisierung({ resolution }), netz = false).getValidToken().exceptionOrNull()

        assertTrue(fehler is TokenException.RefreshFailed)
        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(fehler))
        assertNotNull("ein Funkloch darf das Token nicht verwerfen", repo.token)
    }

    @Test
    fun `Resolution MIT Netz verlangt Zustimmung und verwirft das tote Token`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())
        val auth = FakeAutorisierung({ resolution })

        val fehler = manager(repo, auth, netz = true).getValidToken().exceptionOrNull()

        assertTrue(fehler is TokenException.ConsentRequired)
        assertEquals(WartungTokenFehler.Art.ANMELDUNG, WartungTokenFehler.einstufe(fehler))
        assertNull(repo.token)
    }

    @Test
    fun `Zeitueberschreitung beim Abruf ist voruebergehend - auch mit Netz`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())
        val auth = FakeAutorisierung({ throw ExecutionException(TimeoutException()) })

        val fehler = manager(repo, auth, netz = true).getValidToken().exceptionOrNull()

        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(fehler))
        assertNotNull(repo.token)
    }

    @Test
    fun `unbekannter Fehler mit Netz bleibt endgueltig wie frueher die GoogleAuthException`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())
        val auth = FakeAutorisierung({ throw IllegalStateException("kaputt") })

        val fehler = manager(repo, auth, netz = true).getValidToken().exceptionOrNull()

        assertTrue(fehler is TokenException.RefreshFailed)
        assertEquals(WartungTokenFehler.Art.ANMELDUNG, WartungTokenFehler.einstufe(fehler))
    }

    @Test
    fun `scheitert das Leeren des Caches, wird NICHT abgerufen und voruebergehend abgebrochen`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())
        val auth = FakeAutorisierung({ token("neu") }, leerenWirft = IllegalStateException("GMS haengt"))

        val fehler = manager(repo, auth, netz = true).getValidToken().exceptionOrNull()

        assertEquals(listOf("leere:alt"), auth.protokoll)
        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(fehler))
        assertEquals("alt", repo.token?.accessToken)
    }

    @Test
    fun `authorize OFFLINE mit Resolution startet keinen Dialog und meldet eine Netzursache`() = runTest {
        val repo = FakeTokenRepository(null)
        val activity = mock<Activity>()
        var gemeldet: Boolean? = null

        val ergebnis = manager(repo, FakeAutorisierung({ resolution }), netz = false)
            .authorize("nutzer@example.com", activity) { gemeldet = it }

        verifyNoInteractions(activity)
        assertTrue(ergebnis.exceptionOrNull() is TokenException.AuthorizationFailed)
        assertTrue(WartungTokenFehler.istNetzursache(ergebnis.exceptionOrNull()))
        assertEquals(false, gemeldet)
    }

    @Test
    fun `authorize mit Token speichert eine frische Kette`() = runTest {
        val repo = FakeTokenRepository(null)
        var gemeldet: Boolean? = null

        val ergebnis = manager(repo, FakeAutorisierung({ token("frisch") }), netz = true)
            .authorize("nutzer@example.com") { gemeldet = it }

        assertEquals("frisch", ergebnis.getOrThrow().accessToken)
        assertEquals(0, repo.token?.rotationCount)
        assertNull(repo.token?.previousRotationId)
        assertEquals(true, gemeldet)
    }

    @Test
    fun `invalidate verwirft lokal auch wenn das Leeren des Caches scheitert`() = runTest {
        val repo = FakeTokenRepository(abgelaufen())
        val auth = FakeAutorisierung({ token("x") }, leerenWirft = IllegalStateException("GMS haengt"))

        assertTrue(manager(repo, auth, netz = true).invalidate().isSuccess)
        assertNull(repo.token)
    }
}
