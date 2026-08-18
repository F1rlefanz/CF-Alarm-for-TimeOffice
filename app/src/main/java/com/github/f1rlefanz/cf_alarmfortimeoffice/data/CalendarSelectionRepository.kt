package com.github.f1rlefanz.cf_alarmfortimeoffice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Extension property for Context to create DataStore
// corruptionHandler: eine kaputte Präferenzdatei wird durch einen leeren Zustand ersetzt statt
// den DataStore.data-Flow dauerhaft (für die gesamte Prozesslaufzeit) zu blockieren - siehe
// initializeFromDataStore(), deren try/catch sonst der einzige Fangnetz-Punkt wäre und den
// Collector nur einmalig beendet, ohne _selectedCalendarIds je wieder zu aktualisieren.
private val Context.calendarSelectionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calendar_selection_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = { emptyPreferences() })
)

/**
 * REINE FUNKTION (deshalb top-level + `internal`: direkt testbar).
 *
 * Darf das Ergebnis eines Reads als Wahrheit in den StateFlow?
 *
 * Eine LEERE Auswahl ist nur dann eine Aussage, wenn der Store ueberhaupt lesbar war. Vor der
 * ersten Entsperrung liefert ein CE-Read still `emptyPreferences()` und meldet Erfolg - "keine
 * Kalender ausgewaehlt" ist dann eine Erfindung, und jeder Wartungslauf bricht mit "No calendars
 * selected" ab, ohne dass irgendwo ein Fehler entstuende.
 *
 * Ein NICHT leeres Ergebnis dagegen kann aus einem gesperrten Store gar nicht stammen und wird
 * immer akzeptiert - so bleibt die Regel auch dann richtig, wenn der `UserManager` sich irrt.
 */
internal fun shouldAcceptSelectionRead(userUnlocked: Boolean, ids: Set<String>): Boolean =
    userUnlocked || ids.isNotEmpty()

/**
 * CalendarSelectionRepository - Persistente Speicherung der ausgewählten Kalender
 * 
 * SINGLE SOURCE OF TRUTH IMPLEMENTATION:
 * ✅ Zentrale Verwaltung der ausgewählten Kalender-IDs
 * ✅ Persistente Speicherung mit DataStore Preferences
 * ✅ StateFlow-basierte API mit synchronem .value Zugriff
 * ✅ Atomare State Updates - keine Race Conditions
 * ✅ Result-basierte Fehlerbehandlung
 * ✅ Comprehensive CRUD Operations
 * 
 * ARCHITECTURE (HILT MIGRATION):
 * - MutableStateFlow für internen State
 * - Automatische Synchronisation mit DataStore
 * - .value Zugriff für ViewModels ohne Coroutine-Overhead
 */
@Singleton
class CalendarSelectionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ICalendarSelectionRepository {

    private val dataStore = context.calendarSelectionDataStore
    private val selectedCalendarIdsKey = stringSetPreferencesKey("selected_calendar_ids")

    /**
     * Ist der Nutzer entsperrt, also CREDENTIAL-ENCRYPTED Storage lesbar?
     *
     * Gleiche Umsetzung und gleiche Fehlerrichtung wie in `AlarmRepository` und
     * `BackgroundServiceManager`: im Zweifel `true`. Ein falsch-positives "gesperrt" wuerde die
     * Kalenderauswahl dauerhaft als unlesbar melden.
     */
    private val userUnlocked: Boolean
        get() = try {
            context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked ?: true
        } catch (e: Exception) {
            Logger.w(LogTags.CALENDAR, "UserManager nicht abfragbar - Nutzer gilt als entsperrt", e)
            true
        }
    
