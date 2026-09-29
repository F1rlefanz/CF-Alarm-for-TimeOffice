@file:Suppress("UnusedImport") // False positive - encodeToString is used in persistToDataStore()

package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import kotlinx.coroutines.flow.onStart
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.UserManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmEntry
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AlarmInfoData(
    val id: Int,
    val shiftId: String,
    val shiftName: String,
    val triggerTime: Long,
    val formattedTime: String,
    val eventId: String = "",
    val eventChecksum: String = "",
    val shiftEndTime: Long = 0,  // Schichtende (Epoch-Millis) für SHIFT_END-Dimmfenster; 0 = unbekannt
    val shiftStartTime: Long = 0,  // Schichtbeginn (Epoch-Millis) für DND-"Dienstzeit"-Fenster; 0 = unbekannt
    val isSilent: Boolean = false  // "Stille Schicht" - siehe AlarmInfo.isSilent
)

@Singleton
class AlarmRepository @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>,
    private val directBootAlarmStore: DirectBootAlarmStore,
    @param:ApplicationContext private val appContext: Context
) : IAlarmRepository {

    companion object {
        private val ALARMS_KEY = stringPreferencesKey("active_alarms")

        /** Sicherung des rohen, nicht dekodierbaren Bestands - siehe [backupBrokenAlarms]. */
        internal const val BROKEN_ALARMS_KEY_NAME = "active_alarms_broken"
        private val BROKEN_ALARMS_KEY = stringPreferencesKey(BROKEN_ALARMS_KEY_NAME)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Coroutine Scope für asynchrone Operationen
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory cache für schnellen Zugriff + reaktive UI
    private val _activeAlarms = MutableStateFlow<List<AlarmInfo>>(emptyList())
    /**
     * `onStart { awaitInitialLoad() }` ist tragend: ein reiner Beobachter ruft keine Methode, und
     * ohne den Haken heilte sich ein im gesperrten Zustand ergebnisloser Init-Load fuer ihn nie -
     * der Prozess ueberlebt das Entsperren. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     */
    override val activeAlarms: Flow<List<AlarmInfo>> =
        _activeAlarms.asStateFlow().onStart { awaitInitialLoad() }

    /**
     * BEREIT-SIGNAL für den asynchronen Init-Load: sonst ist der leere Start-Cache nicht von
     * „es gibt keine Alarme" zu unterscheiden, und der spät zurückkehrende Init-Load überschreibt
     * Cache, DataStore und Direct-Boot-Spiegel mit seinem alten Snapshot. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     *
     * Wird in `finally` IMMER erfüllt, damit ein Lesefehler die Aufrufer nicht hängen lässt.
     */
    private val initialLoadDone = CompletableDeferred<Unit>()

    /**
     * Serialisiert ALLE Read-Modify-Write-Pfade auf dem Ganzlisten-Bestand (Init-Bereinigung,
     * save/delete/deleteAll/cleanup). Ohne ihn schreiben zwei unabhängige Aufrufer je eine
     * komplette Liste aus ihrem eigenen Snapshot — eine Änderung geht verloren. Vorbild:
     * `DimOverlayPrefs.withOverrideLock`.
     *
     * NICHT reentrant: `persistToDataStore()`/`cleanupExpiredAlarms()` nehmen den Lock deshalb
     * bewusst NICHT selbst, sie werden nur von Lock-Haltern aufgerufen.
     */
    private val stateMutex = Mutex()

    /**
     * SCHREIBSPERRE nach einem gescheiterten Init-Load: die Notlage-Leere darf weder
     * `active_alarms` noch den Direct-Boot-Spiegel überschreiben (verloren gingen v. a. manuelle
     * Alarme). Die System-Alarme werden trotzdem gesetzt, nur die Persistenz bleibt außen vor.
     * Einzige Ausnahme: [deleteAllAlarms] MUSS auch dann räumen, sonst re-armt ein
     * Direct-Boot-Restore gerade pausierte Alarme. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     */
    @Volatile
    private var persistenceBlocked = false

    /**
     * Wurde der Bestand schon EINMAL im entsperrten Zustand gelesen?
     *
     * Vor der ersten Entsperrung liefert der CE-Store still `emptyPreferences()` als ERFOLG (kein
     * Wurf), und der Prozess ueberlebt das Entsperren. Deshalb gilt so ein Read nicht als
     * Ergebnis: die Persistenz bleibt gesperrt, und [awaitInitialLoad] laedt nach dem Entsperren
     * nach. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     */
    @Volatile
    private var loadedWhileUnlocked = false

    /** Laeuft gerade ein Nachlade-Versuch? Verhindert, dass zehn Aufrufer zehn Reads starten. */
    private val reloadMutex = Mutex()

    /**
     * IST DER LETZTE SCHREIBVERSUCH GESCHEITERT (voller Speicher, IOException, beschaedigte
     * Datei)? Abfragbar ueber [istLetzterSchreibvorgangGescheitert]; einziger Konsument ist die
     * Anzeige des manuellen Weckers.
     *
     * NIE MIT [persistenceBlocked] VERODERN: "unlesbar" laesst `clearInternalAlarms()` die
     * `cancelSystemAlarm`-Schleife ueberspringen - nach einem Schreibfehler ist der Bestand aber
     * lesbar, verodert bliebe "Raeumen ohne Cancellen". Anzeigen fragt beide, Raeumen nur
     * [isPersistenceBlocked]. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     *
     * KEINE Dauersperre: ein erfolgreicher Schreibvorgang setzt den Merker sofort wieder auf
     * `false` - er beschreibt den LETZTEN Versuch, nicht die Vergangenheit.
     */
    @Volatile
    private var letzterSchreibvorgangGescheitert = false

    private val userUnlocked: Boolean
        get() = try {
            appContext.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        } catch (e: Exception) {
            // Im Zweifel als entsperrt behandeln: ein falsch-positives "gesperrt" wuerde die
            // Persistenz dauerhaft sperren.
            Logger.w(LogTags.ALARM, "UserManager nicht abfragbar - Nutzer gilt als entsperrt", e)
            true
        }

    init {
        loadAlarmsFromDataStore()
    }

    private fun loadAlarmsFromDataStore() {
        repositoryScope.launch {
            try {
                stateMutex.withLock {
                    // GESPERRTER NUTZER: gar nicht erst lesen. Der Read wuerde nicht werfen,
                    // sondern still eine leere Preferences-Instanz liefern (siehe
                    // [loadedWhileUnlocked]) - und diese Leere waere von "keine Alarme"
                    // nicht zu unterscheiden.
                    if (!userUnlocked) {
                        persistenceBlocked = true
                        _activeAlarms.value = emptyList()
                        Logger.w(
                            LogTags.ALARM,
                            "🔐 PERSISTENCE: Nutzer noch nicht entsperrt - Alarm-Bestand NICHT " +
                                "gelesen (ein Read wuerde still 'leer' liefern). Persistenz bis zum " +
                                "Entsperren gesperrt, danach wird nachgeladen. Der Direct-Boot-Restore " +
                                "arbeitet unabhaengig davon aus dem Device-Protected-Spiegel."
                        )
                        return@withLock
                    }
                    val preferences = dataStore.data.first()
                    val alarmsJson = preferences[ALARMS_KEY]

                    if (alarmsJson != null) {
                        // Dekodieren EIGENSTÄNDIG abfangen: ein defekter Wert ist etwas anderes als
                        // ein IO-Fehler und darf nicht als "keine Alarme" durchgehen (siehe
                        // persistenceBlocked).
                        val alarmsData = try {
                            json.decodeFromString<List<AlarmInfoData>>(alarmsJson)
                        } catch (e: Exception) {
                            persistenceBlocked = true
                            backupBrokenAlarms(alarmsJson)
                            _activeAlarms.value = emptyList()
                            Logger.e(
                                LogTags.ALARM,
                                "❌ PERSISTENCE: Alarm-Bestand (${alarmsJson.length} Zeichen) ist NICHT " +
                                    "dekodierbar - Sicherung unter '$BROKEN_ALARMS_KEY_NAME', Persistenz " +
                                    "und Direct-Boot-Spiegel werden fuer diesen Prozess NICHT mehr " +
                                    "ueberschrieben",
                                e
                            )
                            return@withLock
                        }
                        val alarms = alarmsData.map { it.toAlarmInfo() }

                        // Cleanup: Entferne automatisch abgelaufene Alarme
                        val currentTime = System.currentTimeMillis()
                        val validAlarms = alarms.filter { it.triggerTime > currentTime }

                        _activeAlarms.value = validAlarms
                        loadedWhileUnlocked = true
                        persistenceBlocked = false

                        Logger.business(
                            LogTags.ALARM,
                            "✅ PERSISTENCE: Loaded ${validAlarms.size} alarms from DataStore (removed ${alarms.size - validAlarms.size} expired)"
                        )

                        // Wenn wir abgelaufene Alarme entfernt haben, speichere die bereinigte Liste
                        if (validAlarms.size < alarms.size) {
                            persistToDataStore(validAlarms)
                        } else {
                            // SPIEGEL-ABGLEICH bei JEDEM erfolgreichen Load: faellt nach dem
                            // DataStore-Write der Spiegel-Write aus, bliebe die Divergenz sonst
                            // dauerhaft (der haeufigste Sync-Zweig schreibt das Repository nicht).
                            // `saveAll` ist idempotent und billig. Hergang: Skill
                            // cfalarm-persistenz-und-auth, reference/persistenz.md.
                            directBootAlarmStore.saveAll(
                                validAlarms.map {
                                    DirectBootAlarmEntry(
                                        it.id,
                                        it.shiftName,
                                        it.triggerTime,
                                        formatShiftStartTime(it.shiftStartTime)
                                    )
                                }
                            )
                        }
                    } else {
                        // Hier ist "leer" belastbar: der Nutzer IST entsperrt (oben geprueft), der
                        // Store also wirklich lesbar - es steht schlicht nichts drin.
                        Logger.d(LogTags.ALARM, "📭 PERSISTENCE: No saved alarms found in DataStore")
                        _activeAlarms.value = emptyList()
                        loadedWhileUnlocked = true
                        persistenceBlocked = false
                    }
                }
            } catch (e: Exception) {
                // Auch ein LESEFEHLER (kein Dekodier-Fehler) degradiert den Cache auf leer, während
                // die Datei intakt sein kann - der nächste Write würde sie und den
                // Direct-Boot-Spiegel mit dieser Leere überschreiben. Gleiche Sperre.
                persistenceBlocked = true
                Logger.e(
                    LogTags.ALARM,
                    "❌ PERSISTENCE: Error loading alarms from DataStore - Persistenz und " +
                        "Direct-Boot-Spiegel werden fuer diesen Prozess NICHT mehr ueberschrieben",
                    e
                )
                _activeAlarms.value = emptyList()
            } finally {
                initialLoadDone.complete(Unit)
            }
        }
    }

    /**
     * Legt den rohen, nicht dekodierbaren Alarm-Bestand einmalig unter einem eigenen Schlüssel ab,
     * BEVOR irgendein Schreibpfad ihn überschreiben kann - Vorbild
     * `ShiftConfigRepository.backupBrokenConfig()`. Überschreibt eine vorhandene Sicherung NICHT:
     * die ERSTE ist die dem Original nächste. Fehler werden nur geloggt.
     */
    private suspend fun backupBrokenAlarms(raw: String) {
        try {
            dataStore.edit { preferences ->
                if (preferences[BROKEN_ALARMS_KEY] == null) {
                    preferences[BROKEN_ALARMS_KEY] = raw
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Sicherung des defekten Alarm-Bestands fehlgeschlagen", e)
        }
    }

    /**
     * Wartet auf den Init-Load - und HOLT IHN NACH, wenn er nur deshalb ergebnislos war, weil der
     * Nutzer noch nicht entsperrt hatte (siehe [loadedWhileUnlocked]).
     *
     * Bewusst HIER: jeder Lese- und jeder Ganzlisten-Schreibpfad geht durch diese Funktion. Damit
     * heilt sich der Zustand beim ersten Zugriff nach dem Entsperren von selbst, ohne neuen
     * Receiver, ohne Scheduler und ohne dass ein kuenftiger Aufrufer daran denken muss.
     */
    private suspend fun awaitInitialLoad() {
        initialLoadDone.await()
        if (loadedWhileUnlocked || !userUnlocked) return

        reloadMutex.withLock {
            if (loadedWhileUnlocked) return
            Logger.business(
                LogTags.ALARM,
                "🔓 PERSISTENCE: Nutzer ist jetzt entsperrt - Alarm-Bestand wird nachgeladen"
            )
            reloadFromDataStore()
        }
    }

    /**
     * Der eigentliche Lesevorgang des Nachladens. Teilt [stateMutex] mit allen anderen
     * Ganzlisten-Pfaden, damit er sich nicht mit einem gleichzeitigen Write kreuzt.
     */
    private suspend fun reloadFromDataStore() {
        try {
            stateMutex.withLock {
                val alarmsJson = dataStore.data.first()[ALARMS_KEY]
                if (alarmsJson == null) {
                    _activeAlarms.value = emptyList()
                    loadedWhileUnlocked = true
                    persistenceBlocked = false
                    Logger.d(LogTags.ALARM, "📭 PERSISTENCE: Nachladen - kein Bestand gespeichert")
                    return@withLock
                }
                val alarms = try {
                    json.decodeFromString<List<AlarmInfoData>>(alarmsJson).map { it.toAlarmInfo() }
                } catch (e: Exception) {
                    // Wie im Init-Load: ein defekter Wert sperrt die Persistenz und wird gesichert,
                    // statt als "keine Alarme" durchzugehen.
                    persistenceBlocked = true
                    loadedWhileUnlocked = true
                    backupBrokenAlarms(alarmsJson)
                    _activeAlarms.value = emptyList()
                    Logger.e(
                        LogTags.ALARM,
                        "❌ PERSISTENCE: Nachgeladener Alarm-Bestand ist nicht dekodierbar - " +
                            "Sicherung unter '$BROKEN_ALARMS_KEY_NAME', Persistenz gesperrt",
                        e
                    )
                    return@withLock
                }
                val valid = alarms.filter { it.triggerTime > System.currentTimeMillis() }
                _activeAlarms.value = valid
                loadedWhileUnlocked = true
                persistenceBlocked = false
                Logger.business(
                    LogTags.ALARM,
                    "✅ PERSISTENCE: ${valid.size} Alarme nachgeladen (${alarms.size - valid.size} abgelaufen)"
                )
            }
        } catch (e: Exception) {
            // NICHT `loadedWhileUnlocked` setzen: ein echter Lesefehler soll beim naechsten Zugriff
            // erneut versucht werden. Die Sperre bleibt, damit nichts ueberschrieben wird.
            persistenceBlocked = true
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Nachladen fehlgeschlagen - Persistenz bleibt gesperrt", e)
        }
    }

    /**
     * PERSISTENCE: Speichert Alarme in DataStore
     *
     * ACHTUNG: nimmt [stateMutex] NICHT selbst (nicht reentrant) - nur aus einem Lock-Halter rufen.
     *
     * @param force überschreibt die [persistenceBlocked]-Sperre. NUR für ein ausdrückliches Räumen
     *   (siehe [deleteAllAlarms]) - dort ist "nichts" der gewollte Zustand, nicht eine Notlage.
     * @return `true`, wenn Preferences-Datei UND Direct-Boot-Spiegel wirklich geschrieben wurden.
     *   `false` heißt: der Bestand lebt für diesen Prozess nur noch im Arbeitsspeicher. Beide
     *   Fälle sind bewusst KEINE Exception (siehe [saveAlarm]), aber sie dürfen sich auch nicht
     *   gleich anfühlen - deshalb dieser Rückgabewert statt eines reinen Log-Eintrags.
     */
    private suspend fun persistToDataStore(alarms: List<AlarmInfo>, force: Boolean = false): Boolean {
        if (persistenceBlocked && !force) {
            Logger.w(
                LogTags.ALARM,
                "⛔ PERSISTENCE: Schreiben von ${alarms.size} Alarmen uebersprungen - der Init-Load " +
                    "ist gescheitert, der vorhandene Bestand und der Direct-Boot-Spiegel bleiben " +
                    "unangetastet (Sicherung: '$BROKEN_ALARMS_KEY_NAME')"
            )
            return false
        }
        try {
            val alarmsData = alarms.map { it.toAlarmInfoData() }
            val alarmsJson = json.encodeToString(alarmsData)

            dataStore.edit { preferences ->
                preferences[ALARMS_KEY] = alarmsJson
            }

            // Device-Protected-Spiegel synchron mitschreiben, damit die Alarme nach einem Reboot
            // schon VOR der ersten Entsperrung wiederhergestellt werden koennen (Direct Boot).
            // shiftStartTime (nicht formattedTime/triggerTime, das ist die Weckzeit!) fuellt nach
            // dem Reboot dieselbe "Deine Schicht beginnt um"-Anzeige wie der reguläre Pfad.
            directBootAlarmStore.saveAll(
                alarmsData.map {
                    DirectBootAlarmEntry(it.id, it.shiftName, it.triggerTime, formatShiftStartTime(it.shiftStartTime))
                }
            )

            // Erst NACH dem Spiegel zuruecksetzen: vorher waere "dauerhaft" eine Behauptung ueber
            // einen Vorgang, der noch werfen kann.
            letzterSchreibvorgangGescheitert = false

            Logger.d(LogTags.ALARM, "💾 PERSISTENCE: Saved ${alarms.size} alarms to DataStore (+ Direct-Boot-Spiegel)")
            return true
        } catch (e: Exception) {
            // MERKEN, nicht nur loggen: sonst meldet `isPersistenceBlocked()` gleich danach "alles
            // in Ordnung", und der manuelle Wecker gibt sich als dauerhaft gespeichert aus,
            // obwohl weder Preferences-Datei noch Direct-Boot-Spiegel ihn haben.
            letzterSchreibvorgangGescheitert = true
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Error saving alarms to DataStore", e)
            return false
        }
    }

    /**
     * Meldet bewusst auch dann `success`, wenn der Alarm nur im Arbeitsspeicher liegt (Sperre oder
     * Schreibfehler): die System-Alarme sollen trotzdem klingeln, und `syncAlarms()` armiert erst
     * NACH `saveAlarm()` - ein Wurf hier ergaebe einen stummen Wecker MIT Anzeige. Die Spur ist
     * das WARN unten (Release-Log); wer Dauerhaftigkeit anzeigen will, fragt NACH dem Speichern
     * [isPersistenceBlocked] und [istLetzterSchreibvorgangGescheitert]. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     */
    override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
        return try {
            val currentTime = System.currentTimeMillis()
            if (alarmInfo.triggerTime <= currentTime) {
                Logger.w(
                    LogTags.ALARM,
                    "Rejecting past alarm: ${alarmInfo.formattedTime} (current: ${java.time.LocalDateTime.now()})"
                )
                return Result.failure(IllegalArgumentException("Alarm time is in the past: ${alarmInfo.formattedTime}"))
            }

            awaitInitialLoad()
            stateMutex.withLock {
                val currentAlarms = _activeAlarms.value.toMutableList()
                val existingIndex = currentAlarms.indexOfFirst { it.id == alarmInfo.id }

                if (existingIndex != -1) {
                    currentAlarms[existingIndex] = alarmInfo
                    Logger.d(LogTags.ALARM, "Alarm updated: ${alarmInfo.id}")
                } else {
                    currentAlarms.add(alarmInfo)
                    Logger.business(LogTags.ALARM, "Alarm added", alarmInfo.id.toString())
                }

                _activeAlarms.value = currentAlarms

                val dauerhaft = persistToDataStore(currentAlarms)
                if (!dauerhaft) {
                    Logger.w(
                        LogTags.ALARM,
                        "⚠️ PERSISTENCE: Alarm ${alarmInfo.id} (${alarmInfo.formattedTime}) liegt NUR " +
                            "im Arbeitsspeicher - nicht in der Preferences-Datei und nicht im " +
                            "Direct-Boot-Spiegel. Er klingelt in diesem Prozess, ist aber nach " +
                            "Prozesstod oder Neustart weg (siehe isPersistenceBlocked)"
                    )
                }

                cleanupExpiredAlarms()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error saving alarm: ${alarmInfo.id}", e)
            Result.failure(e)
        }
    }

    /**
     * "IST DER BESTAND IN DIESEM PROZESS UNLESBAR?" - genau das: Init-Load gescheitert oder vor
     * der Entsperrung, Cache degradiert, Schreibpfade gesperrt; `getAllAlarms()` meldet das NICHT
     * als Fehler. NICHT "letzter Schreibvorgang gescheitert" ([istLetzterSchreibvorgangGescheitert])
     * - nie verodern, sonst laesst die Master-Pause armierte, unabbrechbare System-Alarme zurueck.
     * Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     */
    override suspend fun isPersistenceBlocked(): Boolean {
        // Auf den Init-Load warten: vorher ist die Sperre noch nicht entschieden.
        return try {
            awaitInitialLoad()
            persistenceBlocked
        } catch (e: kotlinx.coroutines.CancellationException) {
            // NICHT als Defekt deuten: `awaitInitialLoad()` wirft eine CancellationException, wenn
            // die AUFRUFENDE Coroutine gecancelt wird - das sagt nichts ueber den Bestand. Als
            // "gesperrt" gedeutet haette es `clearInternalAlarms()` in den Fehlerpfad geschickt und
            // z. B. eine Master-Pause scheitern lassen, obwohl alles in Ordnung ist.
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Init-Load nicht abschliessbar - Persistenz gilt als gesperrt", e)
            true
        }
    }

    /**
     * "IST DER ZULETZT GESCHRIEBENE STAND MOEGLICHERWEISE NUR IM ARBEITSSPEICHER?"
     *
     * NUR FUER ANZEIGE UND WARNUNG (einziger Konsument: der manuelle Wecker im `AlarmViewModel`) -
     * darf NIEMALS einen Raeum-, Cancel- oder Loeschweg anhalten; dafuer ist [isPersistenceBlocked]
     * zustaendig, und nur die.
     *
     * Kein Warten auf den Init-Load noetig: der Merker beschreibt einen Schreibvorgang, den es
     * ohne abgeschlossenen Init-Load noch gar nicht gegeben haben kann.
     */
    override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean =
        letzterSchreibvorgangGescheitert

    override suspend fun getAllAlarms(): Result<List<AlarmInfo>> {
        return try {
            // Ohne dieses Warten wäre die leere Startliste im Prozess-Startfenster nicht von
            // "keine Alarme" zu unterscheiden - der Delta-Sync hätte alles für neu gehalten.
            awaitInitialLoad()
            Result.success(_activeAlarms.value)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error getting all alarms", e)
            Result.failure(e)
        }
    }

    override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> {
        return try {
            awaitInitialLoad()
            val alarm = _activeAlarms.value.find { it.id == alarmId }
            Result.success(alarm)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error getting alarm by ID: $alarmId", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
        return try {
            awaitInitialLoad()
            stateMutex.withLock {
                val updatedAlarms = _activeAlarms.value.filter { it.id != alarmId }
                _activeAlarms.value = updatedAlarms

                persistToDataStore(updatedAlarms)
            }

            Logger.business(LogTags.ALARM, "Alarm removed", alarmId.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error deleting alarm: $alarmId", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAllAlarms(): Result<Unit> {
        return try {
            // Master-Pause/autoAlarmEnabled=false laufen hierüber: das Leeren darf NICHT vom
            // nachträglich zurückkehrenden Init-Load wieder aufgefüllt werden.
            awaitInitialLoad()
            stateMutex.withLock {
                _activeAlarms.value = emptyList()

                // force: ein ausdrückliches Räumen muss auch bei blockierter
                // Persistenz durchgehen, sonst re-armt ein Direct-Boot-Restore genau die Alarme,
                // die Master-Pause/autoAlarmEnabled=false gerade abgeschaltet haben. Der defekte
                // Rohbestand ist unter BROKEN_ALARMS_KEY_NAME gesichert.
                persistToDataStore(emptyList(), force = true)
            }

            Logger.business(LogTags.ALARM, "All alarms cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error clearing all alarms", e)
            Result.failure(e)
        }
    }

    override suspend fun alarmExists(alarmId: Int): Result<Boolean> {
        return try {
            awaitInitialLoad()
            val exists = _activeAlarms.value.any { it.id == alarmId }
            Result.success(exists)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error checking if alarm exists: $alarmId", e)
            Result.failure(e)
        }
    }

    /**
     * CLEANUP: Remove expired alarms automatically
     *
     * ACHTUNG: nimmt [stateMutex] NICHT selbst (nicht reentrant) - nur aus einem Lock-Halter rufen.
     */
    private suspend fun cleanupExpiredAlarms() {
        try {
            val currentTime = System.currentTimeMillis()
            val validAlarms = _activeAlarms.value.filter { it.triggerTime > currentTime }
            val expiredCount = _activeAlarms.value.size - validAlarms.size

            if (expiredCount > 0) {
                _activeAlarms.value = validAlarms
                persistToDataStore(validAlarms)
                Logger.w(LogTags.ALARM, "Cleaned up $expiredCount expired alarms")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error during alarm cleanup", e)
        }
    }

    /** shiftStartTime (Epoch-Millis, 0 = unbekannt) -> "dd.MM.yyyy HH:mm", leer bei unbekannt. */
    private fun formatShiftStartTime(shiftStartTime: Long): String {
        if (shiftStartTime <= 0) return ""
        val formatter = DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
        return Instant.ofEpochMilli(shiftStartTime).atZone(ZoneId.systemDefault()).format(formatter)
    }

    // Extension functions for conversion
    private fun AlarmInfo.toAlarmInfoData() = AlarmInfoData(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = formattedTime,
        eventId = eventId,
        eventChecksum = eventChecksum,
        shiftEndTime = shiftEndTime,
        shiftStartTime = shiftStartTime,
        isSilent = isSilent
    )

    private fun AlarmInfoData.toAlarmInfo() = AlarmInfo(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = formattedTime,
        eventId = eventId,
        eventChecksum = eventChecksum,
        shiftEndTime = shiftEndTime,
        shiftStartTime = shiftStartTime,
        isSilent = isSilent
    )
}
