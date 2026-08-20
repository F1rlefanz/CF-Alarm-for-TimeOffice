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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pruefrunde 8, zwei Befunde am MANUELLEN Wecker.
 *
 * BEFUND 1 - EINGEFRORENE SCHICHTLISTE: `loadAvailableShifts()` lief genau einmal aus `init{}`
 * und legte die kompletten `ShiftDefinition`-Objekte in den Zustand. Der `AlarmViewModel` haengt
 * an der Activity und ueberlebt Tabwechsel, Unterscreens und Rotation - wer die Weckzeit eines
 * Schichttyps aenderte und danach ohne App-Neustart einen manuellen Wecker fuer diese Schicht
 * anlegte, bekam die ALTE Zeit armiert, waehrend die Karte sie ausdruecklich bestaetigte. Neu
 * angelegte Schichttypen fehlten in der Auswahl, geloeschte wurden weiter angeboten. Der
 * Kalender-Sync repariert das nicht, er schont manuelle Alarme (`keepManualAlarms`).
 *
 * BEFUND 11 - SCHEINBAR ERFOLGREICHES SPEICHERN: `AlarmRepository.saveAlarm()` meldet auch dann
 * Erfolg, wenn `persistToDataStore()` bei gesperrter Persistenz still zurueckkehrt. Der Wecker
 * klingelt dann in diesem Prozess, steht aber weder in der Preferences-Datei noch im
 * Direct-Boot-Spiegel - nach Prozesstod oder Neustart ist er spurlos weg, und fuer einen
 * manuellen Wecker endgueltig. Der Nutzer muss das erfahren.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8ManuellerWeckerTest {

    private val dispatcher = StandardTestDispatcher()

    /** Weit in der Zukunft, damit die berechnete Weckzeit garantiert noch nicht verstrichen ist. */
    private val zukunftstag: LocalDate = LocalDate.now().plusDays(3)

    private val fruehAlt = ShiftDefinition(
        id = "early",
        name = "Fruehdienst",
        keywords = listOf("F"),
        alarmTime = LocalTime.of(5, 0)
    )
    private val fruehNeu = fruehAlt.copy(alarmTime = LocalTime.of(4, 15))
    private val spaet = ShiftDefinition(
        id = "late",
        name = "Spaetdienst",
        keywords = listOf("S"),
        alarmTime = LocalTime.of(11, 0)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private lateinit var konfiguration: MutableStateFlow<ShiftConfig>
    private lateinit var alarmUseCase: IAlarmUseCase
    private lateinit var alarmRepository: IAlarmRepository
    private lateinit var shiftUseCase: IShiftUseCase

    private fun buildViewModel(
        start: ShiftConfig,
        persistenzGesperrt: Boolean = false
    ): AlarmViewModel {
        konfiguration = MutableStateFlow(start)

        alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(emptyList()))
        alarmUseCase.stub {
            onBlocking { getAllAlarms() } doReturn Result.success(emptyList())
            onBlocking { saveAlarm(any()) } doReturn Result.success(Unit)
            onBlocking { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
            onBlocking { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
            onBlocking { deleteAlarm(any()) } doReturn Result.success(Unit)
        }

        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        skipUseCase.stub {
            onBlocking { getSkipStatus() } doReturn Result.success(AlarmSkipState())
        }

        shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(konfiguration)
        shiftUseCase.stub {
            // Der Einmal-Read im Erstellungspfad sieht denselben Stand wie der Flow.
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(konfiguration.value)
        }

        val alarmPrefs = mock<AlarmPrefs>()
        whenever(alarmPrefs.snoozeMinutes).thenReturn(flowOf(9))
        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub { onBlocking { pausedNow() } doReturn false }

        alarmRepository = mock<IAlarmRepository>()
        alarmRepository.stub {
            onBlocking { isPersistenceBlocked() } doReturn persistenzGesperrt
        }

        return AlarmViewModel(
            alarmUseCase = alarmUseCase,
            alarmSkipUseCase = skipUseCase,
            shiftUseCase = shiftUseCase,
            errorHandler = mock<ErrorHandler>(),
            masterPausePrefs = masterPausePrefs,
            alarmPrefs = alarmPrefs,
            alarmRepository = alarmRepository
        )
    }

    /**
     * Aendert NUR den Einmal-Read, ohne Flow-Emission.
     *
     * Damit bleibt `selectedShift` im Zustand das ALTE Objekt - nur so prueft der Test wirklich
     * die frische Aufloesung im Erstellungspfad und nicht bloss den reaktiven Collector.
     */
    private fun nurEinmalReadAendern(neu: ShiftConfig) {
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(neu)
        }
    }

    // --- BEFUND 1 ---

    @Test
    fun `eine geaenderte Weckzeit erreicht die Karte ohne App-Neustart`() = runTest {
        // OHNE DEN FIX: die Liste steht seit init{} still, calculatedAlarmTime nennt weiter 05:00 -
        // ausgerechnet die Zeit, die der Nutzer soeben selbst geaendert hat.
        val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt)))
        advanceUntilIdle()
        viewModel.selectManualAlarmDate(zukunftstag)
        advanceUntilIdle()

        konfiguration.value = ShiftConfig(definitions = listOf(fruehNeu))
        advanceUntilIdle()

        val stand = viewModel.manualAlarmState.value
        assertEquals(
            "Die Auswahl muss auf das frische Definitionsobjekt zeigen",
            LocalTime.of(4, 15),
            stand.selectedShift?.alarmTime
        )
        assertTrue(
            "Die angezeigte Weckzeit stammt aus der Auswahl und muss mitziehen: ${stand.calculatedAlarmTime}",
            stand.calculatedAlarmTime?.contains("04:15") == true
        )
    }

    @Test
    fun `eine neu angelegte Schichtdefinition erscheint sofort in der Auswahl`() = runTest {
        val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt)))
        advanceUntilIdle()

        konfiguration.value = ShiftConfig(definitions = listOf(fruehAlt, spaet))
        advanceUntilIdle()

        assertEquals(
            listOf("early", "late"),
            viewModel.manualAlarmState.value.availableShifts.map { it.id }
        )
        assertEquals(
            "Die bestehende Auswahl darf durch die Neuanlage nicht springen",
            "early",
            viewModel.manualAlarmState.value.selectedShift?.id
        )
    }

    @Test
    fun `eine weggefallene Auswahl wird nicht stillschweigend durch eine andere Weckzeit ersetzt`() =
        runTest {
            // Der gefaehrliche Fix waere `selectedShift = availableShifts.firstOrNull()`: dann
            // stuende unter derselben Auswahl die Weckzeit einer ANDEREN Schicht.
            val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt, spaet)))
            advanceUntilIdle()
            viewModel.selectManualAlarmShift(spaet)
            advanceUntilIdle()

            konfiguration.value = ShiftConfig(definitions = listOf(fruehAlt))
            advanceUntilIdle()

            val stand = viewModel.manualAlarmState.value
            assertNull(
                "Lieber keine Auswahl als eine fremde Weckzeit unter der alten Auswahl",
                stand.selectedShift
            )
            assertNull(stand.calculatedAlarmTime)
            assertNotNull(
                "Der Wegfall muss benannt werden, sonst ist die Karte ohne Erklaerung leer",
                stand.error
            )
        }

    @Test
    fun `eine ausgeschaltete Definition verschwindet aus der Auswahl`() = runTest {
        val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt, spaet)))
        advanceUntilIdle()

        konfiguration.value = ShiftConfig(definitions = listOf(fruehAlt.copy(isEnabled = false), spaet))
        advanceUntilIdle()

        assertEquals(
            listOf("late"),
            viewModel.manualAlarmState.value.availableShifts.map { it.id }
        )
    }

    @Test
    fun `der angelegte Wecker traegt die frisch gelesene Weckzeit, nicht die ausgewaehlte`() = runTest {
        // Der Zwilling zur Anzeige: hier entsteht die Zeit, die spaeter wirklich klingelt.
        val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt)))
        advanceUntilIdle()
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.selectManualAlarmShift(fruehAlt)
        advanceUntilIdle()

        nurEinmalReadAendern(ShiftConfig(definitions = listOf(fruehNeu)))

        viewModel.createManualAlarm()
        advanceUntilIdle()

        val gespeichert = argumentCaptor<AlarmInfo>()
        verify(alarmUseCase).saveAlarm(gespeichert.capture())
        val erwartet = zukunftstag.atTime(LocalTime.of(4, 15))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(erwartet, gespeichert.firstValue.triggerTime)
    }

    @Test
    fun `ist die gewaehlte Definition beim Anlegen weg, entsteht KEIN Wecker`() = runTest {
        val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt)))
        advanceUntilIdle()
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.selectManualAlarmShift(fruehAlt)
        advanceUntilIdle()

        nurEinmalReadAendern(ShiftConfig(definitions = listOf(spaet)))

        viewModel.createManualAlarm()
        advanceUntilIdle()

        verify(alarmUseCase, never()).saveAlarm(any())
        assertNotNull(viewModel.manualAlarmState.value.error)
    }

    // --- BEFUND 11 ---

    @Test
    fun `ein nur im Arbeitsspeicher gelandeter Wecker wird als solcher gemeldet`() = runTest {
        // OHNE DEN FIX: saveAlarm meldet Erfolg, die Karte zeigt den Wecker, und nach dem
        // naechsten Prozesstod ist er weg - ohne dass je ein Text darauf hingewiesen haette.
        val viewModel = buildViewModel(
            ShiftConfig(definitions = listOf(fruehAlt)),
            persistenzGesperrt = true
        )
        advanceUntilIdle()
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.selectManualAlarmShift(fruehAlt)
        advanceUntilIdle()

        viewModel.createManualAlarm()
        advanceUntilIdle()

        val stand = viewModel.manualAlarmState.value
        assertNotNull(
            "Der Nutzer muss erfahren, dass sein Wecker einen Neustart nicht ueberlebt",
            stand.error
        )
        assertTrue(
            "Der Text muss die WIRKUNG nennen, nicht nur 'Fehler': ${stand.error?.error}",
            stand.error?.error?.contains("dauerhaft") == true
        )
        // Der Wecker bleibt trotzdem stehen: er klingelt in diesem Prozess, ihn zurueckzunehmen
        // waere schlechter als ihn zu behalten.
        verify(alarmUseCase).scheduleSystemAlarm(any())
        verify(alarmUseCase, never()).cancelSystemAlarm(any())
    }

    @Test
    fun `bei intakter Persistenz bleibt die Karte ohne Fehlertext`() = runTest {
        val viewModel = buildViewModel(ShiftConfig(definitions = listOf(fruehAlt)))
        advanceUntilIdle()
        viewModel.selectManualAlarmDate(zukunftstag)
        viewModel.selectManualAlarmShift(fruehAlt)
        advanceUntilIdle()

        viewModel.createManualAlarm()
        advanceUntilIdle()

        assertNull(viewModel.manualAlarmState.value.error)
        verify(alarmUseCase).scheduleSystemAlarm(any())
    }
}