    /**
     * Repository-eigener CoroutineScope für DataStore-Synchronisation.
     * SupervisorJob: ein Fehler in einer Child-Coroutine beeinflusst die anderen nicht.
     *
     * **Hier wird bewusst NIE `.cancel()` gerufen** — das ist Absicht, kein vergessenes
     * Aufräumen (die Frage kam in mehreren Prüfrunden auf). Die Klasse ist ein `@Singleton`
     * mit Prozess-Lebensdauer; es gibt keinen Zeitpunkt, zu dem sie "fertig" wäre. Ein
     * `cancel()` auf einem solchen Scope ist endgültig: jedes spätere `launch` startet
     * lautlos nie mehr, heilbar nur durch Prozess-Neustart. Genau diese Fehlerklasse steckte
     * in `HueBridgeConnectionManager.cleanup()` (siehe CLAUDE.md) — dort wurde daraus
     * `cancelChildren()`.
     *
     * Konkret hinge hier der Collector der Kalenderauswahl daran — inklusive des Wartens auf die
     * erste Entsperrung (`awaitUserUnlocked`). Stirbt er, steht `_selectedCalendarIds` dauerhaft
     * auf `emptySet()`, obwohl Kalender ausgewählt sind — und eine leere Auswahl liest
     * `syncAlarms()` als "keine Schichten". Für eine Wecker-App ist das die gefährlichste Leere.
     */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * INTERNAL STATE: MutableStateFlow für synchronen Zugriff
     * Wird beim Start aus DataStore initialisiert und bei Änderungen aktualisiert
     */
    private val _selectedCalendarIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * PUBLIC API: StateFlow der ausgewählten Kalender-IDs
     * 
     * SYNCHRONER ZUGRIFF: .value liefert aktuellen State sofort
     * REACTIVE: Kann mit collect() oder collectAsStateWithLifecycle() beobachtet werden
     * DISTINCT: Nur echte Änderungen werden emittiert
     */
    override val selectedCalendarIds: StateFlow<Set<String>> = _selectedCalendarIds.asStateFlow()
    
    init {
        // Initialisierung: Lade persistierte Daten in StateFlow
        initializeFromDataStore()
    }
    
    /**
     * Lädt den initialen State aus DataStore in den StateFlow
     * Wird einmalig beim Repository-Start ausgeführt
     */
    private fun initializeFromDataStore() {
        repositoryScope.launch {
            // ZUERST WARTEN, DANN LESEN - der Read darf im gesperrten Zustand gar nicht passieren.
            //
            // Der frueher hier stehende Kommentar behauptete, der Read werfe bei Direct Boot und
            // das `retryWhen` unten fange das auf. Das ist nachweislich falsch: ein CE-Read vor der
            // ersten Entsperrung wirft NICHT, er liefert still leere Preferences und meldet
            // Erfolg. Das `retryWhen` feuerte also nie - und schlimmer: DataStore legt dieses leere
            // Ergebnis in seinen In-Memory-Cache und gibt es fuer die restliche PROZESSLAUFZEIT
            // unveraendert zurueck (die Version steigt nur bei einem erfolgreichen Write, der im
            // gesperrten CE-Storage scheitert). Ein einziger zu frueher Read machte die
            // Kalenderauswahl also prozessweit leer, und jeder Wartungslauf brach mit "No calendars
            // selected" ab - ohne Fehler, ohne Signal. Gemessen relevant, weil der directBootAware
            // BootReceiver dieses Repository injiziert.
            awaitUserUnlocked()

            // WIEDERAUFNAHME statt endgueltigem Aus.
            //
            // Vorher lag das `collect` in einem try/catch: der ERSTE Fehler aus dem Upstream
            // beendete den Collector fuer die gesamte Prozesslaufzeit, und es gibt keinen zweiten
            // Aufrufer dieser Funktion (nur `init{}`). Danach stand `_selectedCalendarIds`
            // dauerhaft auf `emptySet()`, obwohl im DataStore Kalender ausgewaehlt sind - fuer eine
            // Wecker-App ist genau das die gefaehrliche Luege, die diese Klasse an anderer Stelle
            // ausdruecklich bekaempft ("leer" ist von "nichts ausgewaehlt" nicht zu unterscheiden).
            //
            // Das `retryWhen` bleibt trotzdem: es deckt echte Lesefehler ab (IOException auf einer
            // beschaedigten Datei), die sehr wohl werfen.
            dataStore.data
                .map { preferences -> preferences[selectedCalendarIdsKey] ?: emptySet() }
                .distinctUntilChanged()
                .retryWhen { cause, attempt ->
                    if (attempt >= MAX_SYNC_RETRIES) {
                        Logger.e(
                            LogTags.CALENDAR,
                            "❌ Kalenderauswahl konnte nach ${attempt} Versuchen nicht beobachtet " +
                                "werden - der StateFlow bleibt leer. getCurrentSelectedCalendarIds() " +
                                "liest weiterhin direkt aus dem DataStore.",
                            cause
                        )
                        return@retryWhen false
                    }
                    Logger.w(
                        LogTags.CALENDAR,
                        "⚠️ Kalenderauswahl nicht lesbar (Versuch ${attempt + 1}) - " +
                            "neuer Versuch in ${SYNC_RETRY_DELAY_MS * (attempt + 1)}ms",
                        cause
                    )
                    delay(SYNC_RETRY_DELAY_MS * (attempt + 1))
                    true
                }
                .collect { ids ->
                    // Zweites Netz zum Warten oben: irrt sich der UserManager (oder faellt der
                    // Zustand zurueck), darf eine LEERE Liste aus einem gesperrten Store nicht in
                    // den StateFlow. Sie waere von "der Nutzer hat nichts ausgewaehlt" nicht zu
                    // unterscheiden und liest sich fuer syncAlarms() als "keine Schichten".
                    if (!shouldAcceptSelectionRead(userUnlocked, ids)) {
                        Logger.w(
                            LogTags.CALENDAR,
                            "🔒 Leere Kalenderauswahl aus gesperrtem Storage verworfen - der " +
                                "StateFlow behaelt seinen Stand"
                        )
                        return@collect
                    }
                    _selectedCalendarIds.value = ids
                    Logger.d(LogTags.CALENDAR, "Calendar selection synced from DataStore: ${ids.size} calendars")
                }
        }
    }

