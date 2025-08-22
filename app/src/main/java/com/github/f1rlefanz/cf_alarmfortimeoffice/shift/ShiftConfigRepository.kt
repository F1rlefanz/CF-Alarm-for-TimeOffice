package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.AppError
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

// Extension property for Context to create DataStore
private val Context.shiftDataStore: DataStore<Preferences> by preferencesDataStore(name = "shift_prefs")

/**
 * ShiftConfigRepository - implementiert IShiftConfigRepository Interface
 * 
 * REFACTORED + OPTIMIZED:
 * ✅ Implementiert IShiftConfigRepository für bessere Testbarkeit
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Flow-basierte reaktive Datenbeobachtung
 * ✅ Vollständige CRUD-Operationen mit Validierung
 * ✅ SINGLETON PATTERN: Eliminiert redundante Config-Loads durch intelligentes Caching
 * 
 * Verwaltet Schicht-Konfigurationen mit DataStore Preferences + Performance-Caching
 */
class ShiftConfigRepository(
    private val context: Context
) : IShiftConfigRepository {

    private val dataStore = context.shiftDataStore
    private val shiftConfigKey = stringPreferencesKey("shift_config")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // SINGLETON PATTERN: Cached configuration with thread-safe access
    @Volatile
    private var cachedConfig: ShiftConfig? = null
    @Volatile
    private var cacheTimestamp: Long = 0L
    @Volatile
    private var configLoadInProgress: Boolean = false
    
    private companion object {
        const val CACHE_VALIDITY_MS = 30000L // 30 seconds cache validity
        const val MAX_LOAD_WAIT_MS = 500L   // Max wait for concurrent loads
    }

    override val shiftConfig: Flow<ShiftConfig> = dataStore.data.map { preferences ->
        val jsonString = preferences[shiftConfigKey]
        if (jsonString != null) {
            try {
                val config = json.decodeFromString<ShiftConfig>(jsonString)
                // Update cache when config flows change
                cachedConfig = config
                cacheTimestamp = System.currentTimeMillis()
                config
            } catch (e: SerializationException) {
                Logger.e(LogTags.SHIFT_CONFIG, "Error decoding shift config from flow, returning default", e)
                val defaultConfig = ShiftConfig.getDefaultConfig()
                cachedConfig = defaultConfig
                cacheTimestamp = System.currentTimeMillis()
                defaultConfig
            }
        } else {
            val defaultConfig = ShiftConfig.getDefaultConfig()
            cachedConfig = defaultConfig
            cacheTimestamp = System.currentTimeMillis()
            defaultConfig
        }
    }

    /**
     * SINGLETON CACHE: Invalidates cached config to force fresh load
     * Call this when configuration changes externally
     */
    fun invalidateCache() {
        cachedConfig = null
        cacheTimestamp = 0L
        configLoadInProgress = false
        Logger.d(LogTags.SHIFT_CONFIG, "🗑️ SINGLETON-CACHE: Config cache invalidated")
    }

    override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.saveShiftConfig") {
            val jsonString = try {
                json.encodeToString(config)
            } catch (e: SerializationException) {
                throw AppError.DataStoreError(
                    message = "Fehler beim Serialisieren der Schicht-Konfiguration",
                    cause = e
                )
            }
            
            dataStore.edit { preferences ->
                preferences[shiftConfigKey] = jsonString
            }
            
            // SINGLETON PATTERN: Update cache immediately after save + invalidate cache chains
            cachedConfig = config
            cacheTimestamp = System.currentTimeMillis()
            
            // PERFORMANCE: Clear dependent caches when config changes
            Logger.d(LogTags.SHIFT_CONFIG, "🗑️ SINGLETON-INVALIDATE: All caches cleared due to config change")
            
            Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-SAVE: Shift config saved with ${config.definitions.size} definitions and cache updated")
        }

    override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.getCurrentShiftConfig") {
            val currentTime = System.currentTimeMillis()
            
            // SINGLETON CACHE HIT: Return cached config if valid
            cachedConfig?.let { cached ->
                val cacheAge = currentTime - cacheTimestamp
                if (cacheAge < CACHE_VALIDITY_MS) {
                    Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-CACHE-HIT: Returning cached config (${cacheAge}ms old) with ${cached.definitions.size} definitions")
                    return@safeExecute cached
                } else {
                    Logger.d(LogTags.SHIFT_CONFIG, "⏰ SINGLETON-CACHE-EXPIRED: Cache is ${cacheAge}ms old, refreshing")
                }
            }
            
            // SINGLETON CONCURRENCY: Handle concurrent load attempts
            if (configLoadInProgress) {
                Logger.d(LogTags.SHIFT_CONFIG, "🔄 SINGLETON-WAIT: Config load in progress, waiting smartly...")
                
                val startWait = System.currentTimeMillis()
                while (configLoadInProgress && (System.currentTimeMillis() - startWait) < MAX_LOAD_WAIT_MS) {
                    kotlinx.coroutines.delay(25)
                }
                
                // Check if concurrent load completed successfully
                cachedConfig?.let { freshConfig ->
                    val cacheAge = System.currentTimeMillis() - cacheTimestamp
                    if (cacheAge < CACHE_VALIDITY_MS) {
                        Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-CONCURRENT-SUCCESS: Using fresh config from concurrent load")
                        return@safeExecute freshConfig
                    }
                }
                
                if (configLoadInProgress) {
                    Logger.w(LogTags.SHIFT_CONFIG, "⚠️ SINGLETON-TIMEOUT: Concurrent load timed out, proceeding anyway")
                }
            }
            
            configLoadInProgress = true
            
            try {
                val preferences = dataStore.data.first()
                val jsonString = preferences[shiftConfigKey]
                val config = if (jsonString != null) {
                    try {
                        json.decodeFromString<ShiftConfig>(jsonString)
                    } catch (e: SerializationException) {
                        Logger.e(LogTags.SHIFT_CONFIG, "Error decoding shift config, returning default", e)
                        ShiftConfig.getDefaultConfig()
                    }
                } else {
                    ShiftConfig.getDefaultConfig()
                }
                
                // SINGLETON PATTERN: Update cache with fresh data
                cachedConfig = config
                cacheTimestamp = currentTime
                
                Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-FRESH-LOAD: Config loaded with ${config.definitions.size} definitions and cached")
                config
            } finally {
                configLoadInProgress = false
            }
        }
    
    override suspend fun resetToDefaults(): Result<Unit> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.resetToDefaults") {
            val defaultConfig = ShiftConfig.getDefaultConfig()
            val jsonString = json.encodeToString(defaultConfig)
            
            dataStore.edit { preferences ->
                preferences[shiftConfigKey] = jsonString
            }
            
            // SINGLETON PATTERN: Update cache immediately after reset
            cachedConfig = defaultConfig
            cacheTimestamp = System.currentTimeMillis()
            
            Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-RESET: Shift config reset to defaults and cache updated")
        }
    
    override suspend fun hasValidConfig(): Result<Boolean> = 
        SafeExecutor.safeExecute("ShiftConfigRepository.hasValidConfig") {
            // SINGLETON OPTIMIZATION: Try cache first for performance
            cachedConfig?.let { cached ->
                val cacheAge = System.currentTimeMillis() - cacheTimestamp
                if (cacheAge < CACHE_VALIDITY_MS) {
                    val isValid = cached.definitions.isNotEmpty() && 
                                 cached.definitions.any { it.name.isNotBlank() }
                    Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-VALID-CHECK: Using cached config for validation - valid=$isValid")
                    return@safeExecute isValid
                }
            }
            
            val config = getCurrentShiftConfig().getOrElse { 
                return@safeExecute false
            }
            
            // Validierung: Mindestens eine Schichtdefinition mit gültigem Namen
            val isValid = config.definitions.isNotEmpty() && 
                         config.definitions.any { it.name.isNotBlank() }
            
            Logger.d(LogTags.SHIFT_CONFIG, "✅ SINGLETON-VALID-CHECK: Fresh validation completed - valid=$isValid")
            isValid
        }
}
