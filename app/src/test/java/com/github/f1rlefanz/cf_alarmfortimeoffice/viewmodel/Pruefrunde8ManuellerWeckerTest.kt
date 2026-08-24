package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepoTestContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.AlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.tagFreigabeUseCaseOhneFreigaben
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
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
 * Erfolg, wenn nur der Arbeitsspeicher beschrieben wurde. Zwei Wege fuehren dorthin: die Sperre
 * nach einem gescheiterten Init-Load - und ein geworfener Schreibvorgang (voller Speicher,
 * IOException, beschaedigte Datei). Der Wecker klingelt dann in diesem Prozess, steht aber weder
 * in der Preferences-Datei noch im Direct-Boot-Spiegel - nach Prozesstod oder Neustart ist er
 * spurlos weg, und fuer einen manuellen Wecker endgueltig. Der Nutzer muss das erfahren.
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

    /**
     * @param repositorium standardmaessig eine Attrappe mit heiler Persistenz. Die BEFUND-11-Tests
     *   reichen hier den ECHTEN [AlarmRepository] herein - siehe die Begruendung dort.
     */
    private fun buildViewModel(
        start: ShiftConfig,
        repositorium: IAlarmRepository = heilesRepositorium()
    ): AlarmViewModel {
        konfiguration = MutableStateFlow(start)
        alarmRepository = repositorium

        alarmUseCase = mock<IAlarmUseCase>()
        whenever(alarmUseCase.activeAlarms).thenReturn(flowOf(emptyList()))
        alarmUseCase.stub {
            on { getAllAlarms() } doReturn Result.success(emptyList())
            // DURCHREICHEN statt festverdrahtetem Erfolg: `AlarmUseCase.saveAlarm()` tut im
            // Original genau das (eine Zeile Delegation). Nur so entsteht der Fehlschlag im Test
            // dort, wo er auch in der App entsteht - im Schreibweg des Repositoriums.
            on { saveAlarm(any()) } doSuspendableAnswer { aufruf ->
                alarmRepository.saveAlarm(aufruf.getArgument<AlarmInfo>(0))
            }
            on { scheduleSystemAlarm(any()) } doReturn Result.success(Unit)
            on { cancelSystemAlarm(any()) } doReturn Result.success(Unit)
            on { deleteAlarm(any()) } doReturn Result.success(Unit)
        }

        val skipUseCase = mock<IAlarmSkipUseCase>()
        whenever(skipUseCase.skipStatusFlow).thenReturn(flowOf(AlarmSkipState()))
        skipUseCase.stub {
            on { getSkipStatus() } doReturn Result.success(AlarmSkipState())
        }

        shiftUseCase = mock<IShiftUseCase>()
        whenever(shiftUseCase.shiftConfig).thenReturn(konfiguration)
        shiftUseCase.stub {
            // Der Einmal-Read im Erstellungspfad sieht denselben Stand wie der Flow.
            on { getCurrentShiftConfig() } doReturn Result.success(konfiguration.value)
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
            alarmRepository = alarmRepository
        )
    }

    /** Attrappe fuer alle Tests, die mit der Persistenz nichts zu tun haben. */
    private fun heilesRepositorium(): IAlarmRepository = mock<IAlarmRepository>().apply {
        stub {
            on { isPersistenceBlocked() } doReturn false
            on { istLetzterSchreibvorgangGescheitert() } doReturn false
            on { saveAlarm(any()) } doReturn Result.success(Unit)
        }
    }

    /**
     * Das ECHTE Repositorium gegen einen Speicher, der wirklich wirft oder wirklich defekt ist.
     *
     * Der Init-Load wird hier gleich abgewartet (`getAllAlarms()`), damit danach alles auf dem
     * Test-Dispatcher laeuft - das Repositorium laedt sonst nebenlaeufig auf `Dispatchers.IO`,
     * und `advanceUntilIdle()` wuesste davon nichts.
     */
    private suspend fun echtesRepositorium(store: DataStore<Preferences>): IAlarmRepository {
        val repo = AlarmRepository(store, mock<DirectBootAlarmStore>(), AlarmRepoTestContext.unlocked())
        repo.getAllAlarms()
        return repo
    }

    /** Lesen geht, JEDER Schreibversuch wirft - so sieht ein voller Speicher aus. */
    private class NurLesbarerSpeicher : DataStore<Preferences> {
        private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("kein Platz auf dem Geraet")
    }

    /** Schreiben UND Lesen gehen - die Gegenprobe. */
    private class HeilerSpeicher : DataStore<Preferences> {
        private val flow = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data: Flow<Preferences> = flow
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val neu = transform(flow.value)
            flow.value = neu
            return neu
        }
    }

    /**
     * Aendert NUR den Einmal-Read, ohne Flow-Emission.
     *
     * Damit bleibt `selectedShift` im Zustand das ALTE Objekt - nur so prueft der Test wirklich
     * die frische Aufloesung im Erstellungspfad und nicht bloss den reaktiven Collector.
     */
    private fun nurEinmalReadAendern(neu: ShiftConfig) {
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(neu)
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

    /**
     * WARUM DIESER TEST NEU GESCHRIEBEN WURDE.
     *
     * Sein erster Wurf stellte einer Attrappe `isPersistenceBlocked() = true` ein und pruefte dann
     * nach, dass die Karte einen Fehler zeigt - also genau die Frage, die der Fix stellt. Er war
     * damit tautologisch und hat den eigentlichen Defekt zementiert: `persistToDataStore()`
     * scheitert in ZWEI Faellen, aber nur der erste setzte die Sperre. Ein geworfener
     * Schreibvorgang (voller Speicher, IOException, beschaedigte Datei) blieb stumm, obwohl
     * `saveAlarm()` unmittelbar davor "liegt NUR im Arbeitsspeicher" geloggt hat - die Attrappe
     * konnte das gar nicht zeigen, weil sie den Schreibweg nicht hatte.
     *
     * Deshalb laeuft hier jetzt der ECHTE [AlarmRepository] gegen einen Speicher, dessen
     * Schreibvorgang wirklich wirft.
     */
    @Test
    fun `ein am Schreibfehler gescheiterter Wecker wird als nicht dauerhaft gemeldet`() = runTest {
        // OHNE DEN FIX: saveAlarm meldet Erfolg, die Karte zeigt den Wecker, und nach dem
        // naechsten Prozesstod ist er weg - ohne dass je ein Text darauf hingewiesen haette.
        val viewModel = buildViewModel(
            ShiftConfig(definitions = listOf(fruehAlt)),
            repositorium = echtesRepositorium(NurLesbarerSpeicher())
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
        // Gegenprobe mit demselben echten Repositorium - sonst erschiene die Warnung bei JEDEM
        // manuellen Wecker und wuerde von niemandem mehr ernst genommen.
        val viewModel = buildViewModel(
            ShiftConfig(definitions = listOf(fruehAlt)),
            repositorium = echtesRepositorium(HeilerSpeicher())
        )
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
