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
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.EventPage
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

/**
 * Prueft die VERDRAHTUNG des schwersten Befunds der zweiten Pruefrunde - nicht nur die
 * Entscheidungslogik (die haelt [CalendarViewModelAlarmSyncCompletenessTest] als reine Funktion
 * fest), sondern dass `loadEventsForSelectedCalendars()` sie auch wirklich anwendet.
 *
 * DER FEHLER: Der Vordergrund-Ladevorgang laeuft lazy und holt pro Kalender nur die ersten 10
 * Events. Genau diese abgeschnittene Liste ging an `AlarmUseCase.syncAlarms()`, dessen Delta-Sync
 * jeden Alarm entfernt, dessen eventId darin fehlt - "Termin geloescht" und "Termin lag hinter dem
 * Praefix" sind auf der Liste nicht mehr unterscheidbar. Bei mehr als zehn Schichten in 14 Tagen
 * (fuer einen Schichtplan der Normalfall) loeschte damit JEDES App-Oeffnen die spaetesten Wecker.
 *
 * Der Test faehrt den echten Pfad: `observeCalendarSelection()` im `init{}` reagiert auf die
 * Kalenderauswahl und startet den Ladevorgang selbst.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class CalendarViewModelSyncWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setUp() {
        // viewModelScope laeuft auf Dispatchers.Main.immediate - ohne Main-Dispatcher scheitert
        // schon die Konstruktion (uiState startet ein stateIn im Property-Initializer).
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // selectedIds wird bewusst NICHT zurueckgesetzt: JUnit legt pro Testmethode eine neue
        // Instanz dieser Klasse an, das Feld ist also ohnehin frisch. Ein Write NACH
        // resetMain() wuerde dagegen die noch haengenden Collector-Continuations auf den
        // abgeraeumten Main-Dispatcher schicken - eine DispatchException, die wie ein
        // Fehler in der Sache aussieht.
        Dispatchers.resetMain()
    }

    private fun event(id: String, hour: Int) = CalendarEvent(
        id = id,
        title = "Schicht $id",
        startTime = LocalDateTime.of(2026, 8, 20, 0, 0).plusHours(hour.toLong()),
        endTime = LocalDateTime.of(2026, 8, 20, 0, 0).plusHours(hour + 8L),
        calendarId = "cal-a"
    )

    /**
     * @param totalEvents Was die Bridge zwischen Anzeige und Wahrheit ausmacht: der volle
     *   14-Tage-Bestand. Ist er groesser als die gelieferte Seite, ist die Liste ein Praefix.
     */
    private fun buildViewModel(
        pageEvents: List<CalendarEvent>,
        totalEvents: Int,
        completeFetch: CalendarFetchOutcome? = null,
        alarmUseCase: IAlarmUseCase = mock(),
        stateHolder: CalendarStateHolder = CalendarStateHolder(),
        calendarUseCase: ICalendarUseCase = mock()
    ): CalendarViewModel {
        calendarUseCase.stub {
            onBlocking { hasValidAccessToken() } doReturn true
            onBlocking { getCalendarEventsLazy(any(), any(), any()) } doReturn Result.success(
                EventPage(
                    events = pageEvents,
                    offset = 0,
                    maxEvents = 10,
                    totalEvents = totalEvents,
                    hasMore = totalEvents > pageEvents.size
                )
            )
            onBlocking { getCalendarEventsWithStatus(any(), any()) } doReturn
                (completeFetch?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("nicht gestubbt")))
        }

        val selectionRepository = mock<ICalendarSelectionRepository>()
        whenever(selectionRepository.selectedCalendarIds).thenReturn(selectedIds)
        selectionRepository.stub {
            onBlocking { getCurrentSelectedCalendarIds() } doReturn Result.success(setOf("cal-a"))
        }

        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
        }

        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub {
            onBlocking { pausedNow() } doReturn false
        }

        // Kein offener Raeumauftrag: dieser Test beobachtet die Alarm-Verdrahtung des
        // Ladevorgangs, nicht das Aufraeumen nach einer Abwahl.
        val pendingCleanupStore = mock<PendingDeselectionCleanupStore>()
        pendingCleanupStore.stub {
            onBlocking { pendingSince() } doReturn Result.success(null)
            onBlocking { markPending(any()) } doReturn Result.success(Unit)
            onBlocking { clearIfPending() } doReturn Result.success(Unit)
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

    @Test
    fun `gekuerztes Praefix ohne nachforderbare Liste loest KEINEN Alarm-Sync aus`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        // 10 gelieferte Events, aber 13 im Bestand -> Praefix. Das Nachfordern scheitert
        // (getCalendarEventsWithStatus nicht gestubbt -> failure), es gibt also keine
        // nachweislich vollstaendige Liste.
        buildViewModel(
            pageEvents = (0 until 10).map { event("A$it", it) },
            totalEvents = 13,
            alarmUseCase = alarmUseCase
        )

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }

    @Test
    fun `gekuerztes Praefix synchronisiert mit der NACHGEFORDERTEN vollstaendigen Liste`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        val complete = (0 until 13).map { event("A$it", it) }
        buildViewModel(
            pageEvents = complete.take(10),
            totalEvents = 13,
            completeFetch = CalendarFetchOutcome(complete, requestedCalendars = 1),
            alarmUseCase = alarmUseCase
        )

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        // Synchronisiert wird mit ALLEN 13, nie mit den 10 der Anzeige.
        verify(alarmUseCase).syncAlarms(eq(complete), any())
    }

    @Test
    fun `ein Teilerfolg beim Nachfordern loest KEINEN Alarm-Sync aus`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        val partial = (0 until 11).map { event("A$it", it) }
        buildViewModel(
            pageEvents = partial.take(10),
            totalEvents = 13,
            // Ein Kalender hat nicht geantwortet - die Liste sieht vollstaendig aus, ist es aber nicht.
            completeFetch = CalendarFetchOutcome(partial, requestedCalendars = 2, failedCalendarIds = setOf("kaputt")),
            alarmUseCase = alarmUseCase
        )

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }

    @Test
    fun `vollstaendige Liste synchronisiert direkt und weist den StateHolder als vollstaendig aus`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        val holder = CalendarStateHolder()
        val all = (0 until 4).map { event("A$it", it) }
        buildViewModel(
            pageEvents = all,
            totalEvents = 4, // nichts abgeschnitten
            alarmUseCase = alarmUseCase,
            stateHolder = holder
        )

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        verify(alarmUseCase).syncAlarms(eq(all), any())
        assertTrue(
            "Der Holder muss als vollstaendig gelten - ShiftViewModel gibt seine Liste an syncAlarms weiter",
            holder.eventsComplete.value
        )
    }

    @Test
    fun `Abwaehlen ALLER Kalender ueberholt einen laufenden Ladevorgang`() = runTest(dispatcher) {
        // REGRESSION: Der Race-Guard beruht darauf, dass ein ueberholender Vorgang die
        // eventLoadGeneration hochzaehlt. Der else-Zweig von observeCalendarSelection ("Auswahl
        // ist jetzt leer") loeschte Events und StateHolder, zog aber KEINE neue Nummer - ein noch
        // laufender Ladevorgang bestand danach beide Staleness-Pruefungen, schrieb seine Events
        // zurueck und legte ueber syncAlarms() Wecker fuer genau die Kalender an, die der Nutzer
        // soeben abgewaehlt hatte. Die Oberflaeche zeigte dabei korrekt "kein Kalender ausgewaehlt".
        val alarmUseCase = mock<IAlarmUseCase>()
        val holder = CalendarStateHolder()
        val all = (0 until 4).map { event("A$it", it) }
        buildViewModel(
            pageEvents = all,
            totalEvents = 4,
            alarmUseCase = alarmUseCase,
            stateHolder = holder
        )

        // Ladevorgang anstossen und SOFORT wieder abwaehlen - der Lauf ist noch unterwegs.
        selectedIds.value = setOf("cal-a")
        selectedIds.value = emptySet()
        advanceUntilIdle()

        verify(alarmUseCase, never()).syncAlarms(any(), any())
        assertTrue(
            "Nach dem Abwaehlen darf kein zurueckkehrender Ladevorgang die Events wieder einsetzen",
            holder.events.value.isEmpty()
        )
        assertFalse(holder.eventsComplete.value)
    }

    @Test
    fun `Nachladen bleibt nach einem dazwischenkommenden Sofort-Update weiter moeglich`() = runTest(dispatcher) {
        // REGRESSION: Der Erfolgs-Reset von isLoadingMoreEvents lief ueber den GEBATCHTEN Pfad
        // (updateLocalState, 16-33 ms Verzoegerung). Jeder dazwischenkommende
        // updateLocalStateImmediate-Aufruf cancelt diesen Job und verwirft das Pending ersatzlos -
        // das Flag blieb fuer die Lebensdauer des ViewModels auf true, und die Wache am Anfang von
        // loadMoreEvents() liess danach KEINEN weiteren Nachlade-Versuch mehr zu (Dauer-Spinner,
        // weitere Events ohne App-Neustart unerreichbar).
        val all = (0 until 4).map { event("A$it", it) }
        val vm = buildViewModel(
            pageEvents = all,
            totalEvents = 12 // hasMore = true, damit Nachladen ueberhaupt sinnvoll ist
        )
        // uiState ist ein `stateIn(started = Lazily)` - ohne Sammler bleibt es auf dem Startwert.
        backgroundScope.launch { vm.uiState.collect { } }
        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        vm.loadMoreEvents()
        // NUR so weit vorspulen, dass die (sofort antwortende) Abfrage zurueckkehrt - aber noch
        // NICHT ueber das 16-33ms-Batch-Fenster hinaus. Genau in dieser Luecke lag der Fehler.
        advanceTimeBy(5)

        // Das Sofort-Update, das den gebatchten Reset ersatzlos verworfen hat.
        vm.refreshData(forceRefresh = true)
        advanceUntilIdle()

        assertFalse(
            "isLoadingMoreEvents muss zurueckgesetzt sein - bleibt es haengen, laesst die Wache " +
                "am Anfang von loadMoreEvents() keinen weiteren Versuch mehr zu (Dauer-Spinner)",
            vm.uiState.value.isLoadingMoreEvents
        )
    }

    @Test
    fun `bei gekuerztem Praefix wird der StateHolder NICHT als vollstaendig ausgewiesen`() = runTest(dispatcher) {
        val holder = CalendarStateHolder()
        buildViewModel(
            pageEvents = (0 until 10).map { event("A$it", it) },
            totalEvents = 13,
            stateHolder = holder
        )

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        assertFalse(
            "Sonst gibt ShiftViewModel bei der naechsten Konfigurationsaenderung das Praefix an " +
                "syncAlarms() - und die spaetesten Wecker werden geloescht",
            holder.eventsComplete.value
        )
    }
}
