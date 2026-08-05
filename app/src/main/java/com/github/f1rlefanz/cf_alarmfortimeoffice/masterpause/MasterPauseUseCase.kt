package com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Master-Pause: EIN Schalter, der ALLE autonomen Hintergrunddienste gemeinsam an-/abschaltet -
 * die 6h-Wartungskette ([AlarmMaintenanceService]), alle gestellten Alarme ([IAlarmUseCase]),
 * Schicht-Dimmer ([DimScheduleUseCase]), DND-Steuerung ([DndScheduleUseCase]), Hue-SmartScheduler
 * ([HueSmartScheduler]) und den Pre-Alarm-Refresh ([CalendarPreAlarmRefreshScheduler]).
 *
 * [pause] raeumt aktiv auf (Alarme loeschen, Ketten stoppen), statt nur den Schalter umzulegen -
 * sonst liefe alles bis zum naechsten natuerlichen Ende einfach weiter. [resume] baut dieselben
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

    suspend fun pause() {
        prefs.setPaused(true)
        // Device-Protected-Spiegel im selben Atemzug wie das DataStore-Flag - BootReceiver liest
        // ihn bei LOCKED_BOOT_COMPLETED, wo @MainDataStore (CE-Storage) noch nicht lesbar ist.
        directBootAlarmStore.savePaused(true)
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

    suspend fun resume() {
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
