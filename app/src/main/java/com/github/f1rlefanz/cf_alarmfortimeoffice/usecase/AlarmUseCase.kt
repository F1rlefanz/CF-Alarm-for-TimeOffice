package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.AlarmConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet dem Aufrufer, dass [AlarmUseCase.scheduleSystemAlarm] den Alarm NICHT armiert hat, weil
 * er als "naechsten Alarm ueberspringen" markiert ist.
 *
 * WARUM EIN FEHLER UND KEIN ERFOLG: Der Skip-Backstop kehrte frueher einfach zurueck, der Aufrufer
 * bekam `Result.success(Unit)` - "abgewiesen" sah aus wie "armiert". Wer daraufhin eine
 * Erfolgsmeldung anzeigt, behauptet einen Wecker, den es im AlarmManager nicht gibt. Genau das ist
 * ueber die manuellen Wecker passiert, deren ID ein reiner Hash aus Datum und Schicht ist.
 *
 * Aufrufer, die ein Re-Arming ueber den GESAMTEN Bestand fahren (BootReceiver, Delta-Sync), werten
 * das Ergebnis entweder gar nicht aus oder haben ein vorgelagertes Skip-Gate - fuer sie aendert
 * sich nichts ausser einem ehrlicheren Log.
 */
class SkippedAlarmNotArmedException(
    val alarmId: Int,
    val shiftName: String
) : Exception(
    "Alarm $alarmId ($shiftName) ist als uebersprungen markiert und wurde deshalb nicht gestellt"
)

/**
 * UseCase für alle Alarm-bezogenen Operationen - implementiert IAlarmUseCase
 *
 * REFACTORED:
 * ✅ Implementiert IAlarmUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interfaces statt konkrete Implementierungen
 * ✅ Erweiterte Business Logic für Event-zu-Alarm Transformation
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Integration mit ShiftRecognitionEngine für intelligente Alarm-Erstellung
 */
