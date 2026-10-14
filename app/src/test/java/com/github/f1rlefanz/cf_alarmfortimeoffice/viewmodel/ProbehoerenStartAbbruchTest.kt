package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonProbe
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * „Probehören“: Ein Stopp, der eintrifft, solange der Start noch den gespeicherten Anstieg liest,
 * muss diesen Start verwerfen (Review #139). Sonst laeuft der Stopp ins Leere - die Probe ist ja
 * noch aus - und der Read startet danach den Weckton im Hintergrund, ohne Stopp-Knopf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbehoerenStartAbbruchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val read = CompletableDeferred<WecktonAnstieg>()
    private val probe = mock<WecktonProbe>()

    private fun buildViewModel(): AlarmViewModel {
        val alarmUseCase = mock<AlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(emptyList()))
        alarmUseCase.stub { onBlocking { getAllAlarms() } doReturn Result.success(emptyList()) }
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(autoAlarmEnabled = true))
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        whenever(alarmPrefs.wecktonAnstieg).thenReturn(flowOf(WecktonAnstieg.AUS))
        // Der Read haengt, bis der Test ihn freigibt - genau das Fenster des Befunds.
        alarmPrefs.stub { onBlocking { wecktonAnstiegNow() } doSuspendableAnswer { read.await() } }
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { onBlocking { pausedNow() } doReturn false }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            tagFreigabeUseCase = tagFreigabeUseCaseOhneFreigaben(),
            zeitkettenArmierer = mock(),
            wecktonProbe = probe,
            alarmRepository = mock<IAlarmRepository>().apply {
                stub {
                    on { isPersistenceBlocked() } doReturn false
                    on { istLetzterSchreibvorgangGescheitert() } doReturn false
                }
            }
        )
    }

    @Test
    fun `Stopp waehrend des Lesens verwirft den Start`() = runTest(dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.probehoerenStarten()
        runCurrent() // der Start haengt jetzt im Read
        vm.probehoerenStoppen() // ON_STOP / Tabwechsel
        read.complete(WecktonAnstieg.AUS)
        advanceUntilIdle()

        verify(probe, never()).start(any())
        verify(probe, times(1)).stop()
    }

    @Test
    fun `ohne Stopp startet die Probe nach dem Lesen`() = runTest(dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.probehoerenStarten()
        runCurrent()
        read.complete(WecktonAnstieg.AUS)
        advanceUntilIdle()

        verify(probe, times(1)).start(WecktonAnstieg.AUS)
    }
}
