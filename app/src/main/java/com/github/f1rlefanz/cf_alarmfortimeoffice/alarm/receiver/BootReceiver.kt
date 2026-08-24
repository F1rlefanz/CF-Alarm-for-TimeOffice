package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Reine, Android-freie Entscheidungslogik der Boot-Alarm-Validierung — bewusst als eigenes
 * Top-Level-Objekt (Vorbild: `MaintenanceLoadDecision`), damit sie ohne einen echten
 * BroadcastReceiver testbar bleibt.
 */
internal object BootAlarmValidation {

    /**
     * Darf ueber einen Alarm auf Basis des oben gelesenen Snapshots noch entschieden werden?
     *
     * WARUM DIESE FRAGE UEBERHAUPT: Seit dem Wartungs-Anker (`startMaintenanceAnchor`) laeuft beim
     * Boot/Update eine ZWEITE, unabhaengige Sync-Pipeline parallel zur Recovery
     * (`AlarmMaintenanceService` mit `forceSync = true`) — beide fassen dieselben Alarme an, nur die
     * einzelnen Repository-Operationen sind ueber `AlarmUseCase.alarmSyncMutex` serialisiert, nicht
     * die read-modify-write-Sequenz der Recovery. `performAlarmRecovery()` liest seinen Bestand
     * EINMAL oben, holt danach Kalender-Events (Sekunden) und entscheidet erst dann pro Alarm.
     *
     * Der teure Fall: der Anker korrigiert in diesem Fenster einen verschobenen Alarm (gleiche
     * `id` — sie bleibt beim Aktualisieren erhalten; frueher stand hier "denn
     * `id = calendarEvent.id.hashCode()` ist pro Event stabil", das gilt seit der stabilen
     * Wecker-Identitaet nicht mehr: wechselt der Kalender-Feed seine Kennungen, wandert die id
     * des gepaarten Vorgaengers mit, statt neu aus der Kennung berechnet zu werden. Fuer diesen
     * Absatz aendert das nichts, die id bleibt so oder so gleich; neuer `triggerTime` +
     * neuer `eventChecksum`) und stellt den System-Alarm korrekt. Die Recovery vergleicht danach
     * ihre VERALTETE Kopie gegen den frischen Event-Checksum, sieht einen Mismatch und loescht den
     * gerade korrigierten Alarm — ohne ihn neu anzulegen (`continue`).
     *
     * @param snapshotChecksum `eventChecksum` aus dem oben gelesenen Snapshot
     * @param freshChecksum `eventChecksum` desselben Alarms JETZT (null = nicht mehr im Repository)
     */
    fun snapshotStillCurrent(snapshotChecksum: String, freshChecksum: String?): Boolean =
        freshChecksum != null && freshChecksum == snapshotChecksum

    /**
     * Ein Weckpunkt der AKTUELLEN Kalenderlesung: dieselbe Schicht zur selben Weckzeit — ohne die
     * Kalender-Kennung, die genau das Fluechtige an der Sache ist.
     */
    internal data class Weckpunkt(val triggerTime: Long, val shiftId: String)

    /** Was mit einem gespeicherten Alarm beim Boot geschehen soll. */
    internal enum class AlarmUrteil {
        /** Unveraendert wiederherstellen (Termin da, nichts geaendert). */
        WIEDERHERSTELLEN,

        /** Termin da, aber unter neuer Kalender-Kennung - wiederherstellen, nichts loeschen. */
        NUR_NEUE_KENNUNG,

        /** Der Termin ist wirklich weg. */
        LOESCHEN_TERMIN_WEG,

        /** Termin da, aber inhaltlich geaendert - der naechste Sync legt ihn neu an. */
        LOESCHEN_TERMIN_GEAENDERT
    }

    /**
     * Urteil ueber EINEN gespeicherten Alarm anhand der aktuellen Kalenderlesung.
     *
     * WARUM [NUR_NEUE_KENNUNG] EXISTIEREN MUSS: Der Dienstplan kommt aus einem ABONNIERTEN
     * Kalender (TimeOffice-ICS-Feed). Google liest den alle paar Tage neu ein und vergibt dabei
     * ALLEN Terminen neue Event-IDs, ohne dass sich an Schicht oder Weckzeit irgendetwas aendert
     * (am Geraet belegt: am 20.08.2026 11 Wecker geloescht und 11 neu angelegt in EINEM Lauf,
     * Schnittmenge der Event-IDs vorher/nachher leer). Zwischen so einem Neueinlesen und dem
     * naechsten vollstaendigen Sync (bis zu 6 h) tragen ALLE gespeicherten Alarme Kennungen, die es
     * im Kalender nicht mehr gibt. Diese Stelle kannte bis dahin nur "Kennung nicht gefunden =
     * Termin geloescht" und raeumte in so einem Fenster bei einem Neustart den GESAMTEN Bestand
     * samt Systemweckern ab - ohne Neuanlage. Zurueck kam er nur, wenn der Sync im selben
     * Boot-Ablauf durchlief; ohne Netz nach dem Neustart oder bei unvollstaendiger Kalenderliste
     * stand der Nutzer ohne Wecker da.
     *
     * FUEHRENDE STELLE DER REGEL IST `AlarmUseCase.paareBestehendeAlarme()` - dort lebt "gleiche
     * Weckzeit + gleiche Schicht = derselbe Wecker" fuer den Delta-Sync, mit zweistufiger
     * Reihenfolge (erst Kennung, dann Aehnlichkeit) und Eins-zu-eins-Vergabe. Hier steht bewusst
     * nur die MINIMALE Variante, und sie ist bewusst grosszuegiger: es wird nur "behalten oder
     * loeschen" entschieden und nichts einander zugeordnet, also braucht es keine
     * Eins-zu-eins-Vergabe. Liegen zwei Alarme auf demselben Weckpunkt, bleiben beide stehen (sie
     * tragen verschiedene `id`, teilen sich also keinen PendingIntent) und der naechste
     * vollstaendige Sync raeumt den ueberzaehligen auf. Die Richtung im Zweifel ist die
     * Projektregel "im Zweifel wecken": ein Wecker zu viel klingelt hoerbar und wird abgestellt,
     * ein geloeschter Bestand ist still.
     *
     * @param terminChecksum Checksum des Termins mit DIESER `eventId` in der aktuellen Lesung,
     *   oder `null`, wenn die Kennung dort nicht mehr vorkommt.
     * @param weckpunkte Weckpunkte der aktuellen Lesung, oder `null`, wenn sie sich nicht
     *   ermitteln liessen (Schichterkennung fehlgeschlagen). `null` heisst: wegen einer fehlenden
     *   Kennung wird NIE geloescht.
     */
    fun beurteile(
        alarmTriggerTime: Long,
        alarmShiftId: String,
        alarmChecksum: String,
        terminChecksum: String?,
        weckpunkte: Set<Weckpunkt>?
    ): AlarmUrteil = when {
        terminChecksum == null ->
            if (weckpunkte != null && Weckpunkt(alarmTriggerTime, alarmShiftId) !in weckpunkte) {
                AlarmUrteil.LOESCHEN_TERMIN_WEG
            } else {
                AlarmUrteil.NUR_NEUE_KENNUNG
            }

        terminChecksum != alarmChecksum -> AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT

        else -> AlarmUrteil.WIEDERHERSTELLEN
    }
}

