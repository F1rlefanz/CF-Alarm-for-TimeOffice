package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ManualAlarmSnapshot
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Das Ueberspringen wurde MITTEN IM VORGANG zurueckgenommen, weil der uebersprungene Alarm sich
 * nicht loeschen liess (Begruendung an Schritt 4 in [AlarmSkipUseCase.skipNextAlarm]).
 *
 * Der Zustand, den der Aufrufer danach vorfindet: der Eintrag liegt noch im Bestand, sein
 * Systemalarm ist aber bereits gecancelt - ein sichtbarer, stummer Wecker, und damit die
 * schlimmste Klasse ueberhaupt. Deshalb ist [alarmId] Teil des Fehlers und kein Log-Detail: der
 * Aufrufer muss den Systemalarm wieder stellen.
 *
 * [skipFlagCleared] sagt, ob der Skip-Merker dabei wirklich verschwunden ist. Nur dann kommt ein
 * Re-Arming ueberhaupt durch - sonst weist es der Skip-Backstop in
 * `AlarmUseCase.scheduleSystemAlarm()` ab.
 */
class SkipRolledBackException(
    val alarmId: Int,
    val skipFlagCleared: Boolean,
    cause: Throwable?
) : Exception(
    "Ueberspringen von Alarm $alarmId zurueckgenommen: der Alarm liess sich nicht loeschen",
    cause
)

/**
 * Use case implementation for alarm skip functionality.
 * Handles business logic for skipping alarms.
 *
 * CRITICAL FIX: Ensures system alarm is cancelled when alarm is skipped
 * ✅ Prevents "ghost alarms" that trigger despite being skipped
 * ✅ Properly cleans up both DataStore state AND Android AlarmManager
 *
 * EIN UEBERSPRUNGENER ALARM IST WEG - fuer JEDE Alarmart (siehe [skipNextAlarm]). Damit
 * "Aufheben" trotzdem umkehrbar bleibt, wird ein MANUELLER Wecker vorher als
 * [ManualAlarmSnapshot] im Skip-Zustand gesichert; ein kalenderbasierter braucht das nicht, er
 * entsteht aus dem Kalenderstand neu.
 */
