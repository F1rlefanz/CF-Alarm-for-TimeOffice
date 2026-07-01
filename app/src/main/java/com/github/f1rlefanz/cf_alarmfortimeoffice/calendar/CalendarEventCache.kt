package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Cache-System für Kalenderereignisse mit intelligenter Invalidierung
 * 
 * ARCHITECTURE (Briefing 4.0):
 * ✅ 6h TTL (aligned with maintenance interval)
 * ✅ Online-First strategy (no stale cache fallback)
 * ✅ Coroutine-Mutex for thread-safe operations
 * ✅ Memory-efficient caching based on calendar ID and time range
 * ✅ Automatic cache invalidation
 */
class CalendarEventCache {
    
    private data class CacheKey(
        val calendarId: String,
        val baseTime: LocalDateTime // Zur nächsten Stunde gerundet für bessere Cache-Hits
    ) {
        companion object {
            fun create(calendarId: String): CacheKey {
                // Runde auf nächste Stunde für bessere Cache-Wiederverwendung
                val roundedTime = LocalDateTime.now()
                    .truncatedTo(ChronoUnit.HOURS)

                return CacheKey(
                    calendarId = calendarId,
                    baseTime = roundedTime
                )
            }
        }
    }
    
    private data class CacheEntry(
        val events: List<CalendarEvent>,
        val timestamp: LocalDateTime,
        val etag: String? = null // For ETag-based change detection (future)
    ) {
        fun isExpired(): Boolean {
            // 6 hours TTL - aligned with maintenance interval
            return timestamp.plusMinutes(360).isBefore(LocalDateTime.now())
        }
    }
    
    // Coroutine-Mutex für bessere Performance als @Synchronized
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<CacheKey, CacheEntry>()
    private val maxCacheSize = 20 // Prevent memory bloat
    
    /**
     * Checks if valid cache entry exists for given calendar
     * PHASE 2 CLEANUP: daysAhead removed - always 14 days per PROJEKT-BRIEFING 4.0
     */
    suspend fun isCached(calendarId: String): Boolean = cacheMutex.withLock {
        val key = CacheKey.create(calendarId)
        val entry = cache[key]
        
        if (entry != null && !entry.isExpired()) {
            Logger.cache(LogTags.CALENDAR_CACHE, "HIT", "calendar ${calendarId.take(8)}...")
            return@withLock true
        }
        
        if (entry != null && entry.isExpired()) {
            Logger.d(LogTags.CALENDAR_CACHE, "Cache EXPIRED for calendar ${calendarId.take(8)}..., removing entry")
            cache.remove(key)
        }
        
        Logger.cache(LogTags.CALENDAR_CACHE, "MISS", "calendar ${calendarId.take(8)}...")
        return@withLock false
    }
    
    /**
     * Retrieves events from cache
     * PHASE 2 CLEANUP: daysAhead removed - always 14 days per PROJEKT-BRIEFING 4.0
     */
    suspend fun get(calendarId: String): List<CalendarEvent>? = cacheMutex.withLock {
        val key = CacheKey.create(calendarId)
        val entry = cache[key]
        
        return@withLock if (entry != null && !entry.isExpired()) {
            Logger.d(LogTags.CALENDAR_CACHE, "Returning ${entry.events.size} cached events")
            entry.events
        } else {
            if (entry != null) {
                cache.remove(key)
                Logger.d(LogTags.CALENDAR_CACHE, "Removed expired cache entry")
            }
            null
        }
    }
    
    /**
     * Stores events in cache
     * PHASE 2 CLEANUP: daysAhead removed - always 14 days per PROJEKT-BRIEFING 4.0
     */
    suspend fun put(
        calendarId: String, 
        events: List<CalendarEvent>, 
        etag: String? = null
    ) = cacheMutex.withLock {
        // Limit cache size - remove oldest entries
        if (cache.size >= maxCacheSize) {
            val entriesToRemove = cache.entries
                .sortedBy { it.value.timestamp }
                .take(cache.size - maxCacheSize + 1)
            
            entriesToRemove.forEach { (key, _) ->
                cache.remove(key)
            }
            
            Logger.d(LogTags.CALENDAR_CACHE, "Removed ${entriesToRemove.size} cache entries to make space")
        }
        
        val key = CacheKey.create(calendarId)
        val entry = CacheEntry(
            events = events,
            timestamp = LocalDateTime.now(),
            etag = etag
        )

        cache[key] = entry
        Logger.cache(LogTags.CALENDAR_CACHE, "STORED", "${events.size} events (TTL: 6h)")
    }
    

    /**
     * Invalidiert alle Cache-Einträge für einen Kalender
     */
    suspend fun invalidateCalendar(calendarId: String) = cacheMutex.withLock {
        val keysToRemove = cache.keys.filter { it.calendarId == calendarId }
        keysToRemove.forEach { cache.remove(it) }
        Logger.i(LogTags.CALENDAR_CACHE, "Invalidated all cache entries (${keysToRemove.size} entries)")
    }
    
    /**
     * Leert den kompletten Cache
     */
    suspend fun clear() = cacheMutex.withLock {
        val size = cache.size
        cache.clear()
        Logger.i(LogTags.CALENDAR_CACHE, "Cleared complete event cache ($size entries)")
    }
    
    /**
     * Holt ETag für Change Detection
     * PHASE 2 CLEANUP: daysAhead removed - always 14 days per PROJEKT-BRIEFING 4.0
     */
    suspend fun getETag(calendarId: String): String? = cacheMutex.withLock {
        val key = CacheKey.create(calendarId)
        return@withLock cache[key]?.etag
    }
    
    /**
     * Cache statistics for debugging
     */
    suspend fun getCacheStats(): String = cacheMutex.withLock {
        val totalEntries = cache.size
        val expiredEntries = cache.values.count { it.isExpired() }
        val validEntries = totalEntries - expiredEntries
        
        return@withLock "Cache Stats: $validEntries valid, $expiredEntries expired, $totalEntries total"
    }
}
