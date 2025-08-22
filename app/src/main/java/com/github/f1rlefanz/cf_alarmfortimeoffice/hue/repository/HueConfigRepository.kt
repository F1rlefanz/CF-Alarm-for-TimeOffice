package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for Hue Configuration operations using DataStore
 * Implements Clean Architecture with Interface-based DI and Logger integration
 */
class HueConfigRepository(
    private val dataStore: DataStore<Preferences>
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
                val scheduleRulesJson = preferences[SCHEDULE_RULES_KEY] ?: "[]"
                
                val scheduleRules = try {
                    json.decodeFromString<List<HueSchedule>>(scheduleRulesJson)
                } catch (e: Exception) {
                    Logger.w(LogTags.HUE_CONFIG, "Failed to decode schedule rules, using empty list", e)
                    emptyList()
                }
                
                HueConfiguration(
                    bridgeIp = bridgeIp,
                    username = username,
                    isConfigured = bridgeIp.isNotEmpty() && username.isNotEmpty(),
                    scheduleRules = scheduleRules
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
            
            Logger.d(LogTags.HUE_CONFIG, "Retrieved ${scheduleRules.size} schedule rules")
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
                
                // Remove existing rule with same ID if it exists
                currentRules.removeAll { it.id == rule.id }
                
                // Add the new/updated rule
                currentRules.add(rule)
                
                // Save back to preferences
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
                
                // Remove rule with matching ID
                val removed = currentRules.removeAll { it.id == ruleId }
                
                if (removed) {
                    // Save back to preferences
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
    
    override suspend fun getScheduleRulesForShift(shiftPattern: String): Result<List<HueSchedule>> {
        return try {
            val allRulesResult = getScheduleRules()
            
            if (allRulesResult.isFailure) {
                return allRulesResult
            }
            
            val allRules = allRulesResult.getOrNull() ?: emptyList()
            val filteredRules = allRules.filter { rule ->
                rule.shiftPattern.equals(shiftPattern, ignoreCase = true)
            }
            
            Logger.d(LogTags.HUE_CONFIG, "Found ${filteredRules.size} rules for shift pattern: $shiftPattern")
            Result.success(filteredRules)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to get schedule rules for shift: $shiftPattern", e)
            Result.failure(e)
        }
    }
    
    override suspend fun clearConfiguration(): Result<Unit> {
        return try {
            dataStore.edit { preferences ->
                preferences.clear()
            }
            
            Logger.i(LogTags.HUE_CONFIG, "Successfully cleared all Hue configuration")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_CONFIG, "Failed to clear configuration", e)
            Result.failure(e)
        }
    }
}
