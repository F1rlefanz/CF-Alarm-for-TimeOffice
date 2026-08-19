package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.SkipRolledBackException
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ManualAlarmSnapshot
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class AlarmUiState(
    val isLoading: Boolean = false,
    val activeAlarms: List<AlarmInfo> = emptyList(),
    val hasActiveAlarms: Boolean = false,
    val nextAlarmTime: String? = null,
    val error: String? = null
)

data class AlarmSkipUiState(
    val isNextAlarmSkipped: Boolean = false,
    val skippedAlarmId: Int? = null,
    val isLoading: Boolean = false,
    val error: AppErrorState? = null,
    /**
     * Nutzertext zum Ausgang des letzten Skip-Vorgangs, sofern er NICHT einfach aufging:
     *
     * - "Aufheben": der uebersprungene MANUELLE Wecker kam nicht zurueck (Weckzeit verstrichen,
     *   gesicherter Stand unlesbar, Speichern/Stellen fehlgeschlagen).
     * - "Ueberspringen": der Vorgang musste mitten drin zurueckgenommen werden, weil sich der
     *   Alarm nicht loeschen liess (siehe `SkipRolledBackException`).
     *
     * Ohne diesen Text waere der schlechteste Fall stumm: der Nutzer drueckt einen Knopf, die
     * Oberflaeche zeigt danach keinen Skip mehr - und keinen Wecker. `error` taugt dafuer nicht,
     * das zeigt die Oberflaeche nirgends an. Wird beim naechsten Ueberspringen bzw. Aufheben
     * wieder geleert.
     */
    val restoreNotice: String? = null
)

/**
 * MANUAL ALARM UI STATE
 *
 * State für manuelle Alarm-Erstellung nach Schichttausch
 */
data class ManualAlarmUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedShift: ShiftDefinition? = null,
    val availableShifts: List<ShiftDefinition> = emptyList(),
    val calculatedAlarmTime: String? = null,
    val hasActiveManualAlarm: Boolean = false,
    val activeManualAlarm: AlarmInfo? = null,
    val isCreating: Boolean = false,
    val isDeleting: Boolean = false,
    val error: AppErrorState? = null
)

/**
 * MEMORY LEAK FIXED: AlarmViewModel with proper resource cleanup
 *
 * MIGRATION STATUS:
 * ✅ @HiltViewModel annotiert
 * ✅ Constructor Injection mit @Inject
 * ✅ Alle Dependencies über Interfaces
 * ✅ Keine Abhängigkeiten zu anderen ViewModels
 *
 * CRITICAL FIXES:
 * ✅ Added onCleared() for proper cleanup
 * ✅ Job tracking for Flow collections
 * ✅ Resource cleanup on destruction
 * ✅ Memory leak prevention
 */
