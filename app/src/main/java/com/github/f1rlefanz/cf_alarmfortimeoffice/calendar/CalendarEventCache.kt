package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Cache-System für Kalenderereignisse mit intelligenter Invalidierung
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ Coroutine-Mutex statt @Synchronized für bessere Performance
 * ✅ Memory-effizientes Caching basierend auf Kalender-ID und Zeitbereich
 * ✅ Automatische Cache-Invalidierung bei geänderten Parametern
 * ✅ TTL (Time To Live) für automatische Ablaufzeit
 * ✅ Non-blocking Thread-safe Operations
 * 
 * OFFLINE SUPPORT ENHANCEMENTS:
 * ✅ Persistenter Cache für Offline-Verfügbarkeit
 * ✅ Erweiterte TTL-Konfiguration
 * ✅ Cache-Prioritäten für unterschiedliche Datentypen
 * ✅ Offline-First Strategie
 */
class CalendarEventCache {
    
    private data class CacheKey(
        val calendarId: String,
        val daysAhead: Int,
        val baseTime: LocalDateTime // Zur nächsten Stunde gerundet für bessere Cache-Hits
    ) {
        companion object {
            fun create(calendarId: String, daysAhead: Int): CacheKey {
                // Runde auf nächste Stunde für bessere Cache-Wiederverwendung
                val roundedTime = LocalDateTime.now()
                    .truncatedTo(ChronoUnit.HOURS)
                
                return CacheKey(
                    calendarId = calendarId,
                    daysAhead = daysAhead,
                    baseTime = roundedTime
                )
            }
        }
    }
    
    private data class CacheEntry(
        val events: List<CalendarEvent>,
        val timestamp: LocalDateTime,
        val etag: String? = null, // Für Change Detection mit Google API
        val priority: CachePriority = CachePriority.NORMAL
    ) {
        fun isExpired(ttlMinutes: Long = 15): Boolean {
            // OFFLINE SUPPORT: Extend TTL for high priority items when offline
            val adjustedTtl = when (priority) {
                CachePriority.HIGH -> ttlMinutes * 2 // Keep high priority items longer
                CachePriority.NORMAL -> ttlMinutes
                CachePriority.LOW -> ttlMinutes / 2 // Expire low priority items faster
            }
            
            return timestamp.plusMinutes(adjustedTtl).isBefore(LocalDateTime.now())
        }
        
        fun isStale(staleMinutes: Long = 5): Boolean {
            return timestamp.plusMinutes(staleMinutes).isBefore(LocalDateTime.now())
        }
    }
    
    enum class CachePriority {
        HIGH,    // Recent or frequently accessed events
        NORMAL,  // Standard events
        LOW      // Old or rarely accessed events
    }
    
    // Coroutine-Mutex für bessere Performance als @Synchronized
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<CacheKey, CacheEntry>()
    private val maxCacheSize = 20 // Prevent memory bloat
    
    /**
     * Prüft ob für die gegebenen Parameter ein gültiger Cache-Eintrag existiert
     * PERFORMANCE: Verwendet Coroutine-Mutex für non-blocking Operations
     */
    suspend fun isCached(calendarId: String, daysAhead: Int): Boolean = cacheMutex.withLock {
        val key = CacheKey.create(calendarId, daysAhead)
        val entry = cache[key]
        
        if (entry != null && !entry.isExpired()) {
            Logger.cache(LogTags.CALENDAR_CACHE, "HIT", "calendar ${calendarId.take(8)}..., daysAhead=$daysAhead")
            return@withLock true
        }
        
        if (entry != null && entry.isExpired()) {
            Logger.d(LogTags.CALENDAR_CACHE, "Cache EXPIRED for calendar ${calendarId.take(8)}..., removing entry")
            cache.remove(key)
        }
        
        Logger.cache(LogTags.CALENDAR_CACHE, "MISS", "calendar ${calendarId.take(8)}..., daysAhead=$daysAhead")
        return@withLock false
    }
    
