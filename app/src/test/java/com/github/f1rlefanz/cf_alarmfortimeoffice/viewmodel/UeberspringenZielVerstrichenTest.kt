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
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * "Überspringen" darf nur den Wecker treffen, den die Zeile am Knopf nennt (Review 1.48.0).
 *
 * DER FALL: Die Zeile ("Wecker 13.10. 05:30 einmalig überspringen") rechnet nur nach, wenn sich
 * Bestand oder Skip-Zustand aendern. Klingelt der genannte Wecker und wird beendet, bleibt er bis
 * zum naechsten Sync im Bestand - und in der Zeile. `skipNextAlarm()` im UseCase waehlt dagegen zum
 * Tippzeitpunkt neu und haette den Wecker von MORGEN uebersprungen, den die Zeile nie genannt hat:
 * ein verschlafener Dienst. Jetzt rechnet das ViewModel beim Tippen mit der Uhr von jetzt nach und
 * lehnt ab, wenn das Ziel nicht mehr stimmt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UeberspringenZielVerstrichenTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun alarm(id: Int, zeitpunkt: Long, anzeige: String) = AlarmInfo(
        id = id,
        shiftId = "shift$id",
        shiftName = "Frueh",
        triggerTime = zeitpunkt,
        formattedTime = anzeige
    )

    private fun buildViewModel(alarms: List<AlarmInfo>, skipUseCase: IAlarmSkipUseCase): AlarmViewModel {
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(alarms))
        alarmUseCase.stub {
            // Kein Aufraeumen beim Start: der verstrichene Eintrag soll im Bestand bleiben, wie
            // nach einem beendeten Wecker bis zum naechsten Sync.
            on { getAllAlarms() } doReturn Result.success(emptyList())
            on { deleteAlarm(any()) } doReturn Result.success(Unit)
        }
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        val shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(flowOf(ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
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
            zeitkettenArmierer = org.mockito.kotlin.mock(),
            alarmRepository = mock<IAlarmRepository>().apply {
                stub { on { isPersistenceBlocked() } doReturn false }
            }
        )
    }

    @Test
    fun `verstrichener genannter Wecker - nichts wird uebersprungen, die Zeile nennt den naechsten`() =
        runTest(dispatcher) {
            val jetzt = System.currentTimeMillis()
            val gleich = alarm(1, jetzt + 3_000L, "13.10.2026 05:30")
            val morgen = alarm(2, jetzt + 24 * 60 * 60 * 1000L, "14.10.2026 05:30")
            val skipUseCase = mock<IAlarmSkipUseCase>()
            val viewModel = buildViewModel(listOf(gleich, morgen), skipUseCase)
            advanceUntilIdle()
            assertEquals(
                "Vorbedingung: die Zeile nennt den Wecker von gleich",
                gleich.triggerTime,
                viewModel.uiState.value.nextRingingAlarmTriggerTime
            )

            // Der Wecker klingelt und wird beendet - der Bestand aendert sich dabei nicht.
            Thread.sleep(maxOf(0L, gleich.triggerTime - System.currentTimeMillis() + 50L))
            viewModel.skipNextAlarm()
            advanceUntilIdle()

            verifyBlocking(skipUseCase, never()) { skipNextAlarm() }
            assertEquals(
                "Die Zeile nennt jetzt den Wecker, den ein zweiter Tipp treffen wuerde",
                morgen.triggerTime,
                viewModel.uiState.value.nextRingingAlarmTriggerTime
            )
            assertEquals(ueberspringenZielVerstrichenHinweis(weitererWecker = true), viewModel.skipState.value.restoreNotice)
            assertFalse("Kein Ladezustand - es lief ja kein Vorgang", viewModel.skipState.value.isLoading)
        }

    @Test
    fun `stimmt das Ziel noch, wird ganz normal uebersprungen`() = runTest(dispatcher) {
        val jetzt = System.currentTimeMillis()
        val morgen = alarm(2, jetzt + 24 * 60 * 60 * 1000L, "14.10.2026 05:30")
        val skipUseCase = mock<IAlarmSkipUseCase>()
        skipUseCase.stub {
            onBlocking { skipNextAlarm() } doReturn Result.success(
                AlarmSkipResult(alarmId = 2, alarmName = "Frueh", formattedTime = morgen.formattedTime)
            )
        }
        val viewModel = buildViewModel(listOf(morgen), skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        verifyBlocking(skipUseCase, times(1)) { skipNextAlarm() }
        assertNull(viewModel.skipState.value.restoreNotice)
        assertTrue(viewModel.skipState.value.isNextAlarmSkipped)
    }

    @Test
    fun `der Hinweis sagt zuerst, dass nichts uebersprungen wurde`() {
        listOf(true, false).forEach { weiterer ->
            val hinweis = ueberspringenZielVerstrichenHinweis(weiterer)
            assertTrue(hinweis, hinweis.startsWith("Der angezeigte Wecker ist schon vorbei – übersprungen wurde nichts."))
        }
        assertTrue(ueberspringenZielVerstrichenHinweis(false).contains("kein weiterer klingelnder Wecker"))
    }
}
