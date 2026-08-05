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
        AlarmMaintenanceService.cancelNext(context)
        dimSchedule.disable()
        dndSchedule.disable()
        hueSmartScheduler.cleanup()
        calendarPreAlarmRefreshScheduler.cancelAll()
        Logger.business(LogTags.MASTER_PAUSE, "Hintergrunddienste pausiert")
    }

    suspend fun resume() {
        prefs.setPaused(false)
        directBootAlarmStore.savePaused(false)
        AlarmMaintenanceService.scheduleNext(context)
        AlarmMaintenanceService.start(context)
        dimSchedule.enable()
        dndSchedule.enable()
        hueSmartScheduler.initializeSmartScheduling()
        calendarPreAlarmRefreshScheduler.reschedule()
        Logger.business(LogTags.MASTER_PAUSE, "Hintergrunddienste fortgesetzt")
    }
}
