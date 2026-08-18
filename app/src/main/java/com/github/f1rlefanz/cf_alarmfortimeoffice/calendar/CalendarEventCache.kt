package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * Kurzlebiger Cache fuer Kalender-Events, geschluesselt AUSSCHLIESSLICH nach Kalender-ID.
 *
 * WARUM KEINE UHRZEIT IM SCHLUESSEL (bis v1.27.0 stand sie darin):
 * Der Schluessel bestand aus Kalender-ID UND `LocalDateTime.now().truncatedTo(HOURS)`. Geschrieben
 * wurde unter der Schreibstunde, gelesen unter der Lesestunde - ab der naechsten vollen Stunde war
 * ein Eintrag unter keinem gelesenen Schluessel mehr auffindbar. Damit war die im Kommentar
 * zugesicherte "6h TTL" reine Behauptung (effektiv 0-60 Minuten), die Ablauf-Zweige unten konnten
 * NIE greifen (ein gefundener Eintrag war zwangslaeufig juenger als eine Stunde), die
 * Cache-Statistik meldete strukturell immer "0 expired", und Eintraege vergangener Stunden blieben
 * bis zur Groessenverdraengung unauffindbar liegen.
 *
 * WARUM DIE TTL DEUTLICH UNTER DEM WARTUNGSINTERVALL BLEIBEN MUSS:
 * Die 6h-Wartungskette ruft `getCalendarEventsWithCache` im Normallauf OHNE `forceRefresh`. Laege
 * die TTL bei den frueher behaupteten 6 Stunden, liefe genau dieser Lauf systematisch in einen
 * Cache-Treffer und saehe Dienstplan-Aenderungen und -Streichungen gar nicht mehr - der Cache
 * waere dann die Wahrheit, aus der `syncAlarms()` loescht. Die TTL darf deshalb nur denselben
 * Bedienvorgang zusammenfassen (mehrere Bildschirme kurz hintereinander), niemals einen
 * Wartungslauf ueberbruecken.
 *
 * KEIN ETAG MEHR: Die Klasse hielt zusaetzlich einen ETag fuer bedingte Abrufe vor. Dieser Pfad
 * war durch den Stunden-Schluessel nachweislich nie gelaufen und haette bei blosser
 * Schluesselreparatur eine unerprobte Falle scharf geschaltet: der ETag gehoert zu einer Abfrage
 * mit `timeMin = jetzt` / `timeMax = jetzt + 14 Tage`, also zu einem MITWANDERNDEN Fenster. Ein
 * "304 Not Modified" haette damit die Unveraendertheit eines ANDEREN Zeitfensters bescheinigt und
 * eine veraltete Liste als aktuell ausgeliefert - genau die Verwechslung, die in v1.26.2 schon
 * einmal Weckzeiten mit falschem Zonenversatz erzeugt hat.
 */
class CalendarEventCache(
    /**
     * Zeitquelle - einzige Test-Naht der Klasse. Ohne sie liesse sich weder ein Stundenwechsel
     * noch ein Ablauf pruefen, ohne im Test wirklich zu warten.
     */
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) {

    private data class CacheEntry(
        val events: List<CalendarEvent>,
        val timestamp: LocalDateTime
    ) {
        fun isExpired(reference: LocalDateTime): Boolean =
            timestamp.plusMinutes(CalendarEventCache.TTL_MINUTES).isBefore(reference)
    }

    // Coroutine-Mutex fuer bessere Performance als @Synchronized
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()

    /**
     * Prueft, ob ein gueltiger Cache-Eintrag fuer den Kalender existiert.
     */
    suspend fun isCached(calendarId: String): Boolean = cacheMutex.withLock {
        val entry = cache[calendarId]

        if (entry != null && !entry.isExpired(now())) {
            Logger.cache(LogTags.CALENDAR_CACHE, "HIT", "calendar ${calendarId.take(8)}...")
            return@withLock true
        }

        if (entry != null) {
            Logger.d(LogTags.CALENDAR_CACHE, "Cache EXPIRED for calendar ${calendarId.take(8)}..., removing entry")
            cache.remove(calendarId)
        }

        Logger.cache(LogTags.CALENDAR_CACHE, "MISS", "calendar ${calendarId.take(8)}...")
        return@withLock false
    }

    /**
     * Liefert die gecachten Events - oder null, wenn kein gueltiger Eintrag vorliegt.
     */
    suspend fun get(calendarId: String): List<CalendarEvent>? = cacheMutex.withLock {
        val entry = cache[calendarId]

        return@withLock if (entry != null && !entry.isExpired(now())) {
            Logger.d(LogTags.CALENDAR_CACHE, "Returning ${entry.events.size} cached events")
            entry.events
        } else {
            if (entry != null) {
                cache.remove(calendarId)
                Logger.d(LogTags.CALENDAR_CACHE, "Removed expired cache entry")
            }
            null
        }
    }

    /**
     * Legt die Events des Kalenders ab.
     *
     * Es gehoert IMMER die vollstaendige Liste des 14-Tage-Fensters hier hinein, nie eine
     * einzelne Seite: Leser dieses Caches geben den Inhalt als vollstaendige Liste weiter, und
     * "vollstaendig" ist fuer die loeschenden Konsumenten die Erlaubnis, Alarme zu entfernen.
     */
    suspend fun put(
        calendarId: String,
        events: List<CalendarEvent>
    ) = cacheMutex.withLock {
        // Limit cache size - remove oldest entries
        if (cache.size >= MAX_CACHE_SIZE && !cache.containsKey(calendarId)) {
            val entriesToRemove = cache.entries
                .sortedBy { it.value.timestamp }
                .take(cache.size - MAX_CACHE_SIZE + 1)
                .map { it.key }

            entriesToRemove.forEach { cache.remove(it) }

            Logger.d(LogTags.CALENDAR_CACHE, "Removed ${entriesToRemove.size} cache entries to make space")
        }

        cache[calendarId] = CacheEntry(
            events = events,
            timestamp = now()
        )
        Logger.cache(LogTags.CALENDAR_CACHE, "STORED", "${events.size} events (TTL: ${TTL_MINUTES}min)")
    }

    /**
     * Invalidiert den Cache-Eintrag eines Kalenders
     */
    suspend fun invalidateCalendar(calendarId: String) = cacheMutex.withLock {
        val removed = cache.remove(calendarId) != null
        Logger.i(LogTags.CALENDAR_CACHE, "Invalidated cache entry for calendar (found: $removed)")
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
     * Cache statistics for debugging
     */
    suspend fun getCacheStats(): String = cacheMutex.withLock {
        val reference = now()
        val totalEntries = cache.size
        val expiredEntries = cache.values.count { it.isExpired(reference) }
        val validEntries = totalEntries - expiredEntries

        return@withLock "Cache Stats: $validEntries valid, $expiredEntries expired, $totalEntries total"
    }

    companion object {
        /**
         * Lebensdauer eines Eintrags in Minuten.
         *
         * MUSS deutlich unter dem 6h-Wartungsintervall bleiben - siehe Klassenkommentar.
         */
        const val TTL_MINUTES = 15L

        /** Obergrenze der Eintraege (ein Eintrag je Kalender) - Schutz vor Speicherwucherung. */
        const val MAX_CACHE_SIZE = 20
    }
}
