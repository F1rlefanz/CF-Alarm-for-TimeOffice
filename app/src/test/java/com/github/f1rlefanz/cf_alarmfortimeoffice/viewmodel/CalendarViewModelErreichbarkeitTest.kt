package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarPage
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

/**
 * Prueft die VERDRAHTUNG von "offline ist kein Zugriffsverlust" im [CalendarViewModel] - die
 * Entscheidung selbst halten [CalendarViewModelTest] (reine Funktionen) fest, hier geht es darum,
 * dass `loadEventsForSelectedCalendars()` und `loadAvailableCalendars()` sie auch anwenden.
 *
 * Hergang (30.09.2026, am Fairphone): Flugmodus plus Abgleich ergab "Kalender-Autorisierung
 * verloren". Eine Zeile weniger im Ladevorgang (das Mitzaehlen, ob ALLE Fehlschlaege
 * netzbedingt waren) und ein 401 erschiene als "nicht erreichbar" - das faengt nur ein Test an
 * dieser Stelle.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class CalendarViewModelErreichbarkeitTest {

    private val dispatcher = StandardTestDispatcher()
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Was der naechste Terminabruf liefert - zwischen zwei Ladevorgaengen umstellbar. */
    private var terminAbruf: Result<EventPage> = Result.success(EventPage(emptyList(), 0, false))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // selectedIds bewusst NICHT zuruecksetzen - Begruendung in CalendarViewModelSyncWiringTest.
        Dispatchers.resetMain()
    }

    private val einTermin = CalendarEvent(
        id = "A1",
        title = "Fruehdienst",
        startTime = LocalDateTime.of(2026, 10, 1, 6, 0),
        endTime = LocalDateTime.of(2026, 10, 1, 14, 0),
        calendarId = "cal-a"
    )

    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase = mock(),
        calendarUseCase: ICalendarUseCase = mock()
    ): CalendarViewModel {
        calendarUseCase.stub {
            on { hasValidAccessToken() } doReturn true
            onBlocking { getCalendarEventsLazy(any(), any(), any()) } doAnswer { terminAbruf }
        }

        val selectionRepository = mock<ICalendarSelectionRepository>()
        whenever(selectionRepository.selectedCalendarIds).thenReturn(selectedIds)
        selectionRepository.stub {
            on { getCurrentSelectedCalendarIds() } doReturn Result.success(setOf("cal-a"))
        }

        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            on { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
        }

        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub {
            on { pausedNow() } doReturn false
        }

        val pendingCleanupStore = mock<PendingDeselectionCleanupStore>()
        pendingCleanupStore.stub {
            on { pendingSince() } doReturn Result.success(null)
            on { markPending(any()) } doReturn Result.success(Unit)
            on { clearIfPending() } doReturn Result.success(Unit)
        }

        val errorHandler = mock<ErrorHandler>()
        whenever(errorHandler.getErrorMessage(any())).thenReturn("Fehlertext")

        return CalendarViewModel(
            appContext = mock<Context>(),
            calendarUseCase = calendarUseCase,
            calendarSelectionRepository = selectionRepository,
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = errorHandler,
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            masterPausePrefs = masterPausePrefs,
            pendingDeselectionCleanupStore = pendingCleanupStore,
            feedNeueinlesenStore = FakeFeedNeueinlesenStore()
        )
    }

    @Test
    fun `Terminabruf scheitert nur an der Verbindung - nicht erreichbar, Zugriff bleibt gueltig`() = runTest(dispatcher) {
        val alarmUseCase = mock<IAlarmUseCase>()
        terminAbruf = Result.failure(AppError.NetworkError("No internet connection"))
        val vm = buildViewModel(alarmUseCase = alarmUseCase)
        backgroundScope.launch { vm.uiState.collect { } }

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("Ein Funkloch belegt keinen verlorenen Zugriff", state.calendarAuthorizationValid)
        assertTrue("Der Zustand muss sichtbar werden", state.kalenderNichtErreichbar)
        verify(alarmUseCase, never()).syncAlarms(any(), any())
    }

    @Test
    fun `Terminabruf scheitert an der Anmeldung - weiterhin Zugriffsverlust`() = runTest(dispatcher) {
        terminAbruf = Result.failure(AppError.AuthenticationError("Google Calendar authentication failed"))
        val vm = buildViewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(
            "Ein 401 muss weiter zum Knopf \"Kalender-Zugriff erneuern\" fuehren",
            state.calendarAuthorizationValid
        )
        assertFalse(state.kalenderNichtErreichbar)
    }

    @Test
    fun `Kalender gibt es nicht mehr - nicht abrufbar statt Zugriffsverlust`() = runTest(dispatcher) {
        // 404 heisst bei der Calendar-API: geloescht oder nicht mehr freigegeben. Frueher wurde
        // daraus "Kalender-Autorisierung verloren" - ein Erneuern haette nichts geaendert.
        terminAbruf = Result.failure(
            AppError.PermissionError(message = "Kalender nicht gefunden oder nicht mehr freigegeben")
        )
        val vm = buildViewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.calendarAuthorizationValid)
        assertFalse(state.kalenderNichtErreichbar)
        assertTrue(
            "Der fehlende Kalender muss beim Namen auftauchen koennen (Status-Tab, Entfernen)",
            state.unavailableCalendarIds == setOf("cal-a")
        )
        assertTrue("Die Uebersicht braucht den ausdruecklichen Totalausfall-Merker", state.alleKalenderFehlen)
    }

    @Test
    fun `Abruflimit ist kein fehlender Kalender`() = runTest(dispatcher) {
        // Ein 403 "rateLimitExceeded" kommt aus dem Repository als NetworkError - nicht als
        // "Kalender nicht gefunden" mit dem Angebot, ihn zu entfernen.
        terminAbruf = Result.failure(AppError.NetworkError("Google Calendar voruebergehend begrenzt: Forbidden"))
        val vm = buildViewModel()
        backgroundScope.launch { vm.uiState.collect { } }

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.alleKalenderFehlen)
        assertTrue(state.unavailableCalendarIds.isEmpty())
        assertTrue(state.kalenderNichtErreichbar)
    }

    @Test
    fun `der naechste gelungene Abruf nimmt nicht erreichbar zurueck`() = runTest(dispatcher) {
        terminAbruf = Result.failure(AppError.NetworkError("No internet connection"))
        val vm = buildViewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.kalenderNichtErreichbar)

        terminAbruf = Result.success(EventPage(listOf(einTermin), totalEvents = 1, hasMore = false))
        vm.loadEventsForSelectedCalendars()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("Wieder online muss der Hinweis verschwinden", state.kalenderNichtErreichbar)
        assertTrue(state.calendarAuthorizationValid)
    }

    @Test
    fun `Kalenderliste scheitert nur an der Verbindung - kein Freigabe-Problem`() = runTest(dispatcher) {
        // CalendarSelectionScreen leitet aus hasValidToken = false "Kalender-Zugriff nicht
        // freigegeben" samt "Kalender-Zugriff erlauben" ab - offline die falsche Diagnose.
        val calendarUseCase = mock<ICalendarUseCase>()
        calendarUseCase.stub {
            onBlocking { getAvailableCalendarsPaginated(any(), any()) } doReturn
                Result.failure<CalendarPage>(AppError.NetworkError("No internet connection"))
        }
        val vm = buildViewModel(calendarUseCase = calendarUseCase)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.loadAvailableCalendars(resetPagination = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasValidToken)
    }

    @Test
    fun `Kalenderliste scheitert an der Anmeldung - Freigabe fehlt weiterhin`() = runTest(dispatcher) {
        val calendarUseCase = mock<ICalendarUseCase>()
        calendarUseCase.stub {
            onBlocking { getAvailableCalendarsPaginated(any(), any()) } doReturn
                Result.failure<CalendarPage>(Exception("Calendar access requires authorization. Please sign in."))
        }
        val vm = buildViewModel(calendarUseCase = calendarUseCase)
        backgroundScope.launch { vm.uiState.collect { } }

        vm.loadAvailableCalendars(resetPagination = true)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hasValidToken)
    }
}
