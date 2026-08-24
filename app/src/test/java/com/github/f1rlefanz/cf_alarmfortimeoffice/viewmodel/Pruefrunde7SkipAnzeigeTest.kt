package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.SkipRolledBackException
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

/**
 * Haelt fest, dass die Skip-Bedienung einen TOTEN `skipStatusFlow` uebersteht.
 *
 * DER FEHLER (Pruefrunde 7): `skipStatusFlow` endet nach fuenf vergeblichen Leseversuchen
 * endgueltig und bewusst ohne Ersatzwert - und `observeSkipStatus()` sammelt ihn genau einmal aus
 * `init{}`. `skipNextAlarm()` schaltete `isLoading` ein und ueberliess das Zuruecksetzen allein
 * diesem Collector. Kam keine Emission mehr, blieb der Ladezustand fuer den Rest der
 * Prozesslaufzeit stehen - und weil "Ueberspringen" UND "Aufheben" an `!isLoading` haengen, war
 * die Karte tot. Der Zwilling: nach erfolgreichem "Aufheben" zeigte die Oberflaeche weiter
 * "Nächster Alarm wird übersprungen" samt Knopf fuer ein Ueberspringen, das es nicht mehr gab.
 *
 * Dazu die Ruecknahme aus [SkipRolledBackException]: der Systemalarm ist dann schon gecancelt,
 * der Eintrag aber noch da - ein stummer Wecker mit Anzeige. Das ViewModel muss ihn wieder
 * stellen, weil der UseCase es nicht kann (AlarmUseCase haengt fuer den Skip-Backstop bereits an
 * ihm, die Gegenrichtung waere ein DI-Zyklus).
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde7SkipAnzeigeTest {

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

    /** Ein Flow, der nie etwas liefert - genau das Verhalten nach fuenf vergeblichen Leseversuchen. */
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
            alarmRepository = mock<IAlarmRepository>().apply {
                stub {
                    on { isPersistenceBlocked() } doReturn false
                    on { istLetzterSchreibvorgangGescheitert() } doReturn false
                }
            }
        )
    }

    private fun alarmUseCaseWith(
        alarms: List<AlarmInfo> = emptyList(),
        scheduleResult: Result<Unit> = Result.success(Unit)
    ): IAlarmUseCase {
        val useCase = mock<IAlarmUseCase>()
        whenever(useCase.activeAlarms).thenReturn(flowOf(alarms))
        useCase.stub {
            on { getAllAlarms() } doReturn Result.success(alarms)
            on { scheduleSystemAlarm(any()) } doReturn scheduleResult
            on { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
        }
        return useCase
    }

    @Test
    fun `Ueberspringen loest den Ladezustand auch ohne jede Emission des Skip-Flows`() = runTest {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(toterFlow())
        skipUseCase.stub {
            on { skipNextAlarm() } doReturn Result.success(
                AlarmSkipResult(alarmId = 5, alarmName = "Frueh", formattedTime = "05:00")
            )
        }
        val viewModel = buildViewModel(alarmUseCaseWith(listOf(alarm(5))), skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        assertFalse(
            "Bleibt isLoading stehen, sind Ueberspringen UND Aufheben dauerhaft ausgegraut",
            viewModel.skipState.value.isLoading
        )
        assertTrue(
            "Das Schreiben ist bestaetigt - die Oberflaeche muss den Skip auch ohne Emission zeigen",
            viewModel.skipState.value.isNextAlarmSkipped
        )
        assertEquals(5, viewModel.skipState.value.skippedAlarmId)
    }

    @Test
    fun `Aufheben raeumt die Skip-Anzeige auch ohne jede Emission des Skip-Flows`() = runTest {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(toterFlow())
        skipUseCase.stub {
            on { skipNextAlarm() } doReturn Result.success(
                AlarmSkipResult(alarmId = 5, alarmName = "Frueh", formattedTime = "05:00")
            )
            // Kalenderbasierter Alarm: kein gesicherter manueller Wecker im Skip-Zustand.
            on { getSkipStatus() } doReturn Result.success(
                AlarmSkipState(isNextAlarmSkipped = true, skippedAlarmId = 5)
            )
            on { cancelSkip() } doReturn Result.success(Unit)
        }
        val viewModel = buildViewModel(alarmUseCaseWith(listOf(alarm(5))), skipUseCase)
        advanceUntilIdle()
        viewModel.skipNextAlarm()
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        assertFalse(
            "Sonst bietet die Oberflaeche 'Aufheben' fuer ein Ueberspringen an, das es nicht mehr gibt",
            viewModel.skipState.value.isNextAlarmSkipped
        )
        assertFalse(viewModel.skipState.value.isLoading)
    }

    @Test
    fun `ein spaeter wieder lesbarer Skip-Zustand erreicht die Oberflaeche noch`() = runTest {
        var aufrufe = 0
        val skipUseCase = mock<IAlarmSkipUseCase>()
        // Erst kaputt, dann lesbar: ohne erneutes Abonnieren bliebe die Anzeige fuer immer leer.
        whenever(skipUseCase.skipStatusFlow).thenReturn(
            flow {
                if (aufrufe++ == 0) throw IOException("DataStore nicht lesbar")
                emit(AlarmSkipState(isNextAlarmSkipped = true, skippedAlarmId = 11))
            }
        )
        val viewModel = buildViewModel(alarmUseCaseWith(listOf(alarm(11))), skipUseCase)

        advanceUntilIdle()

        assertTrue(
            "Der Collector muss nach dem Ende des Flows erneut abonnieren",
            viewModel.skipState.value.isNextAlarmSkipped
        )
        assertEquals(11, viewModel.skipState.value.skippedAlarmId)
    }

    @Test
    fun `zurueckgenommenes Ueberspringen stellt den Systemalarm wieder und sagt es dem Nutzer`() = runTest {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(toterFlow())
        skipUseCase.stub {
            on { skipNextAlarm() } doReturn Result.failure(
                SkipRolledBackException(alarmId = 5, skipFlagCleared = true, cause = IOException("kaputt"))
            )
        }
        val alarmUseCase = alarmUseCaseWith(listOf(alarm(5)))
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        verify(alarmUseCase).scheduleSystemAlarm(alarm(5))
        assertFalse(viewModel.skipState.value.isNextAlarmSkipped)
        assertNotNull(
            "Ein halb durchgefuehrtes Ueberspringen darf nicht stumm bleiben",
            viewModel.skipState.value.restoreNotice
        )
        assertTrue(
            "Nur wenn das Armieren gelang, darf dort 'klingelt wie geplant' stehen",
            viewModel.skipState.value.restoreNotice!!.contains("klingelt wie geplant")
        )
    }

    @Test
    fun `ohne geraeumten Skip-Merker wird nicht armiert und nichts versprochen`() = runTest {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(toterFlow())
        skipUseCase.stub {
            on { skipNextAlarm() } doReturn Result.failure(
                SkipRolledBackException(alarmId = 5, skipFlagCleared = false, cause = IOException("kaputt"))
            )
        }
        val alarmUseCase = alarmUseCaseWith(listOf(alarm(5)))
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        // Der Skip-Backstop in scheduleSystemAlarm() wuerde es ohnehin abweisen.
        verify(alarmUseCase, never()).scheduleSystemAlarm(any())
        val notiz = viewModel.skipState.value.restoreNotice
        assertNotNull(notiz)
        assertFalse(
            "Kein Versprechen, das die App nicht halten kann",
            notiz!!.contains("klingelt wie geplant")
        )
    }

    /**
     * DER REGRESSIONSFALL: waehrend die Ruecknahme laeuft, raeumt der Skip-Flow den Merker.
     *
     * `stelleNachAbgebrochenemSkipWiederHer()` liest den Alarm-Bestand und stellt den Systemalarm -
     * beides suspendiert. In dieser Zeit trifft genau die Emission ein, die das `clearSkipStatus()`
     * der Ruecknahme im UseCase ausgeloest hat, und `applySkipState(false, null)` schreibt sie in den
     * Zustand. Stand der Aufruf als Argument in einem `copy()` auf `_skipState.value`, war der
     * Empfaenger schon vor der Suspendierung gelesen - die Zuweisung machte die Emission rueckgaengig.
     * Ergebnis: "Nächster Alarm wird übersprungen" samt "Aufheben" neben dem Text, dass der Wecker
     * wie geplant klingelt - zwei Aussagen, die einander widersprechen.
     */
    @Test
    fun `eine Emission waehrend der Ruecknahme wird nicht ueberschrieben`() = runTest {
        val skipFlow = MutableStateFlow(AlarmSkipState())
        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(skipFlow)
        skipUseCase.stub {
            on { skipNextAlarm() } doSuspendableAnswer {
                // Der UseCase setzt den Merker, BEVOR er den Alarm loescht - die Oberflaeche zeigt
                // das Ueberspringen also bereits an, wenn das Loeschen scheitert.
                skipFlow.value = AlarmSkipState(isNextAlarmSkipped = true, skippedAlarmId = 5)
                yield()
                Result.failure(
                    SkipRolledBackException(alarmId = 5, skipFlagCleared = true, cause = IOException("kaputt"))
                )
            }
        }
        val alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(listOf(alarm(5))))
        alarmUseCase.stub {
            on { getAllAlarms() } doSuspendableAnswer {
                // Die Ruecknahme im UseCase hat den Merker geraeumt; die zugehoerige Emission
                // erreicht den Collector genau waehrend dieser Suspendierung.
                skipFlow.value = AlarmSkipState(isNextAlarmSkipped = false, skippedAlarmId = null)
                yield()
                Result.success(listOf(alarm(5)))
            }
            on { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
            on { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
        }
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.skipNextAlarm()
        advanceUntilIdle()

        val stand = viewModel.skipState.value
        assertFalse(
            "Der geraeumte Merker darf nicht von einem alten Schnappschuss ueberschrieben werden",
            stand.isNextAlarmSkipped
        )
        assertNull(
            "Mit stehengebliebener skippedAlarmId faellt der wieder armierte Wecker aus 'Nächster Alarm'",
            stand.skippedAlarmId
        )
        assertTrue(
            "Der Hinweis zur Ruecknahme muss trotzdem ankommen",
            stand.restoreNotice!!.contains("klingelt wie geplant")
        )
    }
}
