@file:Suppress("UnusedImport") // False positive - encodeToString is used in persistToDataStore()

package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

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

/**
 * Repository für Alarm-Daten - implementiert IAlarmRepository Interface
 *
 * ✅ FIXED: Verwendet DataStore für persistente Speicherung
 * ✅ Alarme überleben App-Neustarts
 * ✅ Automatisches Laden beim Repository-Init
 * ✅ Typsicher mit Kotlin Serialization
 * ✅ Asynchron & nicht-blockierend
 *
 * CRITICAL FIX für Bug: "Alarme verschwinden nach App-Schließen"
 * - Ersetzt In-Memory Storage durch DataStore
 * - Lädt Alarme automatisch beim Start
 * - Synchronisiert Änderungen sofort in DataStore
 */
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
    private val directBootAlarmStore: DirectBootAlarmStore
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
    override val activeAlarms: Flow<List<AlarmInfo>> = _activeAlarms.asStateFlow()

    /**
     * BEREIT-SIGNAL für den asynchronen Init-Load.
     *
     * Ohne dieses Signal war der leere Start-Cache von `_activeAlarms` nicht von „es gibt keine
     * Alarme" zu unterscheiden: ein durch einen Hintergrund-Trigger (Wartung/Worker/Boot) frisch
     * gestarteter Prozess bekam von `getAllAlarms()` eine leere Liste, der Delta-Sync hielt jede
     * erkannte Schicht für neu — und der danach zurückkehrende Init-Load überschrieb Cache,
     * DataStore UND Direct-Boot-Spiegel mit seinem alten Snapshot (Last-Writer-Wins gegen den
     * eigenen Initialisierer).
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
     * SCHREIBSPERRE nach einem gescheiterten Init-Load.
     *
     * Ein Dekodier- oder Lesefehler degradiert den Cache zwangsläufig auf `emptyList()` — mehr ist
     * nicht bekannt. Was NICHT passieren darf: dass diese Notlage-Leere über die noch vorhandenen
     * Rohdaten UND über den Direct-Boot-Spiegel geschrieben wird. Genau das tat der Delta-Sync:
     * `getAllAlarms()` liefert leer → jede erkannte Schicht gilt als neu → `saveAlarm()` →
     * `persistToDataStore()` überschreibt `active_alarms` und spiegelt per
     * `directBootAlarmStore.saveAll()`. Verloren gehen dabei genau die Alarme, die sich NICHT aus
     * dem Kalender rekonstruieren lassen (manuelle Alarme) — und der Direct-Boot-Spiegel, der der
     * einzige Weg zurück nach einem Reboot vor der ersten Entsperrung ist.
     *
     * Dieselbe Entscheidung wie in `DimRuleRepository.editRules()` und
     * `ShiftConfigRepository.getCurrentShiftConfig()`: nicht speichern ist besser als alles
     * verlieren. Die System-Alarme werden trotzdem gesetzt (der Wecker klingelt), nur die
     * Persistenz bleibt für diesen Prozess außen vor. Einzige bewusste Ausnahme:
     * [deleteAllAlarms] (Master-Pause / autoAlarmEnabled=false) MUSS auch dann wirklich räumen,
     * sonst re-armt ein Direct-Boot-Restore Alarme, die der Nutzer gerade pausiert hat.
     */
    @Volatile
    private var persistenceBlocked = false

    init {
        // CRITICAL: Lade Alarme beim Repository-Init
        loadAlarmsFromDataStore()
    }

    /**
     * CRITICAL: Lädt Alarme aus DataStore beim Start
     * Wird im init{} Block aufgerufen
     */
    private fun loadAlarmsFromDataStore() {
        repositoryScope.launch {
            try {
                stateMutex.withLock {
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

                        Logger.business(
                            LogTags.ALARM,
                            "✅ PERSISTENCE: Loaded ${validAlarms.size} alarms from DataStore (removed ${alarms.size - validAlarms.size} expired)"
                        )

                        // Wenn wir abgelaufene Alarme entfernt haben, speichere die bereinigte Liste
                        if (validAlarms.size < alarms.size) {
                            persistToDataStore(validAlarms)
                        }
                    } else {
                        Logger.d(LogTags.ALARM, "📭 PERSISTENCE: No saved alarms found in DataStore")
                        _activeAlarms.value = emptyList()
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
     * Wartet auf den Abschluss des Init-Loads. Erste Anweisung JEDER öffentlichen Operation:
     * Leser bekommen so nie einen noch nicht gefüllten Cache als Wahrheit, und Schreiber können
     * nicht vom nachträglich zurückkehrenden Init-Load überschrieben werden.
     */
    private suspend fun awaitInitialLoad() = initialLoadDone.await()

    /**
     * PERSISTENCE: Speichert Alarme in DataStore
     *
     * ACHTUNG: nimmt [stateMutex] NICHT selbst (nicht reentrant) - nur aus einem Lock-Halter rufen.
     *
     * @param force überschreibt die [persistenceBlocked]-Sperre. NUR für ein ausdrückliches Räumen
     *   (siehe [deleteAllAlarms]) - dort ist "nichts" der gewollte Zustand, nicht eine Notlage.
     */
    private suspend fun persistToDataStore(alarms: List<AlarmInfo>, force: Boolean = false) {
        if (persistenceBlocked && !force) {
            Logger.w(
                LogTags.ALARM,
                "⛔ PERSISTENCE: Schreiben von ${alarms.size} Alarmen uebersprungen - der Init-Load " +
                    "ist gescheitert, der vorhandene Bestand und der Direct-Boot-Spiegel bleiben " +
                    "unangetastet (Sicherung: '$BROKEN_ALARMS_KEY_NAME')"
            )
            return
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

            Logger.d(LogTags.ALARM, "💾 PERSISTENCE: Saved ${alarms.size} alarms to DataStore (+ Direct-Boot-Spiegel)")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Error saving alarms to DataStore", e)
        }
    }

    override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
        return try {
            // VALIDATION: Check if alarm is in the future
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
                    // Update existing alarm
                    currentAlarms[existingIndex] = alarmInfo
                    Logger.d(LogTags.ALARM, "Alarm updated: ${alarmInfo.id}")
                } else {
                    // Add new alarm
                    currentAlarms.add(alarmInfo)
                    Logger.business(LogTags.ALARM, "Alarm added", alarmInfo.id.toString())
                }

                // Update cache
                _activeAlarms.value = currentAlarms

                // PERSIST to DataStore
                persistToDataStore(currentAlarms)

                // CLEANUP: Trigger cleanup after save
                cleanupExpiredAlarms()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error saving alarm: ${alarmInfo.id}", e)
            Result.failure(e)
        }
    }

    override suspend fun isPersistenceBlocked(): Boolean {
        // Auf den Init-Load warten: vorher ist die Sperre noch nicht entschieden.
        return try {
            awaitInitialLoad()
            persistenceBlocked
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Init-Load nicht abschliessbar - Persistenz gilt als gesperrt", e)
            true
        }
    }

    override suspend fun getAllAlarms(): Result<List<AlarmInfo>> {
        return try {
            // Ohne dieses Warten wäre die leere Startliste im Prozess-Startfenster nicht von
            // "keine Alarme" zu unterscheiden - der Delta-Sync hätte alles für neu gehalten.
            awaitInitialLoad()
            Result.success(_activeAlarms.value)
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

                // PERSIST to DataStore
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

                // PERSIST to DataStore - force: ein ausdrückliches Räumen muss auch bei blockierter
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