@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val alarmUseCase: IAlarmUseCase,
    private val alarmSkipUseCase: IAlarmSkipUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val errorHandler: ErrorHandler,
    private val masterPausePrefs: MasterPausePrefs,
    private val alarmPrefs: AlarmPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlarmUiState())
    val uiState: StateFlow<AlarmUiState> = _uiState.asStateFlow()

    /** Konfigurierte Schlummer-Dauer in Minuten (Default siehe [AlarmPrefs.DEFAULT_SNOOZE_MINUTES]). */
    val snoozeMinutes: StateFlow<Int> =
        alarmPrefs.snoozeMinutes
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmPrefs.DEFAULT_SNOOZE_MINUTES)

    private val _skipState = MutableStateFlow(AlarmSkipUiState())
    val skipState: StateFlow<AlarmSkipUiState> = _skipState.asStateFlow()

    private val _manualAlarmState = MutableStateFlow(ManualAlarmUiState())
    val manualAlarmState: StateFlow<ManualAlarmUiState> = _manualAlarmState.asStateFlow()

    // MEMORY LEAK FIX: Track Flow collection job for proper cleanup
    private var alarmObservationJob: Job? = null

    /**
     * Laeuft gerade ein Skip-Vorgang ("Ueberspringen" oder "Aufheben")?
     *
     * DIE EINZIGE Wiedereintrittssperre der beiden Knoepfe. Ihr `isLoading` reicht dafuer NICHT:
     * die Wiederaufnahme-Schleife in [observeSkipStatus] loest den Ladezustand, sobald der
     * Skip-Flow endet - und der endet nach fuenf vergeblichen Leseversuchen endgueltig, also
     * moeglicherweise mitten in einem laufenden Vorgang. Beide Knoepfe waeren damit wieder
     * bedienbar, waehrend noch geschrieben wird: der zweite Lauf faende denselben, noch nicht
     * geloeschten Alarm, cancelte dessen Systemalarm ein zweites Mal, und scheiterte einer von
     * beiden, raeumte dessen Ruecknahme den Merker weg, den der andere gerade gesetzt hat.
     * Endstand: kein Merker, kein Systemalarm - ein stumm geloeschter Wecker.
     *
     * Nur vom Main-Dispatcher aus angefasst (beide Aufrufer setzen sie VOR `launch`, damit auch
     * zwei Druecker vor dem ersten Coroutine-Start nicht durchrutschen), deshalb genuegt ein
     * schlichtes Feld ohne Synchronisierung.
     */
    private var skipVorgangLaeuft = false

    /**
     * SINGLE SOURCE OF TRUTH: Shared upstream for active alarms.
     *
     * Zuvor wurde alarmUseCase.activeAlarms zweimal unabhängig collected
     * (observeAlarmStatus + observeManualAlarms), was den kalten Upstream-Flow
     * doppelt subscribt hat. Dieser geteilte Flow fan-out an beide Beobachter,
     * sodass der Upstream nur noch EINMAL aktiv ist. distinctUntilChanged bleibt
     * hier erhalten – identisch zum vorherigen Verhalten beider Collectoren.
     */
    private val sharedActiveAlarms: SharedFlow<List<AlarmInfo>> =
        alarmUseCase.activeAlarms
            .distinctUntilChanged()
            .shareIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                replay = 1
            )

    init {
        observeAlarmStatus()
        observeSkipStatus()
        loadAvailableShifts()
        observeManualAlarms()
        // CLEANUP: Clean expired alarms on startup
        cleanupExpiredAlarmsOnStartup()
    }

    /**
     * CLEANUP: Remove expired alarms when ViewModel starts
     */
    private fun cleanupExpiredAlarmsOnStartup() {
        viewModelScope.launch {
            try {
                // Cast to concrete implementation to access cleanup method
                val repository =
                    alarmUseCase as? com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
                if (repository != null) {
                    // For now, trigger cleanup via deleteAll -> rebuild pattern
                    Logger.d(LogTags.ALARM, "Startup cleanup: checking for expired alarms")

                    // Get all alarms and check for expired ones
                    alarmUseCase.getAllAlarms().onSuccess { allAlarms ->
                        val currentTime = System.currentTimeMillis()
                        val expiredAlarms = allAlarms.filter { it.triggerTime <= currentTime }

                        if (expiredAlarms.isNotEmpty()) {
                            Logger.w(
                                LogTags.ALARM,
                                "Found ${expiredAlarms.size} expired alarms on startup, cleaning up"
                            )
                            // Delete each expired alarm
                            expiredAlarms.forEach { alarm ->
                                alarmUseCase.deleteAlarm(alarm.id)
                            }
                        } else {
                            Logger.d(LogTags.ALARM, "No expired alarms found on startup")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Error during startup cleanup", e)
            }
        }
    }

    /**
     * MEMORY LEAK FIX: Proper Job tracking für Flow collections
     */
    private fun observeAlarmStatus() {
        alarmObservationJob?.cancel() // Cancel any existing observation

        alarmObservationJob = viewModelScope.launch {
            try {
                sharedActiveAlarms
                    .collect { alarms ->
                        // FIXED: Only consider future alarms for "next alarm" calculation
                        val currentTime = System.currentTimeMillis()
                        val futureAlarms = alarms.filter { it.triggerTime > currentTime }

                        _uiState.value = _uiState.value.copy(
                            activeAlarms = alarms, // Show all alarms for debugging
                            hasActiveAlarms = alarms.isNotEmpty(),
                            nextAlarmTime = computeNextAlarmTime(alarms)
                        )

                        Logger.d(
                            LogTags.ALARM,
                            "Active alarms updated: ${alarms.size} total, ${futureAlarms.size} future"
                        )

                        // CLEANUP: Log expired alarms for debugging
                        val expiredAlarms = alarms.filter { it.triggerTime <= currentTime }
                        if (expiredAlarms.isNotEmpty()) {
                            Logger.w(
                                LogTags.ALARM,
                                "Found ${expiredAlarms.size} expired alarms: ${expiredAlarms.map { it.formattedTime }}"
                            )
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrow for proper structured concurrency
                Logger.d(LogTags.ALARM, "Alarm status observation cancelled (app lifecycle)")
                throw e
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Error observing alarm status", e)
                _uiState.value = _uiState.value.copy(
                    error = errorHandler.getErrorMessage(e)
                )
            }
        }
    }

    /**
     * Fruehester Alarm in der Zukunft, der auch wirklich klingeln wird - als fertiger Anzeigetext.
     *
     * Der aktuell uebersprungene Alarm ist ausgenommen. Im Normalfall ist er ohnehin geloescht,
     * aber genau darauf ist kein Verlass: `AlarmSkipUseCase.skipNextAlarm()` schluckt einen
     * fehlgeschlagenen `deleteAlarm()` bewusst (der Skip soll daran nicht scheitern), und
     * `AlarmUseCase.scheduleSystemAlarm()` fuehrt aus demselben Grund einen Skip-Backstop.
     * Bleibt ein Eintrag zurueck, ist er NICHT armiert - als "Nächster Alarm" angekuendigt
     * waere er eine Anzeige, die nicht eintritt.
     */
    private fun computeNextAlarmTime(alarms: List<AlarmInfo>): String? {
        val currentTime = System.currentTimeMillis()
        val skippedAlarmId = _skipState.value.takeIf { it.isNextAlarmSkipped }?.skippedAlarmId
        return alarms
            .filter { it.triggerTime > currentTime && it.id != skippedAlarmId }
            .minByOrNull { it.triggerTime }
            ?.formattedTime
    }

    /**
     * Uebernimmt einen Skip-Zustand in die Oberflaeche und loest dabei IMMER den Ladezustand.
     *
     * Der Ladezustand ist der Grund, warum es diese Stelle gibt: "Ueberspringen" und "Aufheben"
     * sind beide auf `!isLoading` geschaltet. Wer ihn nur in der Flow-Emission zuruecksetzt,
     * sperrt beide Knoepfe fuer den Rest der Prozesslaufzeit, sobald der Flow einmal endet -
     * und `skipStatusFlow` endet nach fuenf vergeblichen Leseversuchen bewusst endgueltig
     * (Begruendung in `AlarmSkipRepository`).
     */
    private fun applySkipState(isNextAlarmSkipped: Boolean, skippedAlarmId: Int?) {
        _skipState.value = _skipState.value.copy(
            isNextAlarmSkipped = isNextAlarmSkipped,
            skippedAlarmId = skippedAlarmId,
            isLoading = false
        )
        // "Naechster Alarm" haengt auch am Skip-Zustand: bleibt der uebersprungene Eintrag
        // ausnahmsweise im Bestand stehen (siehe computeNextAlarmTime), ist er NICHT armiert und
        // darf nicht als naechster Wecker angekuendigt werden. Beide Flows koennen in beliebiger
        // Reihenfolge emittieren, deshalb wird hier nachgerechnet.
        _uiState.value = _uiState.value.copy(
            nextAlarmTime = computeNextAlarmTime(_uiState.value.activeAlarms)
        )
    }

    /**
     * WIEDERAUFNAHME STATT ENDGUELTIGEM AUS - dieselbe Antwort wie in
     * `CalendarSelectionRepository`, aus demselben Grund: diese Funktion laeuft genau einmal aus
     * `init{}`, es gibt keinen zweiten Aufrufer. Endete der Flow (fuenf vergebliche Leseversuche,
     * danach bewusst ohne Ersatzwert), stand die Skip-Anzeige bis zum Prozessende still - und
     * schlimmer: ein zwischenzeitliches `skipNextAlarm()` liess `isLoading` auf `true` zurueck,
     * womit "Ueberspringen" UND "Aufheben" dauerhaft ausgegraut blieben. Der Nutzer kam an einen
     * uebersprungenen Wecker nicht mehr heran.
     *
     * Deshalb: Ladezustand beim Ende des Flows loesen und begrenzt neu abonnieren. Der Zaehler
     * wird bewusst NICHT bei einer Emission zurueckgesetzt - ein Flow, der emittiert UND
     * abschliesst, drehte sonst endlos durch diese Schleife. Die Obergrenze ist verkraftbar,
     * weil die Bedienbarkeit ohnehin nicht mehr am Flow allein haengt (siehe [applySkipState]).
     */
    private fun observeSkipStatus() {
        viewModelScope.launch {
            var versuch = 0
            while (true) {
                try {
                    alarmSkipUseCase.skipStatusFlow
                        .catch { error ->
                            Logger.e(LogTags.ALARM_SKIP, "Error observing skip state", error)
                        }
                        .collect { skipState ->
                            applySkipState(skipState.isNextAlarmSkipped, skipState.skippedAlarmId)
                        }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Rethrow for proper structured concurrency
                    Logger.d(LogTags.ALARM_SKIP, "Skip status observation cancelled (app lifecycle)")
                    throw e
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_SKIP, "Error in skip status observation", e)
                }

                // Hier steht der Flow still - normal endet er nie. Den Spinner sofort loesen,
                // damit die Knoepfe bedienbar bleiben; der zuletzt bekannte Skip-Zustand bleibt
                // bewusst stehen (kein Signal ist besser als ein falsches).
                //
                // NICHT waehrend eines laufenden Skip-Vorgangs: der Spinner ist dann keine
                // Altlast, sondern die Anzeige eines Schreibvorgangs, der noch laeuft (siehe
                // [skipVorgangLaeuft]). Er wird ohnehin von jedem Ausgang des Vorgangs geloest.
                if (!skipVorgangLaeuft) {
                    _skipState.value = _skipState.value.copy(isLoading = false)
                }

                if (versuch >= SKIP_OBSERVE_RESUBSCRIBE_ATTEMPTS) {
                    Logger.e(
                        LogTags.ALARM_SKIP,
                        "❌ Skip-Zustand auch nach $versuch neuen Anlaeufen nicht beobachtbar - " +
                            "die Anzeige bleibt auf ihrem letzten Stand; Ueberspringen und " +
                            "Aufheben funktionieren weiter"
                    )
                    return@launch
                }
                versuch++
                Logger.w(
                    LogTags.ALARM_SKIP,
                    "⚠️ Skip-Zustand endete - neuer Anlauf $versuch/$SKIP_OBSERVE_RESUBSCRIBE_ATTEMPTS " +
                        "in ${SKIP_OBSERVE_RESUBSCRIBE_DELAY_MS / 1000}s"
                )
                delay(SKIP_OBSERVE_RESUBSCRIBE_DELAY_MS)
            }
        }
    }

    @Suppress("unused") // Public API for manual shift control
    fun setAlarmsForShift(shift: ShiftInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Manueller Einzel-Alarm aus einer Schicht: baut die AlarmInfo direkt und
                // speichert sie ueber saveAlarm (kein Event-basierter Delta-Sync noetig).
                val alarmTime = shift.alarmTime
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

                val alarmInfo = AlarmInfo(
                    id = shift.id.hashCode(), // Convert String to Int
                    shiftId = shift.id,
                    shiftName = shift.shiftType.displayName,
                    triggerTime = alarmTime,
                    formattedTime = DateTimeFormatter
                        .ofPattern(DateTimeFormats.STANDARD_DATETIME)
                        .format(
                            LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(alarmTime),
                                ZoneId.systemDefault()
                            )
                        )
                )

                alarmUseCase.saveAlarm(alarmInfo)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        Logger.i(
                            LogTags.ALARM,
                            "Alarm set for shift: ${shift.shiftType.displayName}"
                        )
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = errorHandler.getErrorMessage(error)
                        )
                        Logger.e(LogTags.ALARM, "Failed to set alarm for shift", error)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorHandler.getErrorMessage(e)
                )
                Logger.e(LogTags.ALARM, "Exception setting alarm for shift", e)
            }
        }
    }

    @Suppress("unused") // Public API for alarm management
    fun cancelAlarm(alarmId: Int) {
        viewModelScope.launch {
            try {
                alarmUseCase.deleteAlarm(alarmId)
                    .onSuccess {
                        Logger.i(LogTags.ALARM, "Alarm cancelled: $alarmId")
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            error = errorHandler.getErrorMessage(error)
                        )
                        Logger.e(LogTags.ALARM, "Failed to cancel alarm: $alarmId", error)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = errorHandler.getErrorMessage(e)
                )
                Logger.e(LogTags.ALARM, "Exception cancelling alarm: $alarmId", e)
            }
        }
    }

    @Suppress("unused") // Public API for alarm management
    fun cancelAllAlarms() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                alarmUseCase.deleteAllAlarms()
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        Logger.i(LogTags.ALARM, "All alarms cancelled")
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = errorHandler.getErrorMessage(error)
                        )
                        Logger.e(LogTags.ALARM, "Failed to cancel all alarms", error)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorHandler.getErrorMessage(e)
                )
                Logger.e(LogTags.ALARM, "Exception cancelling all alarms", e)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Setzt die Schlummer-Dauer (Minuten), geklemmt auf einen sinnvollen Bereich. */
    fun setSnoozeMinutes(minutes: Int) {
        viewModelScope.launch {
            alarmPrefs.setSnoozeMinutes(minutes.coerceIn(1, 30))
        }
    }

    fun skipNextAlarm() {
        // Zweiter Druck auf einen noch laufenden Vorgang: nichts tun. Der zweite Lauf faende
        // denselben, noch nicht geloeschten Alarm - Begruendung an [skipVorgangLaeuft].
        if (skipVorgangLaeuft) {
            Logger.w(
                LogTags.ALARM_SKIP,
                "⏳ Ueberspringen ignoriert - es laeuft bereits ein Skip-Vorgang"
            )
            return
        }
        skipVorgangLaeuft = true
        viewModelScope.launch {
            try {
                // restoreNotice gehoert zum VORIGEN Aufheben-Vorgang - beim neuen Ueberspringen weg.
                _skipState.value = _skipState.value.copy(isLoading = true, restoreNotice = null)

                alarmSkipUseCase.skipNextAlarm()
                    .onSuccess { result ->
                        Logger.business(
                            LogTags.ALARM_SKIP,
                            "✅ Next alarm skip activated: ${result.alarmName}"
                        )
                        // Den gerade BESTAETIGT geschriebenen Zustand selbst uebernehmen, statt auf
                        // die naechste Emission zu warten: der Flow kann geendet sein, und dann bliebe
                        // der Spinner ewig stehen - samt gesperrtem "Aufheben" (siehe
                        // [applySkipState]). Eine Vermutung ist das nicht, das Schreiben ist durch.
                        applySkipState(isNextAlarmSkipped = true, skippedAlarmId = result.alarmId)
                    }
                    .onFailure { error ->
                        Logger.e(LogTags.ALARM_SKIP, "❌ Failed to skip next alarm", error)
                        // Ein halb durchgefuehrtes Ueberspringen muss der Nutzer SEHEN: skipState.error
                        // zeigt die Oberflaeche nirgends an, restoreNotice schon (und zwar auch dann,
                        // wenn es gerade weder aktiven Alarm noch aktives Ueberspringen gibt).
                        //
                        // DER TEXT ENTSTEHT VOR DER ZUWEISUNG, und die laeuft ueber `update {}`:
                        // stelleNachAbgebrochenemSkipWiederHer() suspendiert (DataStore-Lesen und
                        // AlarmManager), und genau in dieser Zeit traegt der Collector aus
                        // observeSkipStatus() den vom UseCase bereits geraeumten Merker in den Zustand.
                        // Stand der Aufruf als Argument in einem `copy()` auf `_skipState.value`, war
                        // der Empfaenger schon VOR der Suspendierung gelesen - die Zuweisung machte die
                        // Emission wieder rueckgaengig. Die Oberflaeche zeigte danach "Nächster Alarm
                        // wird übersprungen" samt "Aufheben" fuer ein Ueberspringen, das es nicht mehr
                        // gibt, und blendete den gerade wieder armierten Wecker ueber skippedAlarmId
                        // aus "Nächster Alarm" aus.
                        val hinweis = if (error is SkipRolledBackException) {
                            // NICHT ABBRECHBAR - dieselbe Begruendung wie bei pause()/resume()
                            // der Master-Pause: hier wird ein Zustand HERGESTELLT, nicht bloss
                            // gelesen. Der UseCase haelt seine eigenen Schritte bereits per
                            // withContext(NonCancellable) zusammen und liefert die Ruecknahme
                            // danach als Ergebnis - der Systemalarm ist zu diesem Zeitpunkt
                            // gecancelt, der Eintrag aber noch im Bestand. Ist der
                            // viewModelScope inzwischen tot (Activity endgueltig beendet,
                            // ViewModel geraeumt), starb das Re-Armieren am ersten
                            // Suspensionspunkt - zurueck blieb der sichtbare, stumme Wecker.
                            // Ein Nachholer traegt das nicht zuverlaessig: syncAlarms() re-armt
                            // nur kalenderbasierte Alarme, ein MANUELLER Wecker (den der Sync
                            // per keepManualAlarms nur schont) bliebe bis zum naechsten
                            // Neustart stumm.
                            withContext(NonCancellable) {
                                stelleNachAbgebrochenemSkipWiederHer(error)
                            }
                        } else {
                            "Das Überspringen hat nicht geklappt – am Wecker hat sich nichts " +
                                "geändert. Bitte noch einmal versuchen."
                        }
                        _skipState.update {
                            it.copy(
                                isLoading = false,
                                error = AppErrorState.validationError(
                                    error.message ?: "Failed to skip alarm"
                                ),
                                restoreNotice = hinweis
                            )
                        }
                    }
            } finally {
                // Auch bei Abbruch (ViewModel wird geraeumt) freigeben - sonst bliebe die Sperre
                // fuer die Restlaufzeit der Instanz haengen und beide Knoepfe waeren tot.
                skipVorgangLaeuft = false
            }
        }
    }

    /**
     * Behebt den Zwischenzustand, den ein abgebrochenes Ueberspringen hinterlaesst, und sagt dem
     * Nutzer, was nun gilt.
     *
     * Der Ausgangspunkt ist die schlimmste Klasse ueberhaupt: der Wecker steht sichtbar in der
     * Liste, sein Systemalarm ist aber schon gecancelt - ein stummer Wecker mit Anzeige. Der
     * UseCase kann ihn nicht selbst wieder stellen (`AlarmUseCase` haengt fuer den Skip-Backstop
     * bereits an ihm, die Gegenrichtung waere ein DI-Zyklus), deshalb tut es diese Stelle.
     *
     * Jeder Rueckgabetext beschreibt nur, was wirklich passiert ist - "klingelt wie geplant" darf
     * hier ausschliesslich stehen, wenn das Armieren tatsaechlich gelungen ist.
     */
    private suspend fun stelleNachAbgebrochenemSkipWiederHer(fehler: SkipRolledBackException): String {
        if (!fehler.skipFlagCleared) {
            // Merker noch gesetzt: ein Re-Arming wuerde der Skip-Backstop in
            // scheduleSystemAlarm() abweisen. Der Wecker kommt trotzdem zurueck - spaetestens
            // beim naechsten Neustart armiert der BootReceiver ihn ungefiltert -, aber
            // versprechen laesst sich das dem Nutzer nicht.
            Logger.e(
                LogTags.ALARM_SKIP,
                "❌ Skip fuer Alarm ${fehler.alarmId} abgebrochen, Merker liess sich nicht raeumen - kein Re-Arming moeglich"
            )
            return "Das Überspringen hat nicht geklappt und ließ sich nicht sauber zurücknehmen. " +
                "Bitte im Wecker-Tab prüfen, ob der Wecker noch steht."
        }

        val alarm = alarmUseCase.getAllAlarms().getOrNull()?.find { it.id == fehler.alarmId }
        if (alarm == null) {
            Logger.w(
                LogTags.ALARM_SKIP,
                "⚠️ Abgebrochener Skip: Alarm ${fehler.alarmId} ist nicht mehr im Bestand - kein Re-Arming"
            )
            return "Das Überspringen hat nicht geklappt. Der Wecker ist nicht mehr in der Liste – " +
                "bitte im Wecker-Tab prüfen und gegebenenfalls neu stellen."
        }

        val armiert = alarmUseCase.scheduleSystemAlarm(alarm)
        return if (armiert.isSuccess) {
            Logger.business(
                LogTags.ALARM_SKIP,
                "✅ Abgebrochener Skip zurueckgenommen - Alarm ${alarm.id} wieder armiert"
            )
            "Das Überspringen hat nicht geklappt und wurde zurückgenommen. Der Wecker um " +
                "${alarm.formattedTime} klingelt wie geplant."
        } else {
            Logger.e(
                LogTags.ALARM_SKIP,
                "❌ Abgebrochener Skip: Alarm ${alarm.id} konnte nicht wieder armiert werden",
                armiert.exceptionOrNull()
            )
            "Das Überspringen hat nicht geklappt. Der Wecker um ${alarm.formattedTime} konnte " +
                "nicht wieder gestellt werden – bitte im Wecker-Tab prüfen."
        }
    }

    /**
     * Hebt das Ueberspringen auf UND stellt den Alarm wieder her.
     *
     * [onSkipCleared] laeuft ERST NACH dem erfolgreichen Loeschen des Flags und stoesst den
     * Wiederaufbau aus dem Kalenderstand an - die Reihenfolge ist tragend, ein Sync vor dem
     * Loeschen wuerde am Skip-Gate in syncAlarms() haengenbleiben und den Alarm sofort wieder
     * verwerfen.
     *
     * WARUM ES DEN WIEDERAUFBAU BRAUCHT: skipNextAlarm() loescht den Alarm hart - System-Alarm
     * gecancelt UND AlarmInfo aus dem Repository entfernt (SKIP-IMMEDIATE-UX, damit er sofort aus
     * der Statusleiste verschwindet). cancelSkip() raeumte bis v1.26.2 nur das Flag weg und stellte
     * nichts wieder her: "Ueberspringen" war damit unumkehrbar, obwohl die Oberflaeche ein
     * "Aufheben" anbietet. Fuer eine Wecker-App heisst das ein stillschweigend geloeschter Wecker.
     *
     * Der Wiederaufbau geht bewusst ueber den regulaeren Kalender-Sync statt ueber einen
     * gesicherten AlarmInfo-Schnappschuss: der Kalenderstand ist die Wahrheit, aus der alle Alarme
     * entstehen. Hat sich die Schicht inzwischen verschoben, kommt die AKTUELLE Weckzeit zurueck,
     * nicht die alte - dieselbe Konsistenz wie beim Wiedereinschalten der automatischen Alarme.
     *
     * MANUELLE WECKER GEHEN DIESEN WEG NICHT: sie haben kein Kalender-Event, aus dem der Sync sie
     * rekonstruieren koennte. Fuer sie sichert das Ueberspringen einen vollstaendigen
     * [ManualAlarmSnapshot] im Skip-Zustand; [restoreSkippedManualAlarm] baut ihn daraus wieder
     * auf - siehe dort. Geloescht werden beim Ueberspringen weiterhin BEIDE Alarmarten, damit
     * kein uebersprungener Eintrag im Bestand liegenbleibt, den der Direct-Boot-Spiegel oder die
     * Hue-Tagesplanung ungefiltert weiterverwenden.
     */
    fun cancelSkip(onSkipCleared: () -> Unit = {}) {
        // Dieselbe Wiedereintrittssperre wie beim Ueberspringen: waehrend hier Merker,
        // Schnappschuss und Wecker auseinandergefaedelt werden, darf kein zweiter Vorgang
        // denselben Stand anfassen (Begruendung an [skipVorgangLaeuft]). Besonders hier: ein
        // Aufheben mitten in einem laufenden Ueberspringen raeumte den Merker weg, den jenes
        // gerade setzt - Endstand waere ein geloeschter Wecker ohne Merker und ohne Systemalarm.
        if (skipVorgangLaeuft) {
            Logger.w(
                LogTags.ALARM_SKIP,
                "⏳ Aufheben ignoriert - es laeuft bereits ein Skip-Vorgang"
            )
            return
        }
        skipVorgangLaeuft = true
        viewModelScope.launch {
            try {
                // Den Skip-Zustand VOR dem Aufheben lesen: cancelSkip() raeumt die ganze
                // Schluesselgruppe ab, danach ist der gesicherte manuelle Wecker weg.
                //
                // KEINE stille Degradierung auf null: ein Lesefehler ist etwas ANDERES als "es gab
                // keinen gesicherten Wecker". Wuerden beide gleich behandelt, raeumte "Aufheben" das
                // Flag weg und der manuelle Wecker verschwaende wortlos - der Nutzer haette einen
                // Wecker, den die Oberflaeche nicht mehr als uebersprungen zeigt und den niemand
                // stellt. Der Fehler wird deshalb bis zur Oberflaeche durchgereicht (restoreNotice).
                //
                // isLoading traegt hier zweierlei: den Spinner im "Aufheben"-Knopf und - zusammen
                // mit [skipVorgangLaeuft] - dessen Sperre, solange geschrieben wird.
                _skipState.value = _skipState.value.copy(restoreNotice = null, isLoading = true)

                val status = alarmSkipUseCase.getSkipStatus()
                if (status.isFailure) {
                    // NICHT aufheben. Der Skip-Zustand traegt den gesicherten manuellen Wecker, und
                    // cancelSkip() raeumt die ganze Schluesselgruppe ab - wer hier weitermacht,
                    // vernichtet einen Wecker, den er nicht einmal lesen konnte. Ein Lesefehler ist
                    // typischerweise voruebergehend; der Skip bleibt bestehen und der naechste Versuch
                    // hat wieder alles. Andere Wecker kostet das nichts: das Skip-Gate ist auf
                    // skippedAlarmId gemuenzt (AlarmUseCase.kt:279) und betrifft nur diesen einen.
                    Logger.e(
                        LogTags.ALARM_SKIP,
                        "❌ Skip-Zustand beim Aufheben nicht lesbar - Aufheben abgebrochen, damit ein " +
                            "gesicherter manueller Wecker nicht mit abgeraeumt wird",
                        status.exceptionOrNull()
                    )
                    _skipState.value = _skipState.value.copy(
                        restoreNotice = "Der gespeicherte Stand ließ sich gerade nicht lesen – das " +
                            "Überspringen wurde deshalb NICHT aufgehoben, damit nichts verloren geht. " +
                            "Bitte gleich noch einmal versuchen."
                    )
                    return@launch
                }

                val skipStatus = status.getOrThrow()
                val entschluesselt =
                    if (!skipStatus.isNextAlarmSkipped) {
                        Result.success(null)
                    } else {
                        ManualAlarmSnapshot.decode(skipStatus.skippedManualAlarm)
                    }
                val manualAlarm = entschluesselt.getOrNull()

                // VOR dem Aufheben pruefen, ob der gesicherte manuelle Wecker ueberhaupt zurueckkann.
                // Der Grund fuer diese Reihenfolge: cancelSkip() loescht Flag UND Schnappschuss in
                // einem Zug. Wer erst aufhebt und dann feststellt, dass gerade eine Master-Pause laeuft,
                // hat den Wecker endgueltig verloren - obwohl das Hindernis in einer Stunde weg sein
                // kann. Deshalb: bei einem BEHEBBAREN Hindernis bleibt alles, wie es ist, und der
                // Nutzer erfaehrt, was zu tun ist.
                if (manualAlarm != null) {
                    val hindernis = behebbaresRestoreHindernis(manualAlarm)
                    if (hindernis != null) {
                        Logger.w(
                            LogTags.ALARM_SKIP,
                            "⏸️ Aufheben abgebrochen - manueller Wecker ${manualAlarm.id} koennte jetzt " +
                                "nicht zurueckkehren; Skip und Schnappschuss bleiben erhalten"
                        )
                        _skipState.value = _skipState.value.copy(restoreNotice = hindernis)
                        return@launch
                    }
                }

                alarmSkipUseCase.cancelSkip()
                    .onSuccess {
                        Logger.business(
                            LogTags.ALARM_SKIP,
                            "✅ Skip cancelled by user - Wiederaufbau aus dem Kalenderstand wird angestossen"
                        )
                        // Das Loeschen des Merkers ist bestaetigt - also selbst uebernehmen, statt auf
                        // die naechste Emission zu warten. Ist der Flow geendet, zeigte die
                        // Oberflaeche sonst weiter "Nächster Alarm wird übersprungen" samt
                        // "Aufheben"-Knopf fuer ein Ueberspringen, das es nicht mehr gibt.
                        applySkipState(isNextAlarmSkipped = false, skippedAlarmId = null)
                        // Erst der manuelle Zweig (braucht das bereits geloeschte Flag, sonst weist
                        // der Skip-Backstop in scheduleSystemAlarm() das Re-Arming ab), dann der
                        // kalenderbasierte Wiederaufbau des Aufrufers.
                        if (entschluesselt.isFailure) {
                            // Anders als ein Lesefehler oben ist ein kaputter Schnappschuss DAUERHAFT
                            // kaputt - ihn zu behalten wuerde das Aufheben fuer immer blockieren.
                            // Also aufheben, aber sagen, was verloren ist.
                            Logger.e(
                                LogTags.ALARM_SKIP,
                                "❌ Gesicherter manueller Wecker nicht entschluesselbar - er ist verloren",
                                entschluesselt.exceptionOrNull()
                            )
                            _skipState.value = _skipState.value.copy(
                                restoreNotice = "Das Überspringen wurde aufgehoben, aber der gesicherte " +
                                    "Stand war beschädigt. Falls es ein manuell gestellter Wecker war, " +
                                    "bitte im Wecker-Tab neu anlegen."
                            )
                        } else {
                            restoreSkippedManualAlarm(manualAlarm)
                        }
                        onSkipCleared()
                        // State wird automatisch über skipStatusFlow aktualisiert
                    }
                    .onFailure { error ->
                        Logger.e(LogTags.ALARM_SKIP, "❌ Failed to cancel skip", error)
                    }
            } finally {
                skipVorgangLaeuft = false
                // Der Ladezustand gehoert an dieselbe Klammer wie die Sperre - sonst bliebe der
                // Spinner an einem der frueh abbrechenden Zweige stehen.
                _skipState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Baut einen uebersprungenen MANUELLEN Wecker aus seinem Schnappschuss wieder auf.
     *
     * Nur er braucht das: kalenderbasierte Alarme entstehen beim naechsten Sync von selbst neu
     * (fuer sie ist [alarm] null), ein von Hand gestellter aus keiner Quelle.
     *
     * REIHENFOLGE: erst speichern, dann armieren - ein armierter Wecker ohne Repository-Eintrag
     * waere weder sichtbar noch abbrechbar. Beide Schritte melden ihren Fehlschlag dem Nutzer,
     * statt in einem Log zu verschwinden.
     *
     * Ist die Weckzeit inzwischen verstrichen, wird NICHTS angelegt und NICHTS armiert - ein
     * Wecker in der Vergangenheit klingelt nicht, und ein stumm im Bestand liegender Eintrag
     * waere genau die Luege, die eine Wecker-App nicht erzaehlen darf. Eine "Schicht
     * entfernt"-Meldung entsteht dabei nicht - eine verstrichene Weckzeit ist keine entfernte
     * Schicht.
     *
     * Scheitert das Armieren, wird der gerade gespeicherte Eintrag wieder zurueckgenommen - die
     * Begruendung steht an der Stelle selbst.
     *
     * Die BEHEBBAREN Hindernisse (Master-Pause, "Automatische Alarme" aus, ein zweiter manueller
     * Wecker) sind hier bewusst NICHT mehr geprueft: sie gehoeren nach
     * [behebbaresRestoreHindernis] und damit VOR das Aufheben des Skips, weil sonst Flag und
     * Schnappschuss schon weg waeren, wenn das Hindernis auffaellt.
     */
    /**
     * Nennt das Hindernis, das eine Wiederherstellung JETZT unmoeglich macht, aber SPAETER von
     * selbst oder durch den Nutzer verschwindet - oder null, wenn nichts im Weg steht.
     *
     * Warum das VOR dem Aufheben geprueft wird, steht in [cancelSkip]: Flag und Schnappschuss
     * fallen gemeinsam, ein zu frueh aufgehobener Skip kostet den Wecker endgueltig.
     *
     * Endgueltige Hindernisse gehoeren NICHT hierher: eine verstrichene Weckzeit wird nicht mehr
     * gueltig, und ein beschaedigter Schnappschuss repariert sich nicht. Die bleiben in
     * [restoreSkippedManualAlarm] bzw. in [cancelSkip] - sonst liesse sich das Ueberspringen nie
     * wieder aufheben.
     */
    private suspend fun behebbaresRestoreHindernis(alarm: AlarmInfo): String? {
        // Dieselben zwei Abschaltungen wie beim Anlegen: Wiederherstellen heisst einen Wecker
        // anlegen und stellen - genau das, was createManualAlarm() waehrend der Master-Pause und
        // bei ausgeschalteten "Automatischen Alarmen" verweigert. Ohne dieselben Gates entstuende
        // hier ein Wecker, den der naechste syncAlarms()-Lauf ohne Rueckmeldung wieder wegraeumt.
        if (masterPausePrefs.pausedNow()) {
            return "Der übersprungene manuelle Wecker für ${alarm.formattedTime} kann gerade " +
                "nicht zurückkehren: die Hintergrunddienste sind pausiert. Das Überspringen " +
                "bleibt so lange bestehen – bitte zuerst die Master-Pause beenden und dann " +
                "erneut auf „Aufheben“ tippen."
        }

        // KEIN `getOrNull() ?: true`: ein nicht lesbarer Konfigurationsstand ist etwas anderes
        // als "Automatische Alarme sind an". Wer beides gleich behandelt, laesst den Waechter
        // gruenes Licht geben, cancelSkip() raeumt Flag UND Schnappschuss ab - und wenn die
        // Abschaltung doch aktiv war, loescht der naechste syncAlarms()-Lauf den gerade
        // wiederhergestellten Wecker wortlos. Ein Lesefehler ist genau das BEHEBBARE Hindernis,
        // fuer das es diese Funktion gibt.
        val konfiguration = shiftUseCase.getCurrentShiftConfig()
        if (konfiguration.isFailure) {
            Logger.e(
                LogTags.ALARM_SKIP,
                "❌ Schicht-Konfiguration beim Aufheben nicht lesbar - Aufheben zurueckgestellt",
                konfiguration.exceptionOrNull()
            )
            return "Der übersprungene manuelle Wecker für ${alarm.formattedTime} kann gerade " +
                "nicht zurückkehren: die Einstellungen ließen sich nicht lesen. Das Überspringen " +
                "bleibt bestehen, damit nichts verloren geht – bitte gleich noch einmal versuchen."
        }
        val autoAlarmEnabled = konfiguration.getOrThrow().autoAlarmEnabled
        if (!autoAlarmEnabled) {
            return "Der übersprungene manuelle Wecker für ${alarm.formattedTime} kann gerade " +
                "nicht zurückkehren: „Automatische Alarme“ ist ausgeschaltet, und solange das so " +
                "ist, werden ALLE Wecker gelöscht. Das Überspringen bleibt so lange bestehen – " +
                "bitte zuerst einschalten und dann erneut auf „Aufheben“ tippen."
        }

        // ES GIBT NUR EINEN MANUELLEN WECKER. Zwischen Ueberspringen und Aufheben ist dieser
        // Platz frei (der uebersprungene wurde geloescht), der Nutzer kann in der Zwischenzeit
        // also einen neuen anlegen - createManualAlarm() findet dann keinen bestehenden zum
        // Ersetzen. Wuerde der gesicherte danach einfach zurueckgeschrieben, gaebe es ZWEI:
        // die Karte zeigt nur einen, der "Loeschen"-Knopf trifft nur den gezeigten, und
        // syncAlarms() schont manuelle Alarme ausdruecklich - der andere waere ein armierter
        // Wecker ohne Bedienoberflaeche. Der zuletzt vom Nutzer angelegte gewinnt.
        //
        // AUCH HIER KEIN getOrNull(): ein nicht lesbarer Alarm-Bestand wuerde zu "es gibt keinen
        // anderen manuellen Wecker" degradieren - dieselbe stille Degradierung, die den
        // Schnappschuss vernichtet, den diese Funktion schuetzen soll. Ein Lesefehler ist
        // voruebergehend und damit behebbar; der Skip bleibt so lange stehen.
        val bestand = alarmUseCase.getAllAlarms()
        if (bestand.isFailure) {
            Logger.e(
                LogTags.ALARM_SKIP,
                "❌ Alarm-Bestand beim Aufheben nicht lesbar - Aufheben zurueckgestellt",
                bestand.exceptionOrNull()
            )
            return "Der übersprungene manuelle Wecker für ${alarm.formattedTime} kann gerade " +
                "nicht zurückkehren: die gespeicherten Wecker ließen sich nicht lesen. Das " +
                "Überspringen bleibt bestehen, damit nichts verloren geht – bitte gleich noch " +
                "einmal versuchen."
        }
        val bestehenderManueller = bestand.getOrThrow()
            .firstOrNull { ManualAlarmConstants.isManualAlarm(it) && it.id != alarm.id }
        if (bestehenderManueller != null) {
            // KEIN "einfach neu anlegen" mehr in diesem Text: die ID eines manuellen Weckers ist
            // ein reiner Hash aus Datum und Schicht, der Neuanlegeweg trifft also exakt den noch
            // stehenden Skip-Merker. Dass er trotzdem funktioniert, stellt createManualAlarm()
            // sicher (es hebt einen kollidierenden Skip auf) - empfohlen wird hier aber der
            // Weg, der Schnappschuss und Wecker sauber zusammenfuehrt.
            return "Der übersprungene manuelle Wecker für ${alarm.formattedTime} kann nicht " +
                "zurückkehren, solange ein anderer manueller Wecker " +
                "(${bestehenderManueller.formattedTime}) gestellt ist – es kann immer nur einen " +
                "geben. Das Überspringen bleibt bestehen: bitte den anderen löschen und dann " +
                "erneut auf „Aufheben“ tippen."
        }

        return null
    }

    /**
     * Nimmt einen gerade angelegten, aber nicht armierbaren Alarm zurueck — die einzige Stelle
     * dafuer, weil beide Aufrufer denselben Fehlerfall haben.
     *
     * @see nimmAlarmZurueck fuer den Ablauf, der ohne `NonCancellable` kaputt ging.
     */
    private suspend fun nimmAlarmZurueckUeberUseCase(alarmId: Int, logTag: String): Unit =
        nimmAlarmZurueck(
            alarmId = alarmId,
            logTag = logTag,
            cancelSystemAlarm = { alarmUseCase.cancelSystemAlarm(it) },
            deleteAlarm = { alarmUseCase.deleteAlarm(it) }
        )

    private suspend fun restoreSkippedManualAlarm(alarm: AlarmInfo?) {
        if (alarm == null) return

        if (alarm.triggerTime <= System.currentTimeMillis()) {
            Logger.w(
                LogTags.ALARM_SKIP,
                "⏰ Manueller Wecker ${alarm.id} nicht wiederherstellbar - Weckzeit ${alarm.formattedTime} verstrichen"
            )
            _skipState.value = _skipState.value.copy(
                restoreNotice = "Der übersprungene manuelle Wecker für ${alarm.formattedTime} " +
                    "wurde nicht wieder gestellt: seine Weckzeit ist inzwischen verstrichen. " +
                    "Bitte bei Bedarf einen neuen manuellen Wecker anlegen."
            )
            return
        }

        val saved = alarmUseCase.saveAlarm(alarm)
        if (saved.isFailure) {
            Logger.e(
                LogTags.ALARM_SKIP,
                "❌ Manueller Wecker ${alarm.id} liess sich nicht wiederherstellen",
                saved.exceptionOrNull()
            )
            _skipState.value = _skipState.value.copy(
                restoreNotice = "Der manuelle Wecker für ${alarm.formattedTime} konnte nicht " +
                    "wiederhergestellt werden. Bitte im Wecker-Tab neu anlegen."
            )
            return
        }

        val armed = alarmUseCase.scheduleSystemAlarm(alarm)
        if (armed.isFailure) {
            Logger.e(
                LogTags.ALARM_SKIP,
                "❌ Manueller Wecker ${alarm.id} wiederhergestellt, aber nicht stellbar - wird zurueckgenommen",
                armed.exceptionOrNull()
            )
            // ZURUECKROLLEN, nicht liegenlassen. Sonst bliebe ein Eintrag im Bestand UND im
            // Direct-Boot-Spiegel, den nie ein System-Alarm traegt: ein manueller Wecker wird
            // genau einmal armiert (syncAlarms() re-armiert nur Kalenderalarme, keepManualAlarms
            // SCHONT ihn bloss), es kaeme also kein Nachholer. Die Statuszeile wuerde ihn
            // trotzdem als "Naechster Alarm" ankuendigen - ein stummer Wecker mit Anzeige ist
            // die gefaehrlichste Variante.
            // Reihenfolge wie ueberall: erst cancelSystemAlarm(), dann deleteAlarm().
            nimmAlarmZurueckUeberUseCase(alarm.id, LogTags.ALARM_SKIP)

            _skipState.value = _skipState.value.copy(
                restoreNotice = "Der manuelle Wecker für ${alarm.formattedTime} ließ sich nicht " +
                    "mehr stellen und wurde deshalb NICHT wieder in die Liste aufgenommen – so " +
                    "kündigt keine Anzeige einen Wecker an, der stumm bliebe. Bitte im " +
                    "Wecker-Tab neu anlegen."
            )
            return
        }

        Logger.business(
            LogTags.ALARM_SKIP,
            "✅ Manueller Wecker ${alarm.id} nach 'Aufheben' wiederhergestellt und gestellt (${alarm.formattedTime})"
        )
    }

    // ========================================
    // MANUAL ALARM FUNCTIONALITY
    // ========================================

    private companion object {
        /** Neue Anlaeufe fuer [observeSkipStatus], nachdem der Skip-Flow geendet ist. */
        const val SKIP_OBSERVE_RESUBSCRIBE_ATTEMPTS = 3
        const val SKIP_OBSERVE_RESUBSCRIBE_DELAY_MS = 30_000L
    }

    /**
     * Manual Alarm Constants - simplified approach using existing patterns
     */
    object ManualAlarmConstants {
        const val MANUAL_ALARM_PREFIX = "MANUAL_"
        const val MANUAL_SHIFT_ID_PREFIX = "manual_"

        fun createManualAlarmId(date: LocalDate, shiftId: String): Int {
            // ID-BILDUNG, kein Anzeige-Format: der String geht ueber hashCode() in die Alarm-ID
            val dateString = date.format(DateTimeFormatter.ofPattern(DateTimeFormats.ID_DATE_COMPACT))
            return "$MANUAL_ALARM_PREFIX$dateString$shiftId".hashCode()
        }

        fun createManualShiftId(originalShiftId: String, date: LocalDate): String {
            val dateString = date.format(DateTimeFormatter.ofPattern(DateTimeFormats.ID_DATE_COMPACT))
            return "$MANUAL_SHIFT_ID_PREFIX${originalShiftId}_$dateString"
        }

        fun isManualAlarm(alarmInfo: AlarmInfo): Boolean {
            return alarmInfo.shiftId.startsWith(MANUAL_SHIFT_ID_PREFIX)
        }
    }

    private fun loadAvailableShifts() {
        viewModelScope.launch {
            try {
                // 🔍 CRITICAL DEBUG: Überprüfen was getCurrentShiftConfig() wirklich zurückgibt
                val shiftConfigResult = shiftUseCase.getCurrentShiftConfig()
                Logger.business(
                    LogTags.ALARM,
                    "🔍 SHIFT CONFIG RESULT: success=${shiftConfigResult.isSuccess}"
                )

                shiftConfigResult.getOrNull()?.let { shiftConfig ->
                    // 🔍 DEBUG: Loaded shift config validation
                    Logger.business(
                        LogTags.ALARM,
                        "🔍 CONFIG LOADED: ${shiftConfig.definitions.size} definitions"
                    )

                    Logger.business(
                        LogTags.ALARM,
                        "🔍 LOADED CONFIG has ${shiftConfig.definitions.size} definitions:"
                    )
                    shiftConfig.definitions.forEach { def ->
                        Logger.business(
                            LogTags.ALARM,
                            "   ${def.id}: ${def.name} -> ${def.alarmTime} (${def.getAlarmTimeFormatted()})"
                        )
                    }

                    val availableShifts = shiftConfig.definitions.filter { it.isEnabled }

                    // 🔍 LOG: Available shifts for manual alarm creation

                    _manualAlarmState.value = _manualAlarmState.value.copy(
                        availableShifts = availableShifts,
                        selectedShift = availableShifts.firstOrNull() // Erste verfügbare Schicht
                    )

                    // Update calculated alarm time
                    updateCalculatedAlarmTime()

                    Logger.d(
                        LogTags.ALARM,
                        "Loaded ${availableShifts.size} user-configured shift definitions"
                    )
                } ?: run {
                    Logger.e(LogTags.ALARM, "🚨 CRITICAL: getCurrentShiftConfig() returned null!")

                    // FALLBACK: Versuche direkt die Default-Config zu laden
                    val defaultConfig =
                        com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig.getDefaultConfig()
                    Logger.w(
                        LogTags.ALARM,
                        "🔄 FALLBACK: Using default config with ${defaultConfig.definitions.size} definitions"
                    )

                    val availableShifts = defaultConfig.definitions.filter { it.isEnabled }
                    _manualAlarmState.value = _manualAlarmState.value.copy(
                        availableShifts = availableShifts,
                        selectedShift = availableShifts.firstOrNull(),
                        error = AppErrorState.validationError("Warnung: Default-Konfiguration wird verwendet")
                    )
                    updateCalculatedAlarmTime()
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "🚨 ERROR loading shift definitions", e)
                _manualAlarmState.value = _manualAlarmState.value.copy(
                    error = AppErrorState.validationError("Fehler beim Laden: ${e.message}")
                )
            }
        }
    }

    private fun observeManualAlarms() {
        viewModelScope.launch {
            try {
                sharedActiveAlarms
                    .collect { alarms ->
                        // Filter für manuelle Alarme
                        val manualAlarms = alarms.filter { ManualAlarmConstants.isManualAlarm(it) }
                        val activeManualAlarm = manualAlarms.firstOrNull() // Nur einer zur Zeit

                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            hasActiveManualAlarm = activeManualAlarm != null,
                            activeManualAlarm = activeManualAlarm
                        )

                        Logger.d(LogTags.ALARM, "Manual alarms updated: ${manualAlarms.size}")
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrow for proper structured concurrency
                Logger.d(LogTags.ALARM, "Manual alarms observation cancelled (app lifecycle)")
                throw e
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Error observing manual alarms", e)
            }
        }
    }

    fun selectManualAlarmDate(date: LocalDate) {
        _manualAlarmState.value = _manualAlarmState.value.copy(selectedDate = date)
        updateCalculatedAlarmTime()
    }

    fun selectManualAlarmShift(shift: ShiftDefinition) {
        Logger.d(LogTags.ALARM, "Manual alarm shift selected: ${shift.name}")

        _manualAlarmState.value = _manualAlarmState.value.copy(selectedShift = shift)
        updateCalculatedAlarmTime()
    }

    private fun updateCalculatedAlarmTime() {
        val state = _manualAlarmState.value
        val selectedShift = state.selectedShift
        val selectedDate = state.selectedDate

        if (selectedShift != null) {
            // 🔍 DEBUG: Log die verwendete Schicht-Zeit
            Logger.business(
                LogTags.ALARM,
                "🎯 CALCULATING alarm time for shift: ${selectedShift.name}"
            )
            Logger.business(LogTags.ALARM, "   📅 Date: $selectedDate")
            Logger.business(LogTags.ALARM, "   ⏰ Shift alarmTime: ${selectedShift.alarmTime}")
            Logger.business(
                LogTags.ALARM,
                "   📋 Shift formatted: ${selectedShift.getAlarmTimeFormatted()}"
            )

            // ✅ KORRIGIERT: Verwende die User-konfigurierte Zeit OHNE bescheuerten Offset
            val alarmDateTime = selectedDate.atTime(selectedShift.alarmTime)

            val formattedTime = alarmDateTime.format(
                DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
            )

            Logger.business(LogTags.ALARM, "   🚨 FINAL calculated alarm: $formattedTime")

            _manualAlarmState.value = _manualAlarmState.value.copy(
                calculatedAlarmTime = formattedTime
            )
        } else {
            Logger.w(LogTags.ALARM, "⚠️ NO SHIFT selected for alarm calculation")
            _manualAlarmState.value = _manualAlarmState.value.copy(
                calculatedAlarmTime = null
            )
        }
    }

    fun createManualAlarm() {
        viewModelScope.launch {
            val state = _manualAlarmState.value
            val selectedShift = state.selectedShift
            val selectedDate = state.selectedDate

            if (masterPausePrefs.pausedNow()) {
                _manualAlarmState.value = state.copy(
                    error = AppErrorState.validationError(
                        "Hintergrunddienste sind pausiert – bitte zuerst die Master-Pause beenden"
                    )
                )
                return@launch
            }

            // DIE ZWEITE ABSCHALTUNG: "Automatische Alarme" im Wecker-Tab.
            //
            // Sie ist eine ECHTE, sofortige Pause (CLAUDE.md): `syncAlarms()` raeumt in diesem
            // Zustand ALLE Alarme ab, ausdruecklich auch manuelle - das ist so entschieden und
            // testlich festgeschrieben. Genau deshalb darf hier keiner mehr entstehen: der Nutzer
            // bekaeme eine Erfolgsmeldung fuer einen Wecker, den der naechste `syncAlarms()`-Lauf
            // (jeder App-Start, jede 6h-Wartung) ohne jede Rueckmeldung wieder cancelt und loescht.
            // "Ich habe einen Wecker gestellt und er ist stillschweigend verschwunden" ist fuer
            // eine Wecker-App der schlechteste denkbare Ausgang. Bei der Master-Pause daneben ist
            // dieser Widerspruch schon geschlossen; hier fehlte er.
            val autoAlarmEnabled =
                shiftUseCase.getCurrentShiftConfig().getOrNull()?.autoAlarmEnabled ?: true
            if (!autoAlarmEnabled) {
                _manualAlarmState.value = state.copy(
                    error = AppErrorState.validationError(
                        "„Automatische Alarme“ ist im Wecker-Tab ausgeschaltet – solange das so " +
                            "ist, werden ALLE Wecker geloescht, auch manuell gestellte. Bitte " +
                            "zuerst dort einschalten."
                    )
                )
                return@launch
            }

            if (selectedShift == null) {
                _manualAlarmState.value = state.copy(
                    error = AppErrorState.validationError("Bitte wählen Sie eine Schicht aus")
                )
                return@launch
            }

            _manualAlarmState.value = state.copy(isCreating = true, error = null)

            try {
                // Berechne Alarm-Zeit - ✅ OHNE bescheuerten Offset
                val alarmDateTime = selectedDate.atTime(selectedShift.alarmTime)
                val alarmTimeMillis = alarmDateTime
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

                // Prüfe ob in der Zukunft
                if (alarmTimeMillis <= System.currentTimeMillis()) {
                    _manualAlarmState.value = state.copy(
                        isCreating = false,
                        error = AppErrorState.validationError("Alarm-Zeit muss in der Zukunft liegen")
                    )
                    return@launch
                }

                // Master-Pause erneut pruefen: die suspend-Kette bis hierhin (delete/save/schedule
                // folgen noch) kann Master-Pause ueberholen, die zwischenzeitlich (z.B. auf einem
                // anderen Tab) aktiviert wurde. Anders als AlarmUseCase.syncAlarms() hat dieser
                // manuelle Erstellungspfad keinen zentralen Backstop - der einmalige Check ganz
                // oben deckt nur den Start der Coroutine ab, nicht den tatsaechlichen Persistenz-
                // /Scheduling-Moment.
                if (masterPausePrefs.pausedNow()) {
                    _manualAlarmState.value = _manualAlarmState.value.copy(
                        isCreating = false,
                        error = AppErrorState.validationError(
                            "Hintergrunddienste sind pausiert – bitte zuerst die Master-Pause beenden"
                        )
                    )
                    return@launch
                }

                // Lösche vorherigen manuellen Alarm (nur einer zur Zeit)
                state.activeManualAlarm?.let { existingAlarm ->
                    alarmUseCase.deleteAlarm(existingAlarm.id)
                }

                // Erstelle AlarmInfo
                val manualAlarmId =
                    ManualAlarmConstants.createManualAlarmId(selectedDate, selectedShift.id)
                val manualShiftId =
                    ManualAlarmConstants.createManualShiftId(selectedShift.id, selectedDate)

                // SKIP-KOLLISION AUFLOESEN, BEVOR gespeichert wird.
                //
                // Die ID eines manuellen Weckers ist ein reiner Hash aus Datum und Schicht. Wer
                // denselben Wecker nach dem Ueberspringen noch einmal anlegt - und genau dazu hat
                // die App beim blockierten "Aufheben" auch geraten - trifft damit exakt die ID im
                // Skip-Merker. Der Backstop in scheduleSystemAlarm() weist ihn dann ab, waehrend
                // Eintrag und Karte ihn als gestellt zeigen: stummer Wecker MIT Anzeige. Der
                // Merker laeuft zeitbasiert erst NACH der Weckzeit ab, ein Nachholer existiert
                // nicht (syncAlarms schont manuelle Alarme nur, es armiert sie nicht neu).
                //
                // Wer denselben Wecker neu anlegt, will ihn - also ist das Aufheben des
                // Ueberspringens die richtige Antwort, keine Fehlermeldung. Der Schnappschuss darf
                // dabei mitfallen: er beschreibt genau den Wecker, der hier gerade neu entsteht.
                //
                // Ein nicht lesbarer Skip-Zustand blockiert das Anlegen NICHT (im Zweifel wecken) -
                // dagegen steht die Ruecknahme weiter unten, falls der Backstop doch greift.
                val kollidierenderSkip = alarmSkipUseCase.getSkipStatus().getOrNull()
                    ?.takeIf { it.isNextAlarmSkipped && it.skippedAlarmId == manualAlarmId }
                if (kollidierenderSkip != null) {
                    val aufgehoben = alarmSkipUseCase.cancelSkip()
                    if (aufgehoben.isFailure) {
                        // Nicht weitermachen: der Merker steht noch, der Wecker wuerde gespeichert
                        // und angezeigt, aber nie armiert.
                        Logger.e(
                            LogTags.ALARM_SKIP,
                            "❌ Kollidierendes Ueberspringen fuer Alarm $manualAlarmId liess sich " +
                                "nicht aufheben - manueller Wecker wird NICHT angelegt",
                            aufgehoben.exceptionOrNull()
                        )
                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            isCreating = false,
                            error = AppErrorState.validationError(
                                "Dieser Wecker ist gerade als übersprungen markiert, und das " +
                                    "ließ sich nicht aufheben. Er wurde deshalb NICHT gestellt – " +
                                    "bitte gleich noch einmal versuchen."
                            )
                        )
                        return@launch
                    }
                    Logger.business(
                        LogTags.ALARM_SKIP,
                        "↩️ Ueberspringen fuer Alarm $manualAlarmId aufgehoben - derselbe Wecker " +
                            "wird gerade neu angelegt"
                    )
                }

                val alarmInfo = AlarmInfo(
                    id = manualAlarmId,
                    shiftId = manualShiftId,
                    shiftName = "${selectedShift.name} (Manuell)",
                    triggerTime = alarmTimeMillis,
                    formattedTime = alarmDateTime.format(
                        DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
                    )
                )

                // Speichere Alarm
                alarmUseCase.saveAlarm(alarmInfo)
                    .onSuccess {
                        // Schedule System Alarm
                        alarmUseCase.scheduleSystemAlarm(alarmInfo)
                            .onSuccess {
                                _manualAlarmState.value = _manualAlarmState.value.copy(
                                    isCreating = false
                                )
                                Logger.business(
                                    LogTags.ALARM,
                                    "✅ Manual alarm created: ${selectedShift.name} for $selectedDate"
                                )
                            }
                            .onFailure { error ->
                                // ZURUECKROLLEN, nicht liegenlassen - dieselbe Begruendung wie in
                                // restoreSkippedManualAlarm(): ein Eintrag im Bestand (und im
                                // Direct-Boot-Spiegel), den kein System-Alarm traegt, ist ein
                                // stummer Wecker MIT Anzeige. Ein manueller Wecker wird genau
                                // einmal armiert, syncAlarms() schont ihn nur - es kaeme also
                                // kein Nachholer, waehrend die Karte "Manueller Alarm aktiv" mit
                                // Uhrzeit zeigt.
                                // Reihenfolge wie ueberall: erst cancelSystemAlarm(), dann
                                // deleteAlarm().
                                nimmAlarmZurueckUeberUseCase(alarmInfo.id, LogTags.ALARM)

                                _manualAlarmState.value = _manualAlarmState.value.copy(
                                    isCreating = false,
                                    error = AppErrorState.networkError(
                                        "Der Wecker ließ sich nicht stellen und wurde deshalb " +
                                            "NICHT in die Liste aufgenommen – so kündigt keine " +
                                            "Anzeige einen Wecker an, der stumm bliebe. Bitte " +
                                            "erneut versuchen. (${error.message ?: "unbekannter Fehler"})"
                                    )
                                )
                                Logger.e(LogTags.ALARM, "❌ Failed to schedule manual alarm", error)
                            }
                    }
                    .onFailure { error ->
                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            isCreating = false,
                            error = AppErrorState.validationError(
                                error.message ?: "Fehler beim Speichern des Alarms"
                            )
                        )
                        Logger.e(LogTags.ALARM, "❌ Failed to save manual alarm", error)
                    }

            } catch (e: Exception) {
                _manualAlarmState.value = _manualAlarmState.value.copy(
                    isCreating = false,
                    error = AppErrorState.validationError(
                        e.message ?: "Unbekannter Fehler beim Erstellen des Alarms"
                    )
                )
                Logger.e(LogTags.ALARM, "❌ Exception creating manual alarm", e)
            }
        }
    }

    fun deleteManualAlarm() {
        viewModelScope.launch {
            val activeAlarm = _manualAlarmState.value.activeManualAlarm
            if (activeAlarm == null) {
                Logger.w(LogTags.ALARM, "No active manual alarm to delete")
                return@launch
            }

            _manualAlarmState.value = _manualAlarmState.value.copy(isDeleting = true, error = null)

            try {
                alarmUseCase.deleteAlarm(activeAlarm.id)
                    .onSuccess {
                        _manualAlarmState.value = _manualAlarmState.value.copy(isDeleting = false)
                        Logger.business(
                            LogTags.ALARM,
                            "✅ Manual alarm deleted: ${activeAlarm.shiftName}"
                        )
                    }
                    .onFailure { error ->
                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            isDeleting = false,
                            error = AppErrorState.validationError(
                                error.message ?: "Fehler beim Löschen des Alarms"
                            )
                        )
                        Logger.e(LogTags.ALARM, "❌ Failed to delete manual alarm", error)
                    }
            } catch (e: Exception) {
                _manualAlarmState.value = _manualAlarmState.value.copy(
                    isDeleting = false,
                    error = AppErrorState.validationError(
                        e.message ?: "Unbekannter Fehler beim Löschen des Alarms"
                    )
                )
                Logger.e(LogTags.ALARM, "❌ Exception deleting manual alarm", e)
            }
        }
    }

    fun clearManualAlarmError() {
        _manualAlarmState.value = _manualAlarmState.value.copy(error = null)
    }

    /**
     * MEMORY LEAK PREVENTION: Comprehensive resource cleanup
     * CRITICAL FIX: This was missing and causing memory leaks!
     */
    override fun onCleared() {
        try {
            // MEMORY LEAK FIX: Cancel alarm observation job
            alarmObservationJob?.cancel()
            alarmObservationJob = null

            // MEMORY OPTIMIZATION: Clear state to release references
            _uiState.value = AlarmUiState()
            _skipState.value = AlarmSkipUiState()
            _manualAlarmState.value = ManualAlarmUiState()

            Logger.d(
                LogTags.LIFECYCLE,
                "AlarmViewModel cleared - cleaning up alarm observations and resources"
            )
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during AlarmViewModel cleanup", e)
        }

        // Note: ViewModelScope automatically cancels all remaining coroutines
    }

}

