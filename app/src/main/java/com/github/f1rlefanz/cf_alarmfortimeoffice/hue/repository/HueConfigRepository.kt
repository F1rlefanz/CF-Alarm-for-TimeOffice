package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.HueDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for Hue Configuration operations using DataStore
 */
@Singleton
class HueConfigRepository @Inject constructor(
    @param:HueDataStore private val dataStore: DataStore<Preferences>
) : IHueConfigRepository {
    
    companion object {
        private val BRIDGE_IP_KEY = stringPreferencesKey("hue_bridge_ip")
        private val USERNAME_KEY = stringPreferencesKey("hue_username")
        private val SCHEDULE_RULES_KEY = stringPreferencesKey("hue_schedule_rules")
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    override fun getConfiguration(): Flow<HueConfiguration> {
        return dataStore.data
            .catch { exception ->
                Logger.e(LogTags.HUE_CONFIG, "Error reading configuration", exception)
                emit(emptyPreferences())
            }
            .map { preferences ->
                val bridgeIp = preferences[BRIDGE_IP_KEY] ?: ""
                val username = preferences[USERNAME_KEY] ?: ""
                
                HueConfiguration(
                    bridgeIp = bridgeIp,
                    username = username,
                    isConfigured = bridgeIp.isNotEmpty() && username.isNotEmpty()
                )
            }
    }
    
    override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                preferences[BRIDGE_IP_KEY] = bridgeIp
                preferences[USERNAME_KEY] = username
            }
            
            Logger.i(LogTags.HUE_CONFIG, "Successfully saved bridge configuration")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to save bridge configuration", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getScheduleRules(): Result<List<HueSchedule>> {
        return try {
            val preferences = dataStore.data.first()
            val scheduleRulesJson = preferences[SCHEDULE_RULES_KEY] ?: "[]"
            
            val scheduleRules = json.decodeFromString<List<HueSchedule>>(scheduleRulesJson)
            
            Result.success(scheduleRules)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to get schedule rules", e)
            Result.failure(e)
        }
    }
    
    override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val currentRulesJson = preferences[SCHEDULE_RULES_KEY] ?: "[]"
                val currentRules = json.decodeFromString<List<HueSchedule>>(currentRulesJson).toMutableList()

                currentRules.removeAll { it.id == rule.id }
                currentRules.add(rule)

                val updatedRulesJson = json.encodeToString(currentRules)
                preferences[SCHEDULE_RULES_KEY] = updatedRulesJson
            }
            
            Logger.i(LogTags.HUE_CONFIG, "Successfully saved schedule rule: ${rule.id}")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to save schedule rule: ${rule.id}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val currentRulesJson = preferences[SCHEDULE_RULES_KEY] ?: "[]"
                val currentRules = json.decodeFromString<List<HueSchedule>>(currentRulesJson).toMutableList()
                
                val removed = currentRules.removeAll { it.id == ruleId }
                
                if (removed) {
                    val updatedRulesJson = json.encodeToString(currentRules)
                    preferences[SCHEDULE_RULES_KEY] = updatedRulesJson
                    Logger.i(LogTags.HUE_CONFIG, "Successfully deleted schedule rule: $ruleId")
                } else {
                    Logger.w(LogTags.HUE_CONFIG, "Schedule rule not found for deletion: $ruleId")
                }
            }
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to delete schedule rule: $ruleId", e)
            Result.failure(e)
        }
    }
    
    override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> {
        // Update is the same as save - it will replace existing rule with same ID
        return saveScheduleRule(rule)
    }

    override suspend fun updateScheduleRules(
        transform: (List<HueSchedule>) -> List<HueSchedule>
    ): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                val currentRulesJson = preferences[SCHEDULE_RULES_KEY] ?: "[]"
                // Bewusst OHNE try/catch: ein unlesbarer Bestand muss den Aufruf scheitern
                // lassen, sonst wird er als "keine Regeln" zurueckgeschrieben.
                val currentRules = json.decodeFromString<List<HueSchedule>>(currentRulesJson)

                val updatedRules = transform(currentRules)
                if (updatedRules == currentRules) {
                    Logger.d(LogTags.HUE_CONFIG, "Schedule rules unchanged - kein Schreibvorgang")
                    return@edit
                }

                preferences[SCHEDULE_RULES_KEY] = json.encodeToString(updatedRules)
                Logger.i(LogTags.HUE_CONFIG, "Successfully updated ${updatedRules.size} schedule rules in one transaction")
            }

            Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to update schedule rules", e)
            Result.failure(e)
        }
    }
    
    override suspend fun clearBridgeConfig(): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                preferences.remove(BRIDGE_IP_KEY)
                preferences.remove(USERNAME_KEY)
                // Schedule rules (SCHEDULE_RULES_KEY) are intentionally kept - see
                // IHueConfigRepository.clearBridgeConfig() doc.
            }

            Logger.i(LogTags.HUE_CONFIG, "Successfully cleared bridge configuration (rules kept)")
            Result.success(Unit)

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to clear bridge configuration", e)
            Result.failure(e)
        }
    }
}