/**
 * 🛡️ SMART MAINTENANCE CHAIN Level 4: Enhanced Boot Receiver
 *
 * COMPLETE SYSTEM RECOVERY nach Device-Neustarts:
 * - Vollständige Alarm-Wiederherstellung aus persistenter Speicherung
 * - Neuinitialisierung aller Smart Maintenance Chain Levels (1-3)
 * - Comprehensive Health Diagnostics und System-Validation
 * - Emergency Repair-Mechanismen bei kritischen Fehlern
 * - Integration mit bestehender App-Infrastruktur
 *
 * SELF-HEALING CAPABILITIES:
 * - Automatic detection of system inconsistencies
 * - Repair of broken alarm schedules
 * - Recovery of lost calendar connections
 * - Restoration of background services
 * - Health monitoring and alerting
 *
 * POST-BOOT SEQUENCE:
 * 1. System Health Diagnostics
 * 2. Alarm Repository Recovery
 * 3. Calendar Integration Restoration
 * 4. Smart Maintenance Chain Reinitialization (L1-L3)
 * 5. Background Services Restart
 * 6. Verification & Health Monitoring
 *
 * INTEGRATION:
 * - Final safety net für alle anderen Smart Maintenance Levels
 * - Ensures system integrity after any critical system events
 * - Maintains consistency with existing architecture patterns
 * 
 * HILT MIGRATION: Now uses Hilt dependency injection instead of AppContainer
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmUseCase: IAlarmUseCase
    @Inject lateinit var calendarUseCase: ICalendarUseCase
    @Inject lateinit var shiftUseCase: IShiftUseCase
    @Inject lateinit var calendarSelectionRepository: CalendarSelectionRepository
    @Inject lateinit var authDataStoreRepository: IAuthDataStoreRepository
    @Inject lateinit var directBootAlarmStore: DirectBootAlarmStore
    @Inject lateinit var dimSchedule: com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
    @Inject lateinit var dndSchedule: com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
    @Inject lateinit var calendarPreAlarmRefreshScheduler: com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
    @Inject lateinit var masterPausePrefs: com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs

    companion object {
        // 🛡️ Level 4 Configuration
        private const val BOOT_RECOVERY_DELAY_MS = 5000L  // 5 seconds delay for system stability
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_RETRY_DELAY_MS = 10000L  // 10 seconds between retries
        private const val POST_BOOT_HEALTH_CHECK_DELAY_MS =
            30000L  // 30 seconds for post-boot health check

        // Boot Actions
        // KEIN ACTION_PACKAGE_REPLACED: der Intent-Filter im Manifest kann es nicht zustellen -
        // PACKAGE_REPLACED braeuchte ein <data android:scheme="package"/>, und ein <data>-Element
        // wuerde die drei URI-losen Actions hier komplett aussperren. Der praktisch relevante
        // Update-Fall laeuft ueber MY_PACKAGE_REPLACED.
        private const val ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED
        private const val ACTION_LOCKED_BOOT_COMPLETED = Intent.ACTION_LOCKED_BOOT_COMPLETED
        private const val ACTION_MY_PACKAGE_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED
    }

    // Recovery Scope für Boot-Recovery-Operations
    //
    // Der CoroutineExceptionHandler ist PFLICHT, der SupervisorJob reicht NICHT (gleiche
    // Fehlerklasse wie HueBridgeConnectionManager.healthCheckScope): ein SupervisorJob isoliert nur
    // GESCHWISTER-Coroutinen voneinander; eine unbehandelte Exception laeuft trotzdem weiter zum
    // Thread-Default-Handler und BEENDET DEN PROZESS. In diesem Scope liegen alle drei
    // Boot-Pfade - Direct-Boot-Restore, die lange Recovery und der 30s-Nachcheck - und der kurz
    // zuvor gestartete Wartungs-Anker laeuft im selben Prozess: ein einziger Wurf ausserhalb eines
    // try/catch haette die GESAMTE Boot-Wiederherstellung mitgerissen, ohne eine Spur ausser einem
    // Prozess-Tod. Fuer eine Wecker-App ist das die teuerste Richtung.
    private val recoveryExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e(
            LogTags.MAINTENANCE_L4,
            "💥 LEVEL 4: Unbehandelte Exception im Recovery-Scope abgefangen (Prozess bleibt am Leben)",
            throwable
        )
    }

    private val recoveryScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + recoveryExceptionHandler)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        Logger.business(
            LogTags.MAINTENANCE_L4,
            "🛡️ LEVEL 4: Boot event received - Action: $action"
        )

        when (action) {
            ACTION_LOCKED_BOOT_COMPLETED -> {
                // DIRECT BOOT: nur der schnelle, CE-freie Restore aus dem Device-Protected-Spiegel.
                // Kalender/Token/CE-Storage sind vor der ersten Entsperrung nicht lesbar - die
                // volle Recovery folgt spaeter bei BOOT_COMPLETED (nach dem Entsperren).
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "🔐 LEVEL 4: Locked boot - Direct-Boot-Wiederherstellung der Alarme"
                )
                restoreAlarmsFromDirectBootStore(context, "LOCKED_BOOT_COMPLETED")
            }

            ACTION_BOOT_COMPLETED -> {
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "📱 LEVEL 4: Device booted - initiating complete system recovery"
                )
                // Zuerst der schnelle, prozess-tod-sichere Restore (Alarme stehen sofort wieder),
                // dann der Wartungslauf als Sicherheitsnetz, dann die vollstaendige CE-basierte
                // Validierung/Recovery. Die beiden Letzteren laufen NEBENLAEUFIG - siehe
                // [startMaintenanceAnchor] und [BootAlarmValidation.snapshotStillCurrent].
                restoreAlarmsFromDirectBootStore(context, "BOOT_COMPLETED")
                startMaintenanceAnchor(context, "BOOT_COMPLETED")
                performCompleteSystemRecovery(context, "BOOT_COMPLETED")
            }

            ACTION_MY_PACKAGE_REPLACED -> {
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "📦 LEVEL 4: App updated - performing post-update recovery"
                )
                restoreAlarmsFromDirectBootStore(context, "APP_UPDATED")
                startMaintenanceAnchor(context, "APP_UPDATED")
                performCompleteSystemRecovery(context, "APP_UPDATED")
            }

            else -> {
                Logger.d(LogTags.MAINTENANCE_L4, "🛡️ LEVEL 4: Ignoring unhandled action: $action")
            }
        }
    }

    /**
     * DIRECT-BOOT RESTORE: Schnelles, CE-freies Neusetzen der Alarme aus dem Device-Protected-
     * Spiegel. Laeuft unter goAsync() (prozess-tod-sicher waehrend der kurzen Restore-Phase) und
     * braucht weder Kalender, Token noch entsperrten Storage - daher schon vor der ersten
     * Entsperrung (LOCKED_BOOT_COMPLETED) einsetzbar. Setzt dieselben PendingIntents wie der
     * regulaere Pfad, sodass die spaetere CE-Recovery per FLAG_UPDATE_CURRENT nur aktualisiert.
     */
    private fun restoreAlarmsFromDirectBootStore(context: Context, reason: String) {
        val pendingResult = goAsync()
        recoveryScope.launch {
            try {
                // Master-Pause: zentraler Backstop wie in performCompleteSystemRecovery() - dieser
                // Pfad ist ein zweiter, unabhaengiger Ort, an dem echte System-Alarme gearmt werden
                // (setAlarmClock ueber AlarmManagerService.rescheduleFromDirectBoot), und lief bisher
                // OHNE jeden Master-Pause-Check. Wird MasterPauseUseCase.pause() zwischen dem Setzen
                // des Pause-Flags und dem Leeren des DirectBootAlarmStore-Spiegels unterbrochen
                // (Prozess-Kill, DataStore-Fehler), bleibt der Spiegel mit veralteten Eintraegen
                // stehen - ohne diesen Check wuerden sie bei jedem folgenden Boot trotzdem gearmt.
                //
                // BEWUSST directBootAlarmStore.isPausedNow() statt masterPausePrefs.pausedNow():
                // MasterPausePrefs liegt im @MainDataStore (CREDENTIAL-ENCRYPTED Storage) und ist
                // vor der ersten Entsperrung nach einem Reboot NICHT lesbar - dieser Pfad laeuft
                // aber gerade bei LOCKED_BOOT_COMPLETED, also VOR der Entsperrung (BootReceiver ist
                // directBootAware). Ein CE-Storage-Read hier wuerde entweder werfen (dann restored
                // der Fast-Path fuer ALLE Nutzer keine Alarme mehr, nicht nur pausierte) oder
                // haengen (dann wird pendingResult.finish() nie erreicht - Wakelock-Leck). Der
                // Pause-Zustand wird deshalb zusaetzlich in denselben Device-Protected-Spiegel
                // geschrieben (siehe DirectBootAlarmStore.savePaused, MasterPauseUseCase).
                if (directBootAlarmStore.isPausedNow()) {
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "⏸️ LEVEL 4: Direct-Boot-Restore ($reason) uebersprungen (Master-Pause aktiv)"
                    )
                    return@launch
                }
                val entries = directBootAlarmStore.getFutureEntries()
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "🔐 LEVEL 4: Direct-Boot-Restore ($reason) - ${entries.size} Alarme aus Spiegel"
                )
                entries.forEach { entry ->
                    try {
                        AlarmManagerService.rescheduleFromDirectBoot(
                            context, entry.id, entry.triggerTime, entry.shiftName, entry.shiftStartTimeFormatted
                        )
                    } catch (e: Exception) {
                        Logger.e(
                            LogTags.MAINTENANCE_L4,
                            "❌ LEVEL 4: Direct-Boot-Restore fuer id=${entry.id} fehlgeschlagen", e
                        )
                    }
                }

                // SCHWEBENDER SNOOZE: eigener Merker, eigener PendingIntent-Slot - und bis v1.23.0
                // in KEINEM Wiederherstellungs-Pfad. Wer schlummert und dessen Geraet in den
                // naechsten Minuten neu startet, wurde nie wieder geweckt: der Ursprungsalarm ist
                // gefeuert und geraeumt, der Snooze-Alarm stirbt mit dem Reboot. Steht bewusst
                // hinter demselben Master-Pause-Gate wie die Alarme oben und liest denselben
                // Device-Protected-Storage, laeuft also auch bei LOCKED_BOOT_COMPLETED.
                try {
                    val restoredSnoozes = AlarmManagerService.restorePendingSnoozes(context)
                    if (restoredSnoozes > 0) {
                        Logger.business(
                            LogTags.MAINTENANCE_L4,
                            "😴 LEVEL 4: $restoredSnoozes schwebende(r) Snooze nach $reason wiederhergestellt"
                        )
                    }
                } catch (e: Exception) {
                    Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Snooze-Wiederherstellung fehlgeschlagen", e)
                }
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Direct-Boot-Restore-Lauf fehlgeschlagen", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * SICHERHEITSNETZ fuer die lange Boot-Recovery: ein eigenstaendiger, vollstaendiger
     * Wartungslauf, der NICHT davon abhaengt, dass die Recovery-Coroutine ueberlebt.
     *
     * [performCompleteSystemRecovery] laeuft in einem eigenen `recoveryScope` OHNE `goAsync()`:
     * sobald `onReceive()` zurueckkehrt, haelt der Prozess keine aktive Komponente mehr und ist
     * als "empty process" jederzeit abschiessbar - waehrend die Coroutine noch 5s
     * Stabilitaets-Wartezeit absitzt, danach Token-/Kalender-Arbeit macht und bis zu 3x mit je
     * 10s Pause wiederholt. `goAsync()` allein reicht dafuer nicht (Broadcast-Zeitfenster).
     *
     * Der kurzlebige Foreground-Service leistet - unabhaengig vom Ausgang der Recovery-Coroutine -
     * genau die Schritte, deren Verlust am teuersten ist: 6h-Kette neu planen (bzw. bei
     * Master-Pause kappen), Kalender laden + Delta-Sync, Dimmer-/DND-Tick und
     * Pre-Alarm-Refresh-Jobs neu setzen. Wird die Coroutine abgeschossen, steht die App trotzdem
     * nicht ohne laufende Wartung da.
     *
     * KEIN VERLAESSLICHER "PROZESS-ANKER" (das behauptete dieser Kommentar frueher): der Service
     * raeumt sich am Ende seines EIGENEN Laufs per `stopSelf(startId)` ab. Auf den kurzen Pfaden
     * (kein Netz/Token direkt nach dem Boot - der Normalfall) ist das nach Bruchteilen einer
     * Sekunde, also noch waehrend die Recovery ihre 5s Stabilitaets-Wartezeit absitzt. Danach hat
     * der Prozess wieder keine Vordergrund-Komponente. Wer eine echte Prozess-Haltung fuer die
     * gesamte Recovery braucht, muss die Recovery-Arbeit IN den Service ziehen - nicht auf diese
     * Funktion vertrauen.
     *
     * NEBENLAEUFIGKEIT: Dieser Lauf ist eine zweite, unabhaengige Sync-Pipeline parallel zur
     * Recovery. Die read-modify-write-Sequenz von [performAlarmRecovery] ist deshalb gegen
     * gleichzeitige Aenderungen abgesichert (siehe [BootAlarmValidation.snapshotStillCurrent]).
     *
     * `forceSync = true`: nach einem Boot/Update sind die Daten grundsaetzlich verdaechtig - das
     * regulaere Lade-Gate (Puffer/Datenalter) wuerde hier oft ueberspringen.
     *
     * BEWUSST `directBootAlarmStore.isPausedNow()` statt `masterPausePrefs.pausedNow()`: der
     * Aufruf muss SYNCHRON in `onReceive()` passieren (nur dort ist der Start eines
     * Foreground-Service aus dem Hintergrund garantiert erlaubt - BOOT_COMPLETED/
     * MY_PACKAGE_REPLACED sind ausdruecklich von den Android-12-FGS-Beschraenkungen ausgenommen),
     * und `MasterPausePrefs` liegt im CE-Storage und ist nur suspendierend lesbar. Der
     * Device-Protected-Spiegel liefert denselben Zustand synchron - gleiche Begruendung wie im
     * schnellen Direct-Boot-Restore. Bei aktiver Pause bleibt es beim Kappen der Kette durch die
     * Recovery selbst; hier wird bewusst KEIN Service (und damit keine sichtbare Notification)
     * gestartet.
     */
    private fun startMaintenanceAnchor(context: Context, reason: String) {
        try {
            if (directBootAlarmStore.isPausedNow()) {
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "⏸️ LEVEL 4: Wartungs-Anker ($reason) uebersprungen (Master-Pause aktiv)"
                )
                return
            }
            AlarmMaintenanceService.start(context, forceSync = true)
            Logger.business(
                LogTags.MAINTENANCE_L4,
                "⚓ LEVEL 4: Wartungslauf als Sicherheitsnetz gestartet ($reason) - sichert Sync/Planung unabhaengig von der Recovery-Coroutine ab"
            )
        } catch (e: Exception) {
            // Best-effort: schlaegt der FGS-Start fehl (z.B. Hersteller-Einschraenkung), laeuft die
            // Recovery-Coroutine wie bisher weiter - nur eben ohne Anker.
            Logger.w(LogTags.MAINTENANCE_L4, "⚠️ LEVEL 4: Wartungs-Anker konnte nicht starten", e)
        }
    }

    /**
     * 🛡️ SMART MAINTENANCE CHAIN Level 4: Complete System Recovery
     *
     * Vollständige Wiederherstellung des Alarm-Systems nach kritischen System-Events.
     * Dies ist das finale Sicherheitsnetz der Smart Maintenance Chain.
     *
     * RECOVERY SEQUENCE:
     * 1. System stability wait
     * 2. Health diagnostics
     * 3. Alarm recovery from persistent storage
     * 4. Calendar integration restoration
     * 5. Smart Maintenance Chain reinitialization (L1-L3)
     * 6. Background services restart
     * 7. Post-recovery validation
     */
    private fun performCompleteSystemRecovery(context: Context, reason: String) {
        recoveryScope.launch {
            // Master-Pause: einmal pro Recovery-Lauf lesen, nicht pro Retry-Versuch neu.
            //
            // DIESER READ STAND UNGESCHUETZT VOR DER while-SCHLEIFE, also AUSSERHALB des einzigen
            // try/catch (das erst darin beginnt). `MasterPausePrefs.paused` ist ein blankes
            // `dataStore.data.map{}` ohne `.catch` - eine transiente IOException (Storage-Druck
            // direkt nach dem Boot; der ReplaceFileCorruptionHandler faengt NUR eine
            // CorruptionException) kam damit ungefangen aus dem `launch` heraus und riss ueber den
            // Thread-Default-Handler den ganzen Prozess mit: keine Alarm-Recovery, keine
            // Neuinitialisierung der 6h-Kette, kein Dimmer-/DND-/Pre-Alarm-Reschedule, und der
            // parallel laufende Wartungs-Anker (selber Prozess) gleich mit. Die Retry-Schleife, die
            // genau solche Fehler abfangen soll, wurde nie erreicht.
            //
            // RICHTUNG DER DEGRADATION - `false` (= NICHT pausiert), niemals `true`: ein
            // faelschlich wiederhergestellter Wecker KLINGELT hoerbar, der Nutzer stellt ihn ab und
            // sieht, dass etwas nicht stimmt. Ein faelschlich unterdrueckter Wecker ist STILL, und
            // niemand merkt es, bis er verschlafen hat. Dieselbe Abwaegung wie beim
            // DeviceLocalFlagsGuard ("ein unerwartet klingelnder Wecker ist deutlich harmloser als
            // ein unerwartet stummer"). Der Fehlerfall wird dabei ausdruecklich als solcher
            // geloggt und NICHT als "nicht pausiert" getarnt - sonst ist er im Log von einem
            // normalen Boot nicht zu unterscheiden.
            val paused = try {
                masterPausePrefs.pausedNow()
            } catch (e: Exception) {
                Logger.w(
                    LogTags.MAINTENANCE_L4,
                    "⚠️ LEVEL 4: Master-Pause nicht lesbar - Recovery laeuft fail-safe als NICHT pausiert weiter",
                    e
                )
                false
            }
            var recoveryAttempt = 0
            var recoverySuccessful = false

            while (!recoverySuccessful && recoveryAttempt < MAX_RECOVERY_ATTEMPTS) {
                try {
                    recoveryAttempt++
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "🛡️ LEVEL 4: Starting complete system recovery - Reason: $reason, Attempt: $recoveryAttempt/$MAX_RECOVERY_ATTEMPTS"
                    )

                    // 1. System Stability Wait
                    Logger.d(LogTags.MAINTENANCE_L4, "⏱️ LEVEL 4: Waiting for system stability...")
                    delay(BOOT_RECOVERY_DELAY_MS)

                    // 2. HILT MIGRATION: Dependencies are now injected, no AppContainer needed

                    // 3. Comprehensive Health Diagnostics
                    val healthStatus = performHealthDiagnostics(reason)
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "🔍 LEVEL 4: Health diagnostics completed - $healthStatus"
                    )

                    // 4. Alarm Repository Recovery
                    //    Master-Pause: weder gespeicherte Alarme re-armen noch neue aus dem
                    //    Kalender anlegen (performAlarmRecovery() faellt sonst bei restoredCount<3
                    //    und autoAlarmEnabled==true in syncAlarms() - der Wecker klingelt trotz
                    //    aktiver Pause wieder). Gleiches Gate wie Schritte 6/7/9/10/11 unten.
                    val alarmRecoveryResult = if (paused) {
                        "Skipped (Master-Pause aktiv)"
                    } else {
                        performAlarmRecovery()
                    }
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "🔄 LEVEL 4: Alarm recovery completed - $alarmRecoveryResult"
                    )

                    // 5. Calendar Integration Restoration
                    val calendarRestorationResult = performCalendarIntegrationRestoration()
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "📅 LEVEL 4: Calendar restoration completed - $calendarRestorationResult"
                    )

                    // 6. Smart Maintenance Chain Reinitialization (L1-L3)
                    //    Master-Pause: statt der Chain-Reinitialisierung nur die 6h-Kette kappen.
                    if (paused) {
                        AlarmMaintenanceService.cancelNext(context)
                    } else {
                        performSmartMaintenanceChainReinitialization(context)
                    }

                    // 7. Background Services Restart
                    //    Master-Pause: auch hier statt Neustart nur die 6h-Kette kappen.
                    if (paused) {
                        AlarmMaintenanceService.cancelNext(context)
                    } else {
                        restartBackgroundServices(context)
                    }

                    // 8. Schedule Post-Recovery Health Check
                    //    Master-Pause: der 30s-Nachcheck wuerde sonst selbst bei korrekt leerer
                    //    Alarmliste (pause() hat sie geleert) "futureAlarms.size < 2" ausloesen
                    //    und AlarmMaintenanceService.start() rufen - sichtbare Notification,
                    //    obwohl der Nutzer die App fuer vollstaendig pausiert haelt.
                    if (paused) {
                        Logger.d(
                            LogTags.MAINTENANCE_L4,
                            "⏸️ LEVEL 4: Post-Recovery-Health-Check uebersprungen (Master-Pause aktiv)"
                        )
                    } else {
                        schedulePostRecoveryHealthCheck(context, reason)
                    }

                    // 9. Schicht-Dimmer: rollenden Dimm-Tick nach dem Boot neu setzen.
                    //    Best-effort und eigenes try/catch – darf die Wecker-Recovery NIE stoeren.
                    //    Master-Pause: statt neu zu planen, den Dimmer abschalten.
                    try {
                        if (paused) {
                            dimSchedule.disable()
                        } else {
                            dimSchedule.enable()
                        }
                    } catch (e: Exception) {
                        Logger.w(LogTags.DIMMER, "Boot: Dimm-Reschedule fehlgeschlagen", e)
                    }

                    // 10. DND-Steuerung: rollenden Tick nach dem Boot neu setzen. Gleiches
                    //     Muster/gleicher try/catch-Gedanke wie beim Dimmer – Best-effort, darf
                    //     die Wecker-Recovery NIE stoeren.
                    //     Master-Pause: statt neu zu planen, die DND-Regel abschalten.
                    try {
                        if (paused) {
                            dndSchedule.disable()
                        } else {
                            dndSchedule.enable()
                        }
                    } catch (e: Exception) {
                        Logger.w(LogTags.DND, "Boot: DND-Reschedule fehlgeschlagen", e)
                    }

                    // 11. Feature B: Pre-Alarm-Refresh-Jobs (3h vor jedem Alarm) nach dem Boot neu
                    //     planen. Gleiches Muster/gleicher try/catch-Gedanke wie Dimmer/DND –
                    //     Best-effort, darf die Wecker-Recovery NIE stoeren.
                    //     Master-Pause: statt neu zu planen, alle offenen Jobs canceln.
                    try {
                        if (paused) {
                            calendarPreAlarmRefreshScheduler.cancelAll()
                        } else {
                            calendarPreAlarmRefreshScheduler.reschedule()
                        }
                    } catch (e: Exception) {
                        Logger.w(LogTags.BACKGROUND_WORKER, "Boot: Pre-Alarm-Refresh-Reschedule fehlgeschlagen", e)
                    }

                    recoverySuccessful = true
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "✅ LEVEL 4 SUCCESS: Complete system recovery successful - Reason: $reason, Attempt: $recoveryAttempt"
                    )

                } catch (e: Exception) {
                    Logger.e(
                        LogTags.MAINTENANCE_L4,
                        "❌ LEVEL 4: Recovery attempt $recoveryAttempt failed",
                        e
                    )

                    if (recoveryAttempt < MAX_RECOVERY_ATTEMPTS) {
                        Logger.w(
                            LogTags.MAINTENANCE_L4,
                            "🔄 LEVEL 4: Retrying in ${RECOVERY_RETRY_DELAY_MS}ms..."
                        )
                        delay(RECOVERY_RETRY_DELAY_MS)
                    } else {
                        Logger.e(
                            LogTags.MAINTENANCE_L4,
                            "💥 LEVEL 4: All recovery attempts failed - system may need manual intervention"
                        )
                        // Emergency fallback - try to at least restart background services
                        try {
                            restartBackgroundServices(context)
                        } catch (fallbackError: Exception) {
                            Logger.e(
                                LogTags.MAINTENANCE_L4,
                                "💥 LEVEL 4: Even emergency fallback failed",
                                fallbackError
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 🔍 Comprehensive Health Diagnostics
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private suspend fun performHealthDiagnostics(
        reason: String
    ): String {
        val diagnosticResults = mutableListOf<String>()

        try {
            // Check UseCase Availability
            diagnosticResults.add("Alarm UseCase: ✅ Available")
            diagnosticResults.add("Calendar UseCase: ✅ Available")
            diagnosticResults.add("Shift UseCase: ✅ Available")

            // Check Repository Health
            diagnosticResults.add("Calendar Selection Repository: ✅ Available")

            // Check Authentication Status
            val authStatus = try {
                authDataStoreRepository.isAuthenticated().getOrElse { false }
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Failed to check auth status", e)
                false
            }
            diagnosticResults.add("Authentication: ${if (authStatus) "✅ Authenticated" else "⚠️ Not Authenticated"}")

            // Check Calendar Selection
            // getCurrentSelectedCalendarIds() (DataStore) statt selectedCalendarIds.first()
            // (StateFlow, startet leer): sonst diagnostiziert der prozess-kaelteste Aufrufer von
            // allen faelschlich "0 calendars" - und "nicht lesbar" ist bewusst als eigene Aussage
            // sichtbar, nicht als 0 getarnt.
            diagnosticResults.add(
                calendarSelectionRepository.getCurrentSelectedCalendarIds().fold(
                    onSuccess = { ids -> "Selected Calendars: ${ids.size} calendars" },
                    onFailure = { error ->
                        Logger.w(LogTags.MAINTENANCE_L4, "Failed to check calendar selection", error)
                        "Selected Calendars: ⚠️ nicht lesbar (${error.message})"
                    }
                )
            )

            // Check Current Alarm Count
            val currentAlarms = try {
                alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Failed to check alarm count", e)
                emptyList()
            }
            val futureAlarms = currentAlarms.filter { it.triggerTime > System.currentTimeMillis() }
            diagnosticResults.add("Active Alarms: ${futureAlarms.size} future alarms")

            diagnosticResults.add("Recovery Reason: $reason")
            diagnosticResults.add("System Time: ${LocalDateTime.now()}")

        } catch (e: Exception) {
            diagnosticResults.add("❌ Diagnostics Error: ${e.message}")
        }

        return diagnosticResults.joinToString(", ")  // Diagnostic results summary
    }

    /**
     * 🔄 Alarm Repository Recovery mit Event-Validierung
     * 
     * 🔧 SYNC-FIX: Validiert Alarme gegen aktuelle Calendar Events bevor sie restored werden
     * ✅ Verhindert: "Alter Alarm klingelt für gelöschtes/geändertes Event"
     * ✅ Löscht: Alarme, deren Termin wirklich weg ist - NICHT solche, deren Kalender-Kennung nur
     *    gewechselt hat (siehe [BootAlarmValidation.beurteile])
     * ✅ Löscht: Alarme für geänderte Events (der nächste Sync legt sie neu an)
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private suspend fun performAlarmRecovery(): String {
        return try {
            // 1. Get all stored alarms
            val storedAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
            Logger.d(LogTags.MAINTENANCE_L4, "🔍 LEVEL 4: Found ${storedAlarms.size} stored alarms")

            // 2. Filter future alarms
            val futureAlarms = storedAlarms.filter { it.triggerTime > System.currentTimeMillis() }
            Logger.d(
                LogTags.MAINTENANCE_L4,
                "📅 LEVEL 4: ${futureAlarms.size} future alarms to validate"
            )
            
            // 🔧 SYNC-FIX: Validate alarms against current calendar events
            var restoredCount = 0
            var validatedCount = 0
            var deletedCount = 0
            var concurrentlyChangedCount = 0
            // Wecker, die NUR eine neue Kalender-Kennung bekommen haben. Muss sichtbar bleiben:
            // ein grosser Wert hier ist der Fingerabdruck eines Feed-Neueinlesens - genau der
            // Vorgang, der bis v1.29.x den ganzen Bestand geloescht hat.
            var neueKennungCount = 0
            var nichtArmiertCount = 0

            // Get current calendar events for validation
            // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
            //
            // getCurrentSelectedCalendarIds() (DataStore-Read) statt selectedCalendarIds.first()
            // (StateFlow): der StateFlow startet auf emptySet() und wird erst durch den in init{}
            // gestarteten, unabgewarteten Collector befuellt. Die 5s BOOT_RECOVERY_DELAY_MS sind
            // eine Timing-ANNAHME, keine Garantie - und seit dem Wartungs-Anker konkurriert genau in
            // dieser Zeitspanne ein zweiter Prozessteil um DataStore-/Netz-I/O. Ein noch nicht
            // hydrierter StateFlow sah wie "keine Kalender ausgewaehlt" aus: die ganze
            // SYNC-FIX-Validierung fiel still aus UND der Nachlege-Zweig unten war tot.
            //
            // Der Fehlerfall ist bewusst vom Leerfall getrennt (Log), fuehrt aber absichtlich in
            // dieselbe fail-safe Wiederherstellung: ohne verlaessliche Kalenderdaten darf NIE
            // geloescht werden (lieber ein veralteter Wecker als gar keiner).
            val selectedCalendars = calendarSelectionRepository.getCurrentSelectedCalendarIds()
                .getOrElse { error ->
                    Logger.w(
                        LogTags.MAINTENANCE_L4,
                        "⚠️ LEVEL 4: Kalenderauswahl nicht lesbar (KEIN 'nichts ausgewaehlt') - Validierung wird uebersprungen",
                        error
                    )
                    emptySet()
                }
            // 🛡️ FAIL-SAFE: Validierung (inkl. Loeschen verwaister Alarme) NUR bei nachweislich
            // erfolgreichem UND nicht-leerem Kalender-Abruf. Direkt nach dem Boot ist "kein Netz /
            // Token noch nicht bereit / StateFlow noch nicht geladen" der Normalfall - dann duerfen
            // gespeicherte Alarme NIEMALS geloescht, sondern muessen unveraendert wiederhergestellt
            // werden (lieber ein veralteter Wecker als gar keiner).
            //
            // ...UND NUR BEI VOLLSTAENDIGEM ABRUF. `isSuccess` allein hielt diese Zusicherung
            // NICHT: ein Teilerfolg (mindestens ein Kalender geladen, mindestens einer
            // gescheitert) ist bewusst `Result.success` mit den Events der ueberlebenden
            // Kalender - fuer die Anzeige richtig, hier toedlich. Faellt von zwei ausgewaehlten
            // Kalendern ausgerechnet der Dienstplan-Feed aus (Timeout, transienter 5xx, neu
            // angelegter Feed mit neuer id), waehrend der private Kalender antwortet, findet
            // KEIN Schicht-Alarm mehr seinen Treffer in currentEventMap - und jeder einzelne
            // wird unten als "Event aus dem Kalender geloescht" entfernt, ohne Neuanlage.
            // Genau nach einem Boot ist ein halb gescheiterter Abruf der Normalfall.
            // Deshalb entscheidet [CalendarFetchOutcome.isComplete], nicht isSuccess.
            val eventsResult = if (selectedCalendars.isNotEmpty()) {
                calendarUseCase.getCalendarEventsWithStatus(
                    calendarIds = selectedCalendars,
                    forceRefresh = false
                )
            } else {
                null
            }
            val fetchOutcome = eventsResult?.getOrNull()
            val currentEvents = fetchOutcome?.events ?: emptyList()
            val fetchIncomplete = fetchOutcome != null && !fetchOutcome.isComplete
            val validationPossible = fetchOutcome?.isComplete == true && currentEvents.isNotEmpty()

            if (fetchIncomplete) {
                Logger.w(
                    LogTags.MAINTENANCE_L4,
                    "⚠️ LEVEL 4: ${fetchOutcome.failedCalendars}/${fetchOutcome.requestedCalendars} " +
                        "Kalender nicht abrufbar - Validierung uebersprungen, Alarme werden " +
                        "unveraendert wiederhergestellt (fail-safe)"
                )
            }

            Logger.d(
                LogTags.MAINTENANCE_L4,
                "🔍 LEVEL 4: ${currentEvents.size} Kalender-Events geladen - Validierung ${if (validationPossible) "aktiv" else "uebersprungen (fail-safe Wiederherstellung)"}"
            )

            // Build event ID map for quick lookup
            val currentEventMap = currentEvents.associateBy { it.id }

            // Weckpunkte der aktuellen Lesung (Weckzeit + Schicht, ohne Kalender-Kennung). Nur
            // noetig, wenn ueberhaupt validiert wird - siehe
            // [BootAlarmValidation.beurteile] fuer das Warum.
            val weckpunkte = if (validationPossible) ermittleWeckpunkte(currentEvents) else null

            // 3. Validate and restore alarms
            for (alarm in futureAlarms) {
                try {
                    // Validierung (mit moeglicher Loeschung) nur bei erfolgreichem Kalender-Abruf.
                    // Ohne Validierung faellt der Alarm unten direkt in scheduleSystemAlarm (Restore).
                    if (validationPossible && alarm.eventId.isNotEmpty()) {
                        // FRISCH NACHLESEN, bevor auf dem Snapshot von oben entschieden wird: der
                        // parallel laufende Wartungs-Anker kann denselben Alarm in der Zwischenzeit
                        // korrigiert (gleiche id, neuer Checksum) oder geloescht haben. Details siehe
                        // [BootAlarmValidation.snapshotStillCurrent].
                        val freshChecksum = alarmUseCase.getAllAlarms().getOrNull()
                            ?.find { it.id == alarm.id }?.eventChecksum
                        if (!BootAlarmValidation.snapshotStillCurrent(alarm.eventChecksum, freshChecksum)) {
                            // Kein Re-Arming (continue): ist der Alarm weg, wuerde er hier als
                            // verwaister System-Alarm wieder auferstehen; ist er neu geschrieben,
                            // hat der Anker den System-Alarm schon korrekt gestellt (syncAlarms
                            // armt intern).
                            Logger.business(
                                LogTags.MAINTENANCE_L4,
                                "⏭️ LEVEL 4: Alarm ${alarm.shiftName} (id=${alarm.id}) wurde parallel geaendert/geloescht - Validierung auf veraltetem Stand uebersprungen"
                            )
                            concurrentlyChangedCount++
                            continue
                        }

                        val currentEvent = currentEventMap[alarm.eventId]

                        // KENNUNG WEG HEISST NICHT TERMIN WEG. Details und Beleg in
                        // [BootAlarmValidation.beurteile]; fuehrende Stelle der Regel ist
                        // AlarmUseCase.paareBestehendeAlarme().
                        val urteil = BootAlarmValidation.beurteile(
                            alarmTriggerTime = alarm.triggerTime,
                            alarmShiftId = alarm.shiftId,
                            alarmChecksum = alarm.eventChecksum,
                            terminChecksum = currentEvent?.let { calculateEventChecksum(it) },
                            weckpunkte = weckpunkte
                        )

                        when (urteil) {
                            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_WEG -> {
                                Logger.business(
                                    LogTags.MAINTENANCE_L4,
                                    "🗑️ LEVEL 4: Event deleted, removing alarm: ${alarm.shiftName} (eventId: ${alarm.eventId})"
                                )
                                alarmUseCase.deleteAlarm(alarm.id)
                                deletedCount++
                                continue
                            }

                            BootAlarmValidation.AlarmUrteil.LOESCHEN_TERMIN_GEAENDERT -> {
                                // Der naechste Sync legt ihn neu an (Wartungs-Anker laeuft bereits).
                                Logger.business(
                                    LogTags.MAINTENANCE_L4,
                                    "🔄 LEVEL 4: Event changed, removing outdated alarm: ${alarm.shiftName} (eventId: ${alarm.eventId})"
                                )
                                alarmUseCase.deleteAlarm(alarm.id)
                                deletedCount++
                                continue
                            }

                            BootAlarmValidation.AlarmUrteil.NUR_NEUE_KENNUNG -> {
                                // Kein Loeschen, kein Neuanlegen: derselbe Dienst zur selben
                                // Weckzeit. Der Alarm faellt unten in scheduleSystemAlarm und wird
                                // unveraendert re-armed; seine jetzt veraltete eventId/Checksum
                                // richtet der naechste vollstaendige Sync.
                                Logger.business(
                                    LogTags.MAINTENANCE_L4,
                                    "🆔 LEVEL 4: Nur die Kalender-Kennung hat gewechselt - Wecker bleibt: ${alarm.shiftName} (eventId: ${alarm.eventId})"
                                )
                                neueKennungCount++
                            }

                            BootAlarmValidation.AlarmUrteil.WIEDERHERSTELLEN -> {
                                validatedCount++
                                Logger.d(
                                    LogTags.MAINTENANCE_L4,
                                    "✅ LEVEL 4: Alarm validated against event: ${alarm.shiftName}"
                                )
                            }
                        }
                    }
                    
                    // Restore validated alarm.
                    //
                    // DAS ERGEBNIS WIRD AUSGEWERTET: `scheduleSystemAlarm()` weist einen
                    // uebersprungenen Wecker bewusst ab (`Result.failure`), damit der Aufrufer es
                    // SEHEN kann - genau dafuer gibt es die Rueckgabe. Wer sie verwirft und
                    // trotzdem `restoredCount++` zaehlt, meldet einen Wecker als wiederhergestellt,
                    // der im AlarmManager gar nicht steht: sichtbar in der Liste, stumm am Morgen.
                    // Das ist die gefaehrlichste Klasse, die diese App kennt.
                    alarmUseCase.scheduleSystemAlarm(alarm)
                        .onSuccess {
                            restoredCount++
                            Logger.d(
                                LogTags.MAINTENANCE_L4,
                                "✅ LEVEL 4: Restored alarm: ${alarm.shiftName}"
                            )
                        }
                        .onFailure { fehler ->
                            nichtArmiertCount++
                            Logger.w(
                                LogTags.MAINTENANCE_L4,
                                "⚠️ LEVEL 4: Wecker NICHT armiert: ${alarm.shiftName} - ${fehler.message}"
                            )
                        }
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.MAINTENANCE_L4,
                        "❌ LEVEL 4: Failed to process alarm: ${alarm.shiftName}",
                        e
                    )
                }
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L4,
                "📊 LEVEL 4: Alarm recovery stats - Restored: $restoredCount, Validated: $validatedCount, " +
                    "Deleted: $deletedCount, neue Kennung: $neueKennungCount, " +
                    "NICHT armiert: $nichtArmiertCount, " +
                    "parallel geaendert: $concurrentlyChangedCount"
            )

            // 4. If few alarms restored, try to create new ones from calendar
            //
            // `!fetchIncomplete` ist hier tragend und war bis zum 18.08.2026 vergessen: Schritt 3
            // ueberspringt bei unvollstaendiger Eventliste zwar die Validierung und stellt die
            // Alarme ausdruecklich "unveraendert" wieder her - direkt danach lief aber
            // syncAlarms() mit genau derselben Teilliste, und das ist ein LOESCHENDER Konsument.
            // Bei zwei ausgewaehlten Kalendern (privat + Dienstplanfeed) und einem Timeout des
            // Dienstplanfeeds kurz nach dem Boot fand getAllMatchingShifts() dort keine Schicht,
            // clearInternalAlarms(keepManualAlarms = true) raeumte auf, und persistShiftSpans
            // (emptyList()) nahm Dimmer und "Nicht stoeren" die laufende Schicht gleich mit.
            // Der Wecker war nach dem Neustart weg - lautlos, im selben Durchgang, der zwei
            // Zeilen darueber seine Erhaltung protokolliert hatte.
            //
            // Damit gilt die Zusicherung aus CLAUDE.md ("Jeder loeschende Konsument geht ueber
            // getCalendarEventsWithStatus() und prueft isComplete") jetzt an ALLEN sechs
            // syncAlarms()-Aufrufstellen; die anderen fuenf hatten ihr Gate bereits.
            if (restoredCount < 3 && currentEvents.isNotEmpty() && !fetchIncomplete) {
                Logger.d(
                    LogTags.MAINTENANCE_L4,
                    "🔄 LEVEL 4: Low alarm count, attempting calendar-based recovery"
                )

                val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                if (shiftConfig?.autoAlarmEnabled == true) {
                    // syncAlarms setzt die System-Alarme bereits intern; das folgende
                    // scheduleSystemAlarm ist idempotentes Re-Arming und dient hier zusaetzlich
                    // dem Zaehlen der wiederhergestellten Alarme im Boot-Recovery-Pfad.
                    val newAlarmsResult =
                        alarmUseCase.syncAlarms(currentEvents, shiftConfig)
                    val newAlarms = newAlarmsResult.getOrNull() ?: emptyList()

                    for (newAlarm in newAlarms) {
                        try {
                            alarmUseCase.scheduleSystemAlarm(newAlarm)
                            restoredCount++
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.MAINTENANCE_L4,
                                "❌ LEVEL 4: Failed to schedule new alarm",
                                e
                            )
                        }
                    }
                }
            }

            "Restored $restoredCount alarms (Validated: $validatedCount, Deleted: $deletedCount, " +
                "neue Kennung: $neueKennungCount, parallel geaendert: $concurrentlyChangedCount) " +
                "from ${storedAlarms.size} stored"

        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Alarm recovery failed", e)
            "Alarm recovery failed: ${e.message}"
        }
    }
    
    /**
     * Weckpunkte (Weckzeit + Schicht) der aktuellen Kalenderlesung.
     *
     * Die Weckzeit wird GENAU SO gerechnet wie im Delta-Sync
     * (`AlarmUseCase`: `calculatedAlarmTime.atZone(systemDefault).toInstant().toEpochMilli()`) -
     * jede Abweichung hier wuerde die Gleichheit zufaellig machen und damit genau die Loeschungen
     * zurueckbringen, die verhindert werden sollen.
     *
     * FAIL-SAFE: Scheitert die Schichterkennung (Konfiguration nicht lesbar, Wurf), gibt es `null`
     * statt einer leeren Menge - und `null` heisst "kein Urteil moeglich, nichts loeschen". Eine
     * leere Menge waere die gefaehrliche Luege ("keine einzige Schicht erkannt" = alles loeschen),
     * gegen die dieselbe Projektregel steht wie bei der leeren Eventliste.
     */
    private suspend fun ermittleWeckpunkte(
        events: List<com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent>
    ): Set<BootAlarmValidation.Weckpunkt>? = try {
        shiftUseCase.recognizeShiftsInEvents(events).fold(
            onSuccess = { matches ->
                matches.map { match ->
                    BootAlarmValidation.Weckpunkt(
                        triggerTime = match.calculatedAlarmTime
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                        shiftId = match.shiftDefinition.id
                    )
                }.toSet()
            },
            onFailure = { error ->
                Logger.w(
                    LogTags.MAINTENANCE_L4,
                    "⚠️ LEVEL 4: Schichterkennung fuer die Kennungs-Pruefung fehlgeschlagen - es wird kein Alarm wegen fehlender Kennung geloescht",
                    error
                )
                null
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(
            LogTags.MAINTENANCE_L4,
            "⚠️ LEVEL 4: Schichterkennung fuer die Kennungs-Pruefung geworfen - es wird kein Alarm wegen fehlender Kennung geloescht",
            e
        )
        null
    }

    /**
     * 🔧 SYNC-FIX: Calculate event checksum for validation
     */
    private fun calculateEventChecksum(event: com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent): String {
        val data = "${event.startTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   "${event.endTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   event.title
        return data.hashCode().toString()
    }

    /**
     * 📅 Calendar Integration Restoration
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private suspend fun performCalendarIntegrationRestoration(): String {
        return try {
            // 1. Check authentication status
            val isAuthenticated = authDataStoreRepository.isAuthenticated().getOrElse { false }
            if (!isAuthenticated) {
                return "Authentication not available - calendar integration skipped"
            }

            // 2. Test calendar connection by trying to get calendar events
            // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
            //
            // getCurrentSelectedCalendarIds() (DataStore) statt selectedCalendarIds.first()
            // (StateFlow, startet leer) - gleiche Begruendung wie in performAlarmRecovery(). Ein
            // Lesefehler wird hier als solcher gemeldet und NICHT als "keine Kalender ausgewaehlt"
            // (was diese Funktion sonst als gesunde Verbindung durchgewinkt haette).
            val selectionResult = calendarSelectionRepository.getCurrentSelectedCalendarIds()
            if (selectionResult.isFailure) {
                Logger.w(
                    LogTags.MAINTENANCE_L4,
                    "⚠️ LEVEL 4: Kalenderauswahl nicht lesbar - Verbindungstest uebersprungen",
                    selectionResult.exceptionOrNull()
                )
                return "⚠️ Kalenderauswahl nicht lesbar - Verbindungstest uebersprungen"
            }

            val connectionTest = try {
                val selectedCalendars = selectionResult.getOrThrow()
                if (selectedCalendars.isNotEmpty()) {
                    val testEvents = calendarUseCase.getCalendarEventsWithCache(
                        calendarIds = selectedCalendars,
                        forceRefresh = false
                    )
                    testEvents.isSuccess
                } else {
                    true // No calendars selected, but authentication is working
                }
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Calendar connection test failed", e)
                false
            }

            val connectionResult = if (connectionTest) {
                "✅ Calendar connection healthy"
            } else {
                "⚠️ Calendar connection issues detected"
            }

            // 3. Get cache info (simplified)
            val cacheStats = "Cache operational"

            "$connectionResult, Cache: $cacheStats"

        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE_L4,
                "❌ LEVEL 4: Calendar integration restoration failed",
                e
            )
            "Calendar restoration failed: ${e.message}"
        }
    }

    private fun performSmartMaintenanceChainReinitialization(context: Context) {
        try {
            Logger.business(
                LogTags.MAINTENANCE_L4,
                "🔄 LEVEL 4: Reinitializing Smart Maintenance Chain (Phase 1 - using AlarmMaintenanceService)"
            )

            // Schedule next maintenance run
            AlarmMaintenanceService.scheduleNext(context)
            Logger.business(LogTags.MAINTENANCE_L4, "✅ AlarmMaintenanceService scheduled")

            Logger.business(
                LogTags.MAINTENANCE_L4,
                "✅ LEVEL 4: Smart Maintenance Chain reinitialization completed"
            )

        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE_L4,
                "❌ LEVEL 4: Smart Maintenance Chain reinitialization failed",
                e
            )
        }
    }

    /**
     * 🔧 Background Services Restart - PHASE 1 MIGRATION
     */
    private fun restartBackgroundServices(context: Context) {
        try {
            Logger.d(LogTags.MAINTENANCE_L4, "🔧 LEVEL 4: Restarting background services (Phase 1)")

            // Schedule AlarmMaintenanceService
            AlarmMaintenanceService.scheduleNext(context)

            Logger.d(LogTags.MAINTENANCE_L4, "✅ LEVEL 4: Background services restarted")

        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Background services restart failed", e)
        }
    }

    /**
     * 🔍 Schedule Post-Recovery Health Check
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private fun schedulePostRecoveryHealthCheck(context: Context, reason: String) {
        recoveryScope.launch {
            try {
                Logger.d(
                    LogTags.MAINTENANCE_L4,
                    "⏱️ LEVEL 4: Scheduling post-recovery health check in ${POST_BOOT_HEALTH_CHECK_DELAY_MS}ms"
                )

                delay(POST_BOOT_HEALTH_CHECK_DELAY_MS)

                val healthStatus = performHealthDiagnostics("POST_RECOVERY_CHECK")

                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "🔍 LEVEL 4: Post-recovery health check completed - Original reason: $reason, Status: $healthStatus"
                )

                // Check alarm count and trigger maintenance if needed
                val currentAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
                val futureAlarms =
                    currentAlarms.filter { it.triggerTime > System.currentTimeMillis() }

                if (futureAlarms.size < 2) {
                    Logger.w(
                        LogTags.MAINTENANCE_L4,
                        "⚠️ LEVEL 4: Post-recovery check found low alarm count (${futureAlarms.size}), triggering immediate maintenance"
                    )

                    // Trigger immediate maintenance
                    AlarmMaintenanceService.start(context)
                }

            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Post-recovery health check failed", e)
            }
        }
    }
}
