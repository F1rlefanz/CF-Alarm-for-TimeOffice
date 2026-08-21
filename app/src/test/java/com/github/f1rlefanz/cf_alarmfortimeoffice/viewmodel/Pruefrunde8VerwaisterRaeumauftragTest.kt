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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

/**
 * PRUEFRUNDE 8, WELLE 5 - BEFUND A und C rund um das Aufraeumen nach einer Kalender-Abwahl.
 *
 * BEFUND A (hoch): `clearAlarmsAfterCalendarDeselection()` lief unter `NonCancellable` - das
 * schuetzt gegen den ABBRUCH der Coroutine, nicht gegen den TOD des Prozesses. Der Fehlerzustand
 * lag im Arbeitsspeicher (`CalendarUiState.deselectionCleanupFailures`), und der Wiedereinstieg
 * hing allein am Uebergangs-Merker "es war einmal etwas ausgewaehlt", der beim naechsten App-Start
 * per Konstruktion falsch ist, weil die Auswahl dann von Anfang an leer ist. Stirbt der Prozess
 * zwischen Abwahl und Raeumung - oder scheitert das Raeumen einmal und der Nutzer startet die App
 * neu -, sind die armierten Wecker noch da, der Auftrag und die Warnung aber weg. Die
 * naheliegendste Geste (Kalender abwaehlen, App wegwischen) trifft genau dieses Fenster.
 *
 * Der Auftrag liegt deshalb jetzt dauerhaft im [PendingDeselectionCleanupStore]. Diese Tests
 * halten die REIHENFOLGE fest, die dabei alles traegt: gesetzt VOR dem Raeumen, geloescht erst
 * nach nachweislichem Erfolg. Und die Gegenprobe, ohne die der Merker zur neuen Gefahr wuerde: er
 * darf NUR eine ausdrueckliche Abwahl abbilden, niemals ein leeres Ladeergebnis.
 *
 * BEFUND C (niedrig): `retryDeselectionCleanup()` erhoehte die Generation VOR jeder Pruefung.
 * Lief gerade ein Ladevorgang, verwarf der danach alle seine Ergebnisse.
 *
 * WELLE 8 (mittel): Der Auftrag wurde geloescht, sobald wieder ein Kalender ausgewaehlt war -
 * als waere die neue Auswahl selbst schon der Beleg, dass nichts mehr verwaist ist. Er faellt
 * jetzt erst nach einem gelungenen Sync ueber einer nachweislich vollstaendigen Eventliste.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain, advanceUntilIdle
class Pruefrunde8VerwaisterRaeumauftragTest {

    private val dispatcher = StandardTestDispatcher()
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Simuliert einen Lesefehler des Auswahl-Speichers (nicht: eine leere Auswahl). */
    private var selectionReadFails = false

    /**
     * Protokoll der Zugriffe auf Merker und Alarm-Sync - die REIHENFOLGE ist hier die
     * Zusicherung, nicht die blosse Anzahl.
     */
    private val protokoll = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // selectedIds bewusst NICHT zuruecksetzen - ein Write NACH resetMain() schickt haengende
        // Collector-Continuations auf den abgeraeumten Main-Dispatcher.
        Dispatchers.resetMain()
    }

    private fun event(id: String, hour: Int) = CalendarEvent(
        id = id,
        title = "Schicht $id",
        startTime = LocalDateTime.of(2026, 8, 20, 0, 0).plusHours(hour.toLong()),
        endTime = LocalDateTime.of(2026, 8, 20, 0, 0).plusHours(hour + 8L),
        calendarId = "cal-a"
    )

    private val events = (0 until 3).map { event("A$it", it) }

    /**
     * Was der naechste Kalender-Ladevorgang liefert. Veraenderbar, weil genau der Unterschied
     * zwischen "der neue Kalender hat Termine" und "er hat keine" geprueft werden muss - und
     * beides im selben Testlauf nacheinander auftritt.
     */
    private var geladeneEvents: List<CalendarEvent> = events

    private fun protokollierenderStore(): PendingDeselectionCleanupStore {
        val store = mock<PendingDeselectionCleanupStore>()
        store.stub {
            onBlocking { pendingSince() } doReturn Result.success(null)
            onBlocking { markPending(any()) } doAnswer {
                protokoll += MERKER_GESETZT
                Result.success(Unit)
            }
            onBlocking { clearIfPending() } doAnswer {
                protokoll += MERKER_GELOESCHT
                Result.success(Unit)
            }
        }
        return store
    }

    /**
     * @param ladeSperre wenn gesetzt, haengt der Kalender-Ladevorgang, bis das Deferred erfuellt
     *   ist - so laesst sich ein LAUFENDER Ladevorgang beobachten (Befund C).
     */
    private fun buildViewModel(
        alarmUseCase: IAlarmUseCase,
        store: PendingDeselectionCleanupStore,
        raeumenScheitert: Boolean = false,
        ladeSperre: CompletableDeferred<Unit>? = null
    ): CalendarViewModel {
        val calendarUseCase = mock<ICalendarUseCase>()
        calendarUseCase.stub {
            onBlocking { hasValidAccessToken() } doReturn true
            onBlocking { getCalendarEventsLazy(any(), any(), any()) } doSuspendableAnswer {
                ladeSperre?.await()
                Result.success(
                    EventPage(
                        events = geladeneEvents,
                        offset = 0,
                        maxEvents = 10,
                        // nichts abgeschnitten -> vollstaendige Liste
                        totalEvents = geladeneEvents.size,
                        hasMore = false
                    )
                )
            }
            onBlocking { getCalendarEventsWithStatus(any(), any()) } doReturn
                Result.failure(IllegalStateException("nicht gestubbt"))
        }

        val selectionRepository = mock<ICalendarSelectionRepository>()
        whenever(selectionRepository.selectedCalendarIds).thenReturn(selectedIds)
        selectionRepository.stub {
            onBlocking { getCurrentSelectedCalendarIds() } doAnswer {
                if (selectionReadFails) Result.failure(IllegalStateException("DataStore unlesbar"))
                else Result.success(selectedIds.value)
            }
        }

        alarmUseCase.stub {
            onBlocking { syncAlarms(any(), any()) } doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val uebergeben = invocation.arguments[0] as List<CalendarEvent>
                if (uebergeben.isEmpty()) {
                    protokoll += GERAEUMT
                    if (raeumenScheitert) {
                        Result.failure(IllegalStateException("Alarm liess sich nicht abbrechen"))
                    } else {
                        Result.success(emptyList())
                    }
                } else {
                    protokoll += ANGELEGT
                    Result.success(emptyList())
                }
            }
        }

        val shiftUseCase = mock<IShiftUseCase>()
        shiftUseCase.stub {
            onBlocking { getCurrentShiftConfig() } doReturn Result.success(ShiftConfig())
        }

        val masterPausePrefs = mock<MasterPausePrefs>()
        masterPausePrefs.stub {
            onBlocking { pausedNow() } doReturn false
        }

        return CalendarViewModel(
            appContext = mock<Context>(),
            calendarUseCase = calendarUseCase,
            calendarSelectionRepository = selectionRepository,
            calendarStateHolder = CalendarStateHolder(),
            errorHandler = mock<ErrorHandler>(),
            shiftUseCase = shiftUseCase,
            alarmUseCase = alarmUseCase,
            masterPausePrefs = masterPausePrefs,
            pendingDeselectionCleanupStore = store,
            // Nur fuer die stille Statuszeile - fuer diesen Test ohne Belang.
            feedNeueinlesenStore = FakeFeedNeueinlesenStore()
        )
    }

    // ------------------------------------------------------------------------------------------
    // BEFUND A: der Auftrag ueberlebt den Prozesstod
    // ------------------------------------------------------------------------------------------

    /**
     * DIE KERNZUSICHERUNG. Umgekehrt (erst raeumen, dann merken) bliebe genau das Fenster offen,
     * das dieser Merker schliessen soll.
     */
    @Test
    fun `der Raeumauftrag wird festgehalten BEVOR geraeumt wird`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        val gesetzt = protokoll.indexOf(MERKER_GESETZT)
        val geraeumt = protokoll.indexOf(GERAEUMT)
        assertTrue("Der Auftrag wurde nie festgehalten: $protokoll", gesetzt >= 0)
        assertTrue("Es wurde nie geraeumt: $protokoll", geraeumt >= 0)
        assertTrue(
            "Der Auftrag muss VOR dem Raeumen festliegen, sonst geht er bei einem Prozesstod " +
                "dazwischen verloren: $protokoll",
            gesetzt < geraeumt
        )
    }

    /**
     * Der eigentliche Zweck: nach einem Fehlschlag muss der Auftrag stehenbleiben, sonst gibt es
     * ohne die App keinen zweiten Anlauf mehr.
     */
    @Test
    fun `nach einem gescheiterten Raeumen bleibt der Auftrag stehen`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store, raeumenScheitert = true)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        val gesetzt = protokoll.indexOf(MERKER_GESETZT)
        assertTrue("Der Auftrag wurde nie festgehalten: $protokoll", gesetzt >= 0)
        assertFalse(
            "Nach dem Fehlschlag darf der Auftrag NICHT geloescht werden: $protokoll",
            protokoll.drop(gesetzt).contains(MERKER_GELOESCHT)
        )
    }

    /** Die Gegenprobe: ein erledigter Auftrag muss verschwinden, sonst raeumt die Wartung ewig nach. */
    @Test
    fun `nach erfolgreichem Raeumen faellt der Auftrag`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()

        val geraeumt = protokoll.indexOf(GERAEUMT)
        assertTrue(
            "Der erledigte Auftrag muss nach dem Raeumen geloescht werden: $protokoll",
            protokoll.drop(geraeumt).contains(MERKER_GELOESCHT)
        )
    }

    /**
     * "Leer ist keine Loeschgrundlage" gilt fuer den Merker genauso. Ist die Auswahl nur nicht
     * LESBAR, ist das kein Beleg fuer eine Abwahl - haetten wir hier einen Auftrag festgehalten,
     * wuerde die naechste Wartung die Wecker eines womoeglich weiterhin ausgewaehlten Kalenders
     * raeumen.
     */
    @Test
    fun `eine nicht lesbare Auswahl haelt KEINEN Auftrag fest`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()

        selectionReadFails = true
        selectedIds.value = emptySet()
        advanceUntilIdle()

        assertFalse(
            "Aus einem Lesefehler darf nie ein Raeumauftrag werden: $protokoll",
            protokoll.contains(MERKER_GESETZT)
        )
        assertFalse("Und geraeumt werden darf erst recht nicht: $protokoll", protokoll.contains(GERAEUMT))
    }

    /**
     * Der leere Startwert des noch nicht hydrierten Auswahl-StateFlows ist keine Abwahl - sonst
     * schriebe JEDER App-Start einen Raeumauftrag.
     */
    @Test
    fun `der leere Startwert haelt keinen Auftrag fest`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store)

        advanceUntilIdle()

        assertFalse("Kein Auftrag ohne Abwahl: $protokoll", protokoll.contains(MERKER_GESETZT))
    }

    // ------------------------------------------------------------------------------------------
    // WELLE 8: eine neue Auswahl allein ist kein Beleg fuer "nichts mehr verwaist"
    // ------------------------------------------------------------------------------------------

    /**
     * DER FEHLER: Sobald wieder ein Kalender ausgewaehlt war, wurde der dauerhafte Raeumauftrag
     * sofort verworfen - begruendet damit, dass der Delta-Sync des folgenden Ladevorgangs jeden
     * Alarm raeumt, dessen Termin fehlt. Der laeuft aber nicht in jedem Fall: er sitzt hinter der
     * Pruefung "Eventliste nicht leer". Liefert der neu gewaehlte Kalender null Termine (anderer
     * Kalender, Dienstplan-Feed gerade leer), passiert gar nichts - und mit dem geloeschten
     * Auftrag faengt es auch die 6h-Wartung nicht mehr auf. Die Wecker des abgewaehlten
     * Dienstplans klingeln bis zu 14 Tage weiter, ohne Hinweis und ohne Wiedereinstieg.
     */
    @Test
    fun `eine neue Auswahl ohne Termine loescht den Raeumauftrag NICHT`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store, raeumenScheitert = true)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()
        val gesetzt = protokoll.indexOf(MERKER_GESETZT)
        assertTrue("Vorbedingung: der Auftrag steht offen: $protokoll", gesetzt >= 0)

        // Der Nutzer waehlt einen anderen Kalender - der liefert (gerade) keine Termine.
        geladeneEvents = emptyList()
        selectedIds.value = setOf("cal-b")
        advanceUntilIdle()

        assertFalse(
            "Ohne gelaufenen Sync ist nichts geraeumt - der Auftrag muss stehenbleiben, sonst " +
                "faengt ihn auch die 6h-Wartung nicht mehr auf: $protokoll",
            protokoll.drop(gesetzt).contains(MERKER_GELOESCHT)
        )
    }

    /**
     * Die Gegenrichtung: der Auftrag darf auch nicht ewig stehenbleiben. Ein gelungener Sync ueber
     * einer nachweislich VOLLSTAENDIGEN Eventliste ist der saubere Beleg "hier ist nichts
     * Verwaistes mehr" - genau dort faellt er.
     */
    @Test
    fun `ein gelungener Sync ueber vollstaendiger Liste loescht den Raeumauftrag`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store, raeumenScheitert = true)

        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        selectedIds.value = emptySet()
        advanceUntilIdle()
        val gesetzt = protokoll.indexOf(MERKER_GESETZT)
        assertTrue("Vorbedingung: der Auftrag steht offen: $protokoll", gesetzt >= 0)

        // Neuer Kalender MIT Terminen: der Delta-Sync laeuft und raeumt jeden Alarm ohne Termin.
        selectedIds.value = setOf("cal-b")
        advanceUntilIdle()

        val angelegt = protokoll.drop(gesetzt).indexOf(ANGELEGT)
        assertTrue("Vorbedingung: der Sync ist gelaufen: $protokoll", angelegt >= 0)
        assertTrue(
            "Nach dem belegten Sync muss der Auftrag fallen, sonst raeumt die Wartung ewig " +
                "nach: $protokoll",
            protokoll.drop(gesetzt).drop(angelegt).contains(MERKER_GELOESCHT)
        )
    }

    // ------------------------------------------------------------------------------------------
    // BEFUND C: der zweite Anlauf darf einen laufenden Ladevorgang nicht abwuergen
    // ------------------------------------------------------------------------------------------

    /**
     * DER FEHLER: `retryDeselectionCleanup()` rief
     * `clearAlarmsAfterCalendarDeselection(eventLoadGeneration.incrementAndGet())` - die Erhoehung
     * passierte VOR jeder Pruefung, ob ueberhaupt noch etwas zu raeumen ist. Der Nutzer hat aber
     * genau dann einen Grund zu tippen, wenn die Karte noch steht - und die steht auch dann noch,
     * wenn er inzwischen wieder einen Kalender ausgewaehlt hat und dessen Ladevorgang laeuft.
     * Dieser Lauf galt danach als ueberholt und verwarf alle seine Ergebnisse: keine Termine,
     * keine Wecker, kein zweiter Anlauf.
     *
     * Ohne den Fix bleibt `syncAlarms(events, ...)` aus - der Ladevorgang wirft seine Ergebnisse
     * weg, obwohl der Retry selbst nichts geraeumt hat.
     */
    @Test
    fun `der zweite Anlauf verwirft einen laufenden Ladevorgang nicht`() = runTest(dispatcher) {
        val sperre = CompletableDeferred<Unit>()
        val store = protokollierenderStore()
        val alarmUseCase = mock<IAlarmUseCase>()
        val viewModel = buildViewModel(alarmUseCase = alarmUseCase, store = store, ladeSperre = sperre)

        // Der Nutzer hat wieder einen Kalender ausgewaehlt; der Ladevorgang haengt am Netz.
        selectedIds.value = setOf("cal-a")
        advanceUntilIdle()
        assertFalse("Vorbedingung: der Ladevorgang laeuft noch", protokoll.contains(ANGELEGT))

        // Die Karte aus dem frueheren Fehlschlag steht noch - er tippt auf "Erneut versuchen".
        viewModel.retryDeselectionCleanup()
        advanceUntilIdle()

        // Es gibt nichts zu raeumen (der Speicher meldet wieder einen Kalender).
        assertFalse("Es darf nichts geraeumt werden: $protokoll", protokoll.contains(GERAEUMT))

        // Jetzt antwortet das Netz.
        sperre.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            "Der laufende Ladevorgang muss seine Ergebnisse behalten - sonst kostet ein " +
                "folgenloser Knopfdruck alle Termine und Wecker: $protokoll",
            protokoll.contains(ANGELEGT)
        )
        verify(alarmUseCase).syncAlarms(eq(events), any())
    }

    /**
     * Die Gegenprobe zum Test darueber: wenn wirklich etwas zu raeumen ist, raeumt der zweite
     * Anlauf auch - die Zurueckhaltung bei der Generation darf ihn nicht wirkungslos machen.
     */
    @Test
    fun `der zweite Anlauf raeumt weiterhin wenn nichts mehr ausgewaehlt ist`() = runTest(dispatcher) {
        val store = protokollierenderStore()
        buildViewModel(alarmUseCase = mock(), store = store, raeumenScheitert = true)
            .also { viewModel ->
                selectedIds.value = setOf("cal-a")
                advanceUntilIdle()
                selectedIds.value = emptySet()
                advanceUntilIdle()
                assertEquals(
                    "Vorbedingung: der erste Anlauf ist gescheitert",
                    1,
                    protokoll.count { it == GERAEUMT }
                )

                viewModel.retryDeselectionCleanup()
                advanceUntilIdle()

                assertEquals(
                    "Der zweite Anlauf muss wirklich noch einmal raeumen",
                    2,
                    protokoll.count { it == GERAEUMT }
                )
            }
    }

    private companion object {
        const val MERKER_GESETZT = "merker-gesetzt"
        const val MERKER_GELOESCHT = "merker-geloescht"
        const val GERAEUMT = "sync-leer"
        const val ANGELEGT = "sync-mit-events"
    }
}
