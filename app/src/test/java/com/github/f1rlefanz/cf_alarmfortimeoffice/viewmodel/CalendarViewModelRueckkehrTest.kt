package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeFeedNeueinlesenStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FakeKalenderVorausschauPrefs
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.IOException
import java.time.LocalDateTime

/**
 * Verdrahtung von "Neuladen bei Rueckkehr" (A, 1.48.0): `aktualisiereBeiRueckkehr()` muss ueber den
 * EINEN Ladeweg des ViewModels laufen, den Termin-Zwischenspeicher vorher verwerfen und die alte
 * Anzeige stehen lassen, bis die neue Liste da ist (`forceRefresh = true` ueberspringt den
 * Lazy-Reset). Die Entscheidung selbst haelt [RueckkehrAbgleichTest] als reine Funktion fest.
 *
 * `forceRefresh` laesst sich am Mock nicht direkt pruefen - `getCalendarEventsLazy()` hat keinen
 * solchen Parameter. Geprueft wird deshalb seine WIRKUNG: ohne ihn leerte der Ladeweg die Liste
 * sofort (Lazy-Reset), mit ihm bleibt sie stehen, solange der Abruf laeuft.
 *
 * Der Hintergrund-Leser wird eingespeist: der echte geht ueber einen Hilt-EntryPoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelRueckkehrTest {

    private val dispatcher = StandardTestDispatcher()
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val minute = 60_000L

    /** Wie viele Lazy-Abrufe je Kalender-ID beantwortet wurden. */
    private val abrufe = mutableMapOf<String, Int>()

    /** Verzoegerung je Abruf (nach Aufruf-Nummer, ab 1) - simuliert einen langsamen Abruf. */
    private var verzoegerung: (Int) -> Long = { 0L }

    /** Kalender, deren Abruf scheitert. */
    private val kaputt = mutableSetOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // selectedIds bewusst NICHT zuruecksetzen - Begruendung in CalendarViewModelSyncWiringTest.
        Dispatchers.resetMain()
    }

    private fun event(id: String, kalender: String, hour: Int) = CalendarEvent(
        id = id,
        title = "Schicht $id",
        startTime = LocalDateTime.of(2026, 10, 13, 0, 0).plusHours(hour.toLong()),
        endTime = LocalDateTime.of(2026, 10, 13, 0, 0).plusHours(hour + 8L),
        calendarId = kalender
    )

    /** Erster Abruf liefert "alt", jeder weitere "neu" - so ist sichtbar, welcher Stand angezeigt wird. */
    private fun seite(kalender: String, aufruf: Int): List<CalendarEvent> =
        if (aufruf == 1) listOf(event("$kalender-alt", kalender, 5))
        else listOf(event("$kalender-alt", kalender, 5), event("$kalender-neu", kalender, 14))

    private fun buildViewModel(
        calendarUseCase: ICalendarUseCase = mock(),
        gespeicherteAuswahl: Set<String> = setOf("cal-a")
    ): CalendarViewModel {
        calendarUseCase.stub {
            on { hasValidAccessToken() } doReturn true
            on { getCalendarEventsLazy(any(), any(), any()) } doSuspendableAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val kalender = (invocation.getArgument(0) as Set<String>).single()
                val nr = (abrufe[kalender] ?: 0) + 1
                abrufe[kalender] = nr
                val warte = verzoegerung(nr)
                if (warte > 0) delay(warte)
                if (kalender in kaputt) {
                    Result.failure(IOException("Kalender $kalender antwortet nicht"))
                } else {
                    val events = seite(kalender, nr)
                    // Vollstaendig (totalEvents == geliefert): kein Nachfordern noetig.
                    Result.success(EventPage(events = events, totalEvents = events.size))
                }
            }
        }

        val selectionRepository = mock<ICalendarSelectionRepository>()
        whenever(selectionRepository.selectedCalendarIds).thenReturn(selectedIds)
        selectionRepository.stub {
            on { getCurrentSelectedCalendarIds() } doReturn Result.success(gespeicherteAuswahl)
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

        return CalendarViewModel(
            appContext = mock<Context>(),
            calendarUseCase = calendarUseCase,
            calendarSelectionRepository = selectionRepository,
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = mock<ErrorHandler>(),
            shiftUseCase = shiftUseCase,
            alarmUseCase = mock<IAlarmUseCase>(),
            masterPausePrefs = masterPausePrefs,
            pendingDeselectionCleanupStore = pendingCleanupStore,
            feedNeueinlesenStore = FakeFeedNeueinlesenStore(),
            kalenderVorausschauPrefs = FakeKalenderVorausschauPrefs()
        )
    }

    /** ViewModel bauen, Kalender auswaehlen und den Start-Ladevorgang abschliessen. */
    private fun TestScope.gestartet(
        calendarUseCase: ICalendarUseCase = mock(),
        auswahl: Set<String> = setOf("cal-a")
    ): CalendarViewModel {
        val vm = buildViewModel(calendarUseCase, gespeicherteAuswahl = auswahl)
        // uiState ist stateIn(Lazily) - ohne Sammler bliebe es auf dem Startwert.
        backgroundScope.launch { vm.uiState.collect { } }
        selectedIds.value = auswahl
        advanceUntilIdle()
        return vm
    }

    private fun ids(vm: CalendarViewModel) = vm.uiState.value.events.map { it.id }

    @Test
    fun `Rueckkehr nach mehr als 5 Minuten laedt neu und laesst die alte Anzeige bis dahin stehen`() =
        runTest(dispatcher) {
            val calendarUseCase = mock<ICalendarUseCase>()
            val vm = gestartet(calendarUseCase)
            assertEquals(listOf("cal-a-alt"), ids(vm))
            val ersterAbgleich = vm.uiState.value.letzterAbgleich
            assertNotNull("Der vollstaendige Start-Abgleich muss angezeigt werden", ersterAbgleich)

            // Der zweite Abruf dauert - waehrenddessen muss der alte Stand sichtbar bleiben.
            verzoegerung = { nr -> if (nr >= 2) 1_000L else 0L }
            Thread.sleep(2) // damit der neue Ladebeginn sicher eine andere Millisekunde hat
            vm.aktualisiereBeiRueckkehr(
                jetzt = System.currentTimeMillis() + 6 * minute,
                leseHintergrundAbruf = { 0L }
            )
            advanceTimeBy(500)

            assertTrue("Es wird gerade neu geladen", vm.uiState.value.isLoading)
            assertEquals(
                "forceRefresh: kein Lazy-Reset - die alte Liste bleibt stehen, bis die neue da ist",
                listOf("cal-a-alt"),
                ids(vm)
            )
            assertEquals(
                "Der Zeitpunkt springt erst mit dem fertigen Abgleich weiter",
                ersterAbgleich,
                vm.uiState.value.letzterAbgleich
            )

            advanceUntilIdle()

            // Zwischenspeicher verworfen (sonst laese der Lazy-Pfad 15 min lang die alte Liste)...
            verifyBlocking(calendarUseCase) { invalidateCalendarCache(eq(setOf("cal-a"))) }
            // ...und ueber den Ladeweg neu geholt.
            verifyBlocking(calendarUseCase, times(2)) { getCalendarEventsLazy(any(), any(), any()) }
            assertEquals(listOf("cal-a-alt", "cal-a-neu"), ids(vm))
            val zweiterAbgleich = vm.uiState.value.letzterAbgleich
            assertNotNull(zweiterAbgleich)
            assertTrue("Der neue Abgleich ist juenger als der erste", zweiterAbgleich!! > ersterAbgleich!!)
        }

    @Test
    fun `Rueckkehr innerhalb von 5 Minuten laedt nicht`() = runTest(dispatcher) {
        val calendarUseCase = mock<ICalendarUseCase>()
        val vm = gestartet(calendarUseCase)

        vm.aktualisiereBeiRueckkehr(
            jetzt = System.currentTimeMillis() + 1 * minute,
            leseHintergrundAbruf = { 0L }
        )
        advanceUntilIdle()

        verifyBlocking(calendarUseCase, times(1)) { getCalendarEventsLazy(any(), any(), any()) }
        verifyBlocking(calendarUseCase, never()) { invalidateCalendarCache(any()) }
        assertEquals(listOf("cal-a-alt"), ids(vm))
    }

    @Test
    fun `Hintergrund hat seit dem letzten Laden abgerufen - Rueckkehr laedt auch innerhalb von 5 Minuten`() =
        runTest(dispatcher) {
            val calendarUseCase = mock<ICalendarUseCase>()
            val vm = gestartet(calendarUseCase)

            vm.aktualisiereBeiRueckkehr(
                jetzt = System.currentTimeMillis() + 1 * minute,
                // Die Wartung hat NACH dem Start-Abruf neue Termine geholt.
                leseHintergrundAbruf = { System.currentTimeMillis() + 30_000L }
            )
            advanceUntilIdle()

            verifyBlocking(calendarUseCase, times(2)) { getCalendarEventsLazy(any(), any(), any()) }
            assertEquals(listOf("cal-a-alt", "cal-a-neu"), ids(vm))
        }

    @Test
    fun `App-Start - eine Rueckkehr-Pruefung vor dem ersten Laden laedt nicht doppelt`() = runTest(dispatcher) {
        val calendarUseCase = mock<ICalendarUseCase>()
        // Auswahl schon bekannt, bevor das ViewModel entsteht: der Collector im init{} startet den
        // ersten Ladevorgang. Die Rueckkehr-Pruefung laeuft unmittelbar danach - mit einem
        // Hintergrund-Stempel, der "neuer" waere, und einer Uhr weit in der Zukunft.
        selectedIds.value = setOf("cal-a")
        val vm = buildViewModel(calendarUseCase)
        backgroundScope.launch { vm.uiState.collect { } }
        vm.aktualisiereBeiRueckkehr(
            jetzt = System.currentTimeMillis() + 60 * minute,
            leseHintergrundAbruf = { System.currentTimeMillis() + 60 * minute }
        )
        advanceUntilIdle()

        verifyBlocking(calendarUseCase, times(1)) { getCalendarEventsLazy(any(), any(), any()) }
        verifyBlocking(calendarUseCase, never()) { invalidateCalendarCache(any()) }
    }

    @Test
    fun `waehrend eines laufenden Ladevorgangs startet die Rueckkehr keinen zweiten`() = runTest(dispatcher) {
        val calendarUseCase = mock<ICalendarUseCase>()
        verzoegerung = { 1_000L }
        val vm = buildViewModel(calendarUseCase)
        backgroundScope.launch { vm.uiState.collect { } }
        selectedIds.value = setOf("cal-a")
        advanceTimeBy(100) // Start-Abruf laeuft noch

        vm.aktualisiereBeiRueckkehr(
            jetzt = System.currentTimeMillis() + 6 * minute,
            leseHintergrundAbruf = { 0L }
        )
        advanceUntilIdle()

        verifyBlocking(calendarUseCase, times(1)) { getCalendarEventsLazy(any(), any(), any()) }
    }

    @Test
    fun `ohne Kalenderauswahl laedt die Rueckkehr nichts`() = runTest(dispatcher) {
        val calendarUseCase = mock<ICalendarUseCase>()
        val vm = buildViewModel(calendarUseCase, gespeicherteAuswahl = emptySet())
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.aktualisiereBeiRueckkehr(
            jetzt = System.currentTimeMillis() + 60 * minute,
            leseHintergrundAbruf = { System.currentTimeMillis() }
        )
        advanceUntilIdle()

        verifyBlocking(calendarUseCase, never()) { getCalendarEventsLazy(any(), any(), any()) }
        verifyBlocking(calendarUseCase, never()) { invalidateCalendarCache(any()) }
    }

    @Test
    fun `unlesbarer Hintergrund-Merker - entschieden wird nur nach der Zeit, die App bleibt stehen`() =
        runTest(dispatcher) {
            val calendarUseCase = mock<ICalendarUseCase>()
            val vm = gestartet(calendarUseCase)

            // Innerhalb der 5 Minuten: kein Neuladen, und der Wurf erreicht niemanden.
            vm.aktualisiereBeiRueckkehr(
                jetzt = System.currentTimeMillis() + 1 * minute,
                leseHintergrundAbruf = { throw IllegalStateException("DataStore kaputt") }
            )
            advanceUntilIdle()
            verifyBlocking(calendarUseCase, times(1)) { getCalendarEventsLazy(any(), any(), any()) }

            // Nach den 5 Minuten: Neuladen trotzdem.
            vm.aktualisiereBeiRueckkehr(
                jetzt = System.currentTimeMillis() + 6 * minute,
                leseHintergrundAbruf = { throw IllegalStateException("DataStore kaputt") }
            )
            advanceUntilIdle()
            verifyBlocking(calendarUseCase, times(2)) { getCalendarEventsLazy(any(), any(), any()) }
        }

    @Test
    fun `ein Teilerfolg erneuert den Abgleich-Zeitpunkt nicht`() = runTest(dispatcher) {
        val beide = setOf("cal-a", "cal-b")
        val vm = gestartet(auswahl = beide)
        val vollstaendig = vm.uiState.value.letzterAbgleich
        assertNotNull(vollstaendig)

        // Der Dienstplan-Kalender antwortet beim Neuladen nicht - "abgeglichen" schloesse ihn ein.
        kaputt += "cal-b"
        Thread.sleep(2)
        vm.aktualisiereBeiRueckkehr(
            jetzt = System.currentTimeMillis() + 6 * minute,
            leseHintergrundAbruf = { 0L }
        )
        advanceUntilIdle()

        assertEquals(setOf("cal-b"), vm.uiState.value.unavailableCalendarIds)
        assertEquals(
            "Nach einem Teilerfolg bleibt der letzte VOLLSTAENDIGE Abgleich stehen",
            vollstaendig,
            vm.uiState.value.letzterAbgleich
        )
    }

    @Test
    fun `ein Totalausfall erneuert den Abgleich-Zeitpunkt nicht`() = runTest(dispatcher) {
        val vm = gestartet()
        val vollstaendig = vm.uiState.value.letzterAbgleich
        assertNotNull(vollstaendig)

        kaputt += "cal-a"
        Thread.sleep(2)
        vm.aktualisiereBeiRueckkehr(
            jetzt = System.currentTimeMillis() + 6 * minute,
            leseHintergrundAbruf = { 0L }
        )
        advanceUntilIdle()

        assertEquals(vollstaendig, vm.uiState.value.letzterAbgleich)
    }

    @Test
    fun `die Abwahl des letzten Kalenders leert den Abgleich-Zeitpunkt`() = runTest(dispatcher) {
        val vm = gestartet()
        assertNotNull(vm.uiState.value.letzterAbgleich)

        selectedIds.value = emptySet()
        advanceUntilIdle()

        assertNull(
            "Der Zeitpunkt gehoerte zur alten Auswahl - ohne Kalender ist nichts abgeglichen",
            vm.uiState.value.letzterAbgleich
        )
    }
}