@Singleton
class AlarmUseCase @Inject constructor(
    private val alarmRepository: IAlarmRepository,
    private val alarmManagerService: AlarmManagerService,
    private val shiftConfigRepository: IShiftConfigRepository,
    private val shiftRecognitionEngine: ShiftRecognitionEngine,
    private val alarmSkipUseCase: IAlarmSkipUseCase,
    // Feature B: Schicht-Aenderungs-Notification. Bewusst auf der Implementierung, nicht auf
    // IAlarmUseCase - vermeidet Aenderungen an allen 4 Call-Sites des Interfaces.
    private val shiftChangeNotifier: ShiftChangeNotifier,
    // Master-Pause: aus demselben Grund auf der Implementierung, nicht auf IAlarmUseCase.
    private val masterPausePrefs: MasterPausePrefs,
    // Schichtspannen: ebenfalls bewusst auf der Implementierung statt auf IAlarmUseCase - das
    // Interface bleibt unveraendert, und jeder kuenftige syncAlarms()-Aufrufer schreibt die
    // Spannen automatisch mit, ohne selbst etwas tun zu muessen (gleiche Ueberlegung wie beim
    // ShiftChangeNotifier und beim Master-Pause-Backstop).
    private val shiftSpanStore: ShiftSpanStore
) : IAlarmUseCase {
    
    /**
     * REACTIVE OPTIMIZATION: Direct repository StateFlow for immediate UI updates
     * 
     * FIXED: Replaced polling-based Flow with reactive StateFlow from repository
     * ✅ Eliminates 10-second delay for UI updates
     * ✅ Provides immediate reactivity when alarms change
     * ✅ Better performance (no unnecessary polling)
     * ✅ Follows reactive programming principles
     */
    override val activeAlarms: Flow<List<AlarmInfo>> = 
        alarmRepository.activeAlarms
            .distinctUntilChanged { old, new -> 
                // PERFORMANCE: Only emit when alarm list actually changes
                old.size == new.size && old.zip(new).all { (a, b) -> a.id == b.id && a.triggerTime == b.triggerTime }
            }
    
    /**
     * ORCHESTRATOR: Einziger Einstiegspunkt der Event→Alarm-Pipeline.
     *
     * Delta-Synchronisation statt "alles löschen + neu erstellen":
     * ✅ Erkennt gelöschte Events → löscht nur diese Alarme
     * ✅ Erkennt geänderte Events → aktualisiert nur diese Alarme
     * ✅ Erkennt neue Events → erstellt nur diese Alarme
     * ✅ Unveränderte Events → System-Alarm wird idempotent re-armed (Aufrufer schedulen nicht mehr selbst)
     *
     * Nebenläufigkeit: Ein einziger [alarmSyncMutex] serialisiert ALLE mutierenden
     * Set-Operationen (Sync, deleteAll, deleteAlarm). Ersetzt den früheren
     * check-then-act-`@Volatile`-Boolean (P1) samt "Skip → leere Liste", das der
     * Maintenance-Service als "nichts zu tun" fehldeutete.
     */
    private val alarmSyncMutex = Mutex()

    override suspend fun syncAlarms(
        events: List<CalendarEvent>,
        shiftConfig: ShiftConfig
    ): Result<List<AlarmInfo>> = withContext(Dispatchers.IO) {
        // Serialisiert konkurrierende Aufrufer, statt den zweiten mit leerer Liste abzuweisen.
        alarmSyncMutex.withLock {
            // Selbstheilung fuer das "Naechsten Alarm ueberspringen"-Flag: der eigentlich
            // vorgesehene Rueckmeldepfad (AlarmReceiver -> checkAndProcessSkip) ist fuer den
            // uebersprungenen Alarm strukturell unerreichbar, da dessen System-Alarm beim
            // Ueberspringen sofort geloescht wird und darum nie feuert. syncAlarms() ist der
            // einzige Einstiegspunkt der Event-Alarm-Pipeline (Vordergrund-Sync + 6h-Wartung) und
            // damit der richtige Ort, ein laengst verstrichenes Flag automatisch zu loeschen.
            alarmSkipUseCase.clearExpiredSkip()

            SafeExecutor.safeExecute("AlarmUseCase.syncAlarms") {
                // Master-Pause: zentraler Backstop, NICHT nur an den (aktuell fuenf) bekannten
                // Aufrufstellen (BootReceiver, AlarmMaintenanceService, CalendarViewModel,
                // ShiftViewModel, CalendarPreAlarmRefreshWorker) einzeln gaten. syncAlarms() ist
                // laut Klassenkommentar oben der "einzige Einstiegspunkt der Event→Alarm-Pipeline" -
                // am Fairphone real reproduziert: CalendarViewModel.createAlarmsFromLoadedEvents()
                // war beim ersten Bau dieses Features nicht gegated und hat nach einem Reboot mit
                // aktiver Master-Pause beim naechsten App-Start lautlos wieder 5 Alarme angelegt.
                // Ein Gate pro Aufrufer ist fehleranfaellig (genau das ist damit passiert); dieser
                // eine Gate an der gemeinsamen Stelle faengt JEDEN aktuellen UND kuenftigen Aufrufer.
                if (masterPausePrefs.pausedNow()) {
                    Logger.business(LogTags.ALARM, "⏸️ SYNC: Master-Pause aktiv - Alarme werden geraeumt, keine neuen erstellt")
                    // Ausdruecklicher Nutzer-Wille -> auch ein schwebender Snooze muss weg
                    // (siehe alsoCancelPendingSnoozes).
                    clearInternalAlarms(alsoCancelPendingSnoozes = true)
                    return@safeExecute emptyList()
                }

                if (!shiftConfig.autoAlarmEnabled) {
                    Logger.d(LogTags.ALARM, "Auto-alarm disabled, not creating alarms")
                    clearInternalAlarms(alsoCancelPendingSnoozes = true)
                    return@safeExecute emptyList()
                }
                
                if (events.isEmpty()) {
                    Logger.business(LogTags.ALARM, "✅ SYNC: No calendar events found - clearing calendar alarms")
                    // Keine Events → kalenderbasierte Alarme weg, manuelle bleiben (siehe
                    // keepManualAlarms). Zurueckgegeben wird der VERBLIEBENE Bestand, nicht
                    // emptyList: der Aufrufer protokolliert die Zahl, und "0" waere hier unwahr.
                    persistShiftSpans(emptyList())
                    return@safeExecute clearInternalAlarms(keepManualAlarms = true)
                }
                
                Logger.business(LogTags.ALARM, "🔄 SYNC: Starting intelligent alarm synchronization for ${events.size} events")
                
                // 🔧 SYNC-FIX: INTELLIGENT SYNCHRONIZATION statt blind clearing
                val existingAlarms = alarmRepository.getAllAlarms().getOrNull() ?: emptyList()
                // Feature B: die allererste Befuellung (z.B. nach Neuinstallation/komplettem
                // Zuruecksetzen) soll nicht fuer jeden neuen Alarm eine "Neue Schicht erkannt"-Flut
                // ausloesen - siehe notifyCreated-Aufruf unten.
                val isFirstSync = existingAlarms.isEmpty()
                val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(events)
                
                if (shiftMatches.isEmpty()) {
                    Logger.business(LogTags.ALARM, "✅ SYNC: No matching shifts found - clearing calendar alarms")
                    persistShiftSpans(emptyList())
                    return@safeExecute clearInternalAlarms(keepManualAlarms = true)
                }

                // Schichtspannen VOR dem Vergangenheits-Filter unten schreiben - genau die
                // Schichten, die der Alarm-Bestand nach dem Klingeln nicht mehr hergibt, sind hier
                // noch vollstaendig da. Siehe ShiftSpanStore fuer das Warum.
                persistShiftSpans(
                    shiftMatches.map { match ->
                        ShiftSpan(
                            shiftName = match.shiftDefinition.name,
                            startTime = match.calendarEvent.startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            endTime = match.calendarEvent.endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            alarmTriggerTime = match.calculatedAlarmTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        )
                    }
                )

                // Build checksum map for events
                val eventChecksumMap = events.associate { event ->
                    event.id to calculateEventChecksum(event)
                }
                
                // Build map of new alarms we want to create
                val newAlarmsMap = mutableMapOf<String, AlarmInfo>()  // eventId -> AlarmInfo
                // Events, deren Termin WEITER EXISTIERT und deren Weckzeit lediglich verstrichen
                // ist. Ohne diese Unterscheidung meldet der Loeschzweig unten sie als "Event was
                // deleted from calendar" - der Nutzer bekam dadurch an JEDEM Schichtmorgen eine
                // sachlich falsche "Schicht entfernt"-Benachrichtigung fuer den Dienst, den er
                // gerade antritt.
                val expiredEventIds = mutableSetOf<String>()
                val now = LocalDateTime.now()

                for (shiftMatch in shiftMatches) {
                    try {
                        if (shiftMatch.calculatedAlarmTime.isBefore(now)) {
                            Logger.w(LogTags.ALARM, "⏰ SYNC: Skipping alarm in the past: ${shiftMatch.shiftDefinition.name}")
                            expiredEventIds += shiftMatch.calendarEvent.id
                            continue
                        }

                        val eventId = shiftMatch.calendarEvent.id
                        val checksum = eventChecksumMap[eventId] ?: ""
                        val alarmInfo = createAlarmFromShiftMatch(shiftMatch, eventId, checksum)
                        newAlarmsMap[eventId] = alarmInfo
                    } catch (e: Exception) {
                        Logger.e(LogTags.ALARM, "❌ SYNC: Error processing shift: ${shiftMatch.shiftDefinition.name}", e)
                    }
                }
                
                // 🔧 SYNC-FIX Step 1: Delete alarms for events that no longer exist
                var deletedCount = 0
                for (existingAlarm in existingAlarms) {
                    if (existingAlarm.eventId.isNotEmpty() && !newAlarmsMap.containsKey(existingAlarm.eventId)) {
                        // ZWEI verschiedene Gruende, hier zu landen - und nur EINER davon ist eine
                        // entfernte Schicht:
                        //  1. Der Termin ist wirklich aus dem Kalender verschwunden.
                        //  2. Der Termin laeuft weiter, nur die WECKZEIT ist verstrichen (der
                        //     Wecker hat heute frueh geklingelt). Der Alarm wird trotzdem geraeumt
                        //     - ein abgelaufener Alarm gehoert nicht in den Bestand - aber es ist
                        //     KEINE Aenderung des Dienstplans, also gibt es dafuer auch keine
                        //     Meldung. Die Schichtspanne bleibt davon unberuehrt erhalten, damit
                        //     Dimmer und "Nicht stoeren" die laufende Schicht weiter kennen.
                        val onlyExpired = existingAlarm.eventId in expiredEventIds
                        if (onlyExpired) {
                            Logger.business(LogTags.ALARM, "⌛ SYNC: Weckzeit verstrichen, Termin laeuft weiter: ${existingAlarm.shiftName} (eventId: ${existingAlarm.eventId})")
                        } else {
                            Logger.business(LogTags.ALARM, "🗑️ SYNC: Deleting alarm for deleted event: ${existingAlarm.shiftName} (eventId: ${existingAlarm.eventId})")
                        }
                        // ERST cancellen, DANN loeschen - wie an allen anderen Loeschstellen
                        // (`deleteAlarm()`, `clearInternalAlarms()` Step 1, `AlarmSkipUseCase`).
                        // Umgekehrt gab es ein Fenster, in dem der Alarm im AlarmManager noch
                        // armiert war, aber weder Repository noch Direct-Boot-Spiegel ihn kannten:
                        // ALLE Cancel-Wege der App iterieren ueber den Repository-Bestand, es gibt
                        // also keinen zweiten Anker. Bricht die Sequenz dort ab (Prozess-Tod,
                        // DataStore-Fehler), ist der Wecker unsichtbar UND unabbrechbar - er feuert
                        // bis zum naechsten Geraete-Neustart, und ein Handy laeuft Wochen.
                        alarmManagerService.cancelSystemAlarm(existingAlarm.id)
                        alarmRepository.deleteAlarm(existingAlarm.id).getOrThrow()
                        deletedCount++
                        // Feature B: eigenes try/catch - eine fehlgeschlagene Notification darf die
                        // eigentlich kritische Alarm-Loeschung nie mit rueckgaengig machen.
                        if (!onlyExpired) {
                            try {
                                shiftChangeNotifier.notifyDeleted(existingAlarm)
                            } catch (e: Exception) {
                                Logger.w(LogTags.ALARM, "Schicht-Aenderungs-Notification (Delete) fehlgeschlagen", e)
                            }
                        }
                    }
                }
                
                // 🔧 SYNC-FIX Step 2: Update changed alarms & create new ones
                var updatedCount = 0
                var createdCount = 0
                var skippedCount = 0
                val resultAlarms = mutableListOf<AlarmInfo>()

                // "Naechsten Alarm ueberspringen": EINMAL pro Sync lesen. Fail-safe wie der
                // Silent-/Skip-Check im AlarmReceiver - schlaegt der Lesevorgang fehl (getOrNull =
                // null), gilt NICHTS als uebersprungen und der Alarm wird ganz normal gestellt.
                // Im Zweifel wecken.
                val skippedAlarmId = alarmSkipUseCase.getSkipStatus().getOrNull()
                    ?.takeIf { it.isNextAlarmSkipped }
                    ?.skippedAlarmId

                for ((eventId, newAlarm) in newAlarmsMap) {
                  // Pro Event ein eigenes try/catch: ein einzelner abgelehnter Alarm (z.B.
                  // AlarmRepository.saveAlarm lehnt eine inzwischen verstrichene Weckzeit ab, weil
                  // `now` einmal oben gelesen wurde und die DataStore-Schreibvorgaenge der vorherigen
                  // Events Zeit gekostet haben) hat sonst den GESAMTEN Delta-Sync abgebrochen: der
                  // schon geloeschte Alarm blieb geloescht und alle noch nicht abgearbeiteten
                  // Eintraege der (unsortierten) Map wurden weder erstellt noch re-armed.
                  try {
                    // SKIP-IMMEDIATE-UX: Der uebersprungene Alarm darf hier NICHT wieder auftauchen.
                    // AlarmSkipUseCase.skipNextAlarm() cancelt den System-Alarm sofort und loescht den
                    // Alarm aus dem Repository - fuer den naechsten Sync sah dessen Kalender-Event
                    // damit wie ein NEUES Event aus: System-Alarm wieder scharf UND eine falsche
                    // "Neue Schicht erkannt"-Notification, obwohl der Nutzer den Wecker gerade
                    // abgeschaltet hatte. Das Flag laeuft weiterhin ZEITBASIERT ab
                    // (clearExpiredSkip oben), nicht ueber diesen Zweig.
                    if (skippedAlarmId != null && newAlarm.id == skippedAlarmId) {
                        Logger.business(
                            LogTags.ALARM,
                            "⏭️ SYNC: Uebersprungener Alarm wird nicht neu gestellt: ${newAlarm.shiftName} (id=${newAlarm.id})"
                        )
                        continue
                    }

                    val existingAlarm = existingAlarms.find { it.eventId == eventId }

                    if (existingAlarm != null) {
                        // Alarm exists - check if anything about it changed. Voller Vergleich statt
                        // nur eventChecksum/triggerTime: sonst bleibt newAlarm (frisch aus der
                        // aktuellen ShiftDefinition berechnet, z.B. isSilent/shiftName) unpersistiert,
                        // wenn sich nur ein reines ShiftDefinition-Feld aendert, aber das zugrunde
                        // liegende Kalender-Event gleich bleibt (Checksum+Weckzeit identisch).
                        if (existingAlarm != newAlarm) {
                            // Alarm-Daten geaendert → aktualisieren
                            Logger.business(LogTags.ALARM, "🔄 SYNC: Updating changed alarm: ${newAlarm.shiftName} (eventId: $eventId)")
                            
                            // Delete old - ERST cancellen, DANN loeschen, genau wie im
                            // Loeschzweig oben und an jeder anderen Loeschstelle. Umgekehrt gab
                            // es hier ein Fenster: nach `deleteAlarm()` kennen weder Repository
                            // noch Direct-Boot-Spiegel den Alarm, waehrend er im AlarmManager
                            // noch scharf steht. Stirbt der Prozess dort (Low-Memory-Kill des
                            // kurzlebigen Wartungs-Service, Force-Stop, Akku leer), erreicht ihn
                            // kein Cancel-Weg mehr - alle iterieren ueber den Repository-Bestand.
                            // Wird der Termin danach aus dem Kalender gestrichen, vergibt der
                            // Sync die ID nie wieder, und der Waise klingelt an einem freien Tag
                            // bis zum naechsten Geraete-Neustart.
                            alarmManagerService.cancelSystemAlarm(existingAlarm.id)
                            alarmRepository.deleteAlarm(existingAlarm.id).getOrThrow()

                            // Create new with updated data
                            alarmRepository.saveAlarm(newAlarm).getOrThrow()
                            scheduleSystemAlarm(newAlarm).getOrThrow()
                            resultAlarms.add(newAlarm)
                            updatedCount++
                            // Feature B: eigenes try/catch - eine fehlgeschlagene Notification darf
                            // die eigentlich kritische Alarm-Aktualisierung nie beeintraechtigen.
                            // Die Schwelle (>=10min Delta ODER Namensaenderung) entscheidet
                            // ShiftChangeNotifier.notifyUpdated selbst (siehe exceedsThreshold).
                            try {
                                shiftChangeNotifier.notifyUpdated(existingAlarm, newAlarm)
                            } catch (e: Exception) {
                                Logger.w(LogTags.ALARM, "Schicht-Aenderungs-Notification (Update) fehlgeschlagen", e)
                            }
                        } else {
                            // Wirklich unveraendert - System-Alarm idempotent re-armen, damit der
                            // Aufrufer nicht mehr selbst schedulen muss (kein Doppel-Scheduling).
                            Logger.d(LogTags.ALARM, "✅ SYNC: Alarm unchanged, re-arming system alarm: ${existingAlarm.shiftName}")
                            scheduleSystemAlarm(existingAlarm).getOrThrow()
                            resultAlarms.add(existingAlarm)
                        }
                    } else {
                        // New event → create alarm
                        Logger.business(LogTags.ALARM, "➕ SYNC: Creating alarm for new event: ${newAlarm.shiftName} (eventId: $eventId)")
                        alarmRepository.saveAlarm(newAlarm).getOrThrow()
                        scheduleSystemAlarm(newAlarm).getOrThrow()
                        resultAlarms.add(newAlarm)
                        createdCount++
                        // Feature B: erster Sync ueberhaupt (existingAlarms war leer) flutet nicht -
                        // eigenes try/catch, eine fehlgeschlagene Notification darf die eigentlich
                        // kritische Alarm-Erstellung nie beeintraechtigen.
                        if (!isFirstSync) {
                            try {
                                shiftChangeNotifier.notifyCreated(newAlarm)
                            } catch (e: Exception) {
                                Logger.w(LogTags.ALARM, "Schicht-Aenderungs-Notification (Create) fehlgeschlagen", e)
                            }
                        }
                    }
                  } catch (e: CancellationException) {
                    // MUSS VOR dem generischen catch stehen: CancellationException ist eine
                    // Exception, aber KEIN Event-Fehler. Wird syncAlarms() gecancelt (z.B.
                    // AlarmMaintenanceService.onDestroy() -> serviceScope.cancel(), oder ein
                    // beendeter viewModelScope), wirft ab diesem Moment JEDER suspendierende
                    // Aufruf im Schleifenkoerper sofort - ohne dieses Weiterwerfen lief die
                    // Schleife stur bis zum Ende, loggte pro Event einen "uebersprungen"-Fehler
                    // und meldete anschliessend "complete" mit einer unvollstaendigen Liste.
                    // Der Aufrufer stempelte darauf saveMaintenanceTime() und die Statusanzeige
                    // behauptete einen erfolgreichen Sync, obwohl gar nichts geschrieben wurde.
                    throw e
                  } catch (e: Exception) {
                    // Nur DIESES Event ist gescheitert - der Rest des Delta-Syncs laeuft weiter.
                    skippedCount++
                    Logger.e(
                        LogTags.ALARM,
                        "❌ SYNC: Alarm fuer Event $eventId (${newAlarm.shiftName}) uebersprungen - Rest des Syncs laeuft weiter",
                        e
                    )
                  }
                }

                // "complete" nur, wenn wirklich alles durchlief - sonst behauptet die Abschlusszeile
                // einen vollstaendigen Sync, den es nicht gab (die einzelnen Fehlerzeilen darueber
                // sind in Release-Builds zwar sichtbar, aber leicht zu uebersehen).
                Logger.business(
                    LogTags.ALARM,
                    (if (skippedCount == 0) {
                        "✅ SYNC: Intelligent synchronization complete - "
                    } else {
                        "⚠️ SYNC: Intelligent synchronization UNVOLLSTAENDIG ($skippedCount Event(s) uebersprungen) - "
                    }) +
                    "Created: $createdCount, Updated: $updatedCount, Deleted: $deletedCount, " +
                    "Total: ${resultAlarms.size} alarms"
                )
                
                resultAlarms
            }
        }
    }

    /**
     * 🔧 SYNC-FIX: Calculate event checksum for change detection
     */
    private fun calculateEventChecksum(event: CalendarEvent): String {
        // Simple checksum: hash of critical fields
        val data = "${event.startTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   "${event.endTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   event.title
        return data.hashCode().toString()
    }
    
    override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> = 
        alarmRepository.saveAlarm(alarmInfo)
    
    
    /**
     * Internes Clearing (System-Alarme + Repository). Kein eigener Guard: läuft immer
     * unter [alarmSyncMutex] (aufgerufen aus [syncAlarms], [deleteAllAlarms], Legacy-Pfad).
     *
     * [alsoCancelPendingSnoozes] NUR bei ausdruecklichem Nutzer-Willen setzen (Master-Pause,
     * "Automatische Alarme aus", [deleteAllAlarms]). Der Snooze liegt bewusst in einem eigenen
     * PendingIntent-Slot, damit ihn der Maintenance-Sync NICHT mit abraeumt (siehe
     * AlarmManagerService.snoozeAlarmAction) - genau deshalb darf er in datengetriebenen
     * Aufraeumzweigen ("Kalender liefert gerade keine Events / keine passende Schicht", direkt
     * nach dem Boot oder ohne Netz der Normalfall) auf keinen Fall mitgeloescht werden. Sonst
     * haette der Nutzer "5 Minuten schlummern" gedrueckt und wuerde nie wieder geweckt.
     */
    /**
     * @param keepManualAlarms Manuelle Alarme (leere `eventId`) NICHT anfassen. Pflicht in den
     *   DATENGETRIEBENEN Zweigen ("keine Events" / "keine passende Schicht"): dort geht es um
     *   Kalenderinhalte, und ein manuell gestellter Wecker hat damit nichts zu tun. Der
     *   Delta-Pfad direkt darunter schont sie ausdruecklich (`eventId.isNotEmpty()`) und
     *   `CalendarViewModel` sichert es im Kommentar zu - die beiden Abkuerzungs-Zweige umgingen
     *   diese Zusicherung komplett und loeschten sie mit. Ausgerechnet der manuelle Alarm ist der
     *   EINZIGE, der sich nicht aus dem Kalender rekonstruieren laesst: er kam nie wieder, und im
     *   Log stand "No matching shifts found - clearing all alarms", was wie Normalbetrieb klingt.
     *   `false` bleibt richtig fuer die AUSDRUECKLICHEN Abschaltungen (Master-Pause,
     *   "Automatische Alarme aus", [deleteAllAlarms]): dort will der Nutzer Stille, und der
     *   Direct-Boot-Spiegel muss wirklich leer werden.
     */
    private suspend fun clearInternalAlarms(
        alsoCancelPendingSnoozes: Boolean = false,
        keepManualAlarms: Boolean = false
    ): List<AlarmInfo> {
        Logger.d(LogTags.ALARM, "🧹 INTERNAL-CLEAR: Fast internal clearing (system + repository)")

        // Step 1: Cancel system alarms
        //
        // BEWUSST getAllAlarms() und NICHT activeAlarms.first(): activeAlarms ist ein StateFlow,
        // dessen Startwert emptyList() ist, bis der asynchrone Init-Load des Repositories
        // zurueckkommt - first() liefert genau diesen leeren Wert sofort, ohne zu warten. In einem
        // frisch gestarteten Prozess (Wartungs-Service/Worker/Boot) wurde deshalb KEIN System-Alarm
        // gecancelt, waehrend deleteAllAlarms() gleich danach Repository UND Direct-Boot-Spiegel
        // leerraeumte: der verwaiste AlarmManager-Eintrag feuerte spaeter trotzdem - bei aktiver
        // Master-Pause klingelte der Wecker also trotz Pause. getAllAlarms() wartet auf den
        // Init-Load (siehe AlarmRepository.awaitInitialLoad) und ist damit die einzige verlaessliche
        // Quelle fuer "welche Alarme kennt die App gerade".
        // getOrThrow, nicht getOrNull: laesst sich der Bestand gerade nicht lesen, darf NICHT
        // stillschweigend "nichts zu cancels" daraus werden - dann lieber laut scheitern (der
        // Aufrufer laeuft in safeExecute) und das Repository unangetastet lassen, statt es zu leeren
        // und armierte System-Alarme zurueckzulassen, von denen die App nichts mehr weiss.
        // ZUERST die Sperre pruefen - `getAllAlarms()` allein kann den Fall nicht melden.
        //
        // Der Kommentar unten sicherte "lieber laut scheitern" zu, konnte das aber nicht halten:
        // nach einem gescheiterten Init-Load steht der Cache auf einer leeren Liste, und
        // `getAllAlarms()` gibt genau die als ERFOLG heraus (die Sperre wird nur intern vermerkt).
        // `getOrThrow()` warf also nie, die Cancel-Schleife lief ins Leere - und `deleteAllAlarms()`
        // leerte Store UND Direct-Boot-Spiegel trotzdem, weil es bewusst mit `force = true`
        // schreibt. Ergebnis war exakt die Kombination, die hier ausgeschlossen sein sollte:
        // verwaiste, armierte System-Alarme, von denen die App nichts mehr weiss - bei aktiver
        // Master-Pause klingelt der Wecker dann trotz Pause.
        // GENAU EINE BEDEUTUNG WIRD HIER GEBRAUCHT: "der Bestand ist in diesem Prozess nicht
        // lesbar". Nur dann ist die Cancel-Schleife weiter unten wirkungslos, und nur dann ist das
        // Ueberspringen unten vertretbar. Ein gescheiterter SCHREIBvorgang (voller Speicher,
        // IOException) gehoert ausdruecklich NICHT hierher - der Bestand ist dabei vollstaendig
        // lesbar, die Schleife MUSS laufen. Diese beiden Lagen waren einmal zu einem Signal
        // verodert; die Master-Pause leerte dann Repository und Direct-Boot-Spiegel und liess alle
        // System-Alarme armiert zurueck, wo sie trotz Pause feuerten und ohne Bestandsliste durch
        // nichts mehr abbrechbar waren. Wer hier eine weitere Bedingung ergaenzen will, muss zuerst
        // beantworten, ob sie den Bestand UNLESBAR macht.
        if (alarmRepository.isPersistenceBlocked()) {
            // GESPERRT - aber die Reaktion haengt davon ab, WARUM geraeumt wird.
            //
            // Der erste Wurf dieses Waechters stand vor ALLEM und blockierte damit auch die zwei
            // Schritte, die den unlesbaren Bestand gar nicht brauchen: `cancelAllSnoozes()` liest
            // seinen eigenen Merker im Device-Protected-Storage, und `deleteAllAlarms()` ist die
            // ausdrueckliche, dokumentierte Ausnahme von der Sperre (`force = true`) - ohne sie
            // re-armt der Direct-Boot-Restore genau die Alarme, die gerade abgeschaltet wurden.
            // Ergebnis war: die Master-Pause zeigte "pausiert", waehrend ein schwebender
            // Schlummer-Alarm scharf blieb - genau der Bug, gegen den `cancelAllSnoozes()` gebaut
            // wurde.
            if (keepManualAlarms) {
                // Datengetriebener Zweig ("keine Events" / "keine passende Schicht"): hier ist
                // Raeumen ohne Cancellen die gefaehrliche Kombination. Nichts anfassen, laut
                // scheitern - bestehende Alarme bleiben gesetzt.
                throw IllegalStateException(
                    "Alarm-Bestand ist in diesem Prozess nicht lesbar (Persistenz gesperrt) - es " +
                        "wird NICHT geraeumt. Sonst blieben armierte System-Alarme zurueck, die " +
                        "niemand mehr abbrechen kann. Rohdaten liegen als active_alarms_broken."
                )
            }

            // Ausdrueckliche Abschaltung (Master-Pause, "Automatische Alarme aus",
            // deleteAllAlarms): der Nutzer will Stille. Das Beste, was ohne Bestandsliste geht -
            // und deutlich besser als gar nichts.
            Logger.w(
                LogTags.ALARM,
                "⚠️ INTERNAL-CLEAR: Bestand nicht lesbar (Persistenz gesperrt). Schwebende Snoozes " +
                    "und der Direct-Boot-Spiegel werden trotzdem geraeumt - einzelne bereits " +
                    "armierte System-Alarme lassen sich ohne die Liste NICHT abbrechen und feuern " +
                    "bis zum naechsten Neustart. Rohdaten liegen als active_alarms_broken."
            )
            if (alsoCancelPendingSnoozes) {
                alarmManagerService.cancelAllSnoozes()
            }
            alarmRepository.deleteAllAlarms().getOrThrow()
            return emptyList()
        }

        val activeAlarmsList = alarmRepository.getAllAlarms().getOrThrow()
        val (kept, toRemove) = if (keepManualAlarms) {
            activeAlarmsList.partition { it.eventId.isEmpty() }
        } else {
            emptyList<AlarmInfo>() to activeAlarmsList
        }
        for (alarm in toRemove) {
            alarmManagerService.cancelSystemAlarm(alarm.id)
        }

        // Step 2: Schwebende Snooze-Alarme - eigener Slot, eigener Cancel-Weg. cancelSystemAlarm()
        // erreicht sie strukturell nicht, ein bereits gestellter Snooze lief deshalb bisher durch
        // JEDE App-seitige Abschaltung hindurch und klingelte mitten in der Pause.
        if (alsoCancelPendingSnoozes) {
            alarmManagerService.cancelAllSnoozes()
        }

        // Step 3: Clear repository
        if (kept.isEmpty()) {
            alarmRepository.deleteAllAlarms().getOrThrow()
        } else {
            // Einzeln loeschen, damit die manuellen Alarme im Repository UND im
            // Direct-Boot-Spiegel stehen bleiben - deleteAllAlarms() leert beides.
            for (alarm in toRemove) {
                alarmRepository.deleteAlarm(alarm.id).getOrThrow()
            }
            Logger.business(
                LogTags.ALARM,
                "🛟 INTERNAL-CLEAR: ${kept.size} manuelle(r) Alarm bleibt erhalten " +
                    "(${toRemove.size} kalenderbasierte entfernt)"
            )
        }

        Logger.d(LogTags.ALARM, "✅ INTERNAL-CLEAR: Fast clearing completed")
        return kept
    }

    override suspend fun deleteAlarm(alarmId: Int): Result<Unit> =
        SafeExecutor.safeExecute("AlarmUseCase.deleteAlarm") {
            // Serialisiert gegen syncAlarms/deleteAllAlarms über denselben Mutex.
            alarmSyncMutex.withLock {
                Logger.d(LogTags.ALARM, "🧹 ATOMIC-SINGLE: Deleting single alarm ID=$alarmId")

                // Cancel system alarm first, then repository
                cancelSystemAlarm(alarmId).getOrThrow()
                alarmRepository.deleteAlarm(alarmId).getOrThrow()

                Logger.d(LogTags.ALARM, "✅ ATOMIC-SINGLE: Alarm $alarmId deleted successfully")
            }
        }

    override suspend fun deleteAllAlarms(): Result<Unit> =
        SafeExecutor.safeExecute("AlarmUseCase.deleteAllAlarms") {
            // Serialisiert gegen syncAlarms/deleteAlarm über denselben Mutex.
            alarmSyncMutex.withLock {
                // "Alles loeschen" ist immer ausdruecklicher Nutzer-Wille (Master-Pause,
                // Wecker-Tab-Schalter) - ein schwebender Snooze muss dabei mit weg.
                clearInternalAlarms(alsoCancelPendingSnoozes = true)
            }
        }
    
    override suspend fun scheduleSystemAlarm(alarmInfo: AlarmInfo): Result<Unit> {
        // Zentraler Backstop fuer "Naechsten Alarm ueberspringen": diese Funktion ist der EINZIGE
        // Weg, auf dem ein Alarm (neu, geaendert, unveraendert-re-armed, Boot-Restore) im
        // AlarmManager landet. Ein Gate pro Aufrufer waere genauso fehleranfaellig wie beim
        // Master-Pause-Backstop in syncAlarms() - BootReceiver re-armt z.B. alle gespeicherten
        // Alarme direkt hierueber.
        // Fail-safe: schlaegt der Lesevorgang fehl (getOrNull = null), gilt der Alarm als NICHT
        // uebersprungen und wird gestellt. Im Zweifel wecken.
        //
        // WARUM DER BACKSTOP AUSSERHALB VON safeExecute STEHT UND EINEN FEHLER MELDET:
        // Frueher verliess er den Block per `return@safeExecute` und lieferte damit
        // `Result.success(Unit)` - "abgewiesen" war fuer den Aufrufer nicht von "armiert" zu
        // unterscheiden. Das kostete real einen Wecker: die ID eines manuellen Weckers ist ein
        // reiner Hash aus Datum und Schicht, wer denselben Wecker nach dem Ueberspringen neu
        // anlegt, trifft exakt den Skip-Merker. Der Eintrag wurde gespeichert, die Karte meldete
        // "Manueller Alarm aktiv" mit Uhrzeit - im AlarmManager stand nichts. Ein stummer Wecker
        // MIT Anzeige ist die gefaehrlichste Variante, also muss der Aufrufer das sehen koennen.
        // Der Skip-Merker selbst bleibt unangetastet: er laeuft weiterhin ZEITBASIERT ab.
        val skipState = alarmSkipUseCase.getSkipStatus().getOrNull()
        if (skipState?.isNextAlarmSkipped == true && skipState.skippedAlarmId == alarmInfo.id) {
            Logger.business(
                LogTags.ALARM,
                "⏭️ SCHEDULE: Alarm ${alarmInfo.id} (${alarmInfo.shiftName}) ist als uebersprungen markiert - kein System-Alarm"
            )
            return Result.failure(SkippedAlarmNotArmedException(alarmInfo.id, alarmInfo.shiftName))
        }

        return SafeExecutor.safeExecute("AlarmUseCase.scheduleSystemAlarm") {
            // Create dummy ShiftMatch for AlarmManagerService compatibility
            val shiftDefinition = ShiftDefinition(
                id = alarmInfo.shiftId,
                name = alarmInfo.shiftName,
                keywords = listOf(),
                alarmTime = LocalTime.of(AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE), // Default
                isEnabled = true
            )

            // shiftStartTime (echter Kalender-Schichtbeginn) statt triggerTime (Weckzeit) fuer
            // die synthetische CalendarEvent.startTime - sonst zeigt "Deine Schicht beginnt um"
            // nach JEDEM Re-Arming (Sync/Boot/Maintenance - der eigentliche, staendig genutzte
            // Weg ueber diese Funktion, nicht die einmalige Erstplanung) wieder die Weckzeit
            // statt des Schichtbeginns. 0 = unbekannt (z.B. manueller Alarm ohne echte Schicht) -
            // dann bewusst wie bisher auf die Weckzeit zurueckfallen.
            val shiftStartMillis = alarmInfo.shiftStartTime.takeIf { it > 0 } ?: alarmInfo.triggerTime
            val shiftEndMillis = alarmInfo.shiftEndTime.takeIf { it > 0 }
                ?: (alarmInfo.triggerTime + CalendarConstants.DEFAULT_EVENT_DURATION_MS)

            val calendarEvent = CalendarEvent(
                id = alarmInfo.id.toString(), // Convert Int to String
                title = alarmInfo.shiftName,
                startTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(shiftStartMillis),
                    ZoneId.systemDefault()
                ),
                endTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(shiftEndMillis),
                    ZoneId.systemDefault()
                ),
                calendarId = ""
            )
            
            val shiftMatch = ShiftMatch(
                shiftDefinition = shiftDefinition,
                calendarEvent = calendarEvent,
                calculatedAlarmTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(alarmInfo.triggerTime),
                    ZoneId.systemDefault()
                )
            )
            
            val config = shiftConfigRepository.getCurrentShiftConfig().getOrThrow()
            alarmManagerService.setAlarmFromShiftMatch(shiftMatch, config.autoAlarmEnabled, alarmInfo.id)
        }
    }

    override suspend fun cancelSystemAlarm(alarmId: Int): Result<Unit> =
        SafeExecutor.safeExecute("AlarmUseCase.cancelSystemAlarm") {
            alarmManagerService.cancelSystemAlarm(alarmId)
        }
    
    override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = 
        alarmRepository.getAllAlarms()
    
    /**
     * Erstellt AlarmInfo aus ShiftMatch mit Event-Tracking
     */
    /**
     * Schreibt den Schichtspannen-Bestand ([ShiftSpanStore]) - die Quelle, aus der Dimmer und
     * "Nicht stoeren" ihre Dienstzeit-Fenster beziehen, seit klar ist, dass der Alarm-Bestand die
     * Weckzeit nicht ueberlebt.
     *
     * **Eigenes try/catch, bewusst nicht-fatal.** Dieselbe Haltung wie bei den drei
     * [ShiftChangeNotifier]-Aufrufen: ein fehlgeschlagener Nebenschauplatz darf die eigentlich
     * kritische Alarm-Synchronisation niemals abbrechen oder rueckgaengig machen. Im
     * Fehlerfall behalten Dimmer/DND ihren letzten bekannten Stand - schlechter als frisch, aber
     * unendlich besser als ein ausgefallener Wecker.
     *
     * [CancellationException] wird weitergeworfen: sie ist kein Schreibfehler, sondern die Ansage,
     * dass die umgebende Coroutine endet.
     */
    private suspend fun persistShiftSpans(spans: List<ShiftSpan>) {
        try {
            shiftSpanStore.replaceAll(spans)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Schichtspannen konnten nicht gespeichert werden - Dimmer/DND behalten den letzten Stand", e)
        }
    }

    private fun createAlarmFromShiftMatch(shiftMatch: ShiftMatch, eventId: String, eventChecksum: String): AlarmInfo {
        val alarmTime = shiftMatch.calculatedAlarmTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        
        return AlarmInfo(
            id = shiftMatch.calendarEvent.id.hashCode(), // Convert String ID to Int
            shiftId = shiftMatch.shiftDefinition.id,
            shiftName = shiftMatch.shiftDefinition.name,
            triggerTime = alarmTime,
            formattedTime = formatAlarmTime(alarmTime),
            eventId = eventId,
            eventChecksum = eventChecksum,
            shiftEndTime = shiftMatch.calendarEvent.endTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
            shiftStartTime = shiftMatch.calendarEvent.startTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
            isSilent = shiftMatch.shiftDefinition.isSilent
        )
    }
    
    /**
     * Formatiert Alarm-Zeit für Anzeige - public für ViewModel-Zugriff
     */
    fun formatAlarmTime(timeInMillis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(timeInMillis)
        val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME).format(localDateTime)
    }
    
}
