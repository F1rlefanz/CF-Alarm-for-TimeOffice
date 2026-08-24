package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Haelt fest, dass ein laufender Skip-Vorgang nicht ein zweites Mal gestartet werden kann.
 *
 * DER FEHLER (Regression aus Pruefrunde 7): die neue Wiederaufnahme-Schleife in
 * `observeSkipStatus()` loeste den Ladezustand bedingungslos, sobald der `skipStatusFlow` endete -
 * und der endet nach fuenf vergeblichen Leseversuchen bewusst endgueltig, also moeglicherweise
 * mitten in einem laufenden `skipNextAlarm()`, das ueber Schreiben, Cancellen und Loeschen hinweg
 * suspendiert. Weil "Ueberspringen" und "Aufheben" allein an `!isLoading` haengen, wurden beide
 * Knoepfe waehrend des Schreibens wieder bedienbar. Ein zweiter Druck fand denselben, noch nicht
 * geloeschten Alarm: sein Systemalarm wurde ein zweites Mal gecancelt, und scheiterte einer der
 * beiden Laeufe, raeumte dessen Ruecknahme den Merker weg, den der andere gerade gesetzt hatte -
 * Endstand: kein Merker, kein Systemalarm, ein stumm geloeschter Wecker.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceTimeBy/advanceUntilIdle
class Pruefrunde7SkipWiedereintrittTest {

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
        shiftId = "shift$id",
        shiftName = "Frueh",
        triggerTime = now + 60 * 60 * 1000L,
        formattedTime = "20.08.2026 05:00"
    )

    /** Endet sofort - genau das Verhalten des Skip-Flows nach fuenf vergeblichen Leseversuchen. */
    private fun toterFlow(): Flow<AlarmSkipState> = flow { throw IOException("DataStore nicht lesbar") }

    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase,
        skipUseCase: IAlarmSkipUseCase
    ): AlarmViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        // Reaktive Schichtliste: AlarmViewModel sammelt diesen Flow seit Pruefrunde 8 im init{}.
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(autoAlarmEnabled = true))
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { on { pausedNow() } doReturn false }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            tagFreigabeUseCase = tagFreigabeUseCaseOhneFreigaben(),
            alarmRepository = mock<IAlarmRepository>().apply {
                stub { on { isPersistenceBlocked() } doReturn false }
            }
        )
    }

    private fun alarmUseCaseWith(alarms: List<AlarmInfo>): IAlarmUseCase {
        val useCase = mock<IAlarmUseCase>()
        whenever(useCase.activeAlarms).thenReturn(flowOf(alarms))
        useCase.stub {
            on { getAllAlarms() } doReturn Result.success(alarms)
            on { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
            on { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
        }
        return useCase
    }

    /**
     * Das Zeitfenster ist echt: der erste neue Anlauf der Wiederaufnahme-Schleife liegt 30 s nach
     * dem Ende des Flows, ein Skip-Vorgang schreibt, cancelt und loescht laenger als einen Tick.
     */
    @Test
    fun `ein zweiter Druck waehrend eines laufenden Ueberspringens startet keinen zweiten Vorgang`() = runTest {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(toterFlow())
        skipUseCase.stub {
            on { skipNextAlarm() } doSuspendableAnswer {
                delay(40_000)
                Result.success(AlarmSkipResult(alarmId = 5, alarmName = "Frueh", formattedTime = "05:00"))
            }
        }
        val viewModel = buildViewModel(alarmUseCaseWith(listOf(alarm(5))), skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        // Ueber den ersten neuen Anlauf der Schleife hinaus, aber noch mitten im Schreibvorgang.
        advanceTimeBy(35_000)

        assertTrue(
            "Der Spinner darf nicht geloescht werden, solange der Vorgang laeuft - sonst sind " +
                "beide Knoepfe wieder bedienbar",
            viewModel.skipState.value.isLoading
        )

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        verifyBlocking(skipUseCase, times(1)) { skipNextAlarm() }
    }

    @Test
    fun `Aufheben waehrend eines laufenden Ueberspringens raeumt den Merker nicht weg`() = runTest {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(toterFlow())
        skipUseCase.stub {
            on { skipNextAlarm() } doSuspendableAnswer {
                delay(40_000)
                Result.success(AlarmSkipResult(alarmId = 5, alarmName = "Frueh", formattedTime = "05:00"))
            }
            on { getSkipStatus() } doReturn Result.success(
                AlarmSkipState(isNextAlarmSkipped = true, skippedAlarmId = 5)
            )
            on { cancelSkip() } doReturn Result.success(Unit)
        }
        val viewModel = buildViewModel(alarmUseCaseWith(listOf(alarm(5))), skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceTimeBy(35_000)
        viewModel.cancelSkip()
        advanceUntilIdle()

        verifyBlocking(skipUseCase, never()) { cancelSkip() }
        assertTrue(
            "Der gerade bestaetigt geschriebene Skip muss stehen - ein dazwischenfunkendes " +
                "Aufheben haette Merker und Wecker gleichzeitig geraeumt",
            viewModel.skipState.value.isNextAlarmSkipped
        )
    }
}
