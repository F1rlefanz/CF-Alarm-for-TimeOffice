package com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause

import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Master-Pause: EIN Schalter, der ALLE autonomen Hintergrunddienste gemeinsam an-/abschaltet -
 * die 6h-Wartungskette ([AlarmMaintenanceService]), alle gestellten Alarme ([IAlarmUseCase]),
 * Schicht-Dimmer ([DimScheduleUseCase]), DND-Steuerung ([DndScheduleUseCase]), Hue-SmartScheduler
 * ([HueSmartScheduler]) und den Pre-Alarm-Refresh ([CalendarPreAlarmRefreshScheduler]).
 *
 * [pause] raeumt aktiv auf (Alarme loeschen, Ketten stoppen, einen gerade klingelnden Wecker
 * beenden), statt nur den Schalter umzulegen - sonst liefe alles bis zum naechsten natuerlichen
 * Ende einfach weiter. [resume] baut dieselben
 * Ketten wieder neu auf, analog zum bestehenden Onboarding-/Boot-Restore-Pfad.
 */
@Singleton
class MasterPauseUseCase @Inject constructor(
    private val prefs: MasterPausePrefs,
    private val alarmUseCase: IAlarmUseCase,
    private val dimSchedule: DimScheduleUseCase,
    private val dndSchedule: DndScheduleUseCase,
    private val hueSmartScheduler: HueSmartScheduler,
    private val calendarPreAlarmRefreshScheduler: CalendarPreAlarmRefreshScheduler,
    private val directBootAlarmStore: DirectBootAlarmStore,
    @param:ApplicationContext private val context: Context
) {
    val paused: Flow<Boolean> = prefs.paused

    /**
     * Bringt den Device-Protected-Spiegel des Pausenzustands mit der CE-Wahrheit in Deckung.
     *
     * `KEY_PAUSED` hat genau zwei Schreiber ([pause]/[resume]) und drei Leser, die ALLE im Boot-Pfad
     * sitzen - der Spiegel ist das einzige, was der `BootReceiver` vor der ersten Entsperrung ueber
     * die Pause weiss. `savePaused()` schluckt seinen Fehler; faellt ein Schreibvorgang aus,
     * divergieren beide dauerhaft, denn es gab bisher keinen einzigen Pfad, der sie wieder
     * abgleicht. Beide Richtungen sind schlecht: ein haengendes `true` sperrt die
     * Boot-Wiederherstellung dauerhaft (kein Wecker nach dem naechsten Neustart), ein haengendes
     * `false` re-armt Alarme, die der Nutzer pausiert hat.
     *
     * Wird beim App-Start aufgerufen (best effort, nur bei entsperrtem Geraet - vorher ist der
     * CE-Wert nicht lesbar) und ist billig: ein Read und im Regelfall kein Write.
     */
    suspend fun reconcileDirectBootMirror() {
        val truth = prefs.pausedNow()
        val mirror = directBootAlarmStore.isPausedNow()
        if (truth == mirror) return

        Logger.w(
            LogTags.MASTER_PAUSE,
            "🔄 MASTER-PAUSE: Spiegel und Wahrheit lagen auseinander (Spiegel=$mirror, " +
                "tatsaechlich=$truth) - Spiegel wird korrigiert. Der BootReceiver liest ihn vor der " +
                "ersten Entsperrung; falsch herum haette er entweder die Wiederherstellung gesperrt " +
                "oder pausierte Alarme re-armiert."
        )
        directBootAlarmStore.savePaused(truth)
    }

    /**
     * NICHT abbrechbar: die Sequenz stellt einen Zustand HER, statt nur einen Schalter umzulegen -
     * und der Schalter wird als ERSTES geschrieben. Bricht der Aufrufer-Scope mitten dabei ab
     * (`viewModelScope` des `MasterPauseViewModel`: Activity beendet, Task weggewischt), stehen Flag
     * und Wirklichkeit auseinander. Beide Richtungen sind gefaehrlich: bei `pause()` zeigt die App
     * "pausiert", waehrend 6h-Wartung, Dimmer-Tick, DND-Tick und Hue-Planung weiterlaufen; bei
     * `resume()` zeigt sie "aktiv", waehrend keine dieser Ketten wieder angelaufen ist - der Wecker
     * bliebe STILL, und beim naechsten Boot liest der `BootReceiver` einen Spiegel, der nicht mehr
     * zum Flag passt.
     */
    suspend fun pause() = withContext(NonCancellable) {
        prefs.setPaused(true)
        // Device-Protected-Spiegel im selben Atemzug wie das DataStore-Flag - BootReceiver liest
        // ihn bei LOCKED_BOOT_COMPLETED, wo @MainDataStore (CE-Storage) noch nicht lesbar ist.
        directBootAlarmStore.savePaused(true)
        // EINEN GERADE KLINGELNDEN WECKER BEENDEN (Pruefrunde 8).
        //
        // pause() raeumte bisher nur die PLANUNG ab und liess das gerade Laufende in Ruhe: der
        // Wecker klingelte nach dem Pausieren einfach weiter, waehrend die Oberflaeche
        // "Hintergrunddienste pausiert" zeigte. Schlimmer noch, seine Notification blieb mitsamt
        // Schlummer-Knopf stehen - ein Druck darauf armierte einen neuen Wecker mitten in der
        // Pause, den danach nichts mehr abraeumte (die 6h-Kette ist hier unten gerade gekappt
        // worden). Der Schlummer-Pfad hat dagegen jetzt seinen eigenen Backstop; diese Zeile
        // beseitigt den Anlass.
        //
        // BEWUSST UNBEDINGT, nicht auf AlarmSoundService.alarmActive gegated: die Richtung ist
        // fail-safe zu waehlen, und "Pause heisst still" ist die Zusage der Oberflaeche. Laeuft
        // gar kein Wecker, verarbeitet der Dienst den Stop-Intent und beendet sich sofort wieder;
        // waere der Zustandsmerker dagegen veraltet, bliebe der Wecker laut.
        //
        // try/catch wie bei jedem Schritt unten: aus dem Hintergrund heraus lehnt Android ab
        // Android 8 einen startService() ab, und das darf die Pause nicht zerreissen.
        try {
            context.startService(
                Intent(context, AlarmSoundService::class.java)
                    .setAction(AlarmSoundService.ACTION_STOP_ALARM)
            )
        } catch (e: Exception) {
            Logger.w(
                LogTags.MASTER_PAUSE,
                "⚠️ MASTER-PAUSE: laufender Wecker konnte nicht gestoppt werden - er klingelt " +
                    "weiter, obwohl die App pausiert anzeigt",
                e
            )
        }
        alarmUseCase.deleteAllAlarms()
            .onSuccess {
                Logger.business(LogTags.MASTER_PAUSE, "✅ MASTER-PAUSE: Alarme geloescht")
            }
            .onFailure { error ->
                Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Loeschen der Alarme fehlgeschlagen", error)
            }
        // Jeder Schritt eigenes try/catch (Vorbild: BootReceiver.performCompleteSystemRecovery()) -
        // sonst reisst ein einzelner Fehler alle NACHFOLGENDEN Schritte mit ab, obwohl das
        // Pause-Flag oben bereits geschrieben ist: UI und Flag saegen "pausiert", aber ein
        // spaeterer Schritt (z.B. Hue-Cleanup) liefe unbemerkt weiter.
        try {
            AlarmMaintenanceService.cancelNext(context)
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: 6h-Wartung canceln fehlgeschlagen", e)
        }
        try {
            dimSchedule.disable()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Dimmer-Disable fehlgeschlagen", e)
        }
        try {
            dndSchedule.disable()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: DND-Disable fehlgeschlagen", e)
        }
        try {
            hueSmartScheduler.cleanup()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Hue-Cleanup fehlgeschlagen", e)
        }
        try {
            calendarPreAlarmRefreshScheduler.cancelAll()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Pre-Alarm-Refresh-Cancel fehlgeschlagen", e)
        }
        Logger.business(LogTags.MASTER_PAUSE, "Hintergrunddienste pausiert")
    }

    /** Nicht abbrechbar - siehe [pause]. */
    suspend fun resume() = withContext(NonCancellable) {
        prefs.setPaused(false)
        directBootAlarmStore.savePaused(false)
        // Jeder Schritt eigenes try/catch (Vorbild: BootReceiver.performCompleteSystemRecovery()) -
        // sonst reisst ein einzelner Fehler alle NACHFOLGENDEN Schritte mit ab, obwohl das
        // Pause-Flag oben bereits geschrieben ist: UI und Flag saegen "fortgesetzt", aber ein
        // spaeterer Schritt (z.B. Hue-Init) liefe unbemerkt nicht wieder an.
        try {
            AlarmMaintenanceService.scheduleNext(context)
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: 6h-Wartung neu planen fehlgeschlagen", e)
        }
        try {
            AlarmMaintenanceService.start(context)
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: 6h-Wartung starten fehlgeschlagen", e)
        }
        try {
            dimSchedule.enable()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Dimmer-Enable fehlgeschlagen", e)
        }
        try {
            dndSchedule.enable()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: DND-Enable fehlgeschlagen", e)
        }
        try {
            hueSmartScheduler.initializeSmartScheduling()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Hue-Init fehlgeschlagen", e)
        }
        try {
            calendarPreAlarmRefreshScheduler.reschedule()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: Pre-Alarm-Refresh-Reschedule fehlgeschlagen", e)
        }
        Logger.business(LogTags.MASTER_PAUSE, "Hintergrunddienste fortgesetzt")
    }
}