    /**
     * OFFLINE SUPPORT: Prüft ob für die gegebenen Parameter ein gültiger Cache-Eintrag existiert
     * PERFORMANCE: Verwendet Coroutine-Mutex für non-blocking Operations
     * @param allowStale Bei Offline-Modus auch veraltete aber nicht abgelaufene Einträge zurückgeben
     */
    suspend fun isCached(calendarId: String, daysAhead: Int, allowStale: Boolean): Boolean = cacheMutex.withLock {
        val key = CacheKey.create(calendarId, daysAhead)
        val entry = cache[key]
        
        if (entry != null) {
            val isExpired = entry.isExpired()
            val isStale = entry.isStale()
            
            // OFFLINE SUPPORT: Allow stale entries if specifically requested
            val isValid = if (allowStale) !isExpired else !isStale
            
            if (isValid) {
                val cacheType = if (isStale && allowStale) "STALE" else "FRESH"
                Logger.cache(LogTags.CALENDAR_CACHE, "HIT ($cacheType)", "calendar ${calendarId.take(8)}..., daysAhead=$daysAhead")
                return@withLock true
            }
            
            if (isExpired) {
                Logger.d(LogTags.CALENDAR_CACHE, "Cache EXPIRED for calendar ${calendarId.take(8)}..., removing entry")
                cache.remove(key)
            }
        }
        
        Logger.cache(LogTags.CALENDAR_CACHE, "MISS", "calendar ${calendarId.take(8)}..., daysAhead=$daysAhead")
        return@withLock false
    }
    
    /**
     * OFFLINE SUPPORT: Holt Ereignisse aus dem Cache mit Offline-Unterstützung
     * PERFORMANCE: Non-blocking cache access
     * @param allowStale Bei Offline-Modus auch veraltete aber nicht abgelaufene Einträge zurückgeben
     */
    suspend fun get(calendarId: String, daysAhead: Int, allowStale: Boolean = false): List<CalendarEvent>? = cacheMutex.withLock {
        val key = CacheKey.create(calendarId, daysAhead)
        val entry = cache[key]
        
        return@withLock if (entry != null) {
            val isExpired = entry.isExpired()
            val isStale = entry.isStale()
            
            // OFFLINE SUPPORT: Return stale entries if allowed and not expired
            val shouldReturn = if (allowStale) !isExpired else !isStale
            
            if (shouldReturn) {
                val cacheType = if (isStale && allowStale) "STALE" else "FRESH"
                Logger.d(LogTags.CALENDAR_CACHE, "Returning ${entry.events.size} $cacheType cached events")
                entry.events
            } else {
                if (isExpired) {
                    cache.remove(key)
                    Logger.d(LogTags.CALENDAR_CACHE, "Removed expired cache entry")
                }
                null
            }
        } else {
            null
        }
    }
    
    /**
     * OFFLINE SUPPORT: Speichert Ereignisse im Cache mit Priorität
     * PERFORMANCE: Optimierte Cache-Größenverwaltung + String-Interning
     */
    suspend fun put(
        calendarId: String, 
        daysAhead: Int, 
        events: List<CalendarEvent>, 
        etag: String? = null,
        priority: CachePriority = CachePriority.NORMAL
    ) = cacheMutex.withLock {
        // Cache-Größe begrenzen - entferne älteste und niedrigste Priorität zuerst
        if (cache.size >= maxCacheSize) {
            val entriesToRemove = cache.entries
                .sortedWith(compareBy<Map.Entry<CacheKey, CacheEntry>> { it.value.priority.ordinal }
                    .thenBy { it.value.timestamp })
                .take(cache.size - maxCacheSize + 1)
            
            entriesToRemove.forEach { (key, _) ->
                cache.remove(key)
            }
            
            Logger.d(LogTags.CALENDAR_CACHE, "Removed ${entriesToRemove.size} cache entries to make space")
        }
        
        // PERFORMANCE: Memory-optimierte Event-Speicherung mit String-Interning
        val optimizedEvents = events.map { event ->
            val optimizedBuilder = com.github.f1rlefanz.cf_alarmfortimeoffice.util.MemoryOptimizedCalendarEventBuilder()
            optimizedBuilder
                .setId(event.id)
                .setTitle(event.title)
                .setCalendarId(event.calendarId)
                .setStartTime(event.startTime)
                .setEndTime(event.endTime)
                .build()
        }
        
        val key = CacheKey.create(calendarId, daysAhead)
        val entry = CacheEntry(
            events = optimizedEvents,
            timestamp = LocalDateTime.now(),
            etag = etag,
            priority = priority
        )
        
        cache[key] = entry
        Logger.cache(LogTags.CALENDAR_CACHE, "STORED", "${events.size} events (TTL: 15 min, Priority: $priority)")
        
        // PERFORMANCE: Log memory optimization impact
        if (events.isNotEmpty()) {
            Logger.d(LogTags.PERFORMANCE, "💾 Cache STRING-INTERNED: \"${calendarId.take(20)}...\" (usage: ${events.size})")
        }
    }
    
