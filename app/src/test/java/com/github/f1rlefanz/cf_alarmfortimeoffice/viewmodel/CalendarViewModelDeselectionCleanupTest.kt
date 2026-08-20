package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.EventPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

/**
 * BEFUND 4 (Pruefrunde 8): Waehlt der Nutzer den LETZTEN Kalender ab, blieb kein einziger Wecker
 * auf der Strecke - und es gab danach auch keinen Pfad mehr, der es je nachgeholt haette.
 *
 * DER FEHLER: Der else-Zweig von `observeCalendarSelection()` leerte nur Anzeige und
 * `CalendarStateHolder` und rief KEIN `syncAlarms()`. Alle Hintergrundketten steigen bei leerer
 * Auswahl (6h-Wartung, Pre-Alarm-Worker) bzw. leerer Eventliste (ShiftViewModel) VOR dem Sync
 * aus, der `BootReceiver` armiert die gespeicherten Alarme sogar aktiv neu. Ergebnis: Die
 * Oberflaeche zeigte "kein Kalender ausgewaehlt" und null Termine, waehrend das Geraet bis zu
 * 14 Tage weiter nach dem entfernten Dienstplan weckte und dimmte.
 *
 * DIE GEGENPROBE, die diese Tests genauso festhalten: "leer" ist fuer diese App die
 * gefaehrlichste Luege. Geraeumt werden darf NUR bei einer ausdruecklichen Abwahl - nicht beim
 * leeren Startwert des noch nicht hydrierten Auswahl-StateFlows und nicht, wenn die Auswahl
 * gerade nicht lesbar ist.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class CalendarViewModelDeselectionCleanupTest {

    private val dispatcher = StandardTestDispatcher()
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Simuliert einen Lesefehler des Auswahl-Speichers (nicht: eine leere Auswahl). */
    private var selectionReadFails = false

    /** Simuliert, dass der Speicher entgegen dem StateFlow weiterhin Kalender meldet. */
    private var persistedSelectionOverride: Set<String>? = null

    @Before
    fun setUp() {
        // viewModelScope laeuft auf Dispatchers.Main.immediate - ohne Main-Dispatcher scheitert
        // schon die Konstruktion (uiState startet ein stateIn im Property-Initializer).
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // selectedIds bewusst NICHT zuruecksetzen - ein Write NACH resetMain() schickt haengende
        // Collector-Continuations auf den abgeraeumten Main-Dispatcher (siehe
        // CalendarViewModelSyncWiringTest).
        Dispatchers.resetMain()
    }

    private fun event(id: String, hour: Int) = CalendarEvent(
        id = id,
        title = "Schicht $id",
        startTime = LocalDateTime.of(2026, 8, 20, 0, 0).plusHours(hour.toLong()),
        endTime = LocalDateTime.of(2026, 8, 20, 0, 0).plusHours(hour + 8L),
        calendarId = "cal-a"
    )

    private val events = (0 until 4).map { event("A$it", it) }

    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase = mock(),
        shiftConfig: ShiftConfig = ShiftConfig(),
        masterPaused: Boolean = false,
        stateHolder: CalendarStateHolder = CalendarStateHolder()
    ): CalendarViewModel {
        val calendarUseCase = mock<ICalendarUseCase>()
        calendarUseCase.stub {
            onBlocking { hasValidAccessToken() } doReturn true
            onBlocking { getCalendarEventsLazy(any(), any(), any()) } doReturn Result.success(
                EventPage(
                    events = events,
                    offset = 0,
                    maxEvents = 10,
                    totalEvents = events.size, // nichts abgeschnitten -> vollstaendig
                    hasMore = false
                )
            )
            // Wird bei vollstaendiger Liste nicht gebraucht - bewusst trotzdem gestubbt, damit ein
            // ungestubbter Aufruf nicht als NullPointerException auftaucht.
            onBlocking { getCalendarEventsWithStatus(any(), any()) } doReturn
                Result.failure(IllegalStateException("nicht gestubbt"))
        }

        val selectionRepository = mock<ICalendarSelectionRepository>()
        whenever(selectionRepository.selectedCalendarIds).thenReturn(selectedIds)
        selectionRepository.stub {
            // Die Rueckfrage des ViewModels geht bewusst an den SPEICHER, nicht an den StateFlow -
            // nur so laesst sich "wirklich nichts mehr ausgewaehlt" von "nicht lesbar" trennen.
            onBlocking { getCurrentSelectedCalendarIds() } doAnswer {
                when {
                    selectionReadFails -> Result.failure(IllegalStateException("DataStore unlesbar"))
                    persistedSelectionOverride != null -> Result.success(persistedSelectionOverride!!)
                    else -> Result.success(selectedIds.value)
                }
            }
        }

        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(shiftConfig)
        }

        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub {
            onBlocking { pausedNow() } doReturn masterPaused
        }

        return CalendarViewModel(
            appContext = mock<Context>(),
            calendarUseCase = calendarUseCase,
            calendarSelectionRepository = selectionRepository,
            calendarStateHolder = stateHolder,
            errorHandler = mock<ErrorHandler>(),
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            masterPausePrefs = masterPausePrefs
        )
    }

    /**
     * DER KERNTEST. Ohne den Fix wird `syncAlarms` nach dem Abwaehlen kein zweites Mal gerufen -
     * die Wecker des entfernten Kalenders bleiben stehen.
     */
    @Test
    fun `Abwaehlen des letzten Kalenders raeumt die kalenderbasierten Wecker`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        buildViewModel(alarmUseCase = alarmUseCase)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        // Vorbedingung: aus dem Kalender sind Wecker entstanden.
        verify(alarmUseCase).syncAlarms(eq(events), any())

        selectedIds.value = emptySet()
        advanceUntilIdle()

        // Die leere Liste ist hier die Nutzerentscheidung: syncAlarms raeumt in diesem Zweig die
        // kalenderbasierten Alarme UND die Schichtspannen (Dimmer-/DND-Fenster), schont aber
        // manuelle Wecker (keepManualAlarms).
        verify(alarmUseCase).syncAlarms(eq(emptyList<CalendarEvent>()), any())
    }

    /**
     * DIE GEFAEHRLICHE LEERE: Der Auswahl-StateFlow startet auf emptySet() und wird erst durch
     * einen unabgewarteten Collector befuellt. Wuerde dieser Startwert als Abwahl gelten, raeumte
     * JEDER App-Start alle kalenderbasierten Wecker weg, bevor die Auswahl gelesen ist.
     */
    @Test
    fun `der leere Startwert der Kalenderauswahl raeumt KEINE Wecker`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        buildViewModel(alarmUseCase = alarmUseCase)

        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }

    /**
     * Zweites Netz: ist die Auswahl gerade nicht LESBAR, ist das kein Beleg fuer "nichts
     * ausgewaehlt". Im Zweifel bleiben die Wecker stehen.
     */
    @Test
    fun `bei nicht lesbarer Kalenderauswahl wird NICHT geraeumt`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        buildViewModel(alarmUseCase = alarmUseCase)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        selectionReadFails = true
        selectedIds.value = emptySet()
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(eq(emptyList<CalendarEvent>()), any())
    }

    /**
     * Meldet der Speicher weiterhin Kalender, ist die Leere des StateFlows nicht die Wahrheit
     * (z.B. ein verworfener Read aus gesperrtem Storage) - dann wird nicht geraeumt.
     */
    @Test
    fun `meldet der Speicher weiterhin Kalender wird NICHT geraeumt`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        buildViewModel(alarmUseCase = alarmUseCase)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        persistedSelectionOverride = setOf("cal-a")
        selectedIds.value = emptySet()
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(eq(emptyList<CalendarEvent>()), any())
    }

    /**
     * MANUELLE WECKER: Bei abgeschalteter Automatik nimmt `syncAlarms()` einen ANDEREN Zweig -
     * `clearInternalAlarms(alsoCancelPendingSnoozes = true)` OHNE keepManualAlarms, das also auch
     * manuelle Wecker loescht. Die stammen nicht aus dem Kalender und duerfen eine Kalender-Abwahl
     * ueberleben; deshalb laeuft dieser Pfad bei ausgeschalteter Automatik gar nicht erst an
     * (kalenderbasierte Wecker gibt es dort ohnehin keine).
     */
    @Test
    fun `Abwahl bei abgeschalteter Automatik ruft keinen Sync und gefaehrdet keine manuellen Wecker`() =
        runTest(dispatcher) {
            val alarmUseCase = mock<IAlarmUseCase>()
            buildViewModel(alarmUseCase = alarmUseCase, shiftConfig = ShiftConfig(autoAlarmEnabled = false))

            selectedIds.value = setOf("cal-a")
            advanceUntilIdle()
            selectedIds.value = emptySet()
            advanceUntilIdle()

            verify(alarmUseCase, never()).syncAlarms(any(), any())
        }

    /**
     * Waehrend der Master-Pause sind ohnehin keine Wecker gesetzt. Ein Sync wuerde ueber den
     * zentralen Backstop zusaetzlich einen schwebenden Snooze abbrechen - eine Nebenwirkung, die
     * eine Kalender-Abwahl nicht haben soll.
     */
    @Test
    fun `Abwahl waehrend der Master-Pause loest keinen Sync aus`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        buildViewModel(alarmUseCase = alarmUseCase, masterPaused = true)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }

    /**
     * Schnelles Ab- und sofortiges Wiederanwaehlen desselben Kalenders darf die eben angelegten
     * Wecker nicht kosten. Doppelt abgesichert: der Auswahl-StateFlow konflatiert die
     * Zwischenstellung weg, und selbst wenn der else-Zweig liefe, traegt sein Aufraeumen eine
     * inzwischen ueberholte Generation und bricht vor `syncAlarms()` ab.
     */
    @Test
    fun `schnelles Ab- und Wiederanwaehlen kostet keine Wecker`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        buildViewModel(alarmUseCase = alarmUseCase)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        // Abwaehlen und im selben Zug wieder anwaehlen: der Collector sieht (StateFlow-konflation)
        // nur den letzten Wert - und selbst wenn er beide saehe, traegt das Raeumen eine
        // ueberholte Generation.
        selectedIds.value = emptySet()
        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(eq(emptyList<CalendarEvent>()), any())
    }
}