    /**
     * Blockiert die Coroutine, bis der CREDENTIAL-ENCRYPTED Storage lesbar ist.
     *
     * Bewusst eine Abfrage im Abstand statt eines Broadcast-Empfaengers: das Repository ist ein
     * `@Singleton` am Application-Graphen, und dort etwas Neues zu registrieren ist genau die
     * Falle, die dieser Fix schliesst. Die Abfrage kostet nur einen `UserManager`-Aufruf und
     * fasst KEINEN Speicher an.
     *
     * Ohne Obergrenze - mit wachsendem, gedeckeltem Abstand: ein Neustart nachts kann Stunden vor
     * der ersten Entsperrung liegen. Eine Obergrenze haette genau den Zustand wiederhergestellt,
     * den diese Funktion verhindert (Auswahl bleibt prozessweit leer). Der gesperrte Prozess ist
     * ohnehin kurzlebig.
     */
    private suspend fun awaitUserUnlocked() {
        if (userUnlocked) return
        Logger.i(
            LogTags.CALENDAR,
            "🔒 Kalenderauswahl wird erst nach der ersten Entsperrung gelesen (Direct Boot)"
        )
        var attempt = 0
        while (!userUnlocked) {
            attempt++
            delay(minOf(UNLOCK_POLL_START_MS * attempt, UNLOCK_POLL_MAX_MS))
        }
        Logger.i(LogTags.CALENDAR, "🔓 Nutzer entsperrt - Kalenderauswahl wird jetzt geladen")
    }

    private companion object {
        /** 10 Versuche mit wachsendem Abstand decken echte Lesefehler ab. */
        const val MAX_SYNC_RETRIES = 10L
        const val SYNC_RETRY_DELAY_MS = 3_000L

        /** Abstand der Entsperr-Abfrage: startet kurz, waechst auf hoechstens eine Minute. */
        const val UNLOCK_POLL_START_MS = 1_000L
        const val UNLOCK_POLL_MAX_MS = 60_000L
    }

