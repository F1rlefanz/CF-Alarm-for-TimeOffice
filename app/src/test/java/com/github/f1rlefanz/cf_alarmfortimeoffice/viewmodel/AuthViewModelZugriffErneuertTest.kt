package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.app.Activity
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.AnmeldeWiederherstellung
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

/**
 * Nach einer GELUNGENEN Kalender-Autorisierung meldet das AuthViewModel das nach aussen
 * ([AuthViewModel.kalenderZugriffErneuert]) - daran laedt die Oberflaeche die Termine neu.
 *
 * WARUM (30.09.2026, am Fairphone): "Kalender-Zugriff erneuern" war erfolgreich, die Wartung lief
 * sogar an - die rote Karte "Kalender-Autorisierung verloren" blieb trotzdem stehen. Sie haengt am
 * Ergebnis des letzten Terminabrufs im CalendarViewModel, und den stiess nach der Autorisierung
 * niemand an. Viermal getippt, viermal "tut nichts".
 *
 * Beide Wege werden geprueft: mit Activity (Knoepfe, Auto-Re-Auth) ueber den echten
 * [AuthUseCase], ohne Activity (Wiederaufnahme nach Prozesstod) ueber den Mock.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class AuthViewModelZugriffErneuertTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val token = TokenData(
        accessToken = "neu",
        expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
        scope = "calendar"
    )

    private fun authDataStore(): IAuthDataStoreRepository {
        val repo = mock<IAuthDataStoreRepository>()
        whenever(repo.authData).thenReturn(emptyFlow<AuthData>())
        repo.stub {
            on { getCurrentAuthData() } doReturn Result.success(
                AuthData(isLoggedIn = true, email = "nutzer@example.org")
            )
        }
        return repo
    }

    private fun buildViewModel(authDataStoreRepository: IAuthDataStoreRepository, authUseCase: IAuthUseCase): AuthViewModel {
        val calendarSelectionRepository = mock<ICalendarSelectionRepository>()
        whenever(calendarSelectionRepository.selectedCalendarIds)
            .thenReturn(MutableStateFlow(emptySet()))

        val tokenRepository = mock<TokenRepository>()
        whenever(tokenRepository.observe()).thenReturn(emptyFlow())

        val errorHandler = mock<ErrorHandler>()
        whenever(errorHandler.getErrorMessage(any())).thenReturn("Fehler")

        return AuthViewModel(
            authDataStoreRepository = authDataStoreRepository,
            credentialAuthManager = mock<CredentialAuthManager>(),
            errorHandler = errorHandler,
            authUseCase = authUseCase,
            calendarSelectionRepository = calendarSelectionRepository,
            backgroundServiceManager = mock<BackgroundServiceManager>(),
            tokenRepository = tokenRepository,
            alarmUseCase = mock<IAlarmUseCase>(),
            shiftSpanStore = mock<ShiftSpanStore>(),
            dimSchedule = mock<DimScheduleUseCase>(),
            dndSchedule = mock<DndScheduleUseCase>(),
            hueSmartScheduler = mock<HueSmartScheduler>(),
            calendarPreAlarmRefreshScheduler = mock<CalendarPreAlarmRefreshScheduler>(),
            appContext = mock<Context>(),
            anmeldeWiederherstellung = AnmeldeWiederherstellung.Aus
        )
    }

    /** Echter [AuthUseCase], dessen Token-Manager den Callback so feuert wie authorize(). */
    private fun viewModelMitActivityPfad(erfolg: Boolean): AuthViewModel {
        val repo = authDataStore()
        val manager = mock<OAuth2TokenManager>()
        manager.stub {
            onBlocking { authorize(any(), anyOrNull(), anyOrNull()) } doAnswer { aufruf ->
                @Suppress("UNCHECKED_CAST")
                (aufruf.arguments[2] as ((Boolean) -> Unit)?)?.invoke(erfolg)
                if (erfolg) Result.success(token)
                else Result.failure(TokenException.AuthorizationFailed("abgelehnt"))
            }
        }
        return buildViewModel(repo, AuthUseCase(repo, manager))
    }

    /**
     * Der Activity-Pfad springt in `Dispatchers.IO` (echter Thread, nicht der Test-Dispatcher) -
     * deshalb wird hier in ECHTER Zeit gewartet statt nur virtuell vorgespult.
     */
    private suspend fun signalInEchtzeit(viewModel: AuthViewModel, wartezeitMs: Long): Unit? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(wartezeitMs) { viewModel.kalenderZugriffErneuert.first() }
        }

    @Test
    fun `gelungene Autorisierung mit Activity meldet den erneuerten Zugriff`() = runTest {
        val viewModel = viewModelMitActivityPfad(erfolg = true)

        viewModel.requestCalendarAuthorization(mock<Activity>())
        advanceUntilIdle()

        assertNotNull(
            "Ohne dieses Signal laedt niemand die Termine neu, und die rote Karte bleibt stehen",
            signalInEchtzeit(viewModel, wartezeitMs = 5_000)
        )
    }

    @Test
    fun `abgelehnte Autorisierung mit Activity meldet nichts`() = runTest {
        val viewModel = viewModelMitActivityPfad(erfolg = false)

        viewModel.requestCalendarAuthorization(mock<Activity>())
        advanceUntilIdle()

        assertNull(signalInEchtzeit(viewModel, wartezeitMs = 1_500))
    }

    @Test
    fun `gelungene Autorisierung ohne Activity meldet den erneuerten Zugriff`() = runTest {
        val authUseCase = mock<IAuthUseCase>()
        authUseCase.stub { on { requestCalendarAuthorization(anyOrNull()) } doReturn Result.success(true) }
        val viewModel = buildViewModel(authDataStore(), authUseCase)

        viewModel.requestCalendarAuthorization(activity = null)
        advanceUntilIdle()

        assertNotNull(withTimeoutOrNull(1_000) { viewModel.kalenderZugriffErneuert.first() })
    }

    @Test
    fun `ohne Activity meldet weder ein schwebender Dialog noch ein Fehlschlag etwas`() = runTest {
        listOf(Result.success(false), Result.failure(Exception("x"))).forEach { ergebnis ->
            val authUseCase = mock<IAuthUseCase>()
            authUseCase.stub { on { requestCalendarAuthorization(anyOrNull()) } doReturn ergebnis }
            val viewModel = buildViewModel(authDataStore(), authUseCase)

            viewModel.requestCalendarAuthorization(activity = null)
            advanceUntilIdle()

            assertNull(
                "$ergebnis: kein erneuerter Zugriff, also auch kein Neuladen",
                withTimeoutOrNull(1_000) { viewModel.kalenderZugriffErneuert.first() }
            )
        }
    }
}