class AlarmSkipUseCase @Inject constructor(
    private val alarmSkipRepository: IAlarmSkipRepository,
    private val alarmRepository: IAlarmRepository,
    private val alarmManagerService: AlarmManagerService
) : IAlarmSkipUseCase {

    companion object {
        /**
         * Praefix der `shiftId` eines von Hand gestellten Weckers.
         *
         * Quelle des Werts ist `AlarmViewModel.ManualAlarmConstants.MANUAL_SHIFT_ID_PREFIX`; hier
         * bewusst als eigene Konstante gefuehrt, weil ein UseCase nicht auf ein ViewModel
         * zugreifen darf. Damit daraus keine zweite Wahrheit wird, haelt
         * `AlarmSkipManualAlarmTest` beide Werte gegeneinander fest - laufen sie auseinander,
         * faellt der Test um, nicht der Wecker.
         */
        internal const val MANUAL_SHIFT_ID_PREFIX = "manual_"

        /** Nur ein manueller Alarm braucht den Schnappschuss - siehe [skipNextAlarm]. */
        internal fun isManualAlarm(alarmInfo: AlarmInfo): Boolean =
            alarmInfo.shiftId.startsWith(MANUAL_SHIFT_ID_PREFIX)

        /** Loeschversuche beim Ueberspringen, inklusive des ersten - siehe [loescheMitNachfassen]. */
        internal const val DELETE_ATTEMPTS = 2
        internal const val DELETE_RETRY_DELAY_MS = 250L
    }

    override val skipStatusFlow: Flow<AlarmSkipState> = alarmSkipRepository.skipStatusFlow
    
    override suspend fun skipNextAlarm(): Result<AlarmSkipResult> {
        // Die Ruecknahme muss den Aufrufer MIT IHRER IDENTITAET erreichen: `SafeExecutor`
        // verwandelt jede Exception in einen `AppError`, und damit waeren `alarmId` und
        // `skipFlagCleared` unterwegs verloren - genau die zwei Angaben, mit denen das
        // AlarmViewModel den stumm gewordenen Wecker wieder stellt. Deshalb wird sie hier
        // gemerkt und nach dem Lauf an die Stelle des verpackten Fehlers gesetzt.
        var ruecknahme: SkipRolledBackException? = null
        val ergebnis = SafeExecutor.safeExecute("AlarmSkipUseCase.skipNextAlarm") {
            // 1. Nächsten Alarm ermitteln
            val nextAlarm = findNextAlarm()
                ?: throw IllegalStateException("Kein aktiver Alarm gefunden")

            // 1a. Ist die Alarm-Persistenz gesperrt, kann dieses Ueberspringen gar nicht gelingen -
            // und zwar STILL: `AlarmRepository.deleteAlarm()` entfernt den Eintrag dann nur aus dem
            // Arbeitsspeicher, `persistToDataStore()` kehrt bei gesperrter Persistenz sofort zurueck
            // und meldet trotzdem Erfolg. Die Ruecknahme unten haengt am Fehlersignal des Loeschens
            // und griffe deshalb NIE, waehrend Preferences-Datei und Direct-Boot-Spiegel den Alarm
            // unveraendert weitertragen - genau der Zustand, vor dem Schritt 4 warnt.
            // Also hier abbrechen, BEVOR Merker und Systemalarm angefasst sind: der Wecker bleibt
            // vollstaendig stehen und klingelt, der Nutzer erfaehrt den Fehlschlag. Klingeln ist
            // die sichere Richtung.
            if (alarmRepository.isPersistenceBlocked()) {
                throw IllegalStateException(
                    "Ueberspringen nicht moeglich: der Alarm-Bestand laesst sich gerade nicht " +
                        "speichern - der Wecker bleibt bestehen und klingelt"
                )
            }

            // Ab hier NICHT MEHR ABBRECHBAR - genau wie `pause()`/`resume()` der Master-Pause.
            //
            // Die Schritte 2 bis 4 stellen einen Zustand HER, und zwischen ihnen liegt mit dem
            // Nachfass-Warten in [loescheMitNachfassen] ein echter Abbruchpunkt. Zu diesem
            // Zeitpunkt ist der Systemalarm bereits gecancelt und der Merker gesetzt; ein
            // Abbruch des aufrufenden `viewModelScope` (Activity endgueltig beendet, ViewModel
            // geraeumt) verliesse die Kette als CancellationException - `SafeExecutor` wirft die
            // bewusst weiter -, und weder die Ruecknahme des Merkers hier noch das Re-Arming im
            // AlarmViewModel liefen dann. Zurueck bliebe genau der Zwischenzustand, den die
            // Ruecknahme beseitigen soll: Merker gesetzt, Eintrag im Bestand, Systemalarm
            // gecancelt - und weil der gesetzte Merker jedes Re-Armieren durch den Sync abweist,
            // kaeme der Wecker erst durch das zeitbasierte Ablaufen des Merkers oder einen
            // Neustart zurueck. Das Fenster ist kurz und der Umfang klein (zwei
            // DataStore-Schreibvorgaenge plus 250 ms), das rechtfertigt die Unabbrechbarkeit.
            withContext(NonCancellable) {
                // 2. Skip-Status setzen - MIT Schnappschuss, falls es ein manueller Wecker ist.
                //
                // Die Sicherung passiert VOR dem Loeschen und ist der einzige Schritt hier, dessen
                // Fehlschlag den ganzen Vorgang abbricht (getOrThrow): laesst sich der Zustand nicht
                // schreiben, bleibt der Wecker unangetastet stehen und klingelt - das ist die
                // richtige Richtung. Umgekehrt (erst loeschen, dann sichern) waere ein Absturz
                // dazwischen ein spurlos verschwundener Wecker.
                val manualSnapshot = if (isManualAlarm(nextAlarm)) {
                    ManualAlarmSnapshot.encode(nextAlarm)
                } else {
                    null
                }
                alarmSkipRepository.setNextAlarmSkipped(
                    alarmId = nextAlarm.id,
                    triggerTime = nextAlarm.triggerTime,
                    manualAlarmSnapshot = manualSnapshot
                ).getOrThrow()
            
                // 3. ✅ UX-FIX: Systemalarm SOFORT löschen für direktes User-Feedback
                // User erwartet dass der Alarm aus der Statusleiste verschwindet wenn er "überspringen" drückt
                Logger.business(LogTags.ALARM_SKIP, "⏭️ SKIP-IMMEDIATE: Deleting system alarm ${nextAlarm.id} immediately for better UX")
                try {
                    alarmManagerService.cancelSystemAlarm(nextAlarm.id)
                    Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-IMMEDIATE: System alarm cancelled - user sees immediate feedback")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-IMMEDIATE: Failed to cancel system alarm", e)
                }
            
                // 4. Alarm aus Repository loeschen - IMMER, fuer jede Alarmart.
                //
                // Die Reihenfolge (erst cancelSystemAlarm, dann deleteAlarm) ist Pflicht, und dass
                // hier nichts stehenbleibt, ebenfalls: mehrere unabhaengige Stellen der App lesen den
                // Alarm-Bestand OHNE Skip-Filter - der Direct-Boot-Spiegel, aus dem der BootReceiver
                // jeden Zukunfts-Eintrag direkt wieder armiert (am Skip-Backstop vorbei), und die
                // Hue-Tagesplanung, die daraus ihre Sonnenaufgangs-Starts baut. Ein "uebersprungener"
                // Eintrag, der liegenbleibt, klingelt nach einem naechtlichen Neustart trotzdem und
                // schaltet am uebersprungenen Morgen das Licht ein. Die Umkehrbarkeit fuer manuelle
                // Wecker traegt der Schnappschuss oben, nicht ein geschonter Eintrag.
                //
                // SCHEITERT DAS LOESCHEN, DARF DER SKIP NICHT BESTEHEN BLEIBEN. Bis v1.28.0 wurde der
                // Fehlschlag nur geloggt - der Merker blieb gesetzt, die Oberflaeche meldete
                // "uebersprungen", und genau der Eintrag, vor dem der Absatz darueber warnt, lag
                // weiter im Bestand UND im Direct-Boot-Spiegel. Der Merker liegt im settings-Store
                // (CE-Storage), vor der ersten Entsperrung also unlesbar; der BootReceiver kennt
                // ohnehin keinen Skip. Ein naechtlicher Neustart machte aus dem "uebersprungenen"
                // Wecker damit einen klingelnden - die schlimmste Form der Luege in dieser App.
                //
                // Also: einmal nachfassen (ein DataStore-Schreibfehler ist oft voruebergehend), und
                // wenn auch das scheitert, das Ueberspringen zuruecknehmen und laut scheitern. Der
                // Systemalarm ist zu diesem Zeitpunkt bereits gecancelt, ein blosses Zuruecknehmen
                // des Merkers hinterliesse also einen sichtbaren, aber stummen Eintrag. Deshalb
                // traegt [SkipRolledBackException] die alarmId: der Aufrufer (AlarmViewModel) stellt
                // den Systemalarm sofort wieder. Von HIER aus geht das nicht und kann es nicht gehen -
                // `AlarmUseCase` haengt fuer seinen Skip-Backstop bereits an diesem UseCase, die
                // Gegenrichtung waere ein Zyklus im DI-Graphen.
                val geloescht = loescheMitNachfassen(nextAlarm.id)
                if (geloescht.isFailure) {
                    val merkerZurueckgenommen = alarmSkipRepository.clearSkipStatus()
                    if (merkerZurueckgenommen.isFailure) {
                        // Endzustand hier: Merker gesetzt, Eintrag vorhanden, Systemalarm gecancelt.
                        // Unangenehm, aber die sichere Richtung bleibt gewahrt - der BootReceiver
                        // armiert den Eintrag ungefiltert wieder, und der Merker laeuft zeitbasiert
                        // ab. Der Nutzer erfaehrt es ueber den Fehlschlag dieses Aufrufs.
                        Logger.e(
                            LogTags.ALARM_SKIP,
                            "❌ SKIP: Ruecknahme des Skip-Merkers fuer Alarm ${nextAlarm.id} ebenfalls fehlgeschlagen",
                            merkerZurueckgenommen.exceptionOrNull()
                        )
                    }
                    val abgebrochen = SkipRolledBackException(
                        alarmId = nextAlarm.id,
                        skipFlagCleared = merkerZurueckgenommen.isSuccess,
                        cause = geloescht.exceptionOrNull()
                    )
                    ruecknahme = abgebrochen
                    throw abgebrochen
                }
                Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-IMMEDIATE: Alarm deleted from repository")
            }

            // 5. Result erstellen
            AlarmSkipResult(
                alarmId = nextAlarm.id,
                alarmName = nextAlarm.shiftName,
                formattedTime = nextAlarm.formattedTime
            )
        }
        return ruecknahme?.let { Result.failure(it) } ?: ergebnis
    }

    override suspend fun cancelSkip(): Result<Unit> = 
        alarmSkipRepository.clearSkipStatus()
    
    override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> = 
        SafeExecutor.safeExecute("AlarmSkipUseCase.checkAndProcessSkip") {
            val isSkipped = alarmSkipRepository.isAlarmSkipped(alarmId).getOrThrow()
            
            if (isSkipped) {
                Logger.business(LogTags.ALARM_SKIP, "⏭️ SKIP-FIX: Processing skip for alarm $alarmId")
                
                // CRITICAL FIX Step 1: Cancel the system alarm (Android AlarmManager)
                // This prevents the alarm from triggering again
                try {
                    alarmManagerService.cancelSystemAlarm(alarmId)
                    Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-FIX: System alarm $alarmId cancelled successfully")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-FIX: Failed to cancel system alarm $alarmId", e)
                    // Continue anyway - we still want to clear the skip status
                }
                
                // CRITICAL FIX Step 2: Delete the alarm from repository
                // This removes it from the app's internal alarm list
                //
                // HIER WIRD DER FEHLSCHLAG BEWUSST NUR GELOGGT - anders als in [skipNextAlarm],
                // und das soll so bleiben. Der Unterschied ist der Zeitpunkt: dort steht der
                // Wecktermin noch bevor, eine Ruecknahme bringt den Nutzer sauber in den
                // Ausgangszustand zurueck. Hier FEUERT der Alarm gerade - der Nutzer hat ihn
                // vorher ausdruecklich uebersprungen, und die einzige Aufgabe dieses Pfades ist,
                // ihn stillzulegen. Ein Abbruch wuerde Schritt 3 (Merker raeumen) verhindern und
                // den Skip auf den naechsten, voellig anderen Alarm durchschlagen lassen. Ein
                // liegengebliebener Eintrag mit verstrichener Weckzeit ist demgegenueber harmlos:
                // BootReceiver und Hue-Tagesplanung sehen nur Zukunfts-Eintraege.
                try {
                    alarmRepository.deleteAlarm(alarmId).getOrThrow()
                    Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-FIX: Alarm $alarmId deleted from repository")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-FIX: Failed to delete alarm from repository", e)
                    // Continue anyway - we still want to clear the skip status
                }
                
                // CRITICAL FIX Step 3: Clear skip status after successful processing
                // This prevents the skip from affecting other alarms
                alarmSkipRepository.clearSkipStatus().getOrThrow()
                Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-FIX: Alarm $alarmId successfully skipped and cleaned up")
                
                SkipProcessResult.ALARM_SKIPPED
            } else {
                Logger.business(LogTags.ALARM_SKIP, "▶️ Alarm $alarmId executed normally (not skipped)")
                SkipProcessResult.ALARM_EXECUTED
            }
        }
    
    override suspend fun getSkipStatus(): Result<AlarmSkipState> =
        alarmSkipRepository.getSkipStatus()

    override suspend fun clearExpiredSkip(): Result<Boolean> =
        SafeExecutor.safeExecute("AlarmSkipUseCase.clearExpiredSkip") {
            val skipState = alarmSkipRepository.getSkipStatus().getOrThrow()
            val isExpired = skipState.isNextAlarmSkipped &&
                skipState.skippedAlarmTriggerTime > 0 &&
                System.currentTimeMillis() > skipState.skippedAlarmTriggerTime

            if (isExpired) {
                alarmSkipRepository.clearSkipStatus().getOrThrow()
                Logger.business(
                    LogTags.ALARM_SKIP,
                    "⏰ Skip abgelaufen (Ziel-Alarm ${skipState.skippedAlarmId} längst verstrichen) – automatisch zurückgesetzt"
                )
            }

            isExpired
        }

    /**
     * Loescht den uebersprungenen Alarm und fasst bei einem Fehlschlag genau einmal nach.
     *
     * Der haeufige Fall ist ein voruebergehender DataStore-Schreibfehler; ihn sofort in die
     * Ruecknahme laufen zu lassen, wuerde dem Nutzer ein funktionierendes Ueberspringen ohne Not
     * verweigern. Bleibt es beim Fehlschlag, entscheidet die Aufrufstelle (siehe [skipNextAlarm]).
     */
    private suspend fun loescheMitNachfassen(alarmId: Int): Result<Unit> {
        var ergebnis = loescheUndPruefeDauerhaftigkeit(alarmId)
        var versuch = 1
        while (ergebnis.isFailure && versuch < DELETE_ATTEMPTS) {
            Logger.w(
                LogTags.ALARM_SKIP,
                "⚠️ SKIP: Loeschen des uebersprungenen Alarms $alarmId fehlgeschlagen " +
                    "(Versuch $versuch/$DELETE_ATTEMPTS) - wird wiederholt",
                ergebnis.exceptionOrNull()
            )
            delay(DELETE_RETRY_DELAY_MS)
            ergebnis = loescheUndPruefeDauerhaftigkeit(alarmId)
            versuch++
        }
        return ergebnis
    }

    /**
     * Loescht einmal und prueft, ob das Loeschen ueberhaupt dauerhaft sein KONNTE.
     *
     * `AlarmRepository.deleteAlarm()` meldet auch dann Erfolg, wenn nur der Arbeitsspeicher
     * geraeumt wurde: bei gesperrter Persistenz (gescheiterter Init-Load) kehrt
     * `persistToDataStore()` sofort zurueck, ohne zu schreiben und ohne zu werfen. Ohne diese
     * Nachfrage wuerde die Ruecknahme in [skipNextAlarm] genau im wichtigsten Fall nicht anspringen
     * - Preferences-Datei und Direct-Boot-Spiegel behielten den Alarm, der BootReceiver armierte
     * ihn nach einem naechtlichen Neustart ungefiltert wieder, und der "uebersprungene" Wecker
     * klingelte doch. Die Sperre kann zwischen [skipNextAlarm]s Vorpruefung und hier auch erst
     * entstehen (ein Nachlade-Versuch scheitert nebenlaeufig), deshalb wird sie hier erneut gefragt.
     */
    private suspend fun loescheUndPruefeDauerhaftigkeit(alarmId: Int): Result<Unit> {
        val ergebnis = alarmRepository.deleteAlarm(alarmId)
        if (ergebnis.isFailure) return ergebnis
        return if (alarmRepository.isPersistenceBlocked()) {
            Result.failure(
                IllegalStateException(
                    "Alarm $alarmId wurde nur aus dem Arbeitsspeicher entfernt - die Persistenz " +
                        "ist gesperrt, Alarm-Bestand und Direct-Boot-Spiegel behalten ihn"
                )
            )
        } else {
            ergebnis
        }
    }

    private suspend fun findNextAlarm(): AlarmInfo? {
        val currentTime = System.currentTimeMillis()
        return alarmRepository.getAllAlarms()
            .getOrNull()
            ?.filter { it.triggerTime > currentTime }
            ?.minByOrNull { it.triggerTime }
    }
}
