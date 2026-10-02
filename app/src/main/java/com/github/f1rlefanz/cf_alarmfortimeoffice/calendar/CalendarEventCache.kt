package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.KalenderEventAbruf
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
 * mit `timeMin = jetzt` / `timeMax = jetzt + Vorausschau`, also zu einem MITWANDERNDEN Fenster. Ein
 * "304 Not Modified" haette damit die Unveraendertheit eines ANDEREN Zeitfensters bescheinigt und
 * eine veraltete Liste als aktuell ausgeliefert - genau die Verwechslung, die in v1.26.2 schon
 * einmal Weckzeiten mit falschem Zonenversatz erzeugt hat.
 *
 * DAS FENSTER IST TEIL DER FRAGE (#51): Ein Eintrag beantwortet "alle Events der naechsten N Tage"
 * fuer GENAU das N, mit dem er abgerufen wurde. Stellt der Nutzer die Vorausschau um, liefert ein
 * Eintrag des alten Fensters innerhalb der TTL sonst die alte Liste - nach einer Vergroesserung
 * fehlen dann die neuen Wochen, und der Sync haelt die kuerzere Liste fuer das neue Fenster. Ein
 * Eintrag mit anderem Fenster ist deshalb ein FEHLTREFFER. Gespeichert wird ausserdem das
 * Abruf-Ende ([KalenderEventAbruf.horizontEnde]) des URSPRUENGLICHEN Abrufs - ein Treffer reicht
 * nur so weit wie dieser, nicht "jetzt + N".
 */
class CalendarEventCache(
    /**
     * Zeitquelle - einzige Test-Naht der Klasse. Ohne sie liesse sich weder ein Stundenwechsel
     * noch ein Ablauf pruefen, ohne im Test wirklich zu warten.
     */
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) {

    private data class CacheEntry(
        val abruf: KalenderEventAbruf,
        val timestamp: LocalDateTime
    ) {
        fun isExpired(reference: LocalDateTime): Boolean =
            timestamp.plusMinutes(CalendarEventCache.TTL_MINUTES).isBefore(reference)
    }

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CacheEntry>()

    /**
     * Liefert den gecachten Abruf - oder null, wenn kein gueltiger Eintrag fuer GENAU dieses
     * Fenster vorliegt (fehlt, abgelaufen, oder mit anderer Vorausschau abgerufen).
     */
    suspend fun get(calendarId: String, fensterTage: Int): KalenderEventAbruf? = cacheMutex.withLock {
        val entry = cache[calendarId]

        if (entry != null && entry.isExpired(now())) {
            cache.remove(calendarId)
            Logger.d(LogTags.CALENDAR_CACHE, "Cache EXPIRED for calendar ${calendarId.take(8)}..., removing entry")
        } else if (entry != null && entry.abruf.fensterTage != fensterTage) {
            // Nicht entfernen: der Abruf mit dem neuen Fenster ueberschreibt ihn gleich (put).
            Logger.cache(
                LogTags.CALENDAR_CACHE,
                "MISS",
                "calendar ${calendarId.take(8)}... (Fenster ${entry.abruf.fensterTage} statt $fensterTage Tage)"
            )
            return@withLock null
        } else if (entry != null) {
            Logger.cache(LogTags.CALENDAR_CACHE, "HIT", "calendar ${calendarId.take(8)}...")
            return@withLock entry.abruf
        }

        Logger.cache(LogTags.CALENDAR_CACHE, "MISS", "calendar ${calendarId.take(8)}...")
        null
    }

    /**
     * Legt die Events des Kalenders ab.
     *
     * Es gehoert IMMER die vollstaendige Liste des Abruf-Fensters hier hinein, nie eine
     * einzelne Seite: Leser dieses Caches geben den Inhalt als vollstaendige Liste weiter, und
     * "vollstaendig" ist fuer die loeschenden Konsumenten die Erlaubnis, Alarme zu entfernen.
     */
    suspend fun put(
        calendarId: String,
        abruf: KalenderEventAbruf
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
            abruf = abruf,
            timestamp = now()
        )
        Logger.cache(
            LogTags.CALENDAR_CACHE,
            "STORED",
            "${abruf.events.size} events, ${abruf.fensterTage} Tage (TTL: ${TTL_MINUTES}min)"
        )
    }

    /**
     * Invalidiert den Cache-Eintrag eines Kalenders
     */
    suspend fun invalidateCalendar(calendarId: String) = cacheMutex.withLock {
        val removed = cache.remove(calendarId) != null
        Logger.i(LogTags.CALENDAR_CACHE, "Invalidated cache entry for calendar (found: $removed)")
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
