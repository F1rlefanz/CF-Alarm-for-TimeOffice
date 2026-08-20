package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.SkipRolledBackException
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Pruefrunde 7, Regression ueber dem eigenen Fix: die Ruecknahme eines abgebrochenen
 * Ueberspringens muss auch dann zu Ende laufen, wenn der `viewModelScope` mittendrin stirbt.
 *
 * DER AUSGANGSZUSTAND IST DIE SCHLIMMSTE KLASSE: `skipNextAlarm()` im UseCase haelt seine
 * Schritte zwar per `withContext(NonCancellable)` zusammen und liefert die Ruecknahme danach als
 * `SkipRolledBackException` - der Systemalarm ist zu diesem Zeitpunkt aber gecancelt und der
 * Eintrag noch im Bestand. Erst das Re-Armieren im ViewModel macht daraus wieder einen Wecker.
 * Lief das im blanken `viewModelScope`, starb es am ersten Suspensionspunkt, sobald der Nutzer die
 * App in genau diesem Moment verliess - zurueck blieb ein sichtbarer, stummer Wecker. Ein
 * Nachholer traegt das nicht: `syncAlarms()` re-armt nur kalenderbasierte Alarme, ein MANUELLER
 * Wecker (den der Sync per `keepManualAlarms` nur schont) bliebe bis zum naechsten Neustart stumm.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde7SkipRuecknahmeAbbruchTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun alarm(id: Int) = AlarmInfo(
        id = id,
        shiftId = "manual_$id",
        shiftName = "Von Hand gestellt",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "20.08.2026 05:00"
    )

    @Test
    fun `die Ruecknahme armiert den Wecker auch bei abgeraeumtem ViewModel wieder`() = runTest {
        lateinit var viewModel: AlarmViewModel
        val armiert = mutableListOf<Int>()

        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(MutableStateFlow(AlarmSkipState()))
        skipUseCase.stub {
            onBlocking { skipNextAlarm() } doSuspendableAnswer {
                // Der Nutzer verlaesst die App, waehrend der UseCase in seinem unabbrechbaren
                // Abschnitt steht (Merker gesetzt, Systemalarm gecancelt) - genau das macht
                // onCleared() mit dem viewModelScope.
                viewModel.viewModelScope.cancel()
                Result.failure(
                    SkipRolledBackException(alarmId = 5, skipFlagCleared = true, cause = IOException("kaputt"))
                )
            }
        }

        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(listOf(alarm(5))))
        alarmUseCase.stub {
            // Beide Schritte suspendieren, wie ihre echten Vorbilder (DataStore-Lesen und
            // AlarmManager hinter SafeExecutor). Vermerkt wird NACH dem Suspensionspunkt - nur so
            // unterscheidet der Test "ist gelaufen" von "wurde begonnen und abgebrochen".
            onBlocking { getAllAlarms() } doSuspendableAnswer {
                delay(10)
                Result.success(listOf(alarm(5)))
            }
            onBlocking { scheduleSystemAlarm(any()) } doSuspendableAnswer { aufruf ->
                delay(10)
                armiert += aufruf.getArgument<AlarmInfo>(0).id
                Result.success(Unit)
            }
            onBlocking { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
        }

        viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        assertTrue(
            "Ohne withContext(NonCancellable) stirbt das Re-Armieren am ersten Suspensionspunkt - " +
                "zurueck bleibt ein sichtbarer, stummer Wecker",
            armiert == listOf(5)
        )
    }

    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase,
        skipUseCase: IAlarmSkipUseCase
    ): AlarmViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        // Reaktive Schichtliste: AlarmViewModel sammelt diesen Flow seit Pruefrunde 8 im init{}.
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(autoAlarmEnabled = true))
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { onBlocking { pausedNow() } doReturn false }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            alarmRepository = mock<IAlarmRepository>().apply {
                stub {
                    onBlocking { isPersistenceBlocked() } doReturn false
                    onBlocking { istLetzterSchreibvorgangGescheitert() } doReturn false
                }
            }
        )
    }
}
