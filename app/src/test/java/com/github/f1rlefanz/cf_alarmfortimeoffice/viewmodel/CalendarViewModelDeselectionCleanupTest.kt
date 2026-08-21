package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
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
        stateHolder: CalendarStateHolder = CalendarStateHolder(),
        /** Simuliert einen defekten Konfigurations-Store (nicht: eine leere Konfiguration). */
        shiftConfigReadFails: Boolean = false,
        /**
         * Der dauerhafte Merker fuer einen offenen Raeumauftrag. Default: ein Mock, der jeden
         * Zugriff gelingen laesst - die Tests, die IHN pruefen, reichen einen eigenen herein.
         */
        pendingCleanupStore: PendingDeselectionCleanupStore = mock<PendingDeselectionCleanupStore>().apply {
            stub {
                onBlocking { pendingSince() } doReturn Result.success(null)
                onBlocking { markPending(any()) } doReturn Result.success(Unit)
                onBlocking { clearIfPending() } doReturn Result.success(Unit)
            }
        }
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

        val shiftConfigResult = if (shiftConfigReadFails) {
            Result.failure(IllegalStateException("Konfiguration unlesbar"))
        } else {
            Result.success(shiftConfig)
        }
        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn shiftConfigResult
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
            masterPausePrefs = masterPausePrefs,
            pendingDeselectionCleanupStore = pendingCleanupStore,
            // Nur fuer die stille Statuszeile - fuer diesen Test ohne Belang.
            feedNeueinlesenStore = FakeFeedNeueinlesenStore()
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

    // ------------------------------------------------------------------------------------------
    // REGRESSION (Review ueber Pruefrunde 8): Jeder fail-safe-Abbruch des Aufraeumens stellt genau
    // den Zustand wieder her, gegen den der Fix gebaut wurde - die Oberflaeche zeigt "kein
    // Kalender ausgewaehlt", waehrend bis zu 14 Tage lang Wecker des entfernten Dienstplans
    // klingeln. Bis hierher stand das ausschliesslich im Log. Ein Zustand, der eine Funktion
    // dauerhaft anhaelt, muss sichtbar sein, und es gibt keinen automatischen zweiten Anlauf:
    // beim naechsten App-Start ist die Auswahl von Anfang an leer, der Uebergangs-Merker greift
    // also nicht mehr.
    //
    // ERSTE FASSUNG UND IHRE WIDERLEGUNG: Die Meldung lief zunaechst ueber CalendarUiState.error -
    // "den EINZIGEN Fehlerkanal, den dieses ViewModel hat". Das Nachreview hat das widerlegt:
    // `error` ist ein Meldungspuffer, den MainContentScreen nach dem Anzeigen unbedingt mit
    // clearError() leert. Der Nutzer sah den Hinweis eine Snackbar-Laenge lang, danach war er
    // endgueltig weg - obwohl der Zustand unveraendert weiterbestand. Und der Knopf daneben
    // ("Wiederholen" -> refreshData()) laedt Kalender und Termine neu, ruehrt die verwaisten
    // Wecker aber nicht an, weil das Aufraeumen am Uebergangs-Merker haengt. Ein Zustand, der
    // Wecker verwaist zuruecklaesst, muss aber sichtbar BLEIBEN, und ein angebotener Knopf muss
    // wirken. Deshalb pruefen die Tests unten jetzt das Zustandsfeld
    // CalendarUiState.deselectionCleanupFailures und den zweiten Anlauf retryDeselectionCleanup().
    // ------------------------------------------------------------------------------------------

    /**
     * Der uiState ist ein `stateIn(SharingStarted.Lazily)` - ohne Sammler bleibt sein `value` auf
     * dem Initialwert stehen. Deshalb haengt sich der Test einen an, bevor er liest.
     */
    private fun kotlinx.coroutines.test.TestScope.observeUiState(viewModel: CalendarViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    @Test
    fun `bei nicht lesbarer Kalenderauswahl erfaehrt der Nutzer, dass Wecker klingeln koennen`() =
        runTest(dispatcher) {
            val alarmUseCase = mock<IAlarmUseCase>()
            alarmUseCase.stub {
                onBlocking { syncAlarms(any(), any()) } doReturn Result.success(emptyList())
            }
            val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
            observeUiState(viewModel)

            selectedIds.value = setOf("cal-a")
            advanceUntilIdle()

            selectionReadFails = true
            selectedIds.value = emptySet()
            advanceUntilIdle()

            assertEquals(
                "Der uebersprungene Aufraeum-Lauf muss beim Nutzer ankommen, nicht nur im Log",
                1,
                viewModel.uiState.value.deselectionCleanupFailures
            )
            // Der fluechtige Fehlerkanal wird dafuer NICHT mehr benutzt - er wird nach dem
            // Anzeigen geleert und koennte den Zustand nicht tragen.
            assertNull(viewModel.uiState.value.error)
        }

    @Test
    fun `bei nicht lesbarer Schicht-Konfiguration erfaehrt der Nutzer es`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doReturn Result.success(emptyList())
        }
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase, shiftConfigReadFails = true)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        // Nicht geraeumt (fail-safe, richtig so) - aber eben nicht stillschweigend.
        verify(alarmUseCase, never()).syncAlarms(eq(emptyList<CalendarEvent>()), any())
        assertEquals(1, viewModel.uiState.value.deselectionCleanupFailures)
    }

    @Test
    fun `ein fehlgeschlagenes Aufraeumen wird gemeldet, nicht nur geloggt`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            // Nur das Raeumen scheitert; das erste Anlegen aus den Events gelingt, sonst haenge
            // die Meldung womoeglich am falschen Vorgang.
            onBlocking { syncAlarms(any(), any()) } doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val passedEvents = invocation.arguments[0] as List<CalendarEvent>
                if (passedEvents.isEmpty()) {
                    Result.failure(IllegalStateException("Alarm liess sich nicht abbrechen"))
                } else {
                    Result.success(emptyList())
                }
            }
        }
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        assertEquals(
            "Vorbedingung: bis hierher ist nichts schiefgegangen",
            0,
            viewModel.uiState.value.deselectionCleanupFailures
        )

        selectedIds.value = emptySet()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.deselectionCleanupFailures)
        // Die Fehlermeldung darf das unmittelbar davor geplante Leeren der Terminliste nicht
        // verschlucken (updateLocalStateImmediate verwirft ein noch offenes Batch-Update) - sonst
        // stuenden die Termine des abgewaehlten Kalenders weiter auf dem Schirm.
        assertTrue(
            "Die Terminliste des abgewaehlten Kalenders muss geleert bleiben",
            viewModel.uiState.value.events.isEmpty()
        )
    }

    /**
     * Die Gegenprobe: eine Falschmeldung waere fast so schlimm wie gar keine - der Nutzer wuerde
     * Wecker suchen, die es nicht mehr gibt.
     */
    @Test
    fun `ein gelungenes Aufraeumen meldet keinen Fehler`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doReturn Result.success(emptyList())
        }
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        verify(alarmUseCase).syncAlarms(eq(emptyList<CalendarEvent>()), any())
        // Haelt zugleich fest, dass der Zeitstempel-Schreiber (AlarmMaintenanceService, hier mit
        // einem Context-Mock nicht erreichbar) den Erfolg NICHT in eine Fehlermeldung verwandelt:
        // die Wecker sind geraeumt, egal ob die Buchhaltung dahinter gelingt.
        assertNull(viewModel.uiState.value.error)
        assertEquals(0, viewModel.uiState.value.deselectionCleanupFailures)
    }

    /**
     * Der Text ist die eigentliche Zusicherung: er muss die FOLGE benennen und einen AUSWEG - und
     * keinen Begriff aus dem Code enthalten, mit dem ein Nutzer nichts anfangen kann.
     */
    @Test
    fun `die Meldung nennt Folge und Ausweg und keinen Systemnamen`() {
        val text = CalendarViewModel.DESELECTION_CLEANUP_FAILED_MESSAGE
        assertTrue("Folge fehlt", text.contains("klingeln"))
        assertTrue("Ausweg 'einzeln loeschen' fehlt", text.contains("einzeln"))
        listOf("Sync", "DataStore", "ShiftConfig", "Alarm-Sync", "Repository", "null").forEach {
            assertTrue("Systemname im Nutzertext: $it", !text.contains(it))
        }
    }

    /**
     * WIDERLEGTE FRUEHERE FASSUNG: Der Text nannte als Auswege "im Tab Wecker einzeln loeschen"
     * und "den Kalender noch einmal aus- und danach erneut abwaehlen" - waehrend der einzige
     * Knopf daneben "Wiederholen" hiess und `refreshData()` rief. Ein Text, der auf einen anders
     * beschrifteten Knopf verweist (oder auf gar keinen), schickt den Nutzer ins Leere.
     */
    @Test
    fun `der Text verweist woertlich auf die Beschriftung des angebotenen Knopfes`() {
        assertTrue(
            "Der genannte Weg muss die Knopfbeschriftung sein",
            CalendarViewModel.DESELECTION_CLEANUP_FAILED_MESSAGE
                .contains(CalendarViewModel.DESELECTION_CLEANUP_RETRY_ACTION)
        )
    }

    /**
     * DER KERN DES NACHREVIEWS: Der angebotene Knopf muss das Problem wirklich beheben.
     *
     * Ohne [CalendarViewModel.retryDeselectionCleanup] gab es fuer den Nutzer keinen zweiten
     * Anlauf - `refreshData()` laedt Kalender und Termine neu, das Aufraeumen haengt aber am
     * Uebergangs-Merker `hasSeenNonEmptySelection`, der im else-Zweig laengst zurueckgesetzt ist.
     */
    @Test
    fun `der zweite Anlauf raeumt die Wecker wirklich`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        var raeumenScheitert = true
        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val passedEvents = invocation.arguments[0] as List<CalendarEvent>
                when {
                    passedEvents.isNotEmpty() -> Result.success(emptyList())
                    raeumenScheitert -> Result.failure(IllegalStateException("Alarm liess sich nicht abbrechen"))
                    else -> Result.success(emptyList())
                }
            }
        }
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()
        assertEquals(
            "Vorbedingung: der erste Anlauf ist gescheitert",
            1,
            viewModel.uiState.value.deselectionCleanupFailures
        )

        // Der Nutzer tippt auf den Knopf an der Meldung.
        raeumenScheitert = false
        viewModel.retryDeselectionCleanup()
        advanceUntilIdle()

        // Zweimal geraeumt - der Retry hat den Lauf wirklich noch einmal angestossen.
        verify(alarmUseCase, times(2))
            .syncAlarms(eq(emptyList<CalendarEvent>()), any())
        assertEquals(
            "Nach dem gelungenen zweiten Anlauf ist der Zustand behoben",
            0,
            viewModel.uiState.value.deselectionCleanupFailures
        )
    }

    /**
     * Scheitert auch der zweite Anlauf, darf die Meldung nicht verstummen. Der Zaehler steigt -
     * genau daran haengt in MainContentScreen das erneute Anzeigen.
     */
    @Test
    fun `ein gescheiterter zweiter Anlauf meldet sich erneut`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val passedEvents = invocation.arguments[0] as List<CalendarEvent>
                if (passedEvents.isEmpty()) {
                    Result.failure(IllegalStateException("Alarm liess sich nicht abbrechen"))
                } else {
                    Result.success(emptyList())
                }
            }
        }
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        viewModel.retryDeselectionCleanup()
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.deselectionCleanupFailures)
    }

    /**
     * DIE GEGENPROBE ZUM RETRY: Er umgeht den Uebergangs-Merker, NICHT die Regel "leer ist keine
     * Loeschgrundlage". Ist die Auswahl gerade nicht lesbar, wird auch auf Knopfdruck nicht
     * geraeumt - sonst waere der Knopf ein Weg, Wecker aufgrund eines Lesefehlers zu loeschen.
     */
    @Test
    fun `der zweite Anlauf raeumt NICHT, wenn die Auswahl nicht lesbar ist`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectionReadFails = true
        selectedIds.value = emptySet()
        advanceUntilIdle()

        viewModel.retryDeselectionCleanup()
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(eq(emptyList<CalendarEvent>()), any())
        assertEquals(2, viewModel.uiState.value.deselectionCleanupFailures)
    }

    /**
     * Waehlt der Nutzer wieder einen Kalender an, sind die Wecker gedeckt (der Delta-Sync des
     * Ladevorgangs raeumt jeden Alarm ohne Termin). Ein stehengebliebener Hinweis waere dann eine
     * Falschmeldung - fast so schlimm wie gar keine.
     */
    @Test
    fun `eine neue Kalenderauswahl loest den Hinweis auf`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val passedEvents = invocation.arguments[0] as List<CalendarEvent>
                if (passedEvents.isEmpty()) {
                    Result.failure(IllegalStateException("Alarm liess sich nicht abbrechen"))
                } else {
                    Result.success(emptyList())
                }
            }
        }
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase)
        observeUiState(viewModel)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.deselectionCleanupFailures)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.deselectionCleanupFailures)
    }
}
