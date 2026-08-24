package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ManualAlarmSnapshot
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pruefrunde 6, Befunde 1 und 2 - beide drehen sich um denselben Kern: der uebersprungene MANUELLE
 * Wecker existiert nur noch als Schnappschuss im Skip-Zustand, und jede stille Degradierung auf
 * "kein Problem" kostet ihn.
 *
 * BEFUND 1: Die ID eines manuellen Weckers ist ein reiner Hash aus Datum und Schicht. Wer denselben
 * Wecker nach dem Ueberspringen neu anlegt - wozu die App im blockierten "Aufheben"-Fall sogar
 * geraten hat - trifft exakt die ID im Skip-Merker. Der Backstop in `scheduleSystemAlarm()` wies
 * ihn ab und meldete trotzdem Erfolg: Eintrag im Bestand, "Manueller Alarm aktiv" mit Uhrzeit auf
 * der Karte, im AlarmManager nichts. Der Merker laeuft zeitbasiert erst NACH der Weckzeit ab.
 *
 * BEFUND 2: `behebbaresRestoreHindernis()` las Konfiguration und Alarm-Bestand mit `getOrNull()`.
 * Ein Lesefehler wurde damit zu "kein Hindernis", `cancelSkip()` raeumte Flag UND Schnappschuss ab -
 * genau in dem Moment, in dem die Funktion haette zurueckstellen sollen.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde6SkipManualAlarmTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()

    /** Weit in der Zukunft, damit die berechnete Weckzeit garantiert > now ist. */
    private val zukunftstag: LocalDate = LocalDate.now().plusDays(3)

    private val fruehSchicht = ShiftDefinition(
        id = "early",
        name = "Frueh",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 30)
    )

    /** Dieselbe Bildungsvorschrift, die auch der Skip-Merker trifft. */
    private val manuelleId: Int =
        AlarmViewModel.ManualAlarmConstants.createManualAlarmId(zukunftstag, fruehSchicht.id)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun manualAlarm(id: Int, triggerTime: Long) = AlarmInfo(
        id = id,
        shiftId = "manual_early_20260820",
        shiftName = "Frueh (Manuell)",
        triggerTime = triggerTime,
        formattedTime = "20.08.2026 05:00"
    )

    private fun alarmUseCaseWith(
        alarms: List<AlarmInfo> = emptyList(),
        alarmsResult: Result<List<AlarmInfo>> = Result.success(alarms),
        saveResult: Result<Unit> = Result.success(Unit),
        scheduleResult: Result<Unit> = Result.success(Unit)
    ): IAlarmUseCase {
        val useCase = mock<IAlarmUseCase>()
        whenever(useCase.activeAlarms).thenReturn(flowOf(alarms))
        useCase.stub {
            on { getAllAlarms() } doReturn alarmsResult
            on { saveAlarm(any()) } doReturn saveResult
            on { scheduleSystemAlarm(any()) } doReturn scheduleResult
            on { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
            on { deleteAlarm(any()) } doReturn Result.success(Unit)
        }
        return useCase
    }

    private fun skipUseCaseWith(
        skippedAlarmId: Int? = null,
        snapshot: String? = null,
        cancelResult: Result<Unit> = Result.success(Unit)
    ): IAlarmSkipUseCase {
        val skipUseCase = mock<IAlarmSkipUseCase>()
        val state = if (skippedAlarmId == null) {
            AlarmSkipState()
        } else {
            AlarmSkipState(
                isNextAlarmSkipped = true,
                skippedAlarmId = skippedAlarmId,
                skippedAlarmTriggerTime = now + 60 * 60 * 1000L,
                skippedManualAlarm = snapshot
            )
        }
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(state))
        skipUseCase.stub {
            on { getSkipStatus() } doReturn Result.success(state)
            on { cancelSkip() } doReturn cancelResult
        }
        return skipUseCase
    }

    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase,
        skipUseCase: IAlarmSkipUseCase,
        shiftConfigResult: Result<ShiftConfig> = Result.success(
            ShiftConfig(autoAlarmEnabled = true, definitions = listOf(fruehSchicht))
        )
    ): AlarmViewModel {
        val shiftUseCase = mock<IShiftUseCase>()
        // Reaktive Schichtliste: AlarmViewModel sammelt diesen Flow seit Pruefrunde 8 im init{}.
        whenever(shiftUseCase.shiftConfig)
            .thenReturn(flowOf(shiftConfigResult.getOrNull() ?: ShiftConfig()))
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn shiftConfigResult
        }
        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub {
            on { pausedNow() } doReturn false
        }

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

    // --- BEFUND 1: Neuanlegen waehrend eines kollidierenden Skips ---

    @Test
    fun `createManualAlarm - kollidierendes Ueberspringen wird VOR dem Speichern aufgehoben`() = runTest {
        // OHNE DEN FIX: cancelSkip() wird nie gerufen, der Merker steht noch, und der Backstop in
        // scheduleSystemAlarm() weist den Alarm ab - waehrend die Karte ihn als gestellt anzeigt.
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(skippedAlarmId = manuelleId)
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.selectManualAlarmShift(fruehSchicht)
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.createManualAlarm()
        advanceUntilIdle()

        val reihenfolge = inOrder(skipUseCase, alarmUseCase)
        reihenfolge.verify(skipUseCase).cancelSkip()
        reihenfolge.verify(alarmUseCase).saveAlarm(any())
        reihenfolge.verify(alarmUseCase).scheduleSystemAlarm(any())
        assertNull(
            "Der Weg, den die App dem Nutzer selbst empfiehlt, darf keinen Fehler erzeugen",
            viewModel.manualAlarmState.value.error
        )
    }

    @Test
    fun `createManualAlarm - ein Skip auf einen ANDEREN Alarm wird nicht angefasst`() = runTest {
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(skippedAlarmId = manuelleId + 1)
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.selectManualAlarmShift(fruehSchicht)
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.createManualAlarm()
        advanceUntilIdle()

        verify(skipUseCase, never()).cancelSkip()
        verify(alarmUseCase).saveAlarm(any())
    }

    @Test
    fun `createManualAlarm - laesst sich das kollidierende Ueberspringen nicht aufheben, entsteht KEIN Wecker`() = runTest {
        // Sonst laege ein Eintrag im Bestand, den der noch stehende Merker nie armieren laesst.
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = manuelleId,
            cancelResult = Result.failure(IllegalStateException("DataStore nicht schreibbar"))
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.selectManualAlarmShift(fruehSchicht)
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.createManualAlarm()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        assertNotNull(
            "Ein nicht stellbarer Wecker muss sichtbar scheitern statt still zu entstehen",
            viewModel.manualAlarmState.value.error
        )
    }

    @Test
    fun `createManualAlarm - scheitert das Armieren, wird der gespeicherte Eintrag zurueckgenommen`() = runTest {
        // Ein Eintrag im Bestand ohne System-Alarm ist ein stummer Wecker MIT Anzeige - die
        // gefaehrlichste Variante. OHNE DEN FIX bleibt er liegen und die Karte behauptet ihn.
        val alarmUseCase = alarmUseCaseWith(
            scheduleResult = Result.failure(IllegalStateException("AlarmManager verweigert"))
        )
        val skipUseCase = skipUseCaseWith()
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.selectManualAlarmShift(fruehSchicht)
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.createManualAlarm()
        advanceUntilIdle()

        verify(alarmUseCase).cancelSystemAlarm(eq(manuelleId))
        verify(alarmUseCase).deleteAlarm(eq(manuelleId))
        assertNotNull(viewModel.manualAlarmState.value.error)
    }

    // --- BEFUND 2: Lesefehler sind behebbare Hindernisse, keine Freigabe ---

    @Test
    fun `cancelSkip - nicht lesbarer Alarm-Bestand stellt das Aufheben zurueck statt den Schnappschuss zu vernichten`() = runTest {
        // OHNE DEN FIX: getOrNull() macht aus dem Lesefehler "kein anderer manueller Wecker",
        // cancelSkip() raeumt Flag UND Schnappschuss ab - der Wecker ist endgueltig weg.
        val gesichert = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith(
            alarmsResult = Result.failure(IllegalStateException("Alarm-Bestand nicht lesbar"))
        )
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(gesichert)
        )
        val viewModel = buildViewModel(alarmUseCase, skipUseCase)
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(skipUseCase, never()).cancelSkip()
        assertNotNull(
            "Der Nutzer muss erfahren, warum das Aufheben zurueckgestellt wurde",
            viewModel.skipState.value.restoreNotice
        )
    }

    @Test
    fun `cancelSkip - nicht lesbare Schicht-Konfiguration stellt das Aufheben ebenfalls zurueck`() = runTest {
        // Gleiche Fehlerklasse, gleiche Funktion: `getOrNull()?.autoAlarmEnabled ?: true` machte
        // aus einem Lesefehler "Automatische Alarme sind an".
        val gesichert = manualAlarm(id = 11, triggerTime = now + 60 * 60 * 1000L)
        val alarmUseCase = alarmUseCaseWith()
        val skipUseCase = skipUseCaseWith(
            skippedAlarmId = 11,
            snapshot = ManualAlarmSnapshot.encode(gesichert)
        )
        val viewModel = buildViewModel(
            alarmUseCase,
            skipUseCase,
            shiftConfigResult = Result.failure(IllegalStateException("Konfiguration nicht lesbar"))
        )
        advanceUntilIdle()

        viewModel.cancelSkip()
        advanceUntilIdle()

        verify(skipUseCase, never()).cancelSkip()
        assertNotNull(viewModel.skipState.value.restoreNotice)
    }
}
