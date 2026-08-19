package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
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
 * @param recognitionDispatcher der Dispatcher, auf dem die Erkennung wirklich rechnet - siehe
 *        [getAllMatchingShifts]. Per Vorgabe [Dispatchers.Default]; der Parameter existiert, damit
 *        Tests die Erkennung im virtuellen Zeitplan halten koennen (die Nebenlaeufigkeits-Tests um
 *        Mutex und Epoche brauchen eine deterministische Reihenfolge).
 */
class ShiftRecognitionEngine(
    private val shiftConfigRepository: IShiftConfigRepository,
    private val recognitionDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    
    /**
     * Der komplette Cache-Stand in EINEM unveraenderlichen Objekt: Event-Schluessel, Ergebnis,
     * Veroeffentlichungszeit und die [cacheEpoch], unter der er entstanden ist.
     *
     * WARUM EIN OBJEKT UND NICHT DREI FELDER (Fix v1.22.2): Drei einzelne `@Volatile`-Felder haben
     * keine gemeinsame Atomizitaet - jeder Leser kann eine Mischung aus altem und neuem Stand
     * sehen. Eine einzige `@Volatile`-Referenz zu veroeffentlichen ist dagegen unteilbar: entweder
     * der Leser sieht den ganzen alten Stand oder den ganzen neuen.
     */
    private data class RecognitionCache(
        val eventsHash: Int,
        val matches: List<ShiftMatch>,
        val publishedAt: Long,
        val epoch: Int
    )

    /**
     * PERFORMANCE OPTIMIZATION: Enhanced recognition with intelligent caching
     * Caches results for identical event sets and prevents concurrent calls
     */
    @Volatile
    private var cache: RecognitionCache? = null

    /**
     * INVARIANTE (Fix v1.22.2): Der Zaehler, mit dem [clearRecognitionCache] eine Invalidierung
     * OHNE Mutex durchsetzen kann.
     *
     * [clearRecognitionCache] ist bewusst NICHT `suspend`: der einzige Aufrufer ist
     * `ShiftUseCase.invalidateAllCaches()`, eine normale (nicht-suspend) private Funktion. Ein
     * `suspend`-Umbau hier wuerde diese Signatur und damit fremde Dateien mitziehen - und der
     * Mutex allein wuerde das Problem gar nicht loesen (siehe unten).
     *
     * Das Problem ohne Epoche: ein Lauf haelt den Mutex und hat die ALTE Konfiguration gelesen;
     * mitten darin loescht der Nutzer per Speichern den Cache; danach veroeffentlicht der laufende
     * Lauf seinen ueberholten Stand samt frischem Zeitstempel - die Invalidierung ist verpufft und
     * der alte Stand gilt fuer die volle adaptive Cache-Dauer (2-30s) als Treffer. Genau darueber
     * behielt eine gerade deaktivierte Schicht ihren Wecker.
     *
     * Die Loesung: jeder veroeffentlichte Stand traegt die Epoche, unter der er ENTSTANDEN ist.
     * [clearRecognitionCache] zaehlt sie hoch (und zwar VOR dem Nullen des Standes). Ein Leser
     * akzeptiert nur einen Stand, dessen Epoche noch die aktuelle ist. Damit ist selbst ein
     * Lauf, der zwischen Pruefung und Schreiben ueberholt wird, harmlos: sein Stand ist mit der
     * alten Epoche gestempelt und wird von jedem Leser verworfen.
     */
    @Volatile
    private var cacheEpoch = 0
    @Volatile
    private var cacheHitCount = 0
    @Volatile
    private var configChangeCount = 0

    /**
     * INVARIANTE (Fix v1.22.2): Cache-Pruefung UND Cache-Veroeffentlichung liegen gemeinsam
     * hinter diesem Mutex. Der Mehrfeld-Cache (`lastRecognitionHash`/`cachedMatches`/
     * `lastCacheTime`) hat keine gemeinsame Atomizitaet - `@Volatile` schuetzt nur jedes Feld
     * einzeln. Vorher wurde `lastRecognitionHash` VOR der Erkennung gesetzt und `cachedMatches`
     * erst danach; ein nebenlaeufiger Aufrufer mit identischem Event-Hash traf in diesem Fenster
     * die Cache-Treffer-Bedingung und bekam den ALTEN Stand - im frischen Prozess bzw. direkt
     * nach `clearRecognitionCache()` eine LEERE Liste. `AlarmUseCase.syncAlarms()` versteht eine
     * leere Trefferliste als "keine Schichten" und loescht daraufhin ALLE Alarme.
     *
     * Es gibt mindestens drei voneinander unabhaengige Aufrufer dieser einen Singleton-Instanz
     * (`AlarmUseCase.syncAlarms()`, `ShiftUseCase.recognizeShiftsInEvents()`,
     * `AlarmMaintenanceService`), die letzten beiden ausserhalb jedes Alarm-Mutex - die
     * Ueberlappung ist der Normalfall, nicht der Ausnahmefall.
     *
     * Der Mutex deckt Pruefung und Veroeffentlichung ab, NICHT die Invalidierung: die laeuft ueber
     * [clearRecognitionCache] aus synchronem Kontext und kann den Mutex nicht nehmen. Dafuer sorgt
     * [cacheEpoch] - siehe dort.
     *
     * Der Mutex ersetzt die frueheren Felder `recognitionInProgress` + `MAX_CONCURRENT_WAIT_MS`
     * (Polling mit 200ms-Timeout, das "zur Sicherheit" trotzdem weiterlief und damit genau den
     * halbfertigen Zustand las, den es verhindern sollte). Wer hier wieder ein Boolean-Flag mit
     * Timeout einbaut, holt sich den Fehler zurueck.
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
     * PERFORMANCE: Optimized cache management with lifecycle callbacks
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
        
        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 CACHE-CLEAR: Clearing recognition cache before config update")
        Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 ADAPTIVE-CACHE-CLEAR: Recognition cache cleared due to configuration change (change #$configChangeCount)")
        Logger.d(LogTags.SHIFT_RECOGNITION, "✅ CACHE-CLEAR: Recognition cache cleared successfully")
    }
    
    suspend fun getAllMatchingShifts(events: List<CalendarEvent>): List<ShiftMatch> {
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
        // auf welchem Thread sie warten.
        //
        // Cache-Pruefung und -Veroeffentlichung liegen bewusst BEIDE im selben kritischen
        // Abschnitt - siehe Kommentar an `recognitionMutex`. Ein nebenlaeufiger Aufrufer wartet
        // hier auf das FERTIGE Ergebnis des ersten und bekommt es danach als Cache-Treffer,
        // statt einen halbfertigen Zwischenzustand zu lesen.
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
                        Logger.d(LogTags.SHIFT_RECOGNITION, "✅ ADAPTIVE-CACHE-HIT: Same events processed recently (${cacheAge}ms ago, validity=${adaptiveCacheValidity}ms), returning cached ${snapshot.matches.size} matches (hit #$cacheHitCount)")
                        return@withLock snapshot.matches
                    } else {
                        Logger.d(LogTags.SHIFT_RECOGNITION, "⏰ ADAPTIVE-CACHE-EXPIRED: Cache is ${cacheAge}ms old (validity=${adaptiveCacheValidity}ms), needs refresh")
                    }
                }

                val matches = performRecognition(events)

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
                    matches = matches,
                    publishedAt = System.currentTimeMillis(),
                    epoch = epochAtStart
                )
                if (cacheEpoch != epochAtStart) {
                    Logger.d(LogTags.SHIFT_RECOGNITION, "🔄 CACHE-STALE: Konfiguration wurde waehrend der Erkennung geaendert (Epoche $epochAtStart -> $cacheEpoch), Ergebnis wird nicht als frisch gecacht")
                }

                Logger.d(LogTags.SHIFT_RECOGNITION, "✅ ADAPTIVE-RECOGNITION: Completed with ${matches.size} matches (cache validity: ${getAdaptiveCacheValidity()}ms)")
                matches
            }
        }
    }
    
    private suspend fun performRecognition(events: List<CalendarEvent>): List<ShiftMatch> {
        // Ein gescheiterter Read darf NICHT zu "0 Definitionen" degradieren. Der echte
        // Repository-Pfad fuer eine vorhandene, aber nicht dekodierbare Konfiguration liefert
        // genau ein Result.failure (keine Exception) - mit `getOrNull() ?: emptyList()` wurde
        // daraus lautlos eine leere Trefferliste, und AlarmUseCase.syncAlarms() versteht "leer"
        // als "keine Schichten" und loescht ALLE Alarme. Fuer eine Wecker-App ist "leer" die
        // gefaehrlichste Luege (CLAUDE.md): sie ist nicht von "du hast frei" zu unterscheiden.
        // getOrThrow() reicht den Fehler an den Aufrufer durch; der Cache-Schluessel bleibt dabei
        // unangetastet (siehe getAllMatchingShifts), der naechste Versuch laeuft also frisch.
        val allDefinitions = shiftConfigRepository.getCurrentShiftConfig().getOrThrow().definitions

        // Der Schalter "Schichtdefinition aktiviert" (`ShiftDefinition.isEnabled`) muss die
        // Erkennung wirklich abschalten. Bis v1.22.1 las ihn NIEMAND ausser der Auswahl-UI
        // (AlarmViewModel-Liste, Hue-Regel-Editor) - die Erkennung lief ueber ALLE Definitionen.
        // Folge: eine deaktivierte Schicht verschwand aus den Auswahllisten, erzeugte aber
        // weiterhin Alarme und klingelte. Bewusst NUR hier gefiltert, NICHT in
        // `ShiftConfig.findDefinitionFor()`: dort wird ein BESTEHENDER Alarm einer Definition
        // zugeordnet (Hue-Regeln, stille Schicht) - ein Filter wuerde einem Alarm, der noch aus
        // der Zeit vor dem Deaktivieren stammt, seine Regeln entziehen.
        val shiftDefinitions = allDefinitions.filter { it.isEnabled }
        val matches = mutableListOf<ShiftMatch>()

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
            for (definition in shiftDefinitions) {
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
                }
            }
        }

        Logger.i(LogTags.SHIFT_RECOGNITION, "Recognition complete: Found ${matches.size} shifts")
        return matches.sortedBy { it.calculatedAlarmTime }
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
        // GANZTAEGIGE TERMINE SIND AUSGENOMMEN. Ein ganztaegiger Eintrag (Google: `start.date`
        // ohne Uhrzeit) hat gar keinen Schichtbeginn - die 00:00 sind nur der Anker des
        // Kalendertags. Es gibt also nichts, gegen das "danach" sinnvoll pruefbar waere, und die
        // Heuristik wuerde bei JEDER Weckzeit ab 12:00 zuschlagen (ab 00:00 gerechnet bleibt die
        // Vorlaufzeit nach dem Tagesabzug dann unter 12h). Die Standard-Spaetschicht 12:30 waere
        // dadurch einen ganzen Tag zu frueh geweckt worden, und am eigentlichen Schichttag haette
        // es gar keinen Wecker gegeben. Vor der korrigierten Ganztags-Umrechnung fiel das nicht
        // auf, weil dort UTC-Mitternacht stand (in Europe/Berlin 01:00/02:00) und die Grenze
        // damit zufaellig erst bei 13:00/14:00 lag - eine Zonenabhaengigkeit, die es nie geben
        // sollte. `ShiftRecognitionEngineTest` haelt beide Seiten fest: Ganztags weckt am Tag des
        // Termins, die echte zeitgebundene Nachtschicht (Beginn 00:30, Weckzeit 23:30) weiter am
        // Vortag.
        if (!event.isAllDay && alarmDateTime.isAfter(shiftStartTime)) {
            val previousDayAlarm = alarmDateTime.minusDays(1)
            if (Duration.between(previousDayAlarm, shiftStartTime) <= MAX_NIGHT_SHIFT_LEAD_TIME) {
                return previousDayAlarm
            }
        }
        return alarmDateTime
    }
}
