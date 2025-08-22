package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.delay
import java.time.LocalDateTime

class ShiftRecognitionEngine(
    private val shiftConfigRepository: IShiftConfigRepository
) {
    
    suspend fun findNextShiftAlarm(events: List<CalendarEvent>): ShiftMatch? {
        val now = LocalDateTime.now()
        
        val matchingShifts = getAllMatchingShifts(events)
            .filter { it.calendarEvent.startTime.isAfter(now) }
            .sortedBy { it.calendarEvent.startTime }
        
        return matchingShifts.firstOrNull()
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Enhanced recognition with intelligent caching
     * Caches results for identical event sets and prevents concurrent calls
     */
    @Volatile
    private var lastRecognitionHash = 0
    @Volatile
    private var cachedMatches: List<ShiftMatch> = emptyList()
    @Volatile
    private var recognitionInProgress = false
    @Volatile 
    private var lastCacheTime = 0L
    @Volatile
    private var cacheHitCount = 0
    @Volatile
    private var configChangeCount = 0
    
    private companion object {
        const val BASE_CACHE_VALIDITY_MS = 5000L  // Base 5 seconds
        const val ADAPTIVE_CACHE_MIN_MS = 2000L   // Minimum 2 seconds
        const val ADAPTIVE_CACHE_MAX_MS = 30000L  // Maximum 30 seconds
        const val MAX_CONCURRENT_WAIT_MS = 200L   // Max wait for concurrent operations
    }
    
    /**
     * ADAPTIVE CACHING: Calculates cache validity based on usage patterns
     * Longer cache for stable configs, shorter for frequently changing configs
     */
    private fun getAdaptiveCacheValidity(): Long {
        // Base validity
        var validity = BASE_CACHE_VALIDITY_MS
        
        // STABILITY BONUS: If config is stable (few changes), extend cache
        if (configChangeCount < 3) {
            validity = (validity * 2).coerceAtMost(ADAPTIVE_CACHE_MAX_MS)
        }
        
        // ACTIVITY BONUS: If cache hit frequently, extend validity
        if (cacheHitCount > 5) {
            validity = (validity * 1.5).toLong().coerceAtMost(ADAPTIVE_CACHE_MAX_MS)
        }
        
        // CHANGE PENALTY: If config changed recently, reduce cache
        if (configChangeCount > 5) {
            validity = (validity * 0.5).toLong().coerceAtLeast(ADAPTIVE_CACHE_MIN_MS)
        }
        
        return validity.coerceIn(ADAPTIVE_CACHE_MIN_MS, ADAPTIVE_CACHE_MAX_MS)
    }
    
    /**
     * Clears the recognition cache to force re-processing of events.
     * This should be called when shift configuration changes.
     * PERFORMANCE: Optimized cache management with lifecycle callbacks
     */
    fun clearRecognitionCache() {
        lastRecognitionHash = 0
        cachedMatches = emptyList()
        recognitionInProgress = false
        lastCacheTime = 0L
        
        // ADAPTIVE LEARNING: Track configuration changes for cache optimization
        configChangeCount++
        cacheHitCount = 0 // Reset hit count on config change
        
        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 CACHE-CLEAR: Clearing recognition cache before config update")
        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 ADAPTIVE-CACHE-CLEAR: Recognition cache cleared due to configuration change (change #$configChangeCount)")
        Logger.d(LogTags.SHIFT_RECOGNITION, "✅ CACHE-CLEAR: Recognition cache cleared successfully")
    }
    
    suspend fun getAllMatchingShifts(events: List<CalendarEvent>): List<ShiftMatch> {
        // PERFORMANCE: Calculate hash of input to prevent duplicate processing
        val eventsHash = events.hashCode()
        val currentTime = System.currentTimeMillis()
        
        // ADAPTIVE CACHE: Check cache validity with dynamic expiration
        if (lastRecognitionHash == eventsHash && lastRecognitionHash != 0) {
            val adaptiveCacheValidity = getAdaptiveCacheValidity()
            val cacheAge = currentTime - lastCacheTime
            
            if (cacheAge < adaptiveCacheValidity) {
                cacheHitCount++
                Logger.d(LogTags.SHIFT_RECOGNITION, "✅ ADAPTIVE-CACHE-HIT: Same events processed recently (${cacheAge}ms ago, validity=${adaptiveCacheValidity}ms), returning cached ${cachedMatches.size} matches (hit #$cacheHitCount)")
                return cachedMatches
            } else {
                Logger.d(LogTags.SHIFT_RECOGNITION, "⏰ ADAPTIVE-CACHE-EXPIRED: Cache is ${cacheAge}ms old (validity=${adaptiveCacheValidity}ms), needs refresh")
            }
        }
        
        // ENHANCED DEDUPLICATION: Smart waiting with timeout
        if (recognitionInProgress) {
            Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 WAIT-CONCURRENT: Recognition in progress, waiting smartly...")
            
            val startWait = System.currentTimeMillis()
            while (recognitionInProgress && (System.currentTimeMillis() - startWait) < MAX_CONCURRENT_WAIT_MS) {
                delay(25) // Shorter polling interval
            }
            
            // If still in progress after timeout, proceed anyway to prevent deadlock
            if (recognitionInProgress) {
                Logger.w(LogTags.SHIFT_RECOGNITION, "⚠️ WAIT-TIMEOUT: Concurrent operation timed out after ${MAX_CONCURRENT_WAIT_MS}ms, proceeding anyway")
            } else {
                // Check if the concurrent operation produced the result we need
                if (lastRecognitionHash == eventsHash && lastRecognitionHash != 0) {
                    Logger.d(LogTags.SHIFT_RECOGNITION, "✅ CONCURRENT-SUCCESS: Concurrent operation completed, using fresh results")
                    return cachedMatches
                }
            }
        }
        
        recognitionInProgress = true
        lastRecognitionHash = eventsHash
        lastCacheTime = currentTime
        
        try {
            val matches = performRecognition(events)
            cachedMatches = matches
            Logger.d(LogTags.SHIFT_RECOGNITION, "✅ ADAPTIVE-RECOGNITION: Completed with ${matches.size} matches (cache validity: ${getAdaptiveCacheValidity()}ms)")
            return matches
        } finally {
            recognitionInProgress = false
        }
    }
    
    private suspend fun performRecognition(events: List<CalendarEvent>): List<ShiftMatch> {
        val shiftConfigResult = shiftConfigRepository.getCurrentShiftConfig()
        val shiftDefinitions = shiftConfigResult.getOrNull()?.definitions ?: emptyList()
        val matches = mutableListOf<ShiftMatch>()
        
        Logger.d(LogTags.SHIFT_RECOGNITION, "Starting shift recognition with ${shiftDefinitions.size} definitions and ${events.size} events")
        
        for (event in events) {
            Logger.d(LogTags.SHIFT_RECOGNITION, "Checking event: '${event.title}' at ${event.startTime}")
            
            for (definition in shiftDefinitions) {
                Logger.d(LogTags.SHIFT_RECOGNITION, "Testing definition '${definition.name}' with keywords: ${definition.keywords}")
                
                if (definition.matchesKeywords(event.title)) {
                    val alarmTime = calculateAlarmTime(event, definition)
                    matches.add(
                        ShiftMatch(
                            shiftDefinition = definition,
                            calendarEvent = event,
                            calculatedAlarmTime = alarmTime
                        )
                    )
                    Logger.i(LogTags.SHIFT_RECOGNITION, "✅ MATCH found: '${event.title}' matches '${definition.name}' with keywords ${definition.keywords}")
                    break // Only match first matching definition per event
                } else {
                    Logger.d(LogTags.SHIFT_RECOGNITION, "❌ No match: '${event.title}' doesn't contain any of ${definition.keywords}")
                }
            }
        }
        
        Logger.i(LogTags.SHIFT_RECOGNITION, "Recognition complete: Found ${matches.size} shifts")
        return matches.sortedBy { it.calculatedAlarmTime }
    }
    
    suspend fun findAllShiftMatches(events: List<CalendarEvent>): List<ShiftMatch> {
        return getAllMatchingShifts(events)
    }
    
    private fun calculateAlarmTime(event: CalendarEvent, definition: ShiftDefinition): LocalDateTime {
        val shiftStartTime = event.startTime
        val alarmTime = definition.getAlarmLocalTime()
        
        // Calculate alarm time on the same date as the shift
        val alarmDateTime = LocalDateTime.of(
            shiftStartTime.toLocalDate(),
            alarmTime
        )
        
        // If alarm time is after shift start time, assume it's for the previous day
        return if (alarmDateTime.isAfter(shiftStartTime)) {
            alarmDateTime.minusDays(1)
        } else {
            alarmDateTime
        }
    }
}
