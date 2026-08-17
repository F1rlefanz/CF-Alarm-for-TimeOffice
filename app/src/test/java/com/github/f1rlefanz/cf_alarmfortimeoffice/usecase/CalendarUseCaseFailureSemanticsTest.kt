package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.CalendarItem
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.EventsPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Fixiert die Fehler-Semantik von [CalendarUseCase.getCalendarEventsWithCache].
 *
 * Kernpunkt (CLAUDE.md, "Kalender und Schichterkennung"): fuer eine Wecker-App ist "leer" die gefaehrlichste Luege -
 * nicht von "du hast frei" zu unterscheiden. `AlarmUseCase.syncAlarms()` deutet eine leere
 * Eventliste als "keine Schichten" und LOESCHT daraufhin alle Alarme. Scheitern also ALLE
 * angefragten Kalender (beim Nutzer ist typischerweise genau einer ausgewaehlt), darf das Ergebnis
 * kein `Result.success(emptyList())` sein.
 *
 * Genauso wichtig ist die Gegenrichtung: ein TEILerfolg bleibt Erfolg (sonst wuerde ein einzelner
 * kaputter Zweitkalender den ganzen Sync blockieren), und ein wirklich leerer Kalender bleibt ein
 * legitimer Erfolg mit leerer Liste.
 *
 * Getestet wird der Legacy-Auth-Pfad (`oauth2TokenManager = null`) - er fuehrt durch exakt denselben
 * Sammel-/Fehlerpfad und braucht keine Google-Play-Services.
 */
class CalendarUseCaseFailureSemanticsTest {

    private class FakeAuthDataStoreRepository : IAuthDataStoreRepository {
        private val data = AuthData(
            isLoggedIn = true,
            accessToken = "test-access-token",
            tokenExpiryTime = System.currentTimeMillis() + 60 * 60 * 1000L
        )

        override val authData: Flow<AuthData> = flowOf(data)
        override suspend fun updateAuthData(authData: AuthData): Result<Unit> = Result.success(Unit)
        override suspend fun clearAuthData(): Result<Unit> = Result.success(Unit)
        override suspend fun isAuthenticated(): Result<Boolean> = Result.success(true)
        override suspend fun getCurrentAuthData(): Result<AuthData> = Result.success(data)
        override suspend fun migrateTokenExpiryIfNeeded(): Result<Unit> = Result.success(Unit)
    }

    /** Liefert pro Kalender-ID ein vorbereitetes Ergebnis. */
    private class FakeCalendarRepository(
        private val perCalendar: Map<String, Result<List<CalendarEvent>>>
    ) : ICalendarRepository {
        override fun setContext(context: Context) = Unit

        override suspend fun getCalendarsWithToken(accessToken: String): Result<List<CalendarItem>> =
            Result.success(emptyList())

        override suspend fun getCalendarEventsWithToken(
            accessToken: String,
            calendarId: String
        ): Result<List<CalendarEvent>> = getCalendarEventsWithCache(accessToken, calendarId, false)

        override suspend fun getCalendarEventsWithCache(
            accessToken: String,
            calendarId: String,
            forceRefresh: Boolean
        ): Result<List<CalendarEvent>> =
            perCalendar[calendarId] ?: Result.failure(AppError.UnknownError("unbekannter Kalender"))

        override suspend fun getCalendarEventsWithPagination(
            accessToken: String,
            calendarId: String,
            maxResults: Int,
            pageToken: String?
        ): Result<EventsPage> = Result.success(EventsPage(emptyList(), null, false))

        override suspend fun invalidateCalendarCache(calendarId: String) = Unit
        override suspend fun clearEventCache() = Unit
        override suspend fun getCacheStats(): String = ""
        override fun cleanup() = Unit
    }

    private fun useCase(perCalendar: Map<String, Result<List<CalendarEvent>>>) = CalendarUseCase(
        calendarRepository = FakeCalendarRepository(perCalendar),
        authDataStoreRepository = FakeAuthDataStoreRepository(),
        oauth2TokenManager = null
    )

    private fun event(id: String) = CalendarEvent(
        id = id,
        title = "Frühschicht",
        startTime = LocalDateTime.of(2026, 8, 6, 6, 0),
        endTime = LocalDateTime.of(2026, 8, 6, 14, 0),
        calendarId = "cal-a"
    )

    @Test
    fun `alle Kalender gescheitert ergibt Fehler, nicht leeren Erfolg`() = runTest {
        val useCase = useCase(
            mapOf("cal-a" to Result.failure(AppError.NetworkError("kein Netz")))
        )

        val result = useCase.getCalendarEventsWithCache(setOf("cal-a"), forceRefresh = true)

        assertTrue(
            "Ein Totalausfall darf nicht als leere Erfolgsliste durchgehen (syncAlarms wuerde alle Alarme loeschen)",
            result.isFailure
        )
    }

    @Test
    fun `mehrere Kalender, alle gescheitert ergibt Fehler`() = runTest {
        val useCase = useCase(
            mapOf(
                "cal-a" to Result.failure(AppError.NetworkError("kein Netz")),
                "cal-b" to Result.failure(AppError.AuthenticationError("401"))
            )
        )

        val result = useCase.getCalendarEventsWithCache(setOf("cal-a", "cal-b"), forceRefresh = false)

        assertTrue(result.isFailure)
    }

    @Test
    fun `Teilerfolg bleibt Erfolg mit den geladenen Events`() = runTest {
        val useCase = useCase(
            mapOf(
                "cal-a" to Result.success(listOf(event("e1"))),
                "cal-b" to Result.failure(AppError.NetworkError("kein Netz"))
            )
        )

        val result = useCase.getCalendarEventsWithCache(setOf("cal-a", "cal-b"), forceRefresh = false)

        assertTrue("Ein einzelner kaputter Kalender darf den Sync nicht blockieren", result.isSuccess)
        assertEquals(listOf("e1"), result.getOrThrow().map { it.id })
    }

    @Test
    fun `wirklich leerer Kalender bleibt erfolgreich leer`() = runTest {
        val useCase = useCase(mapOf("cal-a" to Result.success(emptyList())))

        val result = useCase.getCalendarEventsWithCache(setOf("cal-a"), forceRefresh = false)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }
}
