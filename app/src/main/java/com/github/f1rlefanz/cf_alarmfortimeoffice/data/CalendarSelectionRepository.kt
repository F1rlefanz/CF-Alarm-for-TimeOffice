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
     * Repository-eigener CoroutineScope für DataStore-Synchronisation
     * SupervisorJob: Fehler in einem Child-Coroutine beeinflussen andere nicht
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
            // WIEDERAUFNAHME statt endgueltigem Aus.
            //
            // Vorher lag das `collect` in einem try/catch: der ERSTE Fehler aus dem Upstream
            // beendete den Collector fuer die gesamte Prozesslaufzeit, und es gibt keinen zweiten
            // Aufrufer dieser Funktion (nur `init{}`). Danach stand `_selectedCalendarIds`
            // dauerhaft auf `emptySet()`, obwohl im DataStore Kalender ausgewaehlt sind - fuer eine
            // Wecker-App ist genau das die gefaehrliche Luege, die diese Klasse an anderer Stelle
            // ausdruecklich bekaempft ("leer" ist von "nichts ausgewaehlt" nicht zu unterscheiden).
            //
            // Der Fehler ist nicht hypothetisch: entsteht dieses Repository in einem Prozess, der
            // VOR der ersten Entsperrung startet (der directBootAware BootReceiver injiziert es),
            // ist der CE-DataStore garantiert nicht lesbar. Nach dem Entsperren waere er es -
            // deshalb wird es erneut versucht, mit wachsendem Abstand und einer Obergrenze, damit
            // ein dauerhaft defekter Store keine Endlosschleife erzeugt.
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
                        "⚠️ Kalenderauswahl nicht lesbar (Versuch ${attempt + 1}, evtl. Direct Boot) - " +
                            "neuer Versuch in ${SYNC_RETRY_DELAY_MS * (attempt + 1)}ms",
                        cause
                    )
                    delay(SYNC_RETRY_DELAY_MS * (attempt + 1))
                    true
                }
                .collect { ids ->
                    _selectedCalendarIds.value = ids
                    Logger.d(LogTags.CALENDAR, "Calendar selection synced from DataStore: ${ids.size} calendars")
                }
        }
    }

    private companion object {
        /** 10 Versuche mit wachsendem Abstand deckt die Zeit bis zur ersten Entsperrung ab. */
        const val MAX_SYNC_RETRIES = 10L
        const val SYNC_RETRY_DELAY_MS = 3_000L
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
            _selectedCalendarIds.value.isNotEmpty()
        }
}
