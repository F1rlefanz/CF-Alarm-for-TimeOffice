package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WecktonAnstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Haelt fest, was die Start-Aufraeumung des [AlarmViewModel] tut: verstrichene Alarme werden
 * ueber den UseCase geloescht (der dort zuerst den Systemalarm cancelt), kuenftige bleiben.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class AlarmViewModelStartAufraeumenTest {

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

    private fun alarm(id: Int, triggerTime: Long) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh",
        triggerTime = triggerTime,
        formattedTime = "20.08.2026 05:00"
    )

    private fun buildViewModel(alarmUseCase: AlarmUseCase): AlarmViewModel {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig(autoAlarmEnabled = true))
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        whenever(alarmPrefs.wecktonAnstieg).thenReturn(flowOf(WecktonAnstieg.AUS))
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
                stub {
                    on { isPersistenceBlocked() } doReturn false
                    on { istLetzterSchreibvorgangGescheitert() } doReturn false
                }
            }
        )
    }

    @Test
    fun `beim Start wird nur der verstrichene Alarm geloescht`() = runTest {
        val useCase = mock<AlarmUseCase>()
        whenever(useCase.activeAlarms).thenReturn(flowOf(emptyList()))
        useCase.stub {
            on { getAllAlarms() } doReturn Result.success(
                listOf(alarm(1, now - 60 * 60 * 1000L), alarm(2, now + 60 * 60 * 1000L))
            )
            on { deleteAlarm(any()) } doReturn Result.success(Unit)
        }

        buildViewModel(useCase)
        advanceUntilIdle()

        verifyBlocking(useCase, times(1)) { deleteAlarm(1) }
        verifyBlocking(useCase, never()) { deleteAlarm(2) }
    }
}
