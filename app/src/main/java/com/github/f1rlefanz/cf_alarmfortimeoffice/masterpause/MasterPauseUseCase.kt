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
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RufbereitschaftAbfrage
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
    private val rufbereitschaftAbfrage: RufbereitschaftAbfrage,
    @param:ApplicationContext private val context: Context
) {
    val paused: Flow<Boolean> = prefs.paused

    /**
     * Bringt den Device-Protected-Spiegel des Pausenzustands mit der CE-Wahrheit in Deckung.
     * `savePaused()` schluckt seinen Fehler; ohne Abgleich divergierten beide dauerhaft (haengendes
     * `true`: kein Wecker nach dem Neustart; `false`: pausierte Alarme re-armiert). Laeuft beim
     * App-Start, nur bei entsperrtem Geraet. Hergang: Skill cfalarm-wecker-und-boot, master-pause.md.
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
     * NICHT abbrechbar: die Sequenz stellt einen Zustand HER, der Schalter steht als ERSTES - ein
     * Abbruch mittendrin liesse Flag und Wirklichkeit auseinanderstehen (Hergang: master-pause.md).
     */
    suspend fun pause() = withContext(NonCancellable) {
        prefs.setPaused(true)
        // Device-Protected-Spiegel im selben Atemzug wie das DataStore-Flag - BootReceiver liest
        // ihn bei LOCKED_BOOT_COMPLETED, wo @MainDataStore (CE-Storage) noch nicht lesbar ist.
        directBootAlarmStore.savePaused(true)
        // Laufenden Wecker beenden - unbedingt, nicht auf alarmActive gegated; eigenes try/catch,
        // ein abgelehnter startService() darf die Pause nicht zerreissen. Hergang: master-pause.md.
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
        // Jeder Schritt einzeln gekapselt (siehe [schritt]): UI und Flag sagen bereits "pausiert",
        // ein spaeterer Schritt (z.B. Hue-Cleanup) darf nicht unbemerkt weiterlaufen.
        schritt("6h-Wartung canceln") { AlarmMaintenanceService.cancelNext(context) }
        schritt("Dimmer-Disable") { dimSchedule.disable() }
        schritt("DND-Disable") { dndSchedule.disable() }
        schritt("Hue-Cleanup") { hueSmartScheduler.cleanup() }
        schritt("Pre-Alarm-Refresh-Cancel") { calendarPreAlarmRefreshScheduler.cancelAll() }
        // Bis v1.45 fehlte diese Kette hier (#131, G11-14): ihr stuendlicher Wecker blieb scharf und
        // stiess waehrend der Pause noch einen Wartungslauf an, der sie erst dann abraeumte.
        schritt("Rufbereitschafts-Abfrage-Cancel") { rufbereitschaftAbfrage.cancel() }
        Logger.business(LogTags.MASTER_PAUSE, "Hintergrunddienste pausiert")
    }

    /** Nicht abbrechbar - siehe [pause]. */
    suspend fun resume() = withContext(NonCancellable) {
        prefs.setPaused(false)
        directBootAlarmStore.savePaused(false)
        // Jeder Schritt einzeln gekapselt (siehe [schritt]): UI und Flag sagen bereits
        // "fortgesetzt", ein spaeterer Schritt (z.B. Hue-Init) darf nicht unbemerkt ausbleiben.
        schritt("6h-Wartung neu planen") { AlarmMaintenanceService.scheduleNext(context) }
        schritt("6h-Wartung starten") { AlarmMaintenanceService.start(context) }
        schritt("Dimmer-Enable") { dimSchedule.enable() }
        schritt("DND-Enable") { dndSchedule.enable() }
        schritt("Hue-Init") { hueSmartScheduler.initializeSmartScheduling() }
        schritt("Pre-Alarm-Refresh-Reschedule") { calendarPreAlarmRefreshScheduler.reschedule() }
        schritt("Rufbereitschafts-Abfrage-Reschedule") { rufbereitschaftAbfrage.reschedule() }
        Logger.business(LogTags.MASTER_PAUSE, "Hintergrunddienste fortgesetzt")
    }

    /**
     * Ein Schritt von [pause]/[resume] mit eigenem Fang (Vorbild:
     * BootReceiver.performCompleteSystemRecovery()) - sonst reisst ein einzelner Fehler alle
     * NACHFOLGENDEN Schritte mit ab, obwohl das Pause-Flag bereits geschrieben ist.
     * `inline`, damit die suspend-Aufrufe im Block erlaubt sind. Faengt bewusst `Exception`
     * (auch CancellationException): die Aufrufer laufen unter NonCancellable.
     */
    private inline fun schritt(beschreibung: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Logger.w(LogTags.MASTER_PAUSE, "⚠️ MASTER-PAUSE: $beschreibung fehlgeschlagen", e)
        }
    }
}