    /**
     * ATOMIC UPDATE: Kompletter Austausch der ausgewählten Kalender-IDs
     * Aktualisiert sowohl StateFlow als auch DataStore
     */
    override suspend fun saveSelectedCalendarIds(calendarIds: Set<String>): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.saveSelectedCalendarIds") {
            // StateFlow wird automatisch via DataStore-Collector aktualisiert
            dataStore.edit { preferences ->
                preferences[selectedCalendarIdsKey] = calendarIds
            }
            Logger.i(LogTags.CALENDAR, "Calendar selection saved: ${calendarIds.size} calendars selected")
        }

    override suspend fun getCurrentSelectedCalendarIds(): Result<Set<String>> =
        SafeExecutor.safeExecute("CalendarSelectionRepository.getCurrentSelectedCalendarIds") {
            // LIEST DEN DATASTORE, NICHT DEN STATEFLOW.
            //
            // _selectedCalendarIds startet auf emptySet() und wird erst durch den in init{}
            // gestarteten, unabgewarteten Collector befuellt (eigener repositoryScope auf
            // Dispatchers.IO). Wer den .value direkt danach liest, bekommt "keine Kalender
            // ausgewaehlt" - ohne jedes Signal, dass nur noch nicht geladen wurde. Genau die
            // Fehlerklasse, vor der CLAUDE.md warnt: fuer eine Wecker-App ist "leer" von
            // "wirklich nichts ausgewaehlt" nicht zu unterscheiden.
            //
            // Real relevant fuer prozess-kalt gestartete Hintergrund-Aufrufer:
            // CalendarPreAlarmRefreshWorker (WorkManager, 3h vor der Weckzeit) und
            // AlarmMaintenanceService haben kein Aequivalent zum 5s-Warten des BootReceivers -
            // sie haetten den Lauf lautlos als "keine Kalender ausgewaehlt" verbraucht.
            //
            // Diese Funktion ist bereits `suspend`; der DataStore-Read ist der einzige Weg, der
            // die Hydrierung garantiert abwartet. Der StateFlow bleibt unveraendert die Quelle
            // fuer reaktive Beobachter (UI/ViewModels).
            //
            // ABER NICHT VOR DER ERSTEN ENTSPERRUNG: dort liefert der Read still `emptySet()` als
            // Erfolg UND vergiftet den DataStore-Cache fuer die restliche Prozesslaufzeit (siehe
            // initializeFromDataStore). Ein FEHLER ist hier die einzige ehrliche Antwort - die
            // Aufrufer (AlarmMaintenanceService, CalendarPreAlarmRefreshWorker, BootReceiver)
            // brechen den Lauf damit ab, statt ihn als "keine Kalender ausgewaehlt" zu verbrauchen
            // und Alarme zu loeschen.
            if (!userUnlocked) {
                throw AppError.DataStoreError(
                    message = "Kalenderauswahl vor der ersten Entsperrung nicht lesbar " +
                        "(CREDENTIAL-ENCRYPTED Storage) - es wird KEINE leere Auswahl gemeldet"
                )
            }

            dataStore.data
                .map { preferences -> preferences[selectedCalendarIdsKey] ?: emptySet() }
                .first()
        }

    /**
     * GRANULAR UPDATE: Hinzufügen einer einzelnen Kalender-ID
     */
    override suspend fun addCalendarId(calendarId: String): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.addCalendarId") {
            if (calendarId.isBlank()) {
                throw AppError.ValidationError("Calendar ID cannot be blank")
            }
            
            dataStore.edit { preferences ->
                val currentIds = preferences[selectedCalendarIdsKey] ?: emptySet()
                preferences[selectedCalendarIdsKey] = currentIds + calendarId
            }
            Logger.d(LogTags.CALENDAR, "Calendar added to selection: ${calendarId.take(8)}...")
        }

    /**
     * GRANULAR UPDATE: Entfernen einer einzelnen Kalender-ID
     */
    override suspend fun removeCalendarId(calendarId: String): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.removeCalendarId") {
            dataStore.edit { preferences ->
                val currentIds = preferences[selectedCalendarIdsKey] ?: emptySet()
                preferences[selectedCalendarIdsKey] = currentIds - calendarId
            }
            Logger.d(LogTags.CALENDAR, "Calendar removed from selection: ${calendarId.take(8)}...")
        }

    override suspend fun clearSelection(): Result<Unit> = 
        SafeExecutor.safeExecute("CalendarSelectionRepository.clearSelection") {
            dataStore.edit { preferences ->
                preferences.remove(selectedCalendarIdsKey)
            }
            Logger.i(LogTags.CALENDAR, "Calendar selection cleared")
        }

    override suspend fun hasSelectedCalendars(): Result<Boolean> =
        SafeExecutor.safeExecute("CalendarSelectionRepository.hasSelectedCalendars") {
            val ids = _selectedCalendarIds.value
            // "leer" ist nur eine Aussage, wenn der Store lesbar war - im gesperrten Zustand ist
            // der StateFlow noch gar nicht befuellt (der Collector wartet). Ein `false` waere
            // hier eine Erfindung, die als "Onboarding laeuft noch" gelesen wird.
            if (!shouldAcceptSelectionRead(userUnlocked, ids)) {
                throw AppError.DataStoreError(
                    message = "Kalenderauswahl vor der ersten Entsperrung unbekannt " +
                        "(CREDENTIAL-ENCRYPTED Storage) - es wird KEIN 'nichts ausgewaehlt' gemeldet"
                )
            }
            ids.isNotEmpty()
        }
}
