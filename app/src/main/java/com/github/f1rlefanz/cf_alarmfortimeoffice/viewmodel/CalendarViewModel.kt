package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * IMMUTABLE UI State für optimale Compose Performance
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable verhindert unnötige Recompositions
 * ✅ Strukturelle Gleichheit für distinctUntilChanged()
 * ✅ Memory-efficient durch effiziente Copy-Operations
 */
/**
 * PURE, TESTBAR: Ergebnis von [CalendarViewModel.resolveCalendarAuthorizationOutcome].
 * Als eigenständiger Typ statt zweier loser Booleans, damit ein Test die Kombination
 * eindeutig gegen beide Felder prüfen kann.
 */
internal data class CalendarAuthorizationOutcome(
    val everythingFailed: Boolean,
    val authStillValid: Boolean
)

/**
 * PURE, TESTBAR: Ergebnis von [CalendarViewModel.mergeMoreEvents] - die Liste, die nach dem
 * Nachladen in _localUiState UND CalendarStateHolder geschrieben wird, plus die daraus
 * abgeleiteten Pagination-Felder. Als eigener Typ, damit ein Test alle drei Werte gemeinsam
 * gegen dieselbe Eingabe pruefen kann.
 */
internal data class MoreEventsMergeResult(
    val events: List<CalendarEvent>,
    val eventOffset: Int,
    val hasMoreEvents: Boolean
)

@Immutable
data class CalendarUiState(
    val isLoading: Boolean = false,
    val availableCalendars: List<AndroidCalendar> = emptyList(),
    val selectedCalendarIds: Set<String> = emptySet(),
    val events: List<CalendarEvent> = emptyList(),
    val error: String? = null,
    val hasValidToken: Boolean = false,
    // PHASE 2 FIX: Track actual Calendar API authorization status
    val calendarAuthorizationValid: Boolean = false,
    val lastAuthorizationCheck: Long = 0L,
    // PAGINATION SUPPORT: Calendar pagination fields
    val currentPage: Int = 0,
    val hasMoreCalendars: Boolean = false,
    val totalCalendars: Int = 0,
    val isLoadingMore: Boolean = false,
    // LAZY LOADING: Event pagination fields
    val hasMoreEvents: Boolean = false,
    val isLoadingMoreEvents: Boolean = false,
    val eventOffset: Int = 0,
    val totalEvents: Int = 0,
    /**
     * Kalender, die beim letzten Ladevorgang NICHT geantwortet haben, waehrend mindestens einer
     * geantwortet hat - der TEILERFOLG.
     *
     * Warum das ein eigener Zustand sein muss: eine unvollstaendige Eventliste ist zu Recht keine
     * Loeschgrundlage, alle vier `isComplete`-Sperren steigen deshalb aus. Sie verhindern damit
     * aber auch, dass jemals wieder ein Alarm ANGELEGT wird. Bleibt ein Kalender dauerhaft
     * unerreichbar (geloescht, Freigabe entzogen, Feed abgeschaltet), laufen die bestehenden
     * Wecker der Reihe nach aus und nichts waechst nach - bis v1.25.3 ohne jedes Anzeichen, weil
     * die Zahl der gescheiterten Kalender ausschliesslich im Log und in den Sperren stand.
     *
     * AUSDRUECKLICH LEER, wenn ALLE Kalender gescheitert sind: dieser Fall gehoert
     * [calendarAuthorizationValid] und hat seine eigene Anzeige ("Kalender-Autorisierung
     * verloren"). Zwei Warnungen fuer dieselbe Lage waeren schlechter als eine.
     */
    val unavailableCalendarIds: Set<String> = emptySet(),

    /**
     * Zahl der bisher gescheiterten Anlaeufe, nach einer Kalender-Abwahl die kalenderbasierten
     * Wecker zu raeumen. `0` heisst: alles in Ordnung.
     *
     * WARUM EIN EIGENES FELD UND KEIN [error]-TEXT: `error` ist ein Meldungspuffer - er wird in
     * MainContentScreen als Snackbar gezeigt und unmittelbar danach mit `clearError()` geleert.
     * Was hier gemeldet werden muss, ist aber kein Ereignis, sondern ein ZUSTAND: die Oberflaeche
     * zeigt "kein Kalender ausgewaehlt", waehrend bis zu 14 Tage lang Wecker des entfernten
     * Dienstplans klingeln. Ueber `error` sah der Nutzer das genau eine Snackbar-Laenge lang, und
     * danach war der Hinweis endgueltig weg, obwohl der Zustand unveraendert weiterbestand. Als
     * Zustandsfeld bleibt er stehen, bis er wirklich behoben ist, und traegt die Wiedervorlage
     * gleich mit: jeder weitere Fehlschlag erhoeht die Zahl.
     *
     * ANGEZEIGT wird er als bleibende Karte GANZ OBEN im Status-Tab
     * (`StatusTabContent.VerwaisteWeckerNachAbwahlCard`) - nicht als Snackbar in
     * MainContentScreen: die lief als einziger `Indefinite`-Aufruf der App auf dem gemeinsamen
     * SnackbarHostState und blockierte, solange sie stand, jede andere Meldung.
     *
     * ER IST NICHT DER EINZIGE WIEDEREINSTIEG, und darf es auch nicht sein: dieses Feld lebt im
     * Arbeitsspeicher und stirbt mit dem Prozess. Der Auftrag selbst liegt dauerhaft im
     * [PendingDeselectionCleanupStore] und wird auch ohne die App abgearbeitet (6h-Wartung).
     *
     * Zurueckgesetzt wird ausschliesslich, wenn der Zustand aufgeloest ist - geraeumt, Automatik
     * aus, Master-Pause oder ein gelungener Alarm-Sync ueber einer nachweislich vollstaendigen
     * Eventliste (siehe `resolveDeselectionCleanupFailure`). Nicht vom Anzeigen, und ausdruecklich
     * NICHT schon davon, dass wieder ein Kalender ausgewaehlt ist.
     */
    val deselectionCleanupFailures: Int = 0
)