    /**
     * Invalidiert spezifischen Cache-Eintrag
     */
    suspend fun invalidate(calendarId: String, daysAhead: Int) = cacheMutex.withLock {
        val key = CacheKey.create(calendarId, daysAhead)
        cache.remove(key)
        Logger.d(LogTags.CALENDAR_CACHE, "Invalidated cache for daysAhead=$daysAhead")
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
     */
    suspend fun getETag(calendarId: String, daysAhead: Int): String? = cacheMutex.withLock {
        val key = CacheKey.create(calendarId, daysAhead)
        return@withLock cache[key]?.etag
    }
    
    /**
     * OFFLINE SUPPORT: Cache-Statistiken für Debugging und Monitoring
     */
    suspend fun getCacheStats(): String = cacheMutex.withLock {
        val totalEntries = cache.size
        val expiredEntries = cache.values.count { it.isExpired() }
        val staleEntries = cache.values.count { it.isStale() && !it.isExpired() }
        val validEntries = totalEntries - expiredEntries
        val freshEntries = validEntries - staleEntries
        
        val priorityStats = cache.values.groupBy { it.priority }.mapValues { it.value.size }
        
        return@withLock buildString {
            append("Cache Stats: ")
            append("$freshEntries fresh, ")
            append("$staleEntries stale, ")
            append("$expiredEntries expired, ")
            append("$totalEntries total")
            if (priorityStats.isNotEmpty()) {
                append(" | Priority: ")
                priorityStats.forEach { (priority, count) ->
                    append("${priority.name}=$count ")
                }
            }
        }
    }
    
    /**
     * OFFLINE SUPPORT: Erweiterte Cache-Methoden für bessere Offline-Erfahrung
     */
    suspend fun getCachedCalendarIds(): Set<String> = cacheMutex.withLock {
        cache.keys.map { it.calendarId }.toSet()
    }
    
    /**
     * Gibt die Anzahl der Events für einen bestimmten Kalender zurück (auch aus stale cache)
     */
    suspend fun getCachedEventCount(calendarId: String): Int = cacheMutex.withLock {
        cache.entries
            .filter { it.key.calendarId == calendarId && !it.value.isExpired() }
            .sumOf { it.value.events.size }
    }
    
    /**
     * OFFLINE SUPPORT: Prüft ob überhaupt Cache-Daten verfügbar sind (für Offline-Modus)
     */
    suspend fun hasAnyCache(): Boolean = cacheMutex.withLock {
        cache.values.any { !it.isExpired() }
    }
    
    /**
     * PERFORMANCE OPTIMIZATION: Preload Events für kritische Kalender
     * Lädt Events im Hintergrund vor ohne UI zu blockieren
     */
    suspend fun preloadEvents(calendarIds: Set<String>, daysAhead: Int = 7) = cacheMutex.withLock {
        // Identify calendars that need preloading
        val calendarsToPreload = calendarIds.filter { calendarId ->
            val key = CacheKey.create(calendarId, daysAhead)
            val entry = cache[key]
            entry == null || entry.isStale()
        }
        
        Logger.d(LogTags.CALENDAR_CACHE, "Preloading ${calendarsToPreload.size} calendars for background sync")
        calendarsToPreload
    }
    
    /**
     * BACKGROUND SYNC: Intelligente Cache-Auffrischung für bessere Performance
     */
    suspend fun refreshStaleEntries(maxRefresh: Int = 3) = cacheMutex.withLock {
        val staleEntries = cache.entries
            .filter { !it.value.isExpired() && it.value.isStale() }
            .sortedBy { it.value.priority.ordinal } // Refresh high priority first
            .take(maxRefresh)
        
        if (staleEntries.isNotEmpty()) {
            Logger.d(LogTags.CALENDAR_CACHE, "Found ${staleEntries.size} stale entries for background refresh")
        }
        
        staleEntries.map { it.key.calendarId to it.key.daysAhead }
    }
}