/**
 * Die kompensierende Ruecknahme eines Alarms, der gespeichert, aber nicht armiert werden konnte —
 * bewusst als Top-Level-Funktion, damit sie ohne ViewModel pruefbar ist.
 *
 * WELCHER ABLAUF GING KAPUTT: Die Ruecknahme stand als blankes `runCatching { ... }` direkt im
 * `viewModelScope`. `SafeExecutor.safeExecute` wirft `CancellationException` ausdruecklich WEITER
 * (statt sie in ein `Result.failure` zu verwandeln), und `runCatching` faengt `Throwable` — also
 * auch genau diese. Verlaesst der Nutzer die App in dem Moment, in dem das Stellen scheitert
 * (Activity wird abgeraeumt, `viewModelScope` gecancelt), warf `cancelSystemAlarm()` am ersten
 * Suspensionspunkt, `runCatching` schluckte es, und dieselbe Zeile darunter schluckte auch das
 * `deleteAlarm()`. Zurueck blieb der Alarm im Repository UND im Direct-Boot-Spiegel, ohne dass je
 * ein System-Alarm dazu existierte: ein manueller Wecker wird genau einmal armiert (`syncAlarms()`
 * SCHONT ihn per `keepManualAlarms` nur, es armiert ihn nicht nach), ein Nachholer kommt also
 * nicht. Die Karte haette "Manueller Alarm aktiv" mit Uhrzeit gezeigt, waehrend der Fehlertext
 * zusichert, der Wecker sei NICHT aufgenommen worden — der stumme Wecker MIT Anzeige, gegen den
 * diese Ruecknahme ueberhaupt existiert.
 *
 * Deshalb `withContext(NonCancellable)`: eine Ruecknahme STELLT einen Zustand HER, genau wie
 * `MasterPauseUseCase.pause()` oder das Aufraeumen der Dimm-Vorschau. Die Reihenfolge bleibt die
 * projektweite: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.
 */
internal suspend fun nimmAlarmZurueck(
    alarmId: Int,
    logTag: String,
    cancelSystemAlarm: suspend (Int) -> Result<Unit>,
    deleteAlarm: suspend (Int) -> Result<Unit>
) {
    withContext(NonCancellable) {
        runCatching { cancelSystemAlarm(alarmId) }
            .onFailure { Logger.e(logTag, "Ruecknahme: cancelSystemAlarm fehlgeschlagen", it) }
        runCatching { deleteAlarm(alarmId) }
            .onFailure { Logger.e(logTag, "Ruecknahme: deleteAlarm fehlgeschlagen", it) }
    }
}
