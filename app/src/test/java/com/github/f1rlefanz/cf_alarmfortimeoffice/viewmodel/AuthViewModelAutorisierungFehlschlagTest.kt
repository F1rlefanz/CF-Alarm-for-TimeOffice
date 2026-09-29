package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.CredentialAuthManager
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

/**
 * Eine gescheiterte Kalender-Autorisierung muss das Onboarding-Gate entscheiden lassen:
 * `tokenChecked = true` bei `hasValidToken = false`, sonst haengt das Gate im Ladebildschirm.
 *
 * Getestet wird der Zweig ohne Activity (MainActivity ruft ihn so auf). Der Activity-Zweig
 * setzt die konkrete AuthUseCase-Klasse voraus und ist mit einem Mock nicht erreichbar.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class AuthViewModelAutorisierungFehlschlagTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(autorisierung: Result<Boolean>): AuthViewModel {
        val authDataStoreRepository = mock<IAuthDataStoreRepository>()
        // Leerer Flow: die Beobachter im init{} spielen keine Zustaende ein.
        whenever(authDataStoreRepository.authData).thenReturn(emptyFlow<AuthData>())
        authDataStoreRepository.stub {
            on { getCurrentAuthData() } doReturn Result.success(
                AuthData(isLoggedIn = true, email = "nutzer@example.org")
            )
        }

        val calendarSelectionRepository = mock<ICalendarSelectionRepository>()
        whenever(calendarSelectionRepository.selectedCalendarIds)
            .thenReturn(MutableStateFlow(emptySet()))

        val tokenRepository = mock<TokenRepository>()
        whenever(tokenRepository.observe()).thenReturn(emptyFlow())

        val authUseCase = mock<IAuthUseCase>()
        authUseCase.stub { on { requestCalendarAuthorization(anyOrNull()) } doReturn autorisierung }

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
            appContext = mock<Context>()
        )
    }

    @Test
    fun `scheitert die Autorisierung, entscheidet das Gate auf ungueltiges Token`() = runTest {
        val viewModel = buildViewModel(Result.failure(Exception("x")))

        viewModel.requestCalendarAuthorization(activity = null)
        advanceUntilIdle()

        val state = viewModel.authState.value
        // Ausgangszustand der Fixture ist tokenChecked=false - die Zusicherung unterscheidet also.
        assertFalse(state.calendarOps.hasValidToken)
        assertTrue("Ohne tokenChecked haengt das Gate im Ladebildschirm", state.calendarOps.tokenChecked)
        assertFalse(state.calendarOps.calendarsLoading)
        assertEquals("Calendar-Autorisierung fehlgeschlagen: x", state.error)
    }
}
