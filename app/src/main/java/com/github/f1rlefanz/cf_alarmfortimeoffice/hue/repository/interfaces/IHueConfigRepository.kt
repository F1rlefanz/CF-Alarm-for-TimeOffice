package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import kotlinx.coroutines.flow.Flow

/**
 * Interface for Hue Configuration repository operations
 * Follows Clean Architecture principles with testable abstractions
 */
interface IHueConfigRepository {
    
    /**
     * Get configuration as Flow for reactive updates
     */
    fun getConfiguration(): Flow<HueConfiguration>
    
    /**
     * Save bridge connection details
     */
    suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit>
    
    /**
     * Get all saved schedule rules
     */
    suspend fun getScheduleRules(): Result<List<HueSchedule>>
    
    /**
     * Save a schedule rule
     */
    suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit>
    
    /**
     * Delete a schedule rule
     */
    suspend fun deleteScheduleRule(ruleId: String): Result<Unit>
    
    /**
     * Update an existing schedule rule
     */
    suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit>
    
    /**
     * Get schedule rules for specific shift pattern
     */
    suspend fun getScheduleRulesForShift(shiftPattern: String): Result<List<HueSchedule>>
    
    /**
     * Clear all configuration (for reset/logout)
     */
    suspend fun clearConfiguration(): Result<Unit>
}

/**
 * Data class for Hue configuration
 */
data class HueConfiguration(
    val bridgeIp: String = "",
    val username: String = "",
    val isConfigured: Boolean = false,
    val scheduleRules: List<HueSchedule> = emptyList()
)
