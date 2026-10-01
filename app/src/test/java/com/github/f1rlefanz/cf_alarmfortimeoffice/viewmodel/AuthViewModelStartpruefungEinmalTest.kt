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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #129: Die Start-Pruefung im [AuthViewModel] laeuft GENAU EINMAL.
 *
 * Vorher sammelte `checkInitialAuthState()` den Auth-Daten-Flow endlos (`return@collect` verlaesst
 * nur das Lambda). Jede spaetere Emission - Anmelden, Abmelden - startete die Token-Pruefung neu,
 * NEBEN einer gerade laufenden Autorisierung; deren spaetes Ergebnis konnte ein frisches
 * `hasValidToken = true` wieder auf `false` setzen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelStartpruefungEinmalTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `spaetere Auth-Daten-Emissionen starten die Token-Pruefung nicht erneut`() = runTest(dispatcher) {
        val authDaten = MutableStateFlow(AuthData(isLoggedIn = false))
        val repo = mock<IAuthDataStoreRepository>()
        whenever(repo.authData).thenReturn(authDaten)

        val authUseCase = mock<IAuthUseCase>()
        authUseCase.stub {
            onBlocking { hasCalendarAuthorization() } doReturn Result.success(true)
        }

        val calendarSelectionRepository = mock<ICalendarSelectionRepository>()
        whenever(calendarSelectionRepository.selectedCalendarIds).thenReturn(MutableStateFlow(emptySet()))
        calendarSelectionRepository.stub {
            onBlocking { getCurrentSelectedCalendarIds() } doReturn Result.success(emptySet())
        }
        val tokenRepository = mock<TokenRepository>()
        whenever(tokenRepository.observe()).thenReturn(emptyFlow())
        val errorHandler = mock<ErrorHandler>()
        whenever(errorHandler.getErrorMessage(any())).thenReturn("Fehler")

        val viewModel = AuthViewModel(
            authDataStoreRepository = repo,
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
        advanceUntilIdle()
        assertTrue(viewModel.authState.value.calendarOps.tokenChecked)

        // Anmelden und Abmelden schreiben die Auth-Daten neu.
        authDaten.value = AuthData(isLoggedIn = true, email = "nutzer@example.org")
        advanceUntilIdle()
        authDaten.value = AuthData(isLoggedIn = false)
        advanceUntilIdle()

        verify(authUseCase, times(1)).hasCalendarAuthorization()
    }
}
