package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.PendingAuthStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Ein gespeichertes Token mit `OAUTH2_STANDARD` (Enum-Wert bleibt Teil des Token-JSON) darf beim
 * Refresh nicht als `Error` aus `getValidToken()` entkommen - die Aufrufer (u. a. die
 * 6h-Wartung) fangen nur `Exception` bzw. werten ein `Result` aus.
 */
class OAuth2StandardRefreshTest {

    private class FakeTokenRepository(private var token: TokenData?) : TokenRepository {
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

    @Test
    fun `abgelaufenes OAUTH2_STANDARD-Token liefert einen Fehler statt zu werfen`() = runTest {
        val abgelaufen = TokenData(
            accessToken = "alt",
            refreshToken = "refresh",
            expiresAt = System.currentTimeMillis() - 60_000L,
            scope = "https://www.googleapis.com/auth/calendar.readonly",
            tokenProvider = TokenProvider.OAUTH2_STANDARD
        )
        val manager = OAuth2TokenManager(mock<Context>(), FakeTokenRepository(abgelaufen), KeinMerker)

        val ergebnis = manager.getValidToken()

        assertTrue(ergebnis.exceptionOrNull() is TokenException.RefreshFailed)
    }
}
