package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.istKrankmeldung
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime

/**
 * #142: Ergebnis EINES Erkennungslaufs - Schichttreffer und Krank-Termine aus demselben Stand.
 * Ein gemeinsames Objekt statt zweier Laeufe: sonst koennte eine Konfigurationsaenderung zwischen
 * beiden Abfragen einen Termin in BEIDE Listen oder in KEINE stecken.
 *
 * Umbau E1 (#167): [ignorierteTermine] sind Termine, die NUR ein aktiver Typ der Kategorie
 * Ignorieren trifft - keine Schicht, also kein Wecker und keine Schichtspanne. Getrennt gemeldet,
 * damit „Ohne Kalendereintrag“ ihren Tag als leer zaehlen kann. Jeder Termin steht in hoechstens
 * EINER der drei Listen.
 */
data class Erkennung(
    val schichten: List<ShiftMatch>,
    val krankTermine: List<CalendarEvent>,
    val ignorierteTermine: List<CalendarEvent>
)

/**
 * @param recognitionDispatcher der Dispatcher, auf dem die Erkennung wirklich rechnet - siehe
 *        [erkenne]. Per Vorgabe [Dispatchers.Default]; der Parameter existiert, damit
 *        Tests die Erkennung im virtuellen Zeitplan halten koennen (die Nebenlaeufigkeits-Tests um
 *        Mutex und Epoche brauchen eine deterministische Reihenfolge).
 */