/**
 * CalendarViewModel - REFACTORED with Single Source of Truth
 * 
 * MIGRATION STATUS:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ CalendarStateHolder integriert für ViewModel-Entkopplung
 * ✅ Alle Dependencies über Interfaces
 * 
 * STATE SYNCHRONISATION FIXES:
 * ✅ Verwendet ICalendarSelectionRepository als Single Source of Truth
 * ✅ Keine temporären States mehr - nur persistente Speicherung
 * ✅ Debounced + distinctUntilChanged für Performance
 * ✅ Reactive State Management mit Flow Kombinationen
 * ✅ Interface-basierte Abhängigkeiten für bessere Testbarkeit
 * ✅ Eliminiert Race Conditions durch atomare Updates
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val calendarUseCase: ICalendarUseCase,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val calendarStateHolder: CalendarStateHolder,
    private val errorHandler: ErrorHandler,
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,
    private val masterPausePrefs: MasterPausePrefs,
    private val pendingDeselectionCleanupStore: PendingDeselectionCleanupStore
) : ViewModel() {

    private val _localUiState = MutableStateFlow(CalendarUiState())
    
    /**
     * PERFORMANCE OPTIMIZATION: Advanced State Update Batching
     * THREAD-SAFE: Volatile fields für Thread-Safety bei State Updates
     * ADAPTIVE: Dynamische Batch-Delays basierend auf Update-Frequenz
     */
    @Volatile
    private var pendingStateUpdate: CalendarUiState? = null
    @Volatile
    private var batchUpdateJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastBatchTime = 0L
    
    /**
     * SINGLE SOURCE OF TRUTH: Kombiniert lokalen State mit persistiertem Selection State
     * PERFORMANCE: debounce(30) und distinctUntilChanged() verhindern excessive Updates
     * EFFICIENCY: Optimierte Debounce-Zeit für bessere Responsiveness und reduzierte GC-Last
     */
    val uiState: StateFlow<CalendarUiState> = combine(
        _localUiState.asStateFlow(),
        calendarSelectionRepository.selectedCalendarIds
            .debounce(30) // PERFORMANCE: Reduziert von 50ms auf 30ms für noch bessere Responsiveness
            // NOTE: distinctUntilChanged() entfernt - StateFlow ist bereits distinct by design
    ) { localState, persistedCalendarIds ->
        // PERFORMANCE: Nur neuen State erstellen wenn sich tatsächlich etwas geändert hat
        if (localState.selectedCalendarIds != persistedCalendarIds) {
            localState.copy(selectedCalendarIds = persistedCalendarIds)
        } else {
            localState
        }
    }.distinctUntilChanged() // ZUSÄTZLICHE OPTIMIERUNG: Verhindert identische UI State Updates
    .debounce(50) // PERFORMANCE: Batch UI State Updates
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = CalendarUiState()
    )

    /**
     * RACE-GUARD: Monotonic generation counter für loadEventsForSelectedCalendars().
     *
     * loadAvailableCalendars() (oben) wehrt sich mit einem simplen In-Flight-Flag gegen
     * Überlappung - das passt dort, weil ein zweiter Aufruf während des ersten schlicht
     * verworfen wird. Bei loadEventsForSelectedCalendars() geht das nicht: observeCalendarSelection()
     * (Zeile ~249) UND refreshData(forceRefresh = true) (Zeile ~686, z.B. "Aktualisieren"-Button)
     * dürfen beide legitim feuern, und ein simples Boolean-Gate würde den zweiten Aufruf einfach
     * schlucken statt seine - eigentlich aktuelleren - Ergebnisse durchzulassen.
     *
     * Stattdessen zieht jeder Aufruf beim Start eine eigene Generation-Nummer. Bevor er
     * Zwischenergebnisse ODER das Endergebnis in _localUiState/CalendarStateHolder schreibt,
     * prüft er, ob seine Nummer noch die aktuellste ist. Ist inzwischen ein neuerer Aufruf
     * gestartet, gilt dieser Lauf als überholt und verwirft seine (potenziell aus einem
     * langsameren Netzwerk-Call stammenden) Ergebnisse, statt sie über die bereits korrekten
     * Ergebnisse des neueren Laufs zu schreiben - inkl. des daran hängenden
     * alarmUseCase.syncAlarms() mit veralteten Events.
     *
     * MUSS vor dem init{}-Block deklariert sein: Kotlin initialisiert Property-Initializer und
     * init-Bloecke strikt in Textreihenfolge. init{} ruft observeCalendarSelection() auf, dessen
     * launch{} ueber einen unconfined Dispatcher-Pfad synchron genug laeuft, um noch waehrend der
     * Konstruktion loadEventsForSelectedCalendars() zu erreichen - stand diese Property TEXTUELL
     * nach init{}, war eventLoadGeneration dort noch null (NullPointerException beim allerersten
     * App-Start, real am Fairphone reproduziert).
     */
    private val eventLoadGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * LAZY LOADING OPTIMIZATION: Verhindert doppelte Calendar-Loadings
     * THREAD-SAFE: Atomic operations für Race Condition Prevention
     * PERFORMANCE: Time-based throttling für excessive API calls
     *
     * MUSS - wie eventLoadGeneration darueber - vor dem init{}-Block stehen. Beide Felder
     * standen bis zu diesem Fix TEXTUELL NACH init{} und waren nur zufaellig harmlos: als
     * primitive Typen entsprechen ihre Initializer (false/0L) genau den JVM-Feld-Defaults,
     * also gab es weder NPE noch falschen Startwert. Sobald hier aber ein Nicht-Default
     * (z.B. System.currentTimeMillis() als Drossel-Startwert) oder ein Objekt-Typ
     * (AtomicBoolean, Instant?) stehen wuerde, laufen die Initializer NACH init{} - und
     * init{} startet observeCalendarSelection(), dessen StateFlow-Collector auf
     * Dispatchers.Main.immediate noch waehrend der Objekt-Konstruktion feuert und diese
     * Felder liest/schreibt. Genau dieses Muster war am 05.08.2026 ein realer
     * Crash-on-Launch am Fairphone, den kein Unit-Test gefangen hat.
     */
    @Volatile
    private var isCalendarLoadingInProgress = false

    @Volatile
    private var lastCalendarLoadTime = 0L

    /**
     * Merker: hat dieser Collector schon einmal eine NICHT leere Kalenderauswahl gesehen?
     *
     * Unterscheidet die EINE legitime Leere ("der Nutzer hat den letzten Kalender abgewaehlt")
     * von der gefaehrlichen ("die Auswahl ist noch nicht geladen"). Der StateFlow des
     * Repositories startet auf `emptySet()` und wird erst durch einen unabgewarteten Collector
     * befuellt - der allererste Wert, den `observeCalendarSelection()` sieht, ist bei JEDEM
     * App-Start leer. Wuerde schon dieser Wert als Abwahl gelten, raeumte jeder App-Start
     * saemtliche kalenderbasierten Wecker weg, bevor die Auswahl ueberhaupt gelesen ist - genau
     * die "leer ist die gefaehrlichste Luege"-Falle, gegen die diese App an mehreren Stellen
     * abgesichert ist.
     *
     * MUSS - wie die beiden Felder darueber - TEXTUELL VOR dem init{}-Block stehen: init{}
     * startet den Collector, der dieses Feld noch waehrend der Objekt-Konstruktion liest und
     * schreibt (siehe eventLoadGeneration).
     */
    @Volatile
    private var hasSeenNonEmptySelection = false

    init {
        checkTokenValidity()
        observeCalendarSelection()
    }

    /**
     * PERFORMANCE OPTIMIZATION: Batched State Updates
     * Sammelt State-Updates und emmittiert sie als Batch für bessere Performance
     */
    /**
     * PERFORMANCE: Advanced Batched State Updates
     * ADAPTIVE TIMING: 16ms für normale Updates, 33ms bei hoher Frequenz
     * FRAME-SYNC: Optimiert für 60fps UI Performance
     */
    private fun updateLocalState(updateFunc: (CalendarUiState) -> CalendarUiState) {
        batchUpdateJob?.cancel()
        
        val currentState = _localUiState.value
        val newState = updateFunc(currentState)
        
        // PERFORMANCE: Nur Update wenn sich der State tatsächlich geändert hat
        if (currentState != newState) {
            pendingStateUpdate = newState
            
            // ADAPTIVE BATCHING: Dynamische Delays basierend auf Update-Frequenz
            val currentTime = System.currentTimeMillis()
            val timeSinceLastBatch = currentTime - lastBatchTime
            val batchDelay = if (timeSinceLastBatch < 100) {
                33L // 30fps bei häufigen Updates für Stabilität
            } else {
                16L // 60fps bei normaler Frequenz
            }
            
            batchUpdateJob = viewModelScope.launch {
                kotlinx.coroutines.delay(batchDelay)
                pendingStateUpdate?.let { update ->
                    _localUiState.value = update
                    pendingStateUpdate = null
                    lastBatchTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    /**
     * IMMEDIATE UPDATE: Für kritische State-Änderungen die sofort emmittiert werden müssen
     */
    private fun updateLocalStateImmediate(updateFunc: (CalendarUiState) -> CalendarUiState) {
        batchUpdateJob?.cancel()
        pendingStateUpdate = null
        
        val currentState = _localUiState.value
        val newState = updateFunc(currentState)
        
        if (currentState != newState) {
            _localUiState.value = newState
        }
    }

    private fun checkTokenValidity() {
        viewModelScope.launch {
            val hasValidToken = calendarUseCase.hasValidAccessToken()
            updateLocalState { it.copy(hasValidToken = hasValidToken) }
            
            if (hasValidToken && shouldLoadCalendars()) {
                loadAvailableCalendars(resetPagination = true)
            }
        }
    }
    
    /**
     * DEDUPLICATION: Intelligent Calendar Loading Decision
     * THREAD-SAFE: Atomic reads und time-based guards
     * PERFORMANCE: Verhindert redundante API-Calls durch Event-Deduplication
     */
    private fun shouldLoadCalendars(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastLoad = currentTime - lastCalendarLoadTime
        val currentState = _localUiState.value
        
        // PERFORMANCE GUARDS: Multiple conditions für intelligente Loading-Entscheidung
        val hasEmptyCalendars = currentState.availableCalendars.isEmpty()
        val notCurrentlyLoading = !isCalendarLoadingInProgress
        val sufficientTimeGap = timeSinceLastLoad > 3000 // Erhöht von 2s auf 3s
        val notRecentlyLoaded = currentState.availableCalendars.isEmpty() || timeSinceLastLoad > 10000 // 10s für reload
        
        return hasEmptyCalendars && notCurrentlyLoading && sufficientTimeGap && notRecentlyLoaded
    }

    /**
     * REACTIVE PATTERN: Beobachtet Änderungen der Calendar Selection
     * AUTOMATIC LOADING: Lädt Events automatisch bei Selection-Änderungen
     * BUG FIX: Lädt Events mit aktueller daysAhead-Konfiguration neu
     * LAZY LOADING: Initial nur begrenzte Anzahl Events für bessere Performance
     */
    private fun observeCalendarSelection() {
        viewModelScope.launch {
            // NOTE: distinctUntilChanged() entfernt - StateFlow ist bereits distinct by design
            calendarSelectionRepository.selectedCalendarIds
                .collect { selectedIds ->
                    val calendarCount = selectedIds.size
                    Logger.d(LogTags.CALENDAR, "🔄 REACTIVE-CALENDAR: Calendar selection changed - $calendarCount calendars")
                    
                    // LAZY LOADING: Auto-load events with lazy loading when selection changes
                    if (selectedIds.isNotEmpty()) {
                        hasSeenNonEmptySelection = true
                        // HIER WIRD NICHTS AUFGELOEST - weder der Hinweis noch der dauerhafte
                        // Raeumauftrag. Bis v1.29.2 geschah beides an dieser Stelle, begruendet
                        // damit, dass der Delta-Sync des gleich folgenden Ladevorgangs jeden
                        // Alarm raeumt, dessen Termin fehlt. Dieser Sync laeuft aber NICHT in
                        // jedem Fall: er sitzt hinter der Pruefung "Eventliste nicht leer" und
                        // steigt zusaetzlich fail-safe aus, wenn die Liste nicht nachweislich
                        // vollstaendig ist. Liefert der neu gewaehlte Kalender null Termine
                        // (anderer Kalender, Dienstplan-Feed gerade leer), passiert gar nichts -
                        // und mit dem geloeschten Auftrag faengt es auch die 6h-Wartung nicht
                        // mehr auf: die Wecker des abgewaehlten Dienstplans klingeln bis zu
                        // 14 Tage weiter, ohne Hinweis und ohne Wiedereinstieg.
                        //
                        // Aufgeloest wird deshalb erst, wo es BELEGT ist: nach einem gelungenen
                        // Sync ueber einer nachweislich vollstaendigen Eventliste
                        // (siehe createAlarmsFromLoadedEvents).
                        //
                        // GEGENRICHTUNG: Der Auftrag bleibt dadurch nicht ewig stehen und kann
                        // auch keine wieder gewollten Wecker loeschen - die 6h-Wartung prueft die
                        // Auswahl selbst erneut und verwirft ihn als hinfaellig, sobald wieder
                        // ein Kalender ausgewaehlt ist (AlarmMaintenanceService, AbwahlRaeumauftrag).
                        loadEventsForSelectedCalendars(
                            loadAll = false, // LAZY LOADING: Start with lazy loading
                            initialPageSize = 10 // LAZY LOADING: Load only 10 events initially
                        )
                    } else {
                        // Nur ein Uebergang "es WAR etwas ausgewaehlt -> jetzt nichts mehr" ist
                        // eine Abwahl. Der Startwert emptySet() ist es nicht (siehe
                        // hasSeenNonEmptySelection).
                        val wasDeselection = hasSeenNonEmptySelection
                        hasSeenNonEmptySelection = false
                        // RACE-GUARD: Auch das Abwaehlen ALLER Kalender ist ein Ereignis, das
                        // laufende Ladevorgaenge ueberholt - es muss deshalb genauso eine neue
                        // Generation ziehen wie ein neuer Ladevorgang. Ohne das bestand ein noch
                        // laufender Lauf (Netz, Sekunden) beide Staleness-Pruefungen, schrieb nach
                        // dem Leeren seine Events zurueck in UI-State und CalendarStateHolder und
                        // legte ueber syncAlarms() Wecker aus den Terminen genau der Kalender an,
                        // die der Nutzer soeben abgewaehlt hatte - waehrend die Oberflaeche
                        // korrekt "kein Kalender ausgewaehlt" zeigte.
                        val deselectGeneration = eventLoadGeneration.incrementAndGet()

                        // Clear events und reset pagination wenn keine Kalender ausgewählt
                        updateLocalState {
                            it.copy(
                                events = emptyList(),
                                eventOffset = 0,
                                totalEvents = 0,
                                hasMoreEvents = false
                            )
                        }
                        // CRITICAL: Update CalendarStateHolder when clearing events
                        calendarStateHolder.clearEvents()

                        if (wasDeselection) {
                            // Der Collector selbst darf hier nicht warten (er muss fuer die
                            // naechste Auswahl-Aenderung sofort wieder bereit sein) - die
                            // Reihenfolge sichert stattdessen die Generation ab.
                            clearAlarmsAfterCalendarDeselection(deselectGeneration)
                        }
                    }
                }
        }
    }

    /**
     * Raeumt die kalenderbasierten Wecker, nachdem der Nutzer den LETZTEN Kalender abgewaehlt hat.
     *
     * DER FEHLER, den das schliesst: Der else-Zweig von [observeCalendarSelection] leerte bisher
     * nur Anzeige und [CalendarStateHolder] - ohne jeden Alarm-Sync. Das Abwaehlen EINES von
     * mehreren Kalendern raeumte dessen Wecker korrekt ab (der Delta-Sync des naechsten
     * Ladevorgangs entfernt jeden Alarm, dessen eventId fehlt), das Abwaehlen des LETZTEN dagegen
     * nicht. Danach gab es auch keinen nachholenden Pfad mehr: die 6h-Wartung, der
     * Pre-Alarm-Worker und der ShiftViewModel-Pfad steigen bei leerer Auswahl bzw. leerer
     * Eventliste alle VOR `syncAlarms()` aus, und der `BootReceiver` armiert die gespeicherten
     * Alarme sogar aktiv neu. Folge: Die Oberflaeche zeigte "kein Kalender ausgewaehlt" und null
     * Termine, waehrend das Geraet bis zu 14 Tage lang weiter nach dem entfernten Dienstplan
     * weckte und der Bildschirm zu dessen Dienstzeiten gedimmt wurde. Abstellen liess sich das nur
     * durch Einzelloeschung jedes Weckers oder die Master-Pause.
     *
     * WARUM DAS HIER AUSDRUECKLICH ERLAUBT IST, obwohl "leer" fuer diese App sonst die
     * gefaehrlichste Luege ist: Diese Leere stammt nicht aus einem Abruf, sondern aus einer
     * ausdruecklichen Nutzeraktion. Die `isComplete`-/Leerlisten-Sperren der uebrigen Aufrufer
     * bleiben davon unberuehrt - sie schuetzen gegen einen GESCHEITERTEN Abruf, und ein
     * gescheiterter Abruf kann diesen Pfad nicht ausloesen. Abgesichert ist das doppelt:
     *  1. [hasSeenNonEmptySelection] - es muss ein Uebergang "war ausgewaehlt -> ist es nicht mehr"
     *     sein, nicht der leere Startwert des noch nicht hydrierten StateFlows.
     *  2. Eine Rueckfrage direkt beim DataStore ueber `getCurrentSelectedCalendarIds()`. Sie
     *     unterscheidet "wirklich leer" von "nicht lesbar" (Result.failure) - bei Zweifel wird
     *     NICHT geraeumt.
     *
     * Geraeumt wird ueber `syncAlarms(emptyList(), config)`, nicht ueber ein eigenes Loeschen:
     * dessen Leerlisten-Zweig ist genau der schonende - er schreibt `persistShiftSpans(emptyList())`
     * (sonst dimmen Dimmer und DND weiter nach dem alten Dienstplan, denn `syncAlarms()` ist der
     * EINZIGE Schreiber des `ShiftSpanStore`) und raeumt mit `keepManualAlarms = true`. Manuelle
     * Wecker stammen nicht aus dem Kalender und duerfen eine Kalender-Abwahl ueberleben. Und der
     * Weg ueber `syncAlarms()` haelt zugleich die Loeschreihenfolge ein (erst `cancelSystemAlarm()`,
     * dann `deleteAlarm()`), die ein eigenes Loeschen hier neu haette nachbauen muessen.
     *
     * DER AUFTRAG UEBERLEBT DEN PROZESSTOD: Sobald die Rueckfrage beim Speicher die Abwahl belegt
     * hat, wird sie im [PendingDeselectionCleanupStore] festgehalten - VOR dem Raeumen - und erst
     * nach nachweislichem Erfolg wieder geloescht. `NonCancellable` schuetzt nur gegen den Abbruch
     * der Coroutine; wird der Prozess getoetet (App weggewischt, Force-Stop, "App bei Nichtnutzung
     * pausieren"), war der Auftrag bis dahin restlos weg: der Fehlerzustand lag im
     * Arbeitsspeicher, und der Uebergangs-Merker [hasSeenNonEmptySelection] ist beim naechsten
     * App-Start per Konstruktion falsch, weil die Auswahl dann von Anfang an leer ist.
     * Abgearbeitet wird der Auftrag danach von der 6h-Wartung, ganz ohne die App.
     *
     * @param deselectGeneration Die beim Abwaehlen gezogene Generation. Waehlt der Nutzer waehrend
     *   der Rueckfragen oben schon wieder einen Kalender an, hat dessen Ladevorgang eine hoehere
     *   Nummer - dann ist dieses Raeumen ueberholt und wuerde die frisch angelegten Wecker sofort
     *   wieder loeschen. `null` fuer den zweiten Anlauf auf Nutzerwunsch: dort gibt es keine
     *   Abwahl-Generation, und eine neue wird erst gezogen, wenn wirklich geraeumt wird (siehe
     *   [retryDeselectionCleanup]).
     */
    private fun clearAlarmsAfterCalendarDeselection(deselectGeneration: Long?) {
        viewModelScope.launch {
            // NonCancellable: Das hier stellt einen Zustand HER ("die Wecker der entfernten Quelle
            // sind weg"). Verlaesst der Nutzer die App unmittelbar nach dem Abwaehlen, wird der
            // viewModelScope gecancelt - ein auf halbem Weg abgebrochener Lauf liesse den
            // Wecker-Bestand verwaist zurueck, und dieses ViewModel selbst holt das nie nach: beim
            // naechsten App-Start ist die Auswahl von Anfang an leer, also greift der
            // Uebergangs-Merker nicht mehr. Gegen den TOD DES PROZESSES hilft NonCancellable
            // dagegen nicht - dafuer gibt es den dauerhaften Auftrag im
            // [PendingDeselectionCleanupStore] weiter unten.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    // 1) Rueckfrage an die QUELLE, nicht an den StateFlow: unterscheidet
                    //    "wirklich nichts mehr ausgewaehlt" von "nicht lesbar".
                    val persistedSelection = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                        .getOrElse { error ->
                            Logger.w(
                                LogTags.CALENDAR,
                                "Abwahl-Aufraeumen uebersprungen: Kalenderauswahl nicht lesbar - " +
                                    "bestehende Wecker bleiben (fail-safe)",
                                error
                            )
                            // Fail-safe heisst hier NICHT stillschweigend: die Wecker der
                            // entfernten Quelle bleiben stehen, waehrend die Oberflaeche
                            // "kein Kalender ausgewaehlt" zeigt. Genau dieser Widerspruch muss
                            // beim Nutzer ankommen (siehe reportDeselectionCleanupFailure).
                            reportDeselectionCleanupFailure()
                            return@withContext
                        }

                    if (persistedSelection.isNotEmpty()) {
                        Logger.d(
                            LogTags.CALENDAR,
                            "Abwahl-Aufraeumen uebersprungen: der Speicher meldet weiterhin " +
                                "${persistedSelection.size} ausgewaehlte Kalender"
                        )
                        // Es ist wieder ein Kalender ausgewaehlt - hier wird deshalb NICHT
                        // geraeumt. Aufgeloest wird aber auch nichts: "wieder ausgewaehlt" ist
                        // kein Beleg dafuer, dass die Wecker des alten Dienstplans weg sind
                        // (dazu muss der Sync des Ladevorgangs wirklich gelaufen sein - siehe
                        // observeCalendarSelection). Hinweis und Auftrag bleiben stehen, bis
                        // genau das belegt ist.
                        return@withContext
                    }

                    // AB HIER ist die Abwahl belegt: der Speicher meldet LESBAR "nichts
                    // ausgewaehlt". Erst dieser Nachweis darf den dauerhaften Auftrag setzen -
                    // ein leeres LADEERGEBNIS erreicht diese Zeile nie, und genau das ist der
                    // Unterschied zwischen "der Nutzer hat abgewaehlt" und "leer ist die
                    // gefaehrlichste Luege".
                    //
                    // VOR jedem Raeumen, nicht danach: sonst bleibt genau das Fenster offen, das
                    // dieser Merker schliessen soll. Scheitert das Festhalten selbst, wird
                    // trotzdem weitergeraeumt - der Auftrag ist dann nur nicht prozessfest, also
                    // so gut wie vorher, aber nicht schlechter.
                    pendingDeselectionCleanupStore.markPending()

                    // 2) Master-Pause: dort ist der Bestand ohnehin geraeumt. Ein Sync waehrend der
                    //    Pause wuerde ueber den zentralen Backstop zusaetzlich einen schwebenden
                    //    Snooze abbrechen - eine Nebenwirkung, die eine Kalender-Abwahl nicht haben soll.
                    if (masterPausePrefs.pausedNow()) {
                        Logger.business(
                            LogTags.ALARM,
                            "⏸️ Master-Pause aktiv - Abwahl-Aufraeumen nicht noetig, es sind keine Wecker gesetzt"
                        )
                        // Es steht ohnehin kein Wecker - nichts ist verwaist, der Hinweis faellt,
                        // und der eben festgehaltene Auftrag ist gegenstandslos.
                        resolveDeselectionCleanupFailure()
                        pendingDeselectionCleanupStore.clearIfPending()
                        return@withContext
                    }

                    // 3) ShiftConfig. Ist sie nicht lesbar, wird NICHT geraeumt (fail-safe, wie in
                    //    createAlarmsFromLoadedEvents): ein Defekt im Konfigurations-Store darf
                    //    keine Wecker kosten.
                    val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                    if (shiftConfig == null) {
                        Logger.e(
                            LogTags.ALARM,
                            "❌ Abwahl-Aufraeumen uebersprungen: ShiftConfig nicht lesbar - " +
                                "bestehende Wecker bleiben unveraendert"
                        )
                        reportDeselectionCleanupFailure()
                        return@withContext
                    }

                    // Bei abgeschalteter Automatik gibt es keine kalenderbasierten Wecker mehr
                    // (das Abschalten selbst raeumt sie). syncAlarms() wuerde in diesem Zweig
                    // zusaetzlich MANUELLE Wecker loeschen - die haben mit dem Kalender nichts zu
                    // tun und ueberleben eine Abwahl.
                    if (!shiftConfig.autoAlarmEnabled) {
                        Logger.d(
                            LogTags.ALARM,
                            "Abwahl-Aufraeumen uebersprungen: Automatik ist aus, es gibt keine " +
                                "kalenderbasierten Wecker"
                        )
                        // Ohne Automatik gibt es keine kalenderbasierten Wecker, die verwaisen
                        // koennten (das Abschalten selbst raeumt sie) - der Hinweis faellt, und
                        // der Auftrag ist gegenstandslos. Er darf hier NICHT stehenbleiben: die
                        // Wartung wuerde ihn spaeter genauso ablehnen, aber ein liegengebliebener
                        // Auftrag ist ein Versprechen, das niemand mehr einloest.
                        resolveDeselectionCleanupFailure()
                        pendingDeselectionCleanupStore.clearIfPending()
                        return@withContext
                    }

                    // 4) RACE-GUARD unmittelbar vor dem einzigen schreibenden Aufruf.
                    if (deselectGeneration != null && deselectGeneration != eventLoadGeneration.get()) {
                        Logger.d(
                            LogTags.CALENDAR,
                            "Abwahl-Aufraeumen uebersprungen: Abwahl $deselectGeneration inzwischen " +
                                "ueberholt (${eventLoadGeneration.get()})"
                        )
                        // Der Auftrag bleibt bewusst stehen: ueberholt heisst, dass gerade wieder
                        // geladen wird - ob dessen Auswahl wirklich nicht leer ist, entscheidet
                        // der naechste Durchgang (hier oder in der Wartung), nicht dieser Abbruch.
                        return@withContext
                    }

                    // ZWEITER ANLAUF (deselectGeneration == null): Die Generation wird ERST HIER
                    // gezogen - wenn feststeht, dass wirklich geraeumt wird. Vorher stand sie am
                    // Anfang von retryDeselectionCleanup(), also VOR jeder Pruefung: lief in dem
                    // Moment ein Ladevorgang (der Nutzer hat inzwischen wieder einen Kalender
                    // ausgewaehlt, die Karte stand aber noch), erklaerte die Erhoehung ihn fuer
                    // ueberholt - er verwarf danach alle Ergebnisse, und es gab weder Termine noch
                    // Wecker noch einen zweiten Anlauf.
                    if (deselectGeneration == null) {
                        eventLoadGeneration.incrementAndGet()
                    }

                    Logger.business(
                        LogTags.ALARM,
                        "🗑️ ABWAHL: letzter Kalender abgewaehlt - kalenderbasierte Wecker und " +
                            "Schichtspannen werden geraeumt (manuelle Wecker bleiben)"
                    )
                    alarmUseCase.syncAlarms(emptyList(), shiftConfig)
                        .onSuccess { remaining ->
                            Logger.business(
                                LogTags.ALARM,
                                "✅ ABWAHL: aufgeraeumt - ${remaining.size} Wecker verbleiben (manuelle)"
                            )
                            // Der Zustand ist behoben - auch wenn er aus einem frueheren
                            // Fehlschlag stammte und der Nutzer den zweiten Anlauf angestossen hat.
                            resolveDeselectionCleanupFailure()
                            // Erst JETZT faellt der dauerhafte Auftrag: nachweislich geraeumt.
                            pendingDeselectionCleanupStore.clearIfPending()
                            // Eigenes try: der Zeitstempel ist reine Buchhaltung fuer die
                            // 6h-Wartung. Scheitert ER, sind die Wecker trotzdem weg - dann darf
                            // dem Nutzer nicht das Gegenteil gemeldet werden (der aeussere
                            // catch-Zweig setzt die Fehlermeldung).
                            try {
                                AlarmMaintenanceService.recordSyncTime(appContext)
                            } catch (e: java.util.concurrent.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.w(
                                    LogTags.ALARM,
                                    "ABWAHL: aufgeraeumt, aber der Zeitstempel der Wartung liess " +
                                        "sich nicht schreiben",
                                    e
                                )
                            }
                        }
                        .onFailure { error ->
                            Logger.e(LogTags.ALARM, "❌ ABWAHL: Aufraeumen fehlgeschlagen", error)
                            // Der dauerhafte Auftrag bleibt ausdruecklich stehen - er ist der
                            // Wiedereinstieg fuer die 6h-Wartung, falls der Nutzer die App nach
                            // dem Fehlschlag nie wieder oeffnet.
                            reportDeselectionCleanupFailure()
                        }
                } catch (e: java.util.concurrent.CancellationException) {
                    // Eine Abbruch-Ausnahme darf nie als Fehler geschluckt werden - sie gehoert
                    // weitergereicht (CancellationException ist in kotlinx.coroutines genau diese Klasse).
                    throw e
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM, "❌ ABWAHL: Ausnahme beim Aufraeumen", e)
                    reportDeselectionCleanupFailure()
                }
            }
        }
    }

    /**
     * Meldet dem Nutzer, dass das Aufraeumen nach der Kalender-Abwahl NICHT durchgekommen ist.
     *
     * WARUM DAS SEIN MUSS: Jeder fail-safe-Abbruch in [clearAlarmsAfterCalendarDeselection] laesst
     * bewusst die bestehenden Wecker stehen - das ist richtig (ein Lesefehler darf keine Wecker
     * kosten), aber es stellt genau den Zustand wieder her, gegen den die Funktion gebaut wurde:
     * die Oberflaeche zeigt "kein Kalender ausgewaehlt", waehrend bis zu 14 Tage lang Wecker des
     * entfernten Dienstplans klingeln. Stuende das nur im Log, waere der Fehler fuer den Nutzer
     * unsichtbar - und ein Zustand, der eine Funktion dauerhaft anhaelt, muss sichtbar sein.
     *
     * IN DIESEM ViewModel gibt es keinen automatischen zweiten Anlauf: beim naechsten App-Start ist
     * die Auswahl von Anfang an leer, der Uebergangs-Merker [hasSeenNonEmptySelection] greift also
     * nicht mehr. Deshalb ist die Meldung ein bleibender ZUSTAND
     * ([CalendarUiState.deselectionCleanupFailures]) und kein fluechtiger Hinweis, und deshalb
     * bietet sie mit [retryDeselectionCleanup] einen Knopf an, der den Anlauf wirklich wiederholt.
     *
     * Der Auftrag selbst haengt allerdings NICHT mehr an dieser Meldung: er liegt dauerhaft im
     * [PendingDeselectionCleanupStore] und wird von der 6h-Wartung abgearbeitet, auch wenn der
     * Nutzer die App nie wieder oeffnet. Diese Meldung bleibt trotzdem noetig - sie ist das
     * einzige, was der Nutzer sieht, solange die Wecker noch klingeln koennen.
     */
    private fun reportDeselectionCleanupFailure() {
        updateDeselectionCleanupFailures { it + 1 }
    }

    /**
     * Der Gegenpart zu [reportDeselectionCleanupFailure]: der Zustand ist aufgeloest.
     *
     * Das ist NICHT nur der gelungene Raeum-Lauf. Aufgeloest ist er auch, wenn es gar nichts (mehr)
     * zu raeumen gibt: bei abgeschalteter Automatik und waehrend der Master-Pause steht kein
     * kalenderbasierter Wecker. Ein stehengebliebener Hinweis waere dann eine Falschmeldung - der
     * Nutzer suchte Wecker, die es nicht mehr gibt.
     *
     * NICHT AUFGELOEST wird dagegen, sobald wieder ein Kalender ausgewaehlt ist. Das war bis
     * v1.29.2 so und war falsch: gedeckt sind die alten Wecker erst, wenn der Delta-Sync des
     * Ladevorgangs wirklich gelaufen ist - und der laeuft bei leerer oder unvollstaendiger
     * Eventliste gerade nicht. Der Beleg liegt deshalb im `onSuccess` von
     * [createAlarmsFromLoadedEvents].
     */
    private fun resolveDeselectionCleanupFailure() {
        updateDeselectionCleanupFailures { 0 }
    }

    /**
     * Schreibt den Zaehler - und schont dabei ein noch nicht ausgeliefertes Batch-Update.
     *
     * `updateLocalStateImmediate` statt `updateLocalState`: Letzteres schiebt das Schreiben in
     * einen `viewModelScope.launch`-Batch. Der meldende Pfad laeuft aber gerade dann, wenn der
     * Nutzer die App verlassen haben kann (NonCancellable) - im abgeraeumten Scope kaeme der Batch
     * nie an, und die Meldung ginge verloren.
     *
     * FALLE: updateLocalStateImmediate verwirft dafuer ein noch offenes Batch-Update
     * (pendingStateUpdate) und rechnet auf `_localUiState.value` weiter. Unmittelbar vor dem
     * meldenden Pfad wurde aber genau so ein Batch geplant - das Leeren der Terminliste im
     * else-Zweig von [observeCalendarSelection]. Ohne die Uebernahme unten kaeme die Meldung an,
     * waehrend die Termine des abgewaehlten Kalenders weiter angezeigt wuerden.
     */
    private fun updateDeselectionCleanupFailures(transform: (Int) -> Int) {
        val pending = pendingStateUpdate
        val base = pending ?: _localUiState.value
        val next = transform(base.deselectionCleanupFailures)
        // Aendert sich der Zaehler nicht, wird hier gar nichts angefasst. Sonst risse ein
        // wirkungsloses Zuruecksetzen - und das laeuft bei JEDER Kalenderauswahl - den gerade
        // geplanten Batch vorzeitig durch die Sofort-Zustellung.
        if (next == base.deselectionCleanupFailures) return
        updateLocalStateImmediate { base.copy(deselectionCleanupFailures = next) }
    }

    /**
     * Zweiter Anlauf fuer das Aufraeumen nach einer Kalender-Abwahl - der Knopf an der Meldung.
     *
     * WARUM DER UEBERGANGS-MERKER HIER BEWUSST NICHT GILT: [hasSeenNonEmptySelection] beschreibt
     * einen Uebergang, der beim Scheitern laengst vorbei ist; er ist im else-Zweig von
     * [observeCalendarSelection] sogar schon zurueckgesetzt. Ein Retry, der ihn abfragte, taete
     * nichts - genau das war der Fehler der ersten Fassung, in der der angebotene Knopf
     * `refreshData()` rief und die verwaisten Wecker nicht anfasste.
     *
     * DIE REGEL "leer ist keine Loeschgrundlage" bleibt dabei unangetastet: die zweite, eigentliche
     * Sicherung steckt in [clearAlarmsAfterCalendarDeselection] selbst - sie fragt den Speicher
     * ueber `getCurrentSelectedCalendarIds()` und raeumt nur, wenn der LESBAR "nichts ausgewaehlt"
     * meldet. Aus einem gescheiterten Lesevorgang wird also weiterhin nie "leer" geschlossen. Und
     * die Rechtfertigung ist dieselbe wie bei der Abwahl selbst: eine ausdrueckliche Nutzeraktion,
     * kein Abrufergebnis.
     *
     * KEINE GENERATION IM VORAUS: Der Aufruf uebergibt bewusst `null`. Frueher stand hier
     * `eventLoadGeneration.incrementAndGet()` - also VOR jeder Pruefung, ob ueberhaupt noch etwas
     * zu raeumen ist. Lief in dem Moment ein Ladevorgang (der Nutzer hatte inzwischen wieder einen
     * Kalender ausgewaehlt, die Karte stand aber noch), galt dieser Lauf ab sofort als ueberholt
     * und verwarf am Ende alle seine Ergebnisse: keine Termine, keine Wecker, und weil das
     * Aufraeumen selbst am "der Speicher meldet weiterhin Kalender"-Zweig ausstieg, auch kein
     * zweiter Anlauf. Die Generation wird jetzt erst gezogen, wenn wirklich geraeumt wird.
     */
    fun retryDeselectionCleanup() {
        Logger.business(LogTags.ALARM, "🔁 ABWAHL: zweiter Anlauf auf Nutzerwunsch")
        clearAlarmsAfterCalendarDeselection(deselectGeneration = null)
    }

    /**
     * PROGRESSIVE CALENDAR LOADING: Verhindert Main-Thread-Blockierung
     * YIELD-BASED: Gibt Control an UI-Thread zwischen Verarbeitungsschritten ab
     * BATCHED: Verarbeitet Kalender in kleinen Chunks für bessere Responsiveness
     */
    fun loadAvailableCalendars(pageSize: Int = 20, resetPagination: Boolean = true) {
        viewModelScope.launch {
            // LAZY LOADING: Prevent duplicate loading operations
            if (isCalendarLoadingInProgress && resetPagination) {
                Logger.d(LogTags.CALENDAR, "Calendar loading already in progress, skipping duplicate request")
                return@launch
            }
            
            // TIME-BASED GUARD: Prevent rapid successive calls
            val currentTime = System.currentTimeMillis()
            if (resetPagination && (currentTime - lastCalendarLoadTime) < 1000) {
                Logger.d(LogTags.CALENDAR, "Calendar loading too frequent, throttling request")
                return@launch
            }
            
            val currentState = _localUiState.value
            val isLoadingMore = !resetPagination && currentState.availableCalendars.isNotEmpty()
            val targetPage = if (resetPagination) 0 else currentState.currentPage
            
            if (resetPagination) {
                isCalendarLoadingInProgress = true
                lastCalendarLoadTime = currentTime
            }
            
            // IMMEDIATE UI FEEDBACK: Show loading state instantly
            updateLocalStateImmediate { 
                it.copy(
                    isLoading = resetPagination,
                    isLoadingMore = isLoadingMore,
                    error = null
                )
            }
            
            try {
                // BACKGROUND LOADING: Load calendars in background
                val calendarPageResult = calendarUseCase.getAvailableCalendarsPaginated(
                    page = targetPage,
                    pageSize = pageSize
                )
                
                calendarPageResult.onSuccess { calendarPage ->
                    // PROGRESSIVE UPDATE: Update UI progressively as data becomes available
                    val newCalendars = if (resetPagination) {
                        calendarPage.calendars
                    } else {
                        currentState.availableCalendars + calendarPage.calendars
                    }
                    
                    // YIELD TO UI: Allow UI thread to process updates
                    kotlinx.coroutines.delay(16) // One frame at 60fps
                    
                    val currentPage = calendarPage.page
                    val newCalendarCount = calendarPage.calendars.size
                    val totalCalendars = calendarPage.totalCalendars
                    
                    updateLocalStateImmediate { 
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            availableCalendars = newCalendars,
                            hasValidToken = true,
                            currentPage = currentPage,
                            hasMoreCalendars = calendarPage.hasNextPage,
                            totalCalendars = totalCalendars
                        )
                    }
                    
                    if (resetPagination) {
                        isCalendarLoadingInProgress = false
                    }
                    
                    Logger.i(LogTags.CALENDAR, "Progressive calendar loading completed - page $currentPage: $newCalendarCount calendars, total: $totalCalendars")
                    
                    // DIAGNOSTIC: Log special case when no calendars are found
                    if (resetPagination && totalCalendars == 0) {
                        Logger.w(LogTags.CALENDAR, "🔍 CALENDAR-DIAGNOSIS: Google account has no calendars accessible via Calendar API")
                        Logger.i(LogTags.CALENDAR, "🔍 CALENDAR-DIAGNOSIS: This could mean:")
                        Logger.i(LogTags.CALENDAR, "   - User's Google account has no calendars created")
                        Logger.i(LogTags.CALENDAR, "   - Calendar access is restricted by organization policy")  
                        Logger.i(LogTags.CALENDAR, "   - API permissions are insufficient")
                        Logger.i(LogTags.CALENDAR, "💡 CALENDAR-DIAGNOSIS: User should create a calendar in Google Calendar first")
                    } else if (resetPagination && totalCalendars > 0) {
                        Logger.i(LogTags.CALENDAR, "✅ CALENDAR-DIAGNOSIS: Successfully found $totalCalendars calendars - user can proceed with calendar selection")
                    }
                    
                }.onFailure { error ->
                    updateLocalStateImmediate { 
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = errorHandler.getErrorMessage(error),
                            hasValidToken = false
                        )
                    }
                    
                    if (resetPagination) {
                        isCalendarLoadingInProgress = false
                    }
                    
                    Logger.e(LogTags.CALENDAR, "Progressive calendar loading failed", error)
                }
                
            } catch (e: Exception) {
                updateLocalStateImmediate { 
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = errorHandler.getErrorMessage(e),
                        hasValidToken = false
                    )
                }
                
                if (resetPagination) {
                    isCalendarLoadingInProgress = false
                }
                
                Logger.e(LogTags.CALENDAR, "Exception during progressive calendar loading", e)
            }
        }
    }
    
    /**
     * PAGINATION: Load next page of calendars
     */
    fun loadMoreCalendars(pageSize: Int = 20) {
        val currentState = _localUiState.value
        if (currentState.hasMoreCalendars && !currentState.isLoadingMore) {
            viewModelScope.launch {
                updateLocalState { it.copy(isLoadingMore = true, error = null) }
                
                calendarUseCase.getAvailableCalendarsPaginated(
                    page = currentState.currentPage + 1,
                    pageSize = pageSize
                ).onSuccess { calendarPage ->
                    val allCalendars = currentState.availableCalendars + calendarPage.calendars
                    val currentPageNumber = calendarPage.page
                    val newCalendarCount = calendarPage.calendars.size
                    val totalCalendarCount = allCalendars.size
                    
                    updateLocalState { 
                        it.copy(
                            isLoadingMore = false,
                            availableCalendars = allCalendars,
                            currentPage = currentPageNumber,
                            hasMoreCalendars = calendarPage.hasNextPage
                        )
                    }
                    
                    Logger.i(LogTags.CALENDAR, "Loaded more calendars page $currentPageNumber: $newCalendarCount new, total: $totalCalendarCount")
                }.onFailure { error ->
                    updateLocalState { 
                        it.copy(
                            isLoadingMore = false,
                            error = errorHandler.getErrorMessage(error)
                        )
                    }
                }
            }
        }
    }

    /**
     * PERFORMANCE CRITICAL: Background Event Loading mit progressiven UI Updates
     * MAIN-THREAD OPTIMIZATION: Komplett asynchrone Event-Loading ohne UI-Blockierung
     * LAZY LOADING: Progressive Event-Darstellung für bessere User Experience
     * 
     * PHASE 2 CLEANUP: daysAhead parameter removed - fixed 14 days per PROJEKT-BRIEFING 4.0
     *
     * @param forceRefresh Ob Cache umgangen werden soll
     * @param initialPageSize Initiale Anzahl Events (LAZY LOADING)
     * @param loadAll Ob alle Events geladen werden sollen (Default: false für Lazy Loading)
     */
    fun loadEventsForSelectedCalendars(
        forceRefresh: Boolean = false,
        initialPageSize: Int = 10, // LAZY LOADING: Nur 10 Events initial
        loadAll: Boolean = false // LAZY LOADING: Default ist Lazy Loading
    ) {
        viewModelScope.launch {
            // RACE-GUARD: Nummer für diesen Aufruf ziehen, bevor irgendein suspend-Call
            // stattfindet - siehe eventLoadGeneration weiter oben.
            val myGeneration = eventLoadGeneration.incrementAndGet()

            val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse {
                    Logger.w(LogTags.CALENDAR, "Could not get selected calendar IDs")
                    emptySet()
                }
            
            if (selectedIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendars selected for event loading")
                return@launch
            }

            // RACE-GUARD: Erneut pruefen, BEVOR irgendein UI-sichtbarer State geschrieben wird -
            // waehrend getCurrentSelectedCalendarIds() suspendierte, koennte ein neuerer Aufruf
            // bereits gestartet UND fertig sein. Ohne diesen Check wuerden die naechsten beiden
            // Schreibvorgaenge (isLoading=true, Lazy-Reset) trotzdem laufen und dem neueren,
            // schon korrekten Ergebnis den Boden unter den Fuessen wegziehen - der spaetere
            // Staleness-Check weiter unten (vgl. eventLoadGeneration) verhindert zwar den
            // fehlerhaften finalen Schreibvorgang, stellt aber isLoading/events NICHT wieder her.
            // Zwischen diesem Check und den beiden Schreibvorgaengen liegt kein weiterer
            // suspend-Punkt, das Fenster ist damit vollstaendig geschlossen.
            if (myGeneration != eventLoadGeneration.get()) {
                Logger.d(LogTags.CALENDAR, "loadEventsForSelectedCalendars superseded before any write (generation $myGeneration)")
                return@launch
            }

            // IMMEDIATE UI FEEDBACK: Show loading state instantly
            updateLocalStateImmediate { it.copy(isLoading = true, error = null) }
            
            try {
                // LAZY LOADING IMPLEMENTATION: Load limited events first
                if (!forceRefresh && !loadAll) {
                    updateLocalStateImmediate { 
                        it.copy(
                            events = emptyList(),
                            eventOffset = 0,
                            totalEvents = 0,
                            hasMoreEvents = true // Assume more events initially
                        )
                    }
                    
                    // CRITICAL: Clear events in CalendarStateHolder when starting lazy loading
                    calendarStateHolder.clearEvents()
                }
                
                val allEvents = mutableListOf<CalendarEvent>()
                var processedCalendars = 0
                var totalEventCount = 0

                // Fehler der einzelnen Kalender mitzaehlen, statt sie nur wegzuloggen.
                // Ohne das galt der Ladevorgang selbst dann als geglueckt, wenn JEDER
                // Kalender an einem toten Token gescheitert war - siehe unten.
                var firstFailure: Throwable? = null
                val failedCalendarIds = mutableSetOf<String>()
                
                // PERFORMANCE OPTIMIZATION: Process calendars sequentially but with proper async handling
                selectedIds.forEach { calendarId ->
                    try {
                        val singleCalendarResult = if (loadAll) {
                            calendarUseCase.getCalendarEventsWithCache(
                                calendarIds = setOf(calendarId),
                                forceRefresh = forceRefresh
                            )
                        } else {
                            // LAZY LOADING: Load only initial page size
                            calendarUseCase.getCalendarEventsLazy(
                                calendarIds = setOf(calendarId),
                                maxEvents = initialPageSize,
                                offset = 0
                            ).map { eventPage ->
                                totalEventCount += eventPage.totalEvents
                                eventPage.events
                            }
                        }
                        
                        singleCalendarResult.onSuccess { events ->
                            allEvents.addAll(events)
                            processedCalendars++

                            // RACE-GUARD: Ein neuerer Aufruf von loadEventsForSelectedCalendars()
                            // läuft bereits (siehe eventLoadGeneration). allEvents/processedCalendars
                            // laufen intern weiter mit, damit spätere Berechnungen in dieser
                            // Coroutine konsistent bleiben - aber es wird nichts mehr nach außen
                            // (UI-State/CalendarStateHolder) geschrieben, sonst überschreibt dieser
                            // langsamere, veraltete Lauf die bereits korrekten Ergebnisse des
                            // neueren Laufs.
                            if (myGeneration != eventLoadGeneration.get()) {
                                return@onSuccess
                            }

                            // PROGRESSIVE UI UPDATE: Update UI with partial results
                            val sortedEvents = allEvents.sortedBy { it.startTime }
                            
                            // LAZY LOADING: Calculate if more events are available
                            val hasMore = if (loadAll) {
                                false // No more events when loading all
                            } else {
                                events.size >= initialPageSize || totalEventCount > sortedEvents.size
                            }
                            
                            updateLocalState { 
                                it.copy(
                                    events = sortedEvents,
                                    totalEvents = if (loadAll) sortedEvents.size else totalEventCount,
                                    hasMoreEvents = hasMore && processedCalendars < selectedIds.size,
                                    eventOffset = sortedEvents.size
                                )
                            }
                            
                            // CRITICAL: Update CalendarStateHolder with progressive events.
                            // Ein Zwischenstand ist per Definition unvollstaendig - es fehlen
                            // mindestens die noch nicht verarbeiteten Kalender.
                            calendarStateHolder.updateEvents(sortedEvents, complete = false)
                            
                    Logger.d(LogTags.CALENDAR, "Progressive loading: ${events.size} events loaded, total: $totalEventCount")
                        }.onFailure { error ->
                            Logger.e(LogTags.CALENDAR, "Failed to load events for calendar ${calendarId.take(8)}...", error)
                            if (firstFailure == null) firstFailure = error
                            failedCalendarIds += calendarId
                            processedCalendars++
                        }

                    } catch (e: Exception) {
                        Logger.e(LogTags.CALENDAR, "Exception loading calendar ${calendarId.take(8)}...", e)
                        if (firstFailure == null) firstFailure = e
                        failedCalendarIds += calendarId
                        processedCalendars++
                    }
                }
                
                // FINAL UPDATE: Complete loading state and CREATE ALARMS
                val finalSortedEvents = allEvents.sortedBy { it.startTime }
                val finalHasMore = if (loadAll) {
                    false
                } else {
                    // LAZY LOADING: More events available if we hit our page size limit
                    finalSortedEvents.size >= (initialPageSize * selectedIds.size) || totalEventCount > finalSortedEvents.size
                }
                
                // Sind ALLE Kalender gescheitert, war das kein erfolgreicher Ladevorgang -
                // egal, was der bisherige Code behauptete.
                //
                // calendarAuthorizationValid DARF NICHT MEHR BEDINGUNGSLOS true SEIN.
                //
                // Frueher stand hier hart `calendarAuthorizationValid = true`, waehrend die
                // Fehler der einzelnen Kalender oben nur weggeloggt wurden. Scheiterten also
                // alle Kalender an einem toten Token, war der Zustand danach:
                // events=leer, error=null, calendarAuthorizationValid=TRUE.
                //
                // Fatal, weil HomeTabContent genau daran den Warnhinweis UND den Knopf
                // "Kalender-Zugriff erneuern" aufhaengt (!calendarAuthorizationValid &&
                // selectedCalendarIds.isNotEmpty()). Der einzige Weg zurueck war damit
                // ausgerechnet im Fehlerfall unsichtbar - und weil der Default false ist,
                // schaltete ein fehlgeschlagener Ladevorgang eine bereits korrekt
                // angezeigte Warnung sogar wieder AUS.
                //
                // Fuer eine Wecker-App ist das die gefaehrlichste Variante: eine leere
                // Schichtliste ohne Hinweis ist von "du hast frei" nicht zu unterscheiden.
                // Mindestens ein Kalender geladen => der Zugriff funktioniert grundsaetzlich.
                // Nur wenn ALLE scheitern, ist die Autorisierung als kaputt zu melden. Ein
                // einzelner fehlschlagender Kalender (geloescht, nicht mehr freigegeben) darf
                // nicht die ganze Anmeldung in Frage stellen.
                //
                // Ausgelagert in resolveCalendarAuthorizationOutcome() (siehe companion object
                // unten) - pur und testbar, damit dieser bereits einmal reale Bug nicht
                // unbemerkt zurueckkehren kann. Siehe CalendarViewModelTest.
                val failure = firstFailure
                val (everythingFailed, authStillValid) = resolveCalendarAuthorizationOutcome(
                    failedCalendars = failedCalendarIds.size,
                    totalSelectedCalendars = selectedIds.size
                )

                // Fehler sichtbar machen, statt eine leere Liste als Wahrheit zu verkaufen.
                val failureMessage = if (everythingFailed && failure != null) {
                    errorHandler.getErrorMessage(failure)
                } else {
                    null
                }

                if (everythingFailed) {
                    Logger.e(
                        LogTags.CALENDAR,
                        "❌ Alle ${failedCalendarIds.size} Kalender fehlgeschlagen - Autorisierung wird als ungueltig gemeldet"
                    )
                }

                // RACE-GUARD: Inzwischen ist ein neuerer Aufruf gestartet - dessen Ergebnisse
                // sind schon (oder werden gerade) korrekt geschrieben. isLoading bewusst NICHT
                // hier zuruecksetzen: der neuere, noch laufende Aufruf hat es selbst auf true
                // gesetzt, und dieser veraltete Lauf darf das nicht unter ihm wegziehen. Auch
                // KEIN syncAlarms() mit diesen veralteten Events - siehe eventLoadGeneration.
                if (myGeneration != eventLoadGeneration.get()) {
                    Logger.d(
                        LogTags.CALENDAR,
                        "Discarding stale loadEventsForSelectedCalendars results (generation $myGeneration superseded by ${eventLoadGeneration.get()})"
                    )
                    return@launch
                }

                updateLocalStateImmediate { state ->
                    state.copy(
                        isLoading = false,
                        events = finalSortedEvents,
                        eventOffset = finalSortedEvents.size,
                        totalEvents = if (loadAll) finalSortedEvents.size else totalEventCount,
                        hasMoreEvents = finalHasMore,
                        calendarAuthorizationValid = authStillValid,
                        lastAuthorizationCheck = System.currentTimeMillis(),
                        error = failureMessage ?: state.error,
                        // Nur der TEILERFOLG. Bei everythingFailed uebernimmt
                        // calendarAuthorizationValid oben die Anzeige - siehe Feld-Kommentar.
                        unavailableCalendarIds = if (everythingFailed) emptySet() else failedCalendarIds
                    )
                }
                
                // Vollstaendigkeit EINMAL bestimmen - sie entscheidet ZWEI Dinge: was der
                // CalendarStateHolder als geteilte Wahrheit ausweist (ShiftViewModel gibt die
                // Liste an syncAlarms weiter!) und ob hier synchronisiert werden darf.
                val displayedListIsComplete = isEventListCompleteForAlarmSync(
                    loadedEventCount = finalSortedEvents.size,
                    totalEventCount = totalEventCount,
                    failedCalendars = failedCalendarIds.size,
                    loadAll = loadAll
                )

                // CRITICAL: Update CalendarStateHolder with final events
                calendarStateHolder.updateEvents(finalSortedEvents, complete = displayedListIsComplete)

                // 🚨 CRITICAL FIX: Automatically create alarms from recognized shifts!
                //
                // ABER NIEMALS AUF EINER UNVOLLSTAENDIGEN LISTE - siehe
                // [isEventListCompleteForAlarmSync]. Die ANZEIGE darf ein Praefix sein, die
                // Grundlage einer Loeschentscheidung nicht.
                if (finalSortedEvents.isNotEmpty()) {
                    // DEBUGGING: Log current state before alarm creation
                    logCurrentStateForDebugging(finalSortedEvents)

                    val eventsForAlarmSync = if (displayedListIsComplete) {
                        finalSortedEvents
                    } else {
                        // Die vollstaendige Liste nachfordern, statt den Sync einfach ausfallen zu
                        // lassen: "App geoeffnet -> Wecker sind aktuell" ist eine tragende
                        // Zusicherung dieser App. Das kostet praktisch nichts, weil
                        // getCalendarEventsLazy() intern ohnehin den kompletten Bestand geholt und
                        // erst danach geschnitten hat - dieser Abruf trifft den Cache
                        // (forceRefresh = false).
                        Logger.d(
                            LogTags.CALENDAR,
                            "Angezeigte Liste ist ein Praefix (${finalSortedEvents.size}/$totalEventCount, " +
                                "${failedCalendarIds.size} Kalender fehlgeschlagen) - vollstaendige Liste fuer den Alarm-Sync nachfordern"
                        )
                        val completeFetch = calendarUseCase
                            .getCalendarEventsWithStatus(calendarIds = selectedIds, forceRefresh = false)
                            .getOrNull()

                        if (completeFetch != null && completeFetch.isComplete && completeFetch.events.isNotEmpty()) {
                            completeFetch.events
                        } else {
                            null
                        }
                    }

                    // RACE-GUARD erneut: das Nachfordern oben ist ein suspend-Punkt, in dem ein
                    // neuerer Ladevorgang gestartet sein kann. Dessen Events sind die aktuellere
                    // Wahrheit; mit den alten zu synchronisieren hiesse, seine Ergebnisse zu
                    // ueberschreiben - inklusive Loeschungen.
                    when {
                        eventsForAlarmSync == null -> Logger.w(
                            LogTags.CALENDAR,
                            "Alarm-Sync uebersprungen: keine nachweislich vollstaendige Eventliste " +
                                "(fail-safe, bestehende Alarme bleiben)"
                        )

                        myGeneration != eventLoadGeneration.get() -> Logger.d(
                            LogTags.CALENDAR,
                            "Alarm-Sync uebersprungen: Ladevorgang $myGeneration inzwischen ueberholt"
                        )

                        else -> {
                            // Erst HIER darf der Holder als vollstaendig gelten: vorher war der
                            // Race-Guard nicht geprueft. Bei einem nachgeforderten Bestand ist das
                            // zugleich die bessere geteilte Wahrheit - ShiftViewModel gibt sie an
                            // syncAlarms() weiter und braucht deshalb den ganzen Bestand, nicht
                            // das Anzeige-Praefix.
                            calendarStateHolder.updateEvents(eventsForAlarmSync, complete = true)
                            // DIES IST DIE EINZIGE AUFRUFSTELLE, und sie erreicht diesen Zweig
                            // nur mit einer nachweislich VOLLSTAENDIGEN, nicht leeren Liste.
                            // Darauf beruht, dass ein gelungener Sync dort den Raeumauftrag nach
                            // einer Kalender-Abwahl loeschen darf. Wer hier einen zweiten
                            // Aufrufer ergaenzt, muss diese Zusicherung mitbringen.
                            createAlarmsFromLoadedEvents(eventsForAlarmSync)
                        }
                    }
                }
                
                if (forceRefresh) {
                    Logger.i(LogTags.CALENDAR, "Progressive calendar events force refreshed - ${finalSortedEvents.size} events loaded for ${CalendarConstants.DEFAULT_DAYS_AHEAD} days${if (!loadAll) " (lazy loaded)" else ""}")
                } else {
                    Logger.d(LogTags.CALENDAR, "Progressive calendar events loaded - ${finalSortedEvents.size} events for ${CalendarConstants.DEFAULT_DAYS_AHEAD} days${if (!loadAll) " (lazy loaded)" else ""}")
                }
                
            } catch (e: Exception) {
                updateLocalStateImmediate { 
                    it.copy(
                        isLoading = false,
                        error = errorHandler.getErrorMessage(e),
                        // PHASE 2 FIX: Mark authorization as invalid on error
                        calendarAuthorizationValid = false,
                        lastAuthorizationCheck = System.currentTimeMillis()
                    )
                }
                Logger.e(LogTags.CALENDAR, "Failed to load calendar events progressively", e)
            }
        }
    }

    /**
     * GRANULAR SELECTION: Einzelne Kalender hinzufügen/entfernen
     */
    fun toggleCalendarSelection(calendarId: String) {
        viewModelScope.launch {
            val currentIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { emptySet() }
            
            if (currentIds.contains(calendarId)) {
                calendarSelectionRepository.removeCalendarId(calendarId)
            } else {
                calendarSelectionRepository.addCalendarId(calendarId)
            }
        }
    }

    /**
     * Entfernt die Kalender aus der Auswahl, die beim letzten Ladevorgang nicht geantwortet haben
     * ([CalendarUiState.unavailableCalendarIds]).
     *
     * AUSSCHLIESSLICH auf Tastendruck des Nutzers - die App tut das NIE von allein. Der Grund
     * steht am Feld: die Kalenderliste ist paginiert, "ID nicht in `availableCalendars`" ist also
     * kein Beweis dafuer, dass der Kalender geloescht wurde. Eine selbsttaetige Bereinigung wuerde
     * bei einem laengeren Ausfall (Freigabe kurzzeitig weg, Server-Stoerung) die Auswahl des
     * Nutzers stillschweigend abraeumen - und danach faende die App den Dienstplan nie wieder,
     * ohne dass irgendwo stuende, warum.
     *
     * Umkehrbar: der Kalender laesst sich in der Kalenderauswahl jederzeit wieder anhaken. Deshalb
     * im Regelfall bewusst ohne Bestaetigungsdialog.
     *
     * EINE AUSNAHME, und sie ist keine Formsache: Bliebe danach KEIN Kalender uebrig, ist das
     * keine Bereinigung mehr, sondern eine Abwahl - und die raeumt seit v1.29.3 alle Wecker der
     * naechsten zwei Wochen samt der Dienstzeit-Fenster fuer Dimmer und "Nicht stoeren"
     * (`clearAlarmsAfterCalendarDeselection`). Ausgeloest wird der Zustand oft durch eine
     * voruebergehende Server- oder Freigabestoerung, also durch etwas, das von allein vergeht.
     * Deshalb fragt die Oberflaeche in genau diesem Fall vorher nach und bietet zuerst den
     * harmlosen Weg an (siehe `StatusTabContent.entfernenWuerdeAuswahlLeeren`). Diese Funktion
     * fuehrt aus, was der Nutzer dort entschieden hat - sie entscheidet nicht selbst.
     *
     * Der Zustand wird hier NICHT selbst geleert - `observeCalendarSelection()` stoesst nach der
     * Aenderung einen neuen Ladevorgang an, und der schreibt [CalendarUiState.unavailableCalendarIds]
     * ohnehin frisch. Ihn hier vorab zu leeren hiesse, ein Ergebnis zu behaupten, das noch niemand
     * gemessen hat.
     */
    fun removeUnavailableCalendarsFromSelection() {
        viewModelScope.launch {
            val betroffen = uiState.value.unavailableCalendarIds
            if (betroffen.isEmpty()) {
                Logger.d(LogTags.CALENDAR, "Kein nicht abrufbarer Kalender in der Auswahl - nichts zu entfernen")
                return@launch
            }

            Logger.business(
                LogTags.CALENDAR,
                "🧹 Nutzer entfernt ${betroffen.size} nicht abrufbare(n) Kalender aus der Auswahl"
            )
            // WARN, damit im Release-Log (nur WARN+) nachvollziehbar bleibt, warum kurz darauf
            // saemtliche kalenderbasierten Wecker verschwinden - sonst sieht der Verlauf nach
            // einer spontanen Raeumung aus.
            if (betroffen.containsAll(uiState.value.selectedCalendarIds)) {
                Logger.w(
                    LogTags.CALENDAR,
                    "⚠️ Nach dem Entfernen bleibt KEIN Kalender ausgewaehlt - das raeumt die " +
                        "kalenderbasierten Wecker und die Dienstzeit-Fenster (vom Nutzer bestaetigt)"
                )
            }
            betroffen.forEach { calendarId ->
                calendarSelectionRepository.removeCalendarId(calendarId)
                    .onFailure { e ->
                        Logger.e(
                            LogTags.CALENDAR,
                            "Kalender ${calendarId.take(8)}... liess sich nicht aus der Auswahl entfernen",
                            e
                        )
                    }
            }
        }
    }

    fun clearError() {
        updateLocalState { it.copy(error = null) }
    }

    fun refreshData(forceRefresh: Boolean = false, useLazyLoading: Boolean = true) {
        if (forceRefresh) {
            Logger.i(LogTags.CALENDAR, "Force refresh requested")
            // Cache für aktuelle Auswahl invalidieren
            viewModelScope.launch {
                val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                    .getOrElse { emptySet() }
                if (selectedIds.isNotEmpty()) {
                    calendarUseCase.invalidateCalendarCache(selectedIds)
                    // LAZY LOADING: Reset event pagination on refresh
                    updateLocalState { 
                        it.copy(
                            eventOffset = 0,
                            hasMoreEvents = false,
                            totalEvents = 0
                        )
                    }
                    loadEventsForSelectedCalendars(
                        forceRefresh = true,
                        loadAll = !useLazyLoading, // LAZY LOADING: Respect lazy loading preference
                        initialPageSize = if (useLazyLoading) 10 else 50
                    )
                    
                    // BACKGROUND SYNC: Start background refresh for other calendars
                    startBackgroundSync()
                } else {
                    loadAvailableCalendars(resetPagination = true) // PAGINATION: Reset to first page on refresh
                }
            }
        } else {
            checkTokenValidity()
        }
    }
    
    /**
     * FIXED: daysAhead is now always 14 days as per Briefing 4.0
     * No longer read from ShiftConfig
     */


    /**
     * LAZY LOADING: Load more events with pagination
     * FIXED: Always uses DEFAULT_DAYS_AHEAD (14 days) per PROJEKT-BRIEFING 4.0
     * PERFORMANCE FIX: Verbesserte Race Condition Prevention
     *
     * OFFSET-SEMANTIK: Nachgeladen wird immer ein PRAEFIX der Vereinigung aller
     * ausgewaehlten Kalender (offset = 0, maxEvents = bereits geladen + limit) - NICHT
     * eine Seite ab [offset].
     *
     * Warum: getCalendarEventsLazy() bildet intern erst die Vereinigung ALLER uebergebenen
     * calendarIds, sortiert sie nach startTime und schneidet daraus subList(offset,
     * offset+maxEvents) heraus. Der Erst-Ladevorgang (loadEventsForSelectedCalendars) laedt
     * dagegen PRO Kalender die ersten initialPageSize Events - bei mehr als einem Kalender
     * ist das eben KEIN Praefix der Vereinigung. Mit dem alten
     * "offset = bisherige Listenlaenge" mischten sich damit zwei unvereinbare
     * Offset-Semantiken: die zurueckgegebene Seite enthielt Events, die bereits in der
     * Liste standen (doppelte LazyColumn-Keys -> IllegalArgumentException "Key was already
     * used" -> Crash; und doppelte Schichten in Home ueber den CalendarStateHolder),
     * waehrend ein Block dazwischen komplett fehlte.
     *
     * Das Praefix ist die einzige Slice, die mit der Erst-Ladung ueberhaupt vergleichbar
     * ist, und kostet nichts: getCalendarEventsLazy() holt intern ohnehin alle Events jedes
     * Kalenders und schneidet erst danach - ein hoeherer offset spart also keinen
     * Netzwerk-Call. Zusaetzlich wird defensiv nach id dedupliziert und neu sortiert
     * (mergeMoreEvents, pur + testbar), damit ein kuenftiger Umbau nicht wieder Duplikate
     * in dieselben zwei Senken schreibt.
     */
    fun loadMoreEvents(offset: Int = 0, limit: Int = 50) {
        viewModelScope.launch {
            val currentState = _localUiState.value

            // RACE CONDITION PROTECTION: Atomic check and set
            if (currentState.isLoadingMoreEvents) {
                Logger.w(LogTags.CALENDAR, "loadMoreEvents already in progress, ignoring duplicate call")
                return@launch
            }

            // RACE-GUARD: Generation nur LESEN, niemals hochzaehlen. Nachladen ist ein
            // Anhaenger an den aktuellen Ladevorgang, kein neuer - wuerde es selbst eine
            // Nummer ziehen, wuerde es einen gerade laufenden
            // loadEventsForSelectedCalendars() faelschlich als "ueberholt" abwuergen
            // (haengender Spinner, leere Liste). Umgekehrt darf ein noch laufendes
            // Nachladen NICHT an die frische Liste eines inzwischen gestarteten
            // "Aktualisieren" anhaengen - siehe Pruefung in onSuccess.
            val baseGeneration = eventLoadGeneration.get()

            // IMMEDIATE STATE UPDATE: Prevent further calls
            updateLocalStateImmediate { it.copy(isLoadingMoreEvents = true, error = null) }

            val selectedIds = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { emptySet() }

            if (selectedIds.isEmpty()) {
                Logger.w(LogTags.CALENDAR, "No calendars selected for loading more events")
                updateLocalStateImmediate { it.copy(isLoadingMoreEvents = false) }
                return@launch
            }

            // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0

            // Der uebergebene offset ist nur ein Hinweis des Aufrufers auf die bereits
            // angezeigte Menge; maszgeblich ist der aktuelle State (er kann inzwischen
            // gewachsen sein).
            val alreadyLoaded = maxOf(offset, _localUiState.value.events.size)
            val requestedMaxEvents = resolveLoadMoreWindow(alreadyLoaded, limit)

            calendarUseCase.getCalendarEventsLazy(
                calendarIds = selectedIds,
                maxEvents = requestedMaxEvents,
                offset = 0
            ).onSuccess { eventPage ->
                // RACE-GUARD: Inzwischen laeuft ein neuer loadEventsForSelectedCalendars() -
                // dessen Liste ist die aktuellere Wahrheit, an die hier nichts angehaengt
                // werden darf. isLoadingMoreEvents muss trotzdem zurueckgesetzt werden:
                // kein anderer Pfad raeumt dieses Flag auf, es wuerde sonst jedes weitere
                // Nachladen dauerhaft blockieren.
                if (baseGeneration != eventLoadGeneration.get()) {
                    Logger.d(
                        LogTags.CALENDAR,
                        "Discarding stale loadMoreEvents results (generation $baseGeneration superseded by ${eventLoadGeneration.get()})"
                    )
                    updateLocalStateImmediate { it.copy(isLoadingMoreEvents = false) }
                    return@onSuccess
                }

                val merged = mergeMoreEvents(
                    currentEvents = _localUiState.value.events,
                    pageEvents = eventPage.events,
                    totalEvents = eventPage.totalEvents
                )

                // updateLocalStateImmediate, NICHT updateLocalState: der gebatchte Pfad legt das
                // Ergebnis nur in pendingStateUpdate und plant einen Job 16-33 ms spaeter. Jeder
                // dazwischenkommende updateLocalStateImmediate-Aufruf (z. B. der Start eines
                // "Aktualisieren") cancelt diesen Job und verwirft das Pending ERSATZLOS. Genau
                // daran haengt hier der einzige Ruecksetzpfad von isLoadingMoreEvents im
                // Erfolgsfall - die Wache am Anfang von loadMoreEvents() haette danach jedes
                // weitere Nachladen fuer die Lebensdauer des ViewModels blockiert (Dauer-Spinner).
                // Die beiden anderen Ruecksetzstellen benutzen aus demselben Grund bereits
                // updateLocalStateImmediate.
                updateLocalStateImmediate {
                    it.copy(
                        isLoadingMoreEvents = false,
                        events = merged.events,
                        hasMoreEvents = merged.hasMoreEvents,
                        eventOffset = merged.eventOffset,
                        totalEvents = eventPage.totalEvents
                    )
                }

                // CRITICAL: Update CalendarStateHolder when loading more events.
                // Nachladen liefert ein groesseres PRAEFIX der Vereinigung, nicht zwangslaeufig
                // den ganzen Bestand - vollstaendig nur, wenn nichts mehr aussteht.
                calendarStateHolder.updateEvents(
                    merged.events,
                    complete = !merged.hasMoreEvents && merged.events.size >= eventPage.totalEvents
                )

                Logger.i(LogTags.CALENDAR, "Loaded ${eventPage.events.size} union-prefix events for ${CalendarConstants.DEFAULT_DAYS_AHEAD} days, total: ${merged.events.size}/${eventPage.totalEvents}")
            }.onFailure { error ->
                // Ebenfalls immediate - gleiche Begruendung wie im Erfolgszweig: ein verworfener
                // Batch liesse isLoadingMoreEvents dauerhaft auf true stehen.
                updateLocalStateImmediate {
                    it.copy(
                        isLoadingMoreEvents = false,
                        error = errorHandler.getErrorMessage(error)
                    )
                }
            }
        }
    }
    
    fun getCacheStats() {
        viewModelScope.launch {
            val stats = calendarUseCase.getCacheStats()
            Logger.i(LogTags.CALENDAR_CACHE, stats)
        }
    }
    
    fun clearEventCache() {
        viewModelScope.launch {
            calendarUseCase.clearEventCache()
            Logger.i(LogTags.CALENDAR_CACHE, "Event cache cleared by user")
        }
    }

    /**
     * 🚨 CRITICAL FIX: Automatically create alarms from loaded events
     * CRITICAL FIX: Only create alarms if events actually exist
     *
     * @param events MUSS eine nachweislich VOLLSTAENDIGE Eventliste sein - die einzige
     *   Aufrufstelle in [loadEventsForSelectedCalendars] stellt das sicher. Nur deshalb darf der
     *   gelungene Sync hier den offenen Raeumauftrag nach einer Kalender-Abwahl loeschen.
     */
    private fun createAlarmsFromLoadedEvents(events: List<CalendarEvent>) {
        viewModelScope.launch {
            try {
                val eventCount = events.size
                
                // CRITICAL FIX: Don't create alarms if no events exist
                if (events.isEmpty()) {
                    Logger.business(LogTags.ALARM, "✅ NO-EVENTS: No calendar events found - no alarms to create")
                    return@launch
                }

                // Master-Pause: dies ist der primaere, bei jedem Kalender-Ladevorgang (App-Start,
                // Vordergrund-Sync) durchlaufene Alarm-Erstellungspfad - unabhaengig vom
                // ShiftViewModel-getriebenen Pfad. Ohne dieses Gate reaktiviert ein simples
                // Oeffnen der App waehrend einer aktiven Master-Pause lautlos alle Wecker (am
                // Fairphone nach einem Reboot-Test reproduziert: 0 Alarme direkt nach dem Boot,
                // aber 5 Alarme nach dem ersten App-Start trotz weiterhin aktiver Pause).
                if (masterPausePrefs.pausedNow()) {
                    Logger.business(LogTags.ALARM, "⏸️ Master-Pause aktiv - Alarm-Erstellung aus geladenen Events uebersprungen")
                    return@launch
                }

                Logger.business(LogTags.ALARM, "🚨 TIMING-FIX: Starting alarm creation for $eventCount loaded events")
                
                // 🔍 DEBUGGING: Log details about the events we found
                events.forEach { event ->
                    Logger.business(LogTags.ALARM, "🔍 FOUND-EVENT: '${event.title}' on ${event.startTime.toLocalDate()} at ${event.startTime.toLocalTime()} (Calendar: ${event.calendarId.take(8)}...)")
                }
                
                // TIMING FIX: Wait for ShiftConfig with retry logic
                var shiftConfig: com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig? = null
                var attempts = 0
                val maxAttempts = 10 // Try for up to 5 seconds (10 * 500ms)
                
                while (shiftConfig == null && attempts < maxAttempts) {
                    shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                    
                    if (shiftConfig == null) {
                        attempts++
                        Logger.d(LogTags.ALARM, "⏳ TIMING-FIX: ShiftConfig not ready yet, attempt $attempts/$maxAttempts")
                        kotlinx.coroutines.delay(500) // Wait 500ms before next attempt
                    }
                }
                
                if (shiftConfig == null) {
                    // KEIN Default-Fallback mehr, und zwar bewusst: bis v1.22.1 schrieb dieser
                    // Zweig `ShiftConfig.getDefaultConfig()` PERSISTENT in den Store. Seit
                    // `ShiftConfigRepository` zwischen "noch nie konfiguriert" und "vorhanden,
                    // aber unlesbar" unterscheidet, kann `getCurrentShiftConfig()` hier nur noch
                    // aus EINEM Grund fehlschlagen: der Store ist defekt oder unlesbar. Der
                    // Not-konfiguriert-Fall liefert die Standardkonfiguration bereits als Erfolg.
                    //
                    // Genau in diesem Defektfall war der Fallback fatal: er hat die Weckzeiten des
                    // Nutzers mit den Standardzeiten ueberschrieben - lautlos, bei JEDEM
                    // Kalender-Ladevorgang, also bei jedem App-Start. Damit war die Sicherung im
                    // Repository ("die echte Konfiguration wird NICHT ueberschrieben") im Betrieb
                    // wirkungslos. Der bewusste Weg zum Default heisst `resetToDefaults()` und
                    // gehoert dem Nutzer.
                    //
                    // Stattdessen fail-safe: diesen Sync auslassen. Bestehende Alarme bleiben
                    // gesetzt, die Rohdaten liegen als `shift_config_broken` gesichert im Store,
                    // und der naechste Ladevorgang versucht es erneut.
                    Logger.e(
                        LogTags.ALARM,
                        "❌ SHIFT-CONFIG: nach $maxAttempts Versuchen nicht lesbar - Alarm-Sync wird " +
                            "AUSGELASSEN. Bestehende Alarme bleiben unveraendert; die Konfiguration wird " +
                            "NICHT mit Standardwerten ueberschrieben."
                    )
                }
                
                if (shiftConfig?.autoAlarmEnabled == true) {
                    Logger.business(LogTags.ALARM, "✅ TIMING-FIX: ShiftConfig available with autoAlarm enabled, creating alarms...")
                    
                    // Orchestrator: syncAlarms fuehrt einen eventId-basierten Delta-Sync durch,
                    // der bestehende UND manuelle Alarme (eventId leer) schont, nur wirklich
                    // entfernte Events loescht und die System-Alarme INTERN setzt (inkl.
                    // idempotentem Re-Arming). Kein Vorab-deleteAllAlarms und kein separates
                    // scheduleSystemAlarm mehr im ViewModel (frueher: Verlustfenster + Doppel-Scheduling).
                    alarmUseCase.syncAlarms(events, shiftConfig)
                        .onSuccess { syncedAlarms ->
                            Logger.business(LogTags.ALARM, "✅ AUTO-ALARM: Alarm-Sync erfolgreich - ${syncedAlarms.size} Alarme aktiv")
                            // HIER, und nur hier, ist "nichts ist mehr verwaist" BELEGT: der
                            // Delta-Sync ist ueber einer nachweislich VOLLSTAENDIGEN Eventliste
                            // durchgelaufen (die einzige Aufrufstelle uebergibt nur eine solche)
                            // und hat damit jeden Alarm entfernt, dessen Termin fehlt - auch die
                            // des zuvor abgewaehlten Dienstplans. Ein blosses "es ist wieder ein
                            // Kalender ausgewaehlt" ist dieser Beleg NICHT: laeuft der Sync nicht
                            // (leere oder unvollstaendige Liste), bleiben die alten Wecker
                            // armiert. Deshalb faellt der dauerhafte Raeumauftrag erst an dieser
                            // Stelle - und mit ihm der Hinweis in der Oberflaeche.
                            resolveDeselectionCleanupFailure()
                            pendingDeselectionCleanupStore.clearIfPending()
                            // "Letzter Sync"-Zeitstempel auch im Vordergrund setzen, damit die
                            // Status-Anzeige den tatsaechlich letzten Sync widerspiegelt (nicht nur die 6h-Wartung).
                            AlarmMaintenanceService.recordSyncTime(appContext)
                        }
                        .onFailure { error ->
                            Logger.e(LogTags.ALARM, "❌ AUTO-ALARM: Alarm-Sync fehlgeschlagen", error)
                        }
                } else {
                    val configStatus = shiftConfig?.let { "autoAlarmEnabled=${it.autoAlarmEnabled}" } ?: "ShiftConfig is null"
                    Logger.w(LogTags.ALARM, "⚠️ TIMING-FIX: Cannot create alarms - $configStatus")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ AUTO-ALARM: Exception during alarm creation", e)
            }
        }
    }

    /**
     * DEBUGGING: Logs current state to help diagnose timing issues
     */
    private suspend fun logCurrentStateForDebugging(events: List<CalendarEvent>) {
        try {
            val eventCount = events.size
            Logger.business(LogTags.ALARM, "🔍 DEBUG-STATE: Starting alarm creation process")
            Logger.business(LogTags.ALARM, "🔍 DEBUG-STATE: Events loaded: $eventCount")
            
            events.take(3).forEach { event ->
                Logger.d(LogTags.ALARM, "🔍 DEBUG-STATE: Event: '${event.title}' at ${event.startTime}")
            }
            
            val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
            if (shiftConfig != null) {
                val definitionCount = shiftConfig.definitions.size
                Logger.business(LogTags.ALARM, "🔍 DEBUG-STATE: ShiftConfig available - autoAlarm=${shiftConfig.autoAlarmEnabled}, definitions=$definitionCount")
                shiftConfig.definitions.forEach { def ->
                    Logger.d(LogTags.ALARM, "🔍 DEBUG-STATE: ShiftDef: '${def.name}' enabled=${def.isEnabled}, keywords=${def.keywords}")
                }
            } else {
                Logger.w(LogTags.ALARM, "🔍 DEBUG-STATE: ⚠️ ShiftConfig is NULL - this is the problem!")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "🔍 DEBUG-STATE: Exception during debugging", e)
        }
    }
    
    /**
     * BACKGROUND SYNC: Intelligente Hintergrund-Synchronisation
     * Aktualisiert stale Cache-Einträge ohne die UI zu blockieren
     */
    private fun startBackgroundSync() {
        viewModelScope.launch {
            try {
                // Get all calendar IDs that might need background sync
                val allCalendarIds = _localUiState.value.availableCalendars.map { it.id }.toSet()
                
                if (allCalendarIds.isNotEmpty()) {
                    val calendarCount = allCalendarIds.size
                    Logger.d(LogTags.CALENDAR, "Starting background sync for $calendarCount calendars")
                    
                    // Use batch processing to avoid overwhelming the API
                    allCalendarIds.chunked(3).forEach { batch ->
                        batch.forEach { calendarId ->
                            // Load events with cache (allows stale) for each calendar
                            // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days
                            calendarUseCase.getCalendarEventsWithCache(
                                calendarIds = setOf(calendarId),
                                forceRefresh = false
                            ).onSuccess {
                                Logger.d(LogTags.CALENDAR, "Background sync completed for calendar ${calendarId.take(8)}...")
                            }.onFailure {
                                Logger.w(LogTags.CALENDAR, "Background sync failed for calendar ${calendarId.take(8)}...", it)
                            }
                        }
                        
                        // Small delay between batches to prevent API rate limiting
                        kotlinx.coroutines.delay(200)
                    }
                    
                    Logger.i(LogTags.CALENDAR, "Background sync completed for all calendars")
                }
            } catch (e: Exception) {
                Logger.w(LogTags.CALENDAR, "Background sync failed", e)
            }
        }
    }
    
    /**
     * CRITICAL FIX: Enhanced Cleanup Resources on ViewModel destruction
     * MEMORY LEAK PREVENTION: Proper resource cleanup to prevent mutex errors
     */
    override fun onCleared() {
        try {
            Logger.d(LogTags.LIFECYCLE, "CalendarViewModel: Starting cleanup...")
            
            // CRITICAL FIX: Cancel ALL pending coroutines immediately
            batchUpdateJob?.cancel()
            batchUpdateJob = null
            pendingStateUpdate = null
            
            // CRITICAL FIX: Reset ALL volatile flags to prevent stale operations
            isCalendarLoadingInProgress = false
            lastCalendarLoadTime = 0L
            
            // CRITICAL FIX: Clear state to prevent memory leaks
            _localUiState.value = CalendarUiState()
            
            Logger.d(LogTags.LIFECYCLE, "CalendarViewModel: Cleanup completed successfully")
            
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during CalendarViewModel cleanup", e)
        }
        
        // Note: ViewModelScope automatically cancels all coroutines
        // CalendarRepository cleanup wird durch DI Container gehandhabt
    }

    companion object {
        /**
         * Nutzertext, wenn das Aufraeumen nach der Kalender-Abwahl gescheitert ist.
         *
         * Aufbau bewusst dreiteilig - WAS ist passiert, welche FOLGE hat das, welcher AUSWEG
         * bleibt. Ohne die Folge klaenge es nach einer Kleinigkeit, dabei klingelt das Geraet
         * weiter nach einem Dienstplan, den der Nutzer gerade entfernt hat. Kein Begriff aus dem
         * Code (kein Sync, kein Speicher, keine Konfiguration): der Nutzer kann mit keinem davon
         * etwas anfangen, und keiner sagt ihm, was zu tun ist.
         *
         * DER AUSWEG MUSS ZUM ANGEBOTENEN KNOPF PASSEN: die erste Fassung nannte zwei ganz andere
         * Wege ("noch einmal aus- und abwaehlen"), waehrend der Knopf daneben `refreshData()` rief
         * und die verwaisten Wecker gar nicht anfasste. Jetzt ist der erste genannte Weg genau der
         * Knopf ([DESELECTION_CLEANUP_RETRY_ACTION]); die Einzelloeschung bleibt als Notausweg
         * daneben stehen, falls auch der zweite Anlauf scheitert.
         *
         * Als Konstante und nicht inline, damit der Test genau diesen Text festhalten kann.
         */
        internal const val DESELECTION_CLEANUP_FAILED_MESSAGE: String =
            "Die Wecker des abgewählten Kalenders konnten nicht entfernt werden. " +
                "Es können weiterhin Wecker aus diesem Dienstplan klingeln – bis zu zwei Wochen " +
                "im Voraus. Tippe auf \"Erneut versuchen\"; hilft das nicht, lösche sie im Tab " +
                "\"Wecker\" einzeln."

        /**
         * Beschriftung des Knopfes an der Meldung. Muss woertlich in
         * [DESELECTION_CLEANUP_FAILED_MESSAGE] vorkommen - ein Text, der auf einen anders
         * beschrifteten Knopf verweist, schickt den Nutzer ins Leere.
         */
        internal const val DESELECTION_CLEANUP_RETRY_ACTION: String = "Erneut versuchen"

        /**
         * PURE, TESTBAR: Entscheidet anhand der Kalender-Ladeergebnisse, ob die
         * Google-Autorisierung noch als gueltig gilt.
         *
         * Bewusst aus loadEventsForSelectedCalendars() ausgelagert - dort haengt daran ein
         * bereits einmal real aufgetretener Bug (siehe Kommentar am Aufrufer):
         * calendarAuthorizationValid stand fest auf true, obwohl JEDER ausgewaehlte Kalender
         * an einem toten Token gescheitert war, und versteckte damit den einzigen Weg zurueck
         * ("Kalender-Zugriff erneuern"). Als reine Funktion ohne Android-/Coroutine-Abhaengigkeiten
         * laesst sich genau diese Kombination in CalendarViewModelTest ohne Mocking festhalten.
         *
         * Regel: Nur wenn ALLE ausgewaehlten Kalender fehlgeschlagen sind, gilt die Autorisierung
         * als kaputt. Ein einzelner fehlschlagender Kalender (geloescht, nicht mehr freigegeben)
         * darf die Anmeldung nicht in Frage stellen.
         */
        internal fun resolveCalendarAuthorizationOutcome(
            failedCalendars: Int,
            totalSelectedCalendars: Int
        ): CalendarAuthorizationOutcome {
            val everythingFailed = failedCalendars > 0 && failedCalendars == totalSelectedCalendars
            return CalendarAuthorizationOutcome(
                everythingFailed = everythingFailed,
                authStillValid = !everythingFailed
            )
        }

        /**
         * PURE, TESTBAR: Darf die geladene Eventliste in die Alarm-Pipeline
         * ([AlarmUseCase.syncAlarms]) gegeben werden?
         *
         * WARUM DAS NOETIG IST: Der Vordergrund-Ladevorgang laeuft im Normalfall LAZY - pro
         * Kalender nur die ersten [initialPageSize] (10) Events, waehrend `totalEventCount` den
         * vollen 14-Tage-Bestand mitzaehlt. Genau diese abgeschnittene Liste ging bis hierher
         * unveraendert an syncAlarms(), und dessen Delta-Sync loescht JEDEN bestehenden Alarm,
         * dessen eventId in der uebergebenen Liste fehlt - er kann "Termin geloescht" nicht von
         * "Termin lag hinter dem 10er-Praefix" unterscheiden. Bei mehr als zehn Schichten in 14
         * Tagen (fuer einen Schichtplan der Normalfall) loeschte damit JEDES App-Oeffnen die
         * spaetesten Wecker, samt "Schicht entfaellt"-Notification, bis die naechste 6h-Wartung
         * sie wieder anlegte.
         *
         * Zweiter Fall, gleiche Fehlerklasse: hat auch nur EIN Kalender nicht geantwortet, fehlen
         * dessen Events - und der Delta-Sync liest das als "alle diese Termine sind weg".
         *
         * Regel deshalb: synchronisiert wird NUR auf einer nachweislich vollstaendigen Liste.
         * Fehlt die Vollstaendigkeit, ist das kein Grund zu loeschen - die 6h-Wartung, der
         * Pre-Alarm-Refresh und ein "Aktualisieren" mit vollem Abruf holen das nach (lieber ein
         * veralteter Wecker als gar keiner).
         */
        internal fun isEventListCompleteForAlarmSync(
            loadedEventCount: Int,
            totalEventCount: Int,
            failedCalendars: Int,
            loadAll: Boolean
        ): Boolean {
            if (failedCalendars > 0) return false
            // Beim vollen Abruf gibt es kein Praefix - die geladene Liste IST der Bestand.
            if (loadAll) return true
            return loadedEventCount >= totalEventCount
        }

        /**
         * PURE, TESTBAR: Wie viele Events aus der Vereinigung aller ausgewaehlten Kalender
         * beim Nachladen angefordert werden.
         *
         * Immer ein PRAEFIX (offset = 0) der Vereinigung, gross genug fuer alles bereits
         * Angezeigte PLUS [limit] neue - siehe die Begruendung an loadMoreEvents(). Negative
         * bzw. nicht-positive Eingaben werden geklemmt, damit ein fehlerhafter Aufrufer
         * nicht eine leere Seite anfordert und "keine weiteren Events" vortaeuscht.
         */
        internal fun resolveLoadMoreWindow(alreadyLoaded: Int, limit: Int): Int =
            maxOf(alreadyLoaded, 0) + maxOf(limit, 1)

        /**
         * PURE, TESTBAR: Fuehrt die bereits angezeigten Events mit der nachgeladenen
         * Vereinigungs-Seite zusammen.
         *
         * Deduplizierung nach [CalendarEvent.id] ist hier NICHT kosmetisch: die Liste landet
         * unverändert in einer LazyColumn mit `key = { event -> event.id }`. Zwei Eintraege
         * mit derselben id lassen SubcomposeLayout mit
         * IllegalArgumentException("Key ... was already used") abstuerzen - und ueber den
         * CalendarStateHolder erkennt ShiftViewModel dieselbe Schicht zweimal.
         *
         * Neu sortiert wird, weil die Vereinigungs-Seite Events enthalten kann, die
         * zeitlich VOR bereits angezeigten liegen (unterschiedliche Kalender).
         *
         * Bei gleicher id gewinnt der Eintrag aus [pageEvents]: er ist frisch aus dem
         * UseCase, waehrend der bereits angezeigte aus einem aelteren Ladevorgang stammt -
         * eine verschobene Schicht wuerde sonst bis zum naechsten vollen Refresh mit der
         * alten Uhrzeit stehenbleiben. Eintraege, die NUR in [currentEvents] stehen
         * (jenseits des geladenen Praefix), bleiben unangetastet erhalten.
         *
         * eventOffset ist die Laenge des Ergebnisses und damit wieder ein gueltiger
         * Vereinigungs-Offset fuer den naechsten Aufruf; hasMoreEvents leitet sich aus dem
         * echten Gesamtbestand ab, nicht aus der Seitengroesse.
         */
        internal fun mergeMoreEvents(
            currentEvents: List<CalendarEvent>,
            pageEvents: List<CalendarEvent>,
            totalEvents: Int
        ): MoreEventsMergeResult {
            val merged = (pageEvents + currentEvents)
                .distinctBy { it.id }
                .sortedBy { it.startTime }

            return MoreEventsMergeResult(
                events = merged,
                eventOffset = merged.size,
                hasMoreEvents = merged.size < totalEvents
            )
        }
    }
}
