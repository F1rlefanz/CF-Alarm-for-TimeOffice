package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarItem
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Fixiert die Token-Aufloesung von [CalendarUseCase] im OAuth2-Zweig (der Legacy-Zweig steht in
 * [CalendarUseCaseFailureSemanticsTest]): welcher Text je [TokenException]-Fall geworfen wird,
 * dass der Fehler generisch bleibt (KEIN [AppError.AuthenticationError] - daran haengt
 * `invalidateTokenIfRejectedByGoogle`, siehe Skill cfalarm-persistenz-und-auth) und dass das
 * geholte Token beim Repository ankommt. Fuer alle drei Aufrufer.
 */
class CalendarUseCaseTokenAufloesungTest {

    private class FakeAuthDataStoreRepository : IAuthDataStoreRepository {
        private val data = AuthData(isLoggedIn = false)
        override val authData: Flow<AuthData> = flowOf(data)
        override suspend fun updateAuthData(authData: AuthData): Result<Unit> = Result.success(Unit)
        override suspend fun clearAuthData(): Result<Unit> = Result.success(Unit)
        override suspend fun isAuthenticated(): Result<Boolean> = Result.success(false)
        override suspend fun getCurrentAuthData(): Result<AuthData> = Result.success(data)
        override suspend fun migrateTokenExpiryIfNeeded(): Result<Unit> = Result.success(Unit)
    }

    private class RecordingCalendarRepository : ICalendarRepository {
        val seenTokens = mutableListOf<String>()

        override suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>> {
            seenTokens += accessToken
            return Result.success(emptyList())
        }

        override suspend fun getCalendarEventsWithCache(
            accessToken: String,
            calendarId: String,
            forceRefresh: Boolean
        ): Result<List<CalendarEvent>> {
            seenTokens += accessToken
            return Result.success(emptyList())
        }

        override suspend fun invalidateCalendarCache(calendarId: String) = Unit
    }

    private enum class Aufrufer { CALENDARS, WITH_STATUS, LAZY }

    private suspend fun rufe(aufrufer: Aufrufer, useCase: CalendarUseCase): Result<*> = when (aufrufer) {
        Aufrufer.CALENDARS -> useCase.getAvailableCalendars()
        Aufrufer.WITH_STATUS -> useCase.getCalendarEventsWithStatus(setOf("cal-a"), forceRefresh = false)
        Aufrufer.LAZY -> useCase.getCalendarEventsLazy(setOf("cal-a"), maxEvents = 10, offset = 0)
    }

    private suspend fun useCaseMit(
        tokenResult: Result<TokenData>,
        repo: ICalendarRepository = RecordingCalendarRepository()
    ): CalendarUseCase {
        val manager = mock<OAuth2TokenManager>()
        whenever(manager.getValidToken()).thenReturn(tokenResult)
        return CalendarUseCase(repo, FakeAuthDataStoreRepository(), manager)
    }

    private suspend fun pruefeFehlertext(aufrufer: Aufrufer, fehler: Throwable, erwartet: String) {
        val result = rufe(aufrufer, useCaseMit(Result.failure(fehler)))
        assertTrue("$aufrufer/${fehler::class.simpleName}: muss scheitern", result.isFailure)
        val geworfen = result.exceptionOrNull()
        assertEquals("$aufrufer/${fehler::class.simpleName}", erwartet, geworfen?.message)
        assertFalse(
            "$aufrufer: Token-Fehler darf nicht als AuthenticationError klassifiziert werden",
            geworfen is AppError.AuthenticationError
        )
    }

    private val gemeinsameTexte = listOf(
        TokenException.AuthorizationExpired("x") to "Your Calendar authorization has expired. Please re-authorize.",
        TokenException.RefreshFailed("x") to "Failed to refresh Calendar access. Please re-authorize.",
        TokenException.ConsentRequired("x") to
            "Der Zugriff auf deinen Kalender wurde entzogen. Bitte melde dich erneut an.",
        IllegalStateException("kaputt") to "Calendar access error: kaputt"
    )

    @Test
    fun `getAvailableCalendars bildet jeden Token-Fehler auf seinen Text ab`() = runTest {
        pruefeFehlertext(
            Aufrufer.CALENDARS,
            TokenException.NoTokenAvailable("x"),
            "Calendar access requires authorization. Please sign in."
        )
        gemeinsameTexte.forEach { (fehler, text) -> pruefeFehlertext(Aufrufer.CALENDARS, fehler, text) }
    }

    @Test
    fun `getCalendarEventsWithStatus bildet jeden Token-Fehler auf seinen Text ab`() = runTest {
        pruefeFehlertext(
            Aufrufer.WITH_STATUS,
            TokenException.NoTokenAvailable("x"),
            "Calendar events require authorization. Please sign in."
        )
        gemeinsameTexte.forEach { (fehler, text) -> pruefeFehlertext(Aufrufer.WITH_STATUS, fehler, text) }
    }

    @Test
    fun `getCalendarEventsLazy bildet jeden Token-Fehler auf seinen Text ab`() = runTest {
        pruefeFehlertext(
            Aufrufer.LAZY,
            TokenException.NoTokenAvailable("x"),
            "Calendar events require authorization. Please sign in."
        )
        gemeinsameTexte.forEach { (fehler, text) -> pruefeFehlertext(Aufrufer.LAZY, fehler, text) }
    }

    @Test
    fun `das geholte OAuth2-Token geht an das Repository`() = runTest {
        val token = TokenData(
            accessToken = "oauth-token",
            expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
            scope = "calendar"
        )
        Aufrufer.entries.forEach { aufrufer ->
            val repo = RecordingCalendarRepository()
            val result = rufe(aufrufer, useCaseMit(Result.success(token), repo))
            assertTrue("$aufrufer: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals("$aufrufer", listOf("oauth-token"), repo.seenTokens)
        }
    }

    @Test
    fun `Token-Pruefung kommt auch bei leerer Kalenderliste zuerst`() = runTest {
        val useCase = useCaseMit(Result.failure(TokenException.NoTokenAvailable("x")))
        assertTrue(useCase.getCalendarEventsWithStatus(emptySet(), forceRefresh = false).isFailure)
        assertTrue(useCase.getCalendarEventsLazy(emptySet(), maxEvents = 10, offset = 0).isFailure)
    }
}
