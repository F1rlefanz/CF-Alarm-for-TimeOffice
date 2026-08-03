package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hilt-EntryPoint fuer [CalendarPreAlarmRefreshWorker]: Das Projekt nutzt keinen
 * HiltWorkerFactory (siehe bestehende Hue-Worker, z. B. `PreAlarmHealthCheckWorker` +
 * `HueSmartSchedulerEntryPoint`) - WorkManager instanziiert den Worker ueber seinen
 * Default-Factory-Weg ohne Konstruktor-Injection, darum der Umweg ueber EntryPointAccessors.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarPreAlarmRefreshEntryPoint {
    fun tokenManager(): OAuth2TokenManager
    fun calendarUseCase(): ICalendarUseCase
    fun shiftUseCase(): IShiftUseCase
    fun alarmUseCase(): IAlarmUseCase
    fun calendarSelectionRepository(): CalendarSelectionRepository
}

/**
 * Vorab-Refresh 3h vor jedem Alarm (siehe [CalendarPreAlarmRefreshScheduler]): laedt
 * Kalender-Events mit `forceRefresh = true` und stoesst [IAlarmUseCase.syncAlarms] an, damit eine
 * kurzfristige TimeOffice-Aenderung eine reelle Chance hat, VOR dem Wecker anzukommen, statt erst
 * bei der naechsten 6h-Wartung erkannt zu werden.
 *
 * Umgeht bewusst die MIN_BUFFER_DAYS-Skip-Logik aus `AlarmMaintenanceService` (die bleibt
 * unveraendert dort - dieser Worker ruft sie nicht auf, er soll bei jedem Lauf frisch laden).
 * Kein Retry bei Fehler (`Result.success()`) - dies ist nur eine Vorbereitung, die naechste
 * regulaere Wartung bzw. der naechste Vordergrund-Sync deckt denselben Zustand ohnehin ab.
 */
class CalendarPreAlarmRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Logger.d(LogTags.BACKGROUND_WORKER, "🔄 Pre-Alarm-Refresh-Worker gestartet")

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                CalendarPreAlarmRefreshEntryPoint::class.java
            )

            val tokenResult = entryPoint.tokenManager().getValidToken()
            if (tokenResult.isFailure) {
                Logger.w(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: kein gueltiges Token, uebersprungen")
                return@withContext Result.success()
            }

            val selectedCalendars = entryPoint.calendarSelectionRepository()
                .getCurrentSelectedCalendarIds()
                .getOrNull() ?: emptySet()

            if (selectedCalendars.isEmpty()) {
                Logger.d(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: keine Kalender ausgewaehlt, uebersprungen")
                return@withContext Result.success()
            }

            val eventsResult = entryPoint.calendarUseCase().getCalendarEventsWithCache(
                calendarIds = selectedCalendars,
                forceRefresh = true
            )
            val events = eventsResult.getOrElse { error ->
                Logger.w(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: Events konnten nicht geladen werden", error)
                return@withContext Result.success()
            }

            val shiftConfig = entryPoint.shiftUseCase().getCurrentShiftConfig().getOrNull()
            if (shiftConfig?.autoAlarmEnabled != true) {
                Logger.d(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: Auto-Alarm deaktiviert, uebersprungen")
                return@withContext Result.success()
            }

            val syncResult = entryPoint.alarmUseCase().syncAlarms(events, shiftConfig)
            if (syncResult.isSuccess) {
                Logger.i(
                    LogTags.BACKGROUND_WORKER,
                    "✅ Pre-Alarm-Refresh abgeschlossen: ${syncResult.getOrThrow().size} Alarme in Sync"
                )
            } else {
                Logger.w(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: Sync fehlgeschlagen", syncResult.exceptionOrNull())
            }

            Result.success()
        } catch (e: Exception) {
            Logger.e(LogTags.BACKGROUND_WORKER, "❌ Pre-Alarm-Refresh-Worker fehlgeschlagen", e)
            // Kein Retry: reine Vorbereitung, die naechste regulaere Wartung deckt denselben
            // Zustand ohnehin ab.
            Result.success()
        }
    }
}