class ShiftRecognitionEngine(
    private val shiftConfigRepository: IShiftConfigRepository,
    private val recognitionDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    
    /**
     * Der komplette Cache-Stand in EINEM unveraenderlichen Objekt: Event-Schluessel, Ergebnis,
     * Veroeffentlichungszeit und die [cacheEpoch], unter der er entstanden ist. Eine einzige
     * `@Volatile`-Referenz ist unteilbar, drei einzelne Felder waeren es nicht.
     * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
     *
     * #142: [erkennung] traegt Schicht- UND Krank-Treffer (seit #167 auch die ignorierten Termine) in
     * EINEM Objekt - alle Listen teilen damit dieselbe Epoche und denselben Veroeffentlichungszeitpunkt.
     */
    private data class RecognitionCache(
        val eventsHash: Int,
        val erkennung: Erkennung,
        val publishedAt: Long,
        val epoch: Int
    )

    @Volatile
    private var cache: RecognitionCache? = null

    /**
     * INVARIANTE: Der Zaehler, mit dem [clearRecognitionCache] eine Invalidierung OHNE Mutex
     * durchsetzen kann. [clearRecognitionCache] ist bewusst NICHT `suspend` (synchroner Aufrufer
     * `ShiftUseCase.invalidateAllCaches()`).
     *
     * Jeder veroeffentlichte Stand traegt die Epoche, unter der er ENTSTANDEN ist; ein Leser
     * akzeptiert nur einen Stand mit der aktuellen Epoche. So wird auch ein Lauf harmlos, der
     * zwischen Pruefung und Schreiben ueberholt wird.
     * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
     */
    @Volatile
    private var cacheEpoch = 0
    @Volatile
    private var cacheHitCount = 0
    @Volatile
    private var configChangeCount = 0

    /**
     * INVARIANTE: Cache-Pruefung UND Cache-Veroeffentlichung liegen gemeinsam hinter diesem
     * Mutex - sonst liest ein nebenlaeufiger Aufrufer eine leere Liste, und `syncAlarms()` loescht
     * darauf ALLE Alarme. Mehrere unabhaengige Aufrufer dieser Singleton-Instanz laufen ausserhalb
     * jedes Alarm-Mutex; Ueberlappung ist der Normalfall. Die Invalidierung deckt [cacheEpoch] ab.
     * Kein Boolean-Flag mit Timeout.
     * Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
     */
    private val recognitionMutex = Mutex()

    private companion object {
        const val BASE_CACHE_VALIDITY_MS = 5000L  // Base 5 seconds
        const val ADAPTIVE_CACHE_MIN_MS = 2000L   // Minimum 2 seconds
        const val ADAPTIVE_CACHE_MAX_MS = 30000L  // Maximum 30 seconds
        val MAX_NIGHT_SHIFT_LEAD_TIME: Duration = Duration.ofHours(12)
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
     *
     * REIHENFOLGE IST TRAGEND: erst [cacheEpoch] hochzaehlen, dann den Stand nullen. Ein Lauf, der
     * gerade im kritischen Abschnitt steckt, sieht die neue Epoche damit spaetestens beim
     * Veroeffentlichen - und sein mit der alten Epoche gestempelter Stand wird ohnehin von jedem
     * Leser verworfen. Andersherum (erst nullen, dann zaehlen) gaebe es ein Fenster, in dem ein
     * Lauf noch mit der alten Epoche als "aktuell" veroeffentlichen darf.
     */
    fun clearRecognitionCache() {
        cacheEpoch++
        cache = null

        // ADAPTIVE LEARNING: Track configuration changes for cache optimization
        configChangeCount++
        cacheHitCount = 0 // Reset hit count on config change
        
        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 ADAPTIVE-CACHE-CLEAR: Recognition cache cleared due to configuration change (change #$configChangeCount)")
    }
    
    /**
     * Unveraenderter Vertrag fuer alle bisherigen Konsumenten: OHNE Krank-Treffer (#142, Review S4)
     * und OHNE ignorierte Termine (#167). Wer eine davon braucht, fragt [erkenne].
     */
    suspend fun getAllMatchingShifts(events: List<CalendarEvent>): List<ShiftMatch> = erkenne(events).schichten

    /**
     * Der EINE Erkennungslauf (#142): Schicht- und Krank-Treffer aus demselben Konfigurationsstand,
     * hinter demselben Mutex, mit derselben Epoche. Wer beide Listen braucht (`syncAlarms()`), ruft
     * DIESE Funktion genau einmal - zwei getrennte Aufrufe koennten zwischen sich eine
     * Invalidierung sehen.
     */
    suspend fun erkenne(events: List<CalendarEvent>): Erkennung {
        // PERFORMANCE: Calculate hash of input to prevent duplicate processing
        val eventsHash = events.hashCode()

        // WARUM DER DISPATCHER-WECHSEL HIER UND NICHT BEI DEN AUFRUFERN (Pruefrunde 7): drei der
        // Aufrufer dieser Engine kamen ueber `ShiftViewModel` und damit auf `Dispatchers.Main` an -
        // auf der ganzen Kette (ViewModel -> ShiftUseCase -> Engine) gab es keinen einzigen
        // Wechsel, und `SafeExecutor` ist reines try/catch. Die Erkennung ist aber reine
        // Rechenarbeit ueber Termine x Definitionen x Muster und lief damit auf dem UI-Thread.
        // Hier gesetzt, weil es die einzige Stelle ist, durch die ALLE Aufrufer muessen; die
        // beiden bereits auf IO laufenden (AlarmUseCase.syncAlarms, AlarmMaintenanceService)
        // wechseln damit nur von IO auf Default, was fuer CPU-Arbeit ohnehin der richtige Pool ist.
        //
        // WICHTIG FUER DIE CACHE-INVARIANTE: der Wechsel liegt AUSSERHALB des Mutex, der kritische
        // Abschnitt bleibt unveraendert EIN Block. Pruefung und Veroeffentlichung liegen weiterhin
        // gemeinsam darin, `epochAtStart` wird weiterhin innerhalb des Locks genommen. Ein Mutex
        // ist dispatcher-unabhaengig - die Reihenfolge der Wartenden aendert sich nicht dadurch,
        // auf welchem Thread sie warten. Zum kritischen Abschnitt selbst: siehe `recognitionMutex`.
        return withContext(recognitionDispatcher) {
            recognitionMutex.withLock {
                // Die Epoche, unter der DIESER Lauf arbeitet - festgehalten, BEVOR die Erkennung
                // beginnt. Ein zwischenzeitliches clearRecognitionCache() macht sie ungueltig.
                val epochAtStart = cacheEpoch

                // ADAPTIVE CACHE: Check cache validity with dynamic expiration
                val snapshot = cache
                if (snapshot != null && snapshot.epoch == epochAtStart && snapshot.eventsHash == eventsHash) {
                    val adaptiveCacheValidity = getAdaptiveCacheValidity()
                    val cacheAge = System.currentTimeMillis() - snapshot.publishedAt

                    if (cacheAge < adaptiveCacheValidity) {
                        cacheHitCount++
                        Logger.d(LogTags.SHIFT_RECOGNITION, "✅ ADAPTIVE-CACHE-HIT: Same events processed recently (${cacheAge}ms ago, validity=${adaptiveCacheValidity}ms), returning cached ${snapshot.erkennung.schichten.size} matches + ${snapshot.erkennung.krankTermine.size} Krank-Termine + ${snapshot.erkennung.ignorierteTermine.size} ignoriert (hit #$cacheHitCount)")
                        return@withLock snapshot.erkennung
                    } else {
                        Logger.d(LogTags.SHIFT_RECOGNITION, "⏰ ADAPTIVE-CACHE-EXPIRED: Cache is ${cacheAge}ms old (validity=${adaptiveCacheValidity}ms), needs refresh")
                    }
                }

                val erkennung = performRecognition(events)

                // ERST JETZT veroeffentlichen - Ergebnis und Cache-Schluessel gemeinsam als EIN
                // Objekt, nachdem die Erkennung wirklich fertig ist. Schlaegt `performRecognition`
                // mit einer Exception fehl, bleibt der alte Stand stehen und der naechste Aufruf
                // versucht es erneut, statt ein Fehlergebnis zu cachen.
                //
                // Der Stand traegt `epochAtStart` - lief zwischendurch ein clearRecognitionCache(),
                // ist er damit als ueberholt erkennbar und wird von jedem Leser verworfen. Das
                // ERGEBNIS geht trotzdem an den Aufrufer zurueck: es ist zwar auf einer inzwischen
                // ersetzten Konfiguration entstanden, aber real erkannt - eine leere Liste
                // zurueckzugeben waere die gefaehrlichere Luege (syncAlarms loescht darauf alle
                // Alarme), und der Aufrufer, der gerade gespeichert hat, loest ohnehin einen neuen
                // Lauf aus.
                cache = RecognitionCache(
                    eventsHash = eventsHash,
                    erkennung = erkennung,
                    publishedAt = System.currentTimeMillis(),
                    epoch = epochAtStart
                )
                if (cacheEpoch != epochAtStart) {
                    Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 CACHE-STALE: Konfiguration wurde waehrend der Erkennung geaendert (Epoche $epochAtStart -> $cacheEpoch), Ergebnis wird nicht als frisch gecacht")
                }

                Logger.d(LogTags.SHIFT_RECOGNITION, "✅ ADAPTIVE-RECOGNITION: Completed with ${erkennung.schichten.size} matches + ${erkennung.krankTermine.size} Krank-Termine + ${erkennung.ignorierteTermine.size} ignoriert (cache validity: ${getAdaptiveCacheValidity()}ms)")
                erkennung
            }
        }
    }
    
    private suspend fun performRecognition(events: List<CalendarEvent>): Erkennung {
        // Ein gescheiterter Read darf NICHT zu "0 Definitionen" degradieren - "leer" loescht in
        // syncAlarms() ALLE Alarme. getOrThrow() reicht den Fehler durch, der Cache bleibt
        // unangetastet. Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
        val allDefinitions = shiftConfigRepository.getCurrentShiftConfig().getOrThrow().definitions

        // `ShiftDefinition.isEnabled` schaltet die Erkennung wirklich ab. Bewusst NUR hier
        // gefiltert, NICHT in `ShiftConfig.findDefinitionFor()`: ein BESTEHENDER Alarm aus der
        // Zeit vor dem Deaktivieren behielte sonst seine Regeln nicht. Hergang: Skill cfalarm-kalender-und-schichten, reference/schichterkennung.md.
        val shiftDefinitions = allDefinitions.filter { it.isEnabled }
        // #142: Krankmeldungs-Typen sind KEINE Schichten - getrennt, bevor die Schleife laeuft.
        // #167: Ignorieren-Typen ebenso. `kategorie` ist ein berechneter Getter, deshalb EINMAL hier
        // aufgeteilt und nicht je Termin. IGNORIEREN schliesst isSickDay aus (ShiftKategorie.ableiten).
        val ignorierDefinitionen = shiftDefinitions.filter { it.kategorie == ShiftKategorie.IGNORIEREN }
        val schichtDefinitionen = shiftDefinitions.filterNot { it.isSickDay || it.kategorie == ShiftKategorie.IGNORIEREN }
        val matches = mutableListOf<ShiftMatch>()
        val krank = mutableListOf<CalendarEvent>()
        val ignoriert = mutableListOf<CalendarEvent>()

        val skipped = allDefinitions.size - shiftDefinitions.size
        if (skipped > 0) {
            Logger.d(LogTags.SHIFT_RECOGNITION, "🚫 DISABLED-SKIP: $skipped von ${allDefinitions.size} Schichtdefinitionen sind deaktiviert und werden nicht erkannt")
        }

        Logger.d(LogTags.SHIFT_RECOGNITION, "Starting shift recognition with ${shiftDefinitions.size} definitions and ${events.size} events")
        
        // KEIN Logging INNERHALB dieser beiden Schleifen (Pruefrunde 7). Hier standen drei
        // `Logger.d` - eines pro Termin, zwei pro Termin x Definition. `Logger.d` prueft
        // `BuildConfig.DEBUG` erst INNERHALB der Funktion; die Zeichenketten samt Ausgabe der
        // Keyword-Liste wurden als Argument also in JEDEM Build gebaut, auch im Release, wo sie
        // anschliessend verworfen wurden. Bei 14 Tagen Dienstplan und einer Handvoll Definitionen
        // sind das mehrere hundert weggeworfene Zeichenketten pro Durchlauf. Was wirklich
        // gebraucht wird, steht ohnehin im Treffer-Log und in der Zusammenfassung darunter.
        for (event in events) {
            // #142 KRANK HAT VORRANG, unabhaengig von der Reihenfolge der Definitionen: ein Termin, der
            // Krank UND eine Schicht trifft, ist eine Krankmeldung und erzeugt KEINEN Schichttreffer -
            // sonst entstuende fuer genau den Tag, den die Krankmeldung freigeben soll, ein Wecker.
            if (shiftDefinitions.istKrankmeldung(event.title)) {
                krank += event
                continue
            }
            // Only match first matching definition per event
            val definition = schichtDefinitionen.firstOrNull { it.matchesKeywords(event.title) }
            if (definition != null) {
                matches.add(
                    ShiftMatch(
                        shiftDefinition = definition,
                        calendarEvent = event,
                        calculatedAlarmTime = calculateAlarmTime(event, definition)
                    )
                )
                Logger.i(LogTags.SHIFT_RECOGNITION, "✅ MATCH found: '${event.title}' matches '${definition.name}' with keywords ${definition.keywords}")
            } else if (ignorierDefinitionen.any { it.matchesKeywords(event.title) }) {
                // #167 ERST NACH der Schicht-Suche, nicht wie Krank davor: ein Ignorieren-Typ
                // verdraengt nie einen echten Treffer. „F TB“ mit Dienst F und Ignorieren TB ist
                // Schicht F, egal in welcher Reihenfolge die Typen stehen - im Zweifel wecken.
                ignoriert += event
            }
        }

        Logger.i(LogTags.SHIFT_RECOGNITION, "Recognition complete: Found ${matches.size} shifts, ${krank.size} Krank-Termine, ${ignoriert.size} ignoriert")
        return Erkennung(matches.sortedBy { it.calculatedAlarmTime }, krank, ignoriert)
    }
    
    private fun calculateAlarmTime(event: CalendarEvent, definition: ShiftDefinition): LocalDateTime {
        val shiftStartTime = event.startTime
        val alarmTime = definition.getAlarmLocalTime()

        // Calculate alarm time on the same date as the shift
        val alarmDateTime = LocalDateTime.of(
            shiftStartTime.toLocalDate(),
            alarmTime
        )

        // Liegt die Weckzeit nach Schichtbeginn, koennte es eine Nachtschicht sein (Weckzeit
        // kurz vor Mitternacht, Schicht beginnt kurz nach Mitternacht) - dann einen Tag
        // zurueckrechnen. Die Vorlaufzeit NACH diesem Abzug muss aber plausibel bleiben (<=
        // MAX_NIGHT_SHIFT_LEAD_TIME), sonst wuerde z.B. eine Weckzeit nur 5min nach
        // Schichtbeginn (Konfigurationsfehler oder knapp getakteter Fall) faelschlich einen
        // Tag zu frueh wecken. Wichtig: die Grenze gilt fuer die Vorlaufzeit NACH dem
        // Tagesabzug, nicht fuer die rohe Differenz am selben Kalendertag - beim echten
        // Mitternachts-Fall (Schicht 00:30, Weckzeit 23:30 Vortag) betraegt die rohe Differenz
        // ~23h, die tatsaechliche Vorlaufzeit nach Abzug aber nur 1h.
        //
        // GANZTAEGIGE TERMINE SIND AUSGENOMMEN: sie haben keinen Schichtbeginn (00:00 ist nur der
        // Anker des Kalendertags), die Heuristik schluege sonst bei jeder Weckzeit ab 12:00 zu.
        // `ShiftRecognitionEngineTest` haelt beide Seiten fest.
        // Hergang: Skill cfalarm-kalender-und-schichten, reference/kalender-datenfluss.md.
        if (!event.isAllDay && alarmDateTime.isAfter(shiftStartTime)) {
            val previousDayAlarm = alarmDateTime.minusDays(1)
            if (Duration.between(previousDayAlarm, shiftStartTime) <= MAX_NIGHT_SHIFT_LEAD_TIME) {
                return previousDayAlarm
            }
        }
        return alarmDateTime
    }
}
