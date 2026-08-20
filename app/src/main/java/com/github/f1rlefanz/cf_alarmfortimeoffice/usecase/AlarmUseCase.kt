package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ShiftChangeNotifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SyncHorizonStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
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
    private val shiftSpanStore: ShiftSpanStore,
    // Bezugspunkt fuer "war diese Schicht beim letzten Sync ueberhaupt sichtbar?" - siehe
    // SyncHorizonStore. Aus demselben Grund wie die drei Abhaengigkeiten darueber bewusst auf der
    // Implementierung statt auf IAlarmUseCase.
    private val syncHorizonStore: SyncHorizonStore
) : IAlarmUseCase {

    companion object {
        /**
         * Ist [alarm] der Wecker, den der Nutzer ueberspringen wollte?
         *
         * ZWEI KRITERIEN, UND DAS ZWEITE IST DAS TRAGENDE:
         *  (a) gleiche `AlarmInfo.id` - das bisherige Verhalten. Bleibt drin, damit ein Merker,
         *      der VOR dieser Aenderung gesetzt wurde, weiter greift, und weil ein manuell
         *      gestellter Wecker seine id aus Datum und Schicht ableitet (stabil).
         *  (b) gleicher WECKZEITPUNKT. Der Grund: `AlarmSkipUseCase.skipNextAlarm()` LOESCHT den
         *      Eintrag aus dem Repository - "immer, fuer jede Alarmart". Beim naechsten Sync gibt
         *      es also gar keinen bestehenden Alarm, den die Paarung wiedererkennen koennte; hat
         *      der abonnierte Feed inzwischen neue Kennungen vergeben, baut der Sync den Wecker
         *      mit einer NEUEN id aus der neuen Kennung neu auf. Ein reiner id-Vergleich griffe
         *      dann ins Leere - der Nutzer drueckt abends "Ueberspringen", der Feed rotiert
         *      nachts, und am freien Morgen klingelt der Wecker doch. Genau das ist am Geraet
         *      belegt (11 Wecker in einem Lauf mit neuen Kennungen). Die id ist historienabhaengig,
         *      der Weckzeitpunkt ist der stabile Anker.
         *
         * WAS NICHT GEPRUEFT WIRD, UND WARUM NICHT: die Schicht. [AlarmSkipState] traegt sie nicht
         * (nur id, Weckzeitpunkt und - bei manuellen Weckern - einen Schnappschuss), und sie hier
         * nachzureichen hiesse, das Zustandsmodell samt Persistenz zu erweitern. Das Restrisiko
         * ist ein ZWEITER kalenderbasierter Wecker auf dieselbe Millisekunde: der bliebe
         * mit-uebersprungen. Es ist eng begrenzt (genau ein Weckzeitpunkt, und der Merker laeuft
         * mit ihm zeitbasiert ab, siehe `clearExpiredSkip`) und praktisch nur bei einer Doppelung
         * im Feed erreichbar - beide Wecker meinten dann denselben Moment.
         *
         * MANUELLE WECKER NEHMEN AM KRITERIUM (b) NICHT TEIL (`eventId.isEmpty()`). Sie sind von
         * der Kennungsrotation gar nicht betroffen, ihre id ist stabil, und ein von Hand auf
         * dieselbe Minute gestellter Wecker soll durch das Ueberspringen einer Schicht nicht
         * stumm werden. Fuer sie bleibt es beim id-Vergleich.
         *
         * `null` oder ein nicht gesetztes Flag heisst NICHT uebersprungen - fail-safe, im Zweifel
         * wecken. Der Lesefehler wird von den Aufrufern zu `null` degradiert (getOrNull), bewusst
         * in diese Richtung.
         */
        internal fun istUebersprungen(skipState: AlarmSkipState?, alarm: AlarmInfo): Boolean {
            if (skipState == null || !skipState.isNextAlarmSkipped) return false
            if (skipState.skippedAlarmId == alarm.id) return true
            // BEIDE Seiten muessen kalenderbasiert sein. Der gepruefte Alarm (`eventId`), und
            // der UEBERSPRUNGENE: `skippedManualAlarm != null` heisst, der Nutzer hat seinen von
            // Hand gestellten Wecker abgeschaltet. Dessen id ist stabil, er braucht (b) gar nicht -
            // und ohne diese Bedingung wuerde er eine Schicht mit derselben Weckzeit
            // mit-abschalten. Beide liegen auf vollen Minuten; die Kollision ist keine Exotik.
            return alarm.eventId.isNotEmpty() &&
                skipState.skippedManualAlarm == null &&
                skipState.skippedAlarmTriggerTime > 0L &&
                skipState.skippedAlarmTriggerTime == alarm.triggerTime
        }
    }
    
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
                // So frueh wie moeglich gelesen: dieser Zeitpunkt wird am Ende eines
                // VOLLSTAENDIGEN Laufs als neuer Bezugspunkt festgehalten und beschreibt, bis
                // wohin das Abruf-Fenster reichte (siehe SyncHorizonStore). Je frueher, desto
                // naeher liegt er am tatsaechlichen Abruf.
                val syncStartedAt = System.currentTimeMillis()

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
                //
                // Ein nicht lesbarer Bestand ist keine leere Liste - und `getAllAlarms()` sagt
                // einem das NICHT: es liefert immer `Result.success(_activeAlarms.value)`, im
                // Notfall eben die Leere. Der einzige ehrliche Zeuge ist `isPersistenceBlocked()`
                // (dieselbe Frage stellt `clearInternalAlarms()`, aus demselben Grund). Ein
                // `getOrThrow()` allein waere hier Theater gewesen.
                //
                // WARUM DAS SEIT DER ID-UEBERNAHME KRITISCH IST: Frueher war
                // `id = eventId.hashCode()` eine reine Funktion des Events - ein auf leerem
                // Bestand neu angelegter Alarm ueberschrieb den gespeicherten Eintrag punktgenau,
                // der Lesefehler heilte sich selbst. Jetzt ist die id historienabhaengig (sie
                // wandert vom gepaarten Vorgaenger mit). Derselbe Lesefehler erzeugt damit einen
                // ZWEITEN Eintrag mit eigener id und einen zweiten armierten Systemwecker: der
                // Wecker feuert doppelt, und der naechste Sync raeumt den Waisen mit einer sachlich
                // falschen "Schicht entfernt"-Meldung ab.
                //
                // Das ist die Hausregel "kein Fehler darf als leeres Erfolgsergebnis
                // durchrutschen" - fuer eine Wecker-App ist "leer" die gefaehrlichste Luege.
                if (alarmRepository.isPersistenceBlocked()) {
                    Logger.w(
                        LogTags.ALARM,
                        "⛔ SYNC abgebrochen: der Alarm-Bestand ist in diesem Prozess nicht lesbar. " +
                            "Auf der erfundenen Leere weiterzurechnen wuerde jede Schicht neu anlegen " +
                            "und - seit die id vom gepaarten Vorgaenger mitwandert - einen ZWEITEN " +
                            "armierten Wecker pro Schicht erzeugen."
                    )
                    throw IllegalStateException(
                        "Alarm-Bestand nicht lesbar - Sync abgebrochen, bestehende Wecker bleiben unangetastet"
                    )
                }
                val existingAlarms = alarmRepository.getAllAlarms().getOrThrow()
                // Feature B: die allererste Befuellung (z.B. nach Neuinstallation/komplettem
                // Zuruecksetzen) soll nicht fuer jeden neuen Alarm eine "Neue Schicht erkannt"-Flut
                // ausloesen - siehe notifyCreated-Aufruf unten.
                val isFirstSync = existingAlarms.isEmpty()

                // Bis wohin reichte das Abruf-Fenster beim LETZTEN vollstaendigen Sync? Alles, was
                // dahinter beginnt, ist erst jetzt ueberhaupt sichtbar geworden - der wandernde
                // 14-Tage-Horizont, keine Dienstplan-Aenderung. Der Alarm dafuer wird trotzdem ganz
                // normal angelegt und gestellt; NUR die Meldung unterbleibt.
                //
                // getOrNull() fasst hier zwei Faelle zusammen, und das ist Absicht: "es gab noch
                // keinen Sync" und "der Merker ist nicht lesbar" fuehren beide zu null und damit zu
                // MELDEN (istHorizontEintritt gibt fuer null false zurueck). Eine ueberfluessige
                // Meldung ist laestig, eine verschwiegene echte Aenderung kostet Vertrauen - die
                // Richtung ist bewusst so gewaehlt und im SyncHorizonStore begruendet. Den
                // Unterschied haelt der Store selbst im Log fest.
                val letzterVollstaendigerSync = syncHorizonStore.letzterVollstaendigerSync().getOrNull()

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
                
                // Kandidaten der aktuellen Kalenderlesung - pro erkannter Schicht einer.
                // ABSICHTLICH AUCH DIE ABGELAUFENEN (neuerAlarm = null): sie werden fuer die
                // Paarung unten gebraucht. Sonst gilt ein Termin, dessen Weckzeit heute frueh
                // verstrichen ist, als "entfernt", sobald der Feed ihm eine neue Kennung gegeben
                // hat - und der Nutzer bekaeme eine "Schicht entfernt"-Meldung fuer den Dienst,
                // den er gerade antritt.
                val kandidaten = mutableListOf<SyncKandidat>()
                val now = LocalDateTime.now()

                for (shiftMatch in shiftMatches) {
                    try {
                        val eventId = shiftMatch.calendarEvent.id
                        val triggerTime = shiftMatch.calculatedAlarmTime
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()

                        if (shiftMatch.calculatedAlarmTime.isBefore(now)) {
                            Logger.w(LogTags.ALARM, "⏰ SYNC: Skipping alarm in the past: ${shiftMatch.shiftDefinition.name}")
                            kandidaten += SyncKandidat(
                                eventId = eventId,
                                triggerTime = triggerTime,
                                shiftId = shiftMatch.shiftDefinition.id,
                                neuerAlarm = null
                            )
                            continue
                        }

                        val checksum = eventChecksumMap[eventId] ?: ""
                        kandidaten += SyncKandidat(
                            eventId = eventId,
                            triggerTime = triggerTime,
                            shiftId = shiftMatch.shiftDefinition.id,
                            neuerAlarm = createAlarmFromShiftMatch(shiftMatch, eventId, checksum)
                        )
                    } catch (e: Exception) {
                        Logger.e(LogTags.ALARM, "❌ SYNC: Error processing shift: ${shiftMatch.shiftDefinition.name}", e)
                    }
                }

                // Zwei Treffer auf dieselbe Event-ID lassen sich nicht getrennt behandeln - wie
                // bisher (`newAlarmsMap[eventId] = alarmInfo`) gewinnt der letzte.
                val eindeutigeKandidaten = kandidaten.associateBy { it.eventId }.values.toList()

                // ERST PAAREN, DANN ENTSCHEIDEN. Die Paarung MUSS vor dem Loeschzweig stehen:
                // sonst loescht Schritt 1 genau die Wecker, die Schritt 2 wiedererkennen wuerde.
                // Siehe [paareBestehendeAlarme] fuer das Warum und die Reihenfolge.
                val paarung = paareBestehendeAlarme(existingAlarms, eindeutigeKandidaten)
                val kandidatJeEventId = eindeutigeKandidaten.associateBy { it.eventId }
                val kandidatJeAlarmId: Map<Int, SyncKandidat> = paarung.entries.associate { (eventId, alarm) ->
                    alarm.id to kandidatJeEventId.getValue(eventId)
                }

                // 🔧 SYNC-FIX Step 1: Alarme loeschen, fuer die es keinen Kandidaten mehr gibt
                var deletedCount = 0
                for (existingAlarm in existingAlarms) {
                    // Manuelle Alarme (leere eventId) bleiben unberuehrt - sie sind die einzigen,
                    // die sich nicht aus dem Kalender rekonstruieren lassen.
                    if (existingAlarm.eventId.isEmpty()) continue

                    val kandidat = kandidatJeAlarmId[existingAlarm.id]
                    if (kandidat?.neuerAlarm != null) {
                        // Gepaart UND weiterhin in der Zukunft: dieser Alarm wird in Schritt 2
                        // weiterverwendet - ggf. nur mit neuer Kalender-Kennung. Nichts loeschen,
                        // nichts cancellen, nichts melden.
                        continue
                    }

                    // ZWEI verschiedene Gruende, hier zu landen - und nur EINER davon ist eine
                    // entfernte Schicht:
                    //  1. Der Termin ist wirklich aus dem Kalender verschwunden (keine Paarung).
                    //  2. Der Termin laeuft weiter, nur die WECKZEIT ist verstrichen (der Wecker
                    //     hat heute frueh geklingelt) - erkennbar daran, dass die Paarung einen
                    //     Kandidaten OHNE neuen Alarm gefunden hat. Der Alarm wird trotzdem
                    //     geraeumt - ein abgelaufener Alarm gehoert nicht in den Bestand - aber es
                    //     ist KEINE Aenderung des Dienstplans, also gibt es dafuer auch keine
                    //     Meldung. Die Schichtspanne bleibt davon unberuehrt erhalten, damit
                    //     Dimmer und "Nicht stoeren" die laufende Schicht weiter kennen.
                    val onlyExpired = kandidat != null
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

                // 🔧 SYNC-FIX Step 2: Update changed alarms & create new ones
                var updatedCount = 0
                var createdCount = 0
                var skippedCount = 0
                // Wecker, die eine neue Kalender-Kennung bekommen haben (Paarung ueber
                // Weckzeit+Schicht) - unabhaengig davon, ob daneben noch etwas Echtes anders war.
                // Muss sichtbar bleiben - siehe die WARN-Zeile unten.
                var neueKennungCount = 0
                val resultAlarms = mutableListOf<AlarmInfo>()

                // "Naechsten Alarm ueberspringen": EINMAL pro Sync lesen. Fail-safe wie der
                // Silent-/Skip-Check im AlarmReceiver - schlaegt der Lesevorgang fehl (getOrNull =
                // null), gilt NICHTS als uebersprungen und der Alarm wird ganz normal gestellt.
                // Im Zweifel wecken.
                val skipState = alarmSkipUseCase.getSkipStatus().getOrNull()

                for (kandidat in eindeutigeKandidaten) {
                  // Abgelaufene Kandidaten hat Schritt 1 bereits abschliessend behandelt.
                  val frischerAlarm = kandidat.neuerAlarm ?: continue
                  val eventId = kandidat.eventId
                  // Pro Event ein eigenes try/catch: ein einzelner abgelehnter Alarm (z.B.
                  // AlarmRepository.saveAlarm lehnt eine inzwischen verstrichene Weckzeit ab, weil
                  // `now` einmal oben gelesen wurde und die DataStore-Schreibvorgaenge der vorherigen
                  // Events Zeit gekostet haben) hat sonst den GESAMTEN Delta-Sync abgebrochen: der
                  // schon geloeschte Alarm blieb geloescht und alle noch nicht abgearbeiteten
                  // Eintraege der (unsortierten) Map wurden weder erstellt noch re-armed.
                  try {
                    val existingAlarm = paarung[eventId]

                    // DIE `AlarmInfo.id` DES BESTEHENDEN ALARMS WIRD BEIBEHALTEN.
                    //
                    // `createAlarmFromShiftMatch` leitet die id aus `calendarEvent.id.hashCode()`
                    // ab. Der Dienstplan kommt aber aus einem ABONNIERTEN Kalender (TimeOffice-ICS):
                    // Google liest den Feed alle paar Tage neu ein und vergibt dabei ALLEN Terminen
                    // neue Event-IDs, ohne dass sich an Schicht oder Weckzeit irgendetwas aendert
                    // (am Fairphone belegt: 11 Alarme geloescht und 11 neu angelegt in EINEM Lauf,
                    // Schnittmenge der Event-IDs vorher/nachher = null).
                    //
                    // An der id haengen der Request-Code des System-Alarms, der Direct-Boot-Spiegel,
                    // die Notification-ID und der Skip-Merker. Eine neue id waere also genau die
                    // Unruhe, die hier abgestellt werden soll: alle Systemwecker abbrechen und neu
                    // stellen (stirbt der kurzlebige Wartungs-Service mitten in der Sequenz, fehlen
                    // Wecker), und ein uebersprungener Wecker verliert seinen Bezug.
                    //
                    // Gilt auch fuer die Paarung ueber die Event-ID: der bestehende Alarm kann aus
                    // einem frueheren Kennungswechsel eine id tragen, die NICHT dem Hash seiner
                    // jetzigen eventId entspricht. Wuerde hier der Hash gewinnen, finge die
                    // ID-Wanderung beim naechsten Lauf von vorne an.
                    val newAlarm = if (existingAlarm != null) {
                        frischerAlarm.copy(id = existingAlarm.id)
                    } else {
                        frischerAlarm
                    }
                    // Nur die Kennung hat gewechselt - Weckzeit und Schicht sind laut Paarung
                    // gleich (siehe [paareBestehendeAlarme]).
                    val nurNeueKennung = existingAlarm != null && existingAlarm.eventId != newAlarm.eventId

                    // SKIP-IMMEDIATE-UX: Der uebersprungene Alarm darf hier NICHT wieder auftauchen.
                    // AlarmSkipUseCase.skipNextAlarm() cancelt den System-Alarm sofort und loescht den
                    // Alarm aus dem Repository - fuer den naechsten Sync sah dessen Kalender-Event
                    // damit wie ein NEUES Event aus: System-Alarm wieder scharf UND eine falsche
                    // "Neue Schicht erkannt"-Notification, obwohl der Nutzer den Wecker gerade
                    // abgeschaltet hatte. Das Flag laeuft weiterhin ZEITBASIERT ab
                    // (clearExpiredSkip oben), nicht ueber diesen Zweig.
                    //
                    // DIE ERKENNUNG DARF NICHT AN DER id ALLEIN HAENGEN - siehe [istUebersprungen].
                    // Weil skipNextAlarm() den Eintrag LOESCHT, gibt es beim naechsten Sync gar
                    // keinen bestehenden Alarm zum Paaren; nach einem Kennungswechsel traegt der
                    // neu gebaute Alarm also eine neue id, und ein reiner id-Vergleich griffe ins
                    // Leere. Der Weckzeitpunkt ist der stabile Anker.
                    if (istUebersprungen(skipState, newAlarm)) {
                        Logger.business(
                            LogTags.ALARM,
                            "⏭️ SYNC: Uebersprungener Alarm wird nicht neu gestellt: ${newAlarm.shiftName} " +
                                "(id=${newAlarm.id}, Weckzeit=${newAlarm.formattedTime})"
                        )
                        continue
                    }

                    if (existingAlarm != null) {
                        // VERGLEICHSBASIS FUER "hat sich etwas geaendert?".
                        //
                        // Die stille Uebernahme deckt GENAU zwei Felder ab, und zwar als
                        // ausdrueckliche Positivliste, nicht als "alles ausser dem, was ich hier
                        // aufzaehle": die Kalender-Kennung selbst und die daraus mitgezogene
                        // Pruefsumme. Ein kuenftig ergaenztes AlarmInfo-Feld faellt damit NICHT
                        // automatisch unter die Stille - es bleibt im Vergleich und schlaegt als
                        // Aenderung durch, bis jemand bewusst entscheidet, es hier aufzunehmen.
                        //
                        // eventChecksum gehoert dazu, weil sie aus Titel und Zeiten des Termins
                        // gerechnet wird: alles daran, was den Wecker betrifft, steht ohnehin
                        // einzeln im AlarmInfo (shiftName, triggerTime, shiftStartTime,
                        // shiftEndTime). Weicht NUR die Pruefsumme ab, hat sich am Wecker nichts
                        // geaendert - dafuer saemtliche Systemwecker abzubrechen und neu zu
                        // stellen waere genau die Unruhe, gegen die die Paarung gebaut ist.
                        //
                        // ALLES UEBRIGE WIRD WEITER VERGLICHEN. Frueher stand `nurNeueKennung` als
                        // erster Zweig VOR diesem Vergleich und verschluckte damit jede weitere
                        // Abweichung - faellt eine Umbenennung mit einem Feed-Neueinlesen zusammen,
                        // verschwand sie lautlos und wurde auch spaeter nie gemeldet, obwohl der
                        // ShiftChangeNotifier eine Namensaenderung ausdruecklich als meldepflichtig
                        // fuehrt. Was meldepflichtig ist, entscheidet weiterhin der Notifier.
                        val vergleichsbasis = if (nurNeueKennung) {
                            existingAlarm.copy(
                                eventId = newAlarm.eventId,
                                eventChecksum = newAlarm.eventChecksum
                            )
                        } else {
                            existingAlarm
                        }

                        if (vergleichsbasis == newAlarm && nurNeueKennung) {
                            // STILL UEBERNEHMEN: kein Loeschen, kein Neuanlegen, kein cancel+neu,
                            // keine Meldung. Fuer den Nutzer hat sich nichts geaendert - derselbe
                            // Dienst zur selben Weckzeit, der Kalender hat ihm nur eine neue
                            // Kennung gegeben.
                            //
                            // saveAlarm() ueberschreibt den Datensatz mit derselben id an Ort und
                            // Stelle (AlarmRepository.saveAlarm: indexOfFirst { it.id == ... }),
                            // deshalb braucht es hier KEIN deleteAlarm() davor - und genau deshalb
                            // auch kein cancelSystemAlarm().
                            //
                            // Das scheduleSystemAlarm() darunter ist das IDEMPOTENTE Re-Armen aus
                            // dem Unveraendert-Zweig (gleicher Request-Code, gleiche Weckzeit,
                            // FLAG_UPDATE_CURRENT) - es ersetzt die Extras des bestehenden
                            // PendingIntents, statt den Wecker abzubrechen und neu zu stellen.
                            Logger.d(
                                LogTags.ALARM,
                                "🔀 SYNC: Neue Kalender-Kennung fuer ${newAlarm.shiftName} " +
                                    "(id=${newAlarm.id}, ${existingAlarm.eventId} -> ${newAlarm.eventId}) - " +
                                    "Weckzeit und Schicht unveraendert"
                            )
                            alarmRepository.saveAlarm(newAlarm).getOrThrow()
                            scheduleSystemAlarm(newAlarm).getOrThrow()
                            resultAlarms.add(newAlarm)
                            neueKennungCount++
                        } else if (vergleichsbasis != newAlarm) {
                            // Alarm exists - check if anything about it changed. Voller Vergleich statt
                            // nur eventChecksum/triggerTime: sonst bleibt newAlarm (frisch aus der
                            // aktuellen ShiftDefinition berechnet, z.B. isSilent/shiftName) unpersistiert,
                            // wenn sich nur ein reines ShiftDefinition-Feld aendert, aber das zugrunde
                            // liegende Kalender-Event gleich bleibt (Checksum+Weckzeit identisch).
                            // Alarm-Daten geaendert -> aktualisieren
                            Logger.business(LogTags.ALARM, "🔄 SYNC: Updating changed alarm: ${newAlarm.shiftName} (eventId: $eventId)")
                            // Kennungswechsel UND echte Aenderung zugleich (Feed neu eingelesen und
                            // der Chef hat etwas umbenannt/verschoben): zaehlt fuer die WARN-Zeile
                            // unten mit, sonst untertreibt die Feed-Diagnose ausgerechnet dann,
                            // wenn zwei Dinge gleichzeitig passieren.
                            if (nurNeueKennung) neueKennungCount++

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
                        // New event -> create alarm
                        Logger.business(LogTags.ALARM, "➕ SYNC: Creating alarm for new event: ${newAlarm.shiftName} (eventId: $eventId)")
                        alarmRepository.saveAlarm(newAlarm).getOrThrow()
                        scheduleSystemAlarm(newAlarm).getOrThrow()
                        resultAlarms.add(newAlarm)
                        createdCount++
                        // ZWEI Gruende, einen neu erstellten Alarm NICHT zu melden:
                        //  1. isFirstSync - erster Sync ueberhaupt (existingAlarms war leer), sonst
                        //     flutet jede Neuinstallation.
                        //  2. Horizont-Eintritt - der Termin ist lediglich neu in das taeglich
                        //     wandernde 14-Tage-Fenster gerutscht und war beim letzten Sync noch gar
                        //     nicht abrufbar. Das ist keine Aenderung des Dienstplans, sondern ein
                        //     Artefakt der Abfrage. Genau das hat dem Nutzer tagelang jeden Morgen
                        //     "Neue Schicht erkannt" fuer den jeweils neuen Randtag gezeigt (siehe
                        //     SyncHorizonStore). Eine Schicht INNERHALB des bereits abgedeckten
                        //     Zeitraums bleibt eine echte Aenderung und wird weiterhin gemeldet.
                        //
                        // Verglichen wird der SCHICHTBEGINN, denn danach filtert die Kalender-
                        // Abfrage (timeMin/timeMax), nicht nach der Weckzeit. shiftStartTime <= 0
                        // heisst "unbekannt" (wie in scheduleSystemAlarm) - dann die Weckzeit.
                        if (!isFirstSync) {
                            val schichtBeginn = newAlarm.shiftStartTime.takeIf { it > 0 } ?: newAlarm.triggerTime
                            // syncStartedAt statt "jetzt": innerhalb EINES Laufs muss dieselbe
                            // Frage fuer alle Events denselben Bezugszeitpunkt haben - sonst
                            // entscheidet ein Lauf, der die Altersgrenze des Merkers gerade
                            // ueberschreitet, fuer die ersten Events anders als fuer die letzten.
                            if (SyncHorizonStore.istHorizontEintritt(
                                    letzterVollstaendigerSync,
                                    schichtBeginn,
                                    syncStartedAt
                                )
                            ) {
                                Logger.business(
                                    LogTags.ALARM,
                                    "🗓️ SYNC: ${newAlarm.shiftName} ist neu in den ${CalendarConstants.DEFAULT_DAYS_AHEAD}-Tage-Horizont " +
                                        "gerutscht - Wecker gestellt, aber keine Aenderungsmeldung"
                                )
                            } else {
                                // Eigenes try/catch - eine fehlgeschlagene Notification darf die
                                // eigentlich kritische Alarm-Erstellung nie beeintraechtigen.
                                try {
                                    shiftChangeNotifier.notifyCreated(newAlarm)
                                } catch (e: Exception) {
                                    Logger.w(LogTags.ALARM, "Schicht-Aenderungs-Notification (Create) fehlgeschlagen", e)
                                }
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
                        "❌ SYNC: Alarm fuer Event $eventId (${frischerAlarm.shiftName}) uebersprungen - Rest des Syncs laeuft weiter",
                        e
                    )
                  }
                }

                // SICHTBAR BLEIBEN: ein neu eingelesener Feed vergibt allen Terminen neue Kennungen.
                // Die Wecker bleiben davon unberuehrt (genau dafuer ist die Paarung da) und der
                // Nutzer bekommt keine Meldung - aber ein DEFEKTER Feed saehe von aussen genauso
                // aus. WARN, damit die Zeile auch im Release-Log landet: ohne sie verschwindet ein
                // Feed-Defekt lautlos, und beim naechsten Mal sucht niemand mehr danach.
                if (neueKennungCount > 0) {
                    Logger.w(
                        LogTags.ALARM,
                        "🔀 SYNC: $neueKennungCount Wecker haben eine neue Kalender-Kennung bekommen " +
                            "(Kalender-Feed neu eingelesen) - ihre Wecker-Identitaet (id) bleibt " +
                            "erhalten. Ob daneben etwas Echtes anders war, sagen die Update-Zeilen " +
                            "darueber; ohne solche gab es weder ein Neustellen noch eine Meldung"
                    )
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
                    "Neue-Kennung: $neueKennungCount, Total: ${resultAlarms.size} alarms"
                )

                // Bezugspunkt NUR nach einem vollstaendigen Lauf fortschreiben. Wurde auch nur ein
                // Event uebersprungen (oder hat die Schleife per Exception/Cancellation
                // abgebrochen - dann kommt der Code hier gar nicht an), bleibt der alte Wert
                // stehen. Der Merker ist eine BEHAUPTUNG ("bis hierhin haben wir alles gesehen und
                // verarbeitet"), und die darf ein unfertiger Lauf nicht aufstellen: sonst gilt ein
                // Horizont als abgearbeitet, dessen Events nie verarbeitet wurden.
                //
                // Die Folge eines stehen gebliebenen Merkers ist ein FRUEHERER Vergleichspunkt,
                // also eher "Horizont-Eintritt" - genau dieselbe Lage wie "die App war eine Woche
                // nicht dran", die laut Anforderung still bleiben soll. Sie bleibt eng begrenzt,
                // weil der naechste vollstaendige Lauf sie sofort aufloest.
                if (skippedCount == 0) {
                    persistSyncHorizon(syncStartedAt)
                }

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
        //
        // Erkennung ueber [istUebersprungen] - DIESELBE wie das Gate in syncAlarms(). Beide
        // muessen denselben Wecker erkennen, sonst haelt nur die Haelfte: das Gate liesse ihn
        // durch und dieser Backstop wuerde ihn abweisen (oder umgekehrt), und der Nutzer bekaeme
        // je nach Weg einen stummen oder einen doch klingelnden Wecker.
        val skipState = alarmSkipUseCase.getSkipStatus().getOrNull()
        if (istUebersprungen(skipState, alarmInfo)) {
            Logger.business(
                LogTags.ALARM,
                "⏭️ SCHEDULE: Alarm ${alarmInfo.id} (${alarmInfo.shiftName}, ${alarmInfo.formattedTime}) " +
                    "ist als uebersprungen markiert - kein System-Alarm"
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

    /**
     * Schreibt den Bezugspunkt fuer die Horizont-Unterscheidung fort ([SyncHorizonStore]).
     *
     * **Eigenes try/catch, bewusst nicht-fatal** - dieselbe Haltung wie bei [persistShiftSpans] und
     * den [ShiftChangeNotifier]-Aufrufen: ein Nebenschauplatz darf die kritische
     * Alarm-Synchronisation nie abbrechen oder rueckgaengig machen. Scheitert das Schreiben, bleibt
     * der alte Bezugspunkt stehen; Folge ist hoechstens eine ueberfluessige Meldung beim naechsten
     * Randtag - nie eine verschwiegene Aenderung.
     *
     * [CancellationException] wird weitergeworfen (kein Schreibfehler, sondern das Ende der
     * umgebenden Coroutine).
     */
    private suspend fun persistSyncHorizon(syncStartedAt: Long) {
        try {
            syncHorizonStore.merkeVollstaendigenSync(syncStartedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Sync-Horizont konnte nicht fortgeschrieben werden", e)
        }
    }

    /**
     * Ein Termin der AKTUELLEN Kalenderlesung, so wie ihn der Delta-Sync braucht.
     *
     * [neuerAlarm] ist `null`, wenn die Weckzeit dieses Termins bereits verstrichen ist. Solche
     * Kandidaten werden trotzdem gefuehrt, weil die Paarung sie braucht: sonst gilt ein Termin,
     * dessen Wecker heute frueh geklingelt hat, als "aus dem Kalender entfernt", sobald der Feed
     * ihm eine neue Kennung gegeben hat.
     */
    private data class SyncKandidat(
        val eventId: String,
        val triggerTime: Long,
        val shiftId: String,
        val neuerAlarm: AlarmInfo?
    )

    /**
     * Ordnet jedem Kandidaten der aktuellen Lesung hoechstens EINEN bestehenden Alarm zu.
     *
     * WARUM ES DIESE FUNKTION GIBT: `AlarmInfo.id` wird aus `calendarEvent.id.hashCode()`
     * abgeleitet, und der Delta-Sync paarte bisher ausschliesslich ueber `eventId`. Der Dienstplan
     * kommt aber aus einem ABONNIERTEN Kalender (TimeOffice-ICS-Feed): Google liest den alle paar
     * Tage neu ein und vergibt dabei ALLEN Terminen neue Event-IDs. Am Geraet belegt (neun Tage
     * Datei-Log): am 17.08. "Created 9 / Deleted 8", am 20.08. "Created 11 / Deleted 11", die
     * Schnittmenge der Event-IDs vorher/nachher jeweils LEER - bei voellig unveraenderten
     * Schichten, Namen und Weckzeiten. Folge waren 11 falsche "Schicht entfernt"- und 11 falsche
     * "Neue Schicht erkannt"-Meldungen in EINEM Lauf, und - schwerer - ein Abbrechen und
     * Neustellen saemtlicher Systemwecker: stirbt der kurzlebige Wartungs-Service mitten in dieser
     * Sequenz (Low-Memory-Kill, Akku leer), fehlen Wecker.
     *
     * REIHENFOLGE, UND WARUM SIE ZWEI GETRENNTE RUNDEN BRAUCHT:
     *  (a) gleiche Kalender-Kennung (`eventId`) - das bisherige Verhalten, unveraendert.
     *  (b) sonst gleiche Weckzeit UND gleiche Schicht - "derselbe Wecker, neue Kennung".
     *
     * Runde (a) MUSS komplett durchlaufen, bevor Runde (b) beginnt. Sonst koennte ein frueher
     * einsortierter Kandidat ueber (b) genau den Alarm greifen, den ein spaeterer Kandidat exakt
     * ueber seine Kennung getroffen haette - und die Kennungs-Zuordnung, die eindeutig IST, waere
     * einer blossen Aehnlichkeit geopfert.
     *
     * Jeder bestehende Alarm wird HOECHSTENS EINMAL vergeben (`offen.remove`). Liegen zwei gleiche
     * Schichten mit gleicher Weckzeit im Bestand (Doppelung im Feed), bekommt jeder Kandidat einen
     * eigenen - keiner geht verloren, keiner wird doppelt gepaart.
     *
     * MANUELLE ALARME (leere `eventId`) NEHMEN NIE TEIL. Sie sind die einzigen, die sich nicht aus
     * dem Kalender rekonstruieren lassen; ein Kalender-Termin darf sie sich nicht aneignen.
     *
     * @return Kandidaten-`eventId` -> bestehender Alarm.
     */
    private fun paareBestehendeAlarme(
        existingAlarms: List<AlarmInfo>,
        kandidaten: List<SyncKandidat>
    ): Map<String, AlarmInfo> {
        val offen = existingAlarms.filter { it.eventId.isNotEmpty() }.toMutableList()
        val ergebnis = mutableMapOf<String, AlarmInfo>()

        // Runde (a): gleiche Kalender-Kennung.
        for (kandidat in kandidaten) {
            val treffer = offen.firstOrNull { it.eventId == kandidat.eventId } ?: continue
            offen.remove(treffer)
            ergebnis[kandidat.eventId] = treffer
        }

        // Runde (b): gleiche Weckzeit UND gleiche Schicht.
        // Kandidaten mit einem noch bevorstehenden Wecker zuerst: haetten ein abgelaufener und ein
        // lebender Kandidat dieselbe Weckzeit und dieselbe Schicht, waere es Verschwendung, den
        // einzigen passenden Alarm an den abgelaufenen zu geben (der ihn nur abraeumen wuerde),
        // waehrend der lebende ihn neu anlegen muesste. `sortedBy` ist stabil, die uebrige
        // Reihenfolge bleibt also die der Schichterkennung.
        val nochOffeneKandidaten = kandidaten
            .filter { it.eventId !in ergebnis }
            .sortedBy { if (it.neuerAlarm != null) 0 else 1 }
        for (kandidat in nochOffeneKandidaten) {
            val treffer = offen.firstOrNull {
                it.triggerTime == kandidat.triggerTime && it.shiftId == kandidat.shiftId
            } ?: continue
            offen.remove(treffer)
            ergebnis[kandidat.eventId] = treffer
        }

        return ergebnis
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
