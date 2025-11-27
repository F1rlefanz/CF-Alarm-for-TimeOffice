@file:Suppress("UnusedImport") // False positive - encodeToString is used in persistToDataStore()

package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val eventChecksum: String = ""
)

@Singleton
class AlarmRepository @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) : IAlarmRepository {

    companion object {
        private val ALARMS_KEY = stringPreferencesKey("active_alarms")
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
                val preferences = dataStore.data.first()
                val alarmsJson = preferences[ALARMS_KEY]

                if (alarmsJson != null) {
                    val alarmsData = json.decodeFromString<List<AlarmInfoData>>(alarmsJson)
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
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Error loading alarms from DataStore", e)
                _activeAlarms.value = emptyList()
            }
        }
    }

    /**
     * PERSISTENCE: Speichert Alarme in DataStore
     */
    private suspend fun persistToDataStore(alarms: List<AlarmInfo>) {
        try {
            val alarmsData = alarms.map { it.toAlarmInfoData() }
            val alarmsJson = json.encodeToString(alarmsData)

            dataStore.edit { preferences ->
                preferences[ALARMS_KEY] = alarmsJson
            }

            Logger.d(LogTags.ALARM, "💾 PERSISTENCE: Saved ${alarms.size} alarms to DataStore")
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

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error saving alarm: ${alarmInfo.id}", e)
            Result.failure(e)
        }
    }

    override suspend fun getAllAlarms(): Result<List<AlarmInfo>> {
        return try {
            Result.success(_activeAlarms.value)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error getting all alarms", e)
            Result.failure(e)
        }
    }

    override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> {
        return try {
            val alarm = _activeAlarms.value.find { it.id == alarmId }
            Result.success(alarm)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error getting alarm by ID: $alarmId", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
        return try {
            val updatedAlarms = _activeAlarms.value.filter { it.id != alarmId }
            _activeAlarms.value = updatedAlarms

            // PERSIST to DataStore
            persistToDataStore(updatedAlarms)

            Logger.business(LogTags.ALARM, "Alarm removed", alarmId.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error deleting alarm: $alarmId", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAllAlarms(): Result<Unit> {
        return try {
            _activeAlarms.value = emptyList()

            // PERSIST to DataStore
            persistToDataStore(emptyList())

            Logger.business(LogTags.ALARM, "All alarms cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error clearing all alarms", e)
            Result.failure(e)
        }
    }

    override suspend fun alarmExists(alarmId: Int): Result<Boolean> {
        return try {
            val exists = _activeAlarms.value.any { it.id == alarmId }
            Result.success(exists)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error checking if alarm exists: $alarmId", e)
            Result.failure(e)
        }
    }

    /**
     * CLEANUP: Remove expired alarms automatically
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

    /**
     * PUBLIC API: Manual cleanup for startup or manual triggers
     *
     * This function is intentionally public for use by external components
     * such as AlarmViewModel or BootReceiver for manual cleanup operations.
     *
     * @return Result with count of expired alarms that were removed
     */
    @Suppress("unused") // Public API - may be used by ViewModel or other components
    suspend fun cleanupExpiredAlarmsManually(): Result<Int> {
        return try {
            val currentTime = System.currentTimeMillis()
            val originalCount = _activeAlarms.value.size
            val validAlarms = _activeAlarms.value.filter { it.triggerTime > currentTime }
            val expiredCount = originalCount - validAlarms.size

            if (expiredCount > 0) {
                _activeAlarms.value = validAlarms
                persistToDataStore(validAlarms)
                Logger.w(LogTags.ALARM, "Manual cleanup: removed $expiredCount expired alarms")
            }

            Result.success(expiredCount)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error during manual alarm cleanup", e)
            Result.failure(e)
        }
    }

    // Extension functions for conversion
    private fun AlarmInfo.toAlarmInfoData() = AlarmInfoData(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = formattedTime,
        eventId = eventId,
        eventChecksum = eventChecksum
    )

    private fun AlarmInfoData.toAlarmInfo() = AlarmInfo(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = formattedTime,
        eventId = eventId,
        eventChecksum = eventChecksum
    )
}
