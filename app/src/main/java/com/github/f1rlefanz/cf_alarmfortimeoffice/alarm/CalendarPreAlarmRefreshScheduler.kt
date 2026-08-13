package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plant pro aktivem Alarm (max. [MAX_LOOKAHEAD_DAYS] Tage Lookahead, max. [MAX_SCHEDULED] Jobs -
 * Hue-Vorbild [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler]
 * begrenzt ebenso) einen WorkManager-`OneTimeWorkRequest` [PRE_ALARM_LEAD_MILLIS] vor der
 * jeweiligen Weckzeit: ein Vorab-Refresh der Kalender-Events, damit eine kurzfristige
 * TimeOffice-Aenderung (z. B. eine per Anruf nachgetragene Schicht) eine bessere Chance hat, VOR
 * dem Wecker anzukommen, statt erst bei der naechsten 6h-Wartung.
 *
 * WorkManager statt Exact-Alarm: ein paar Minuten Verzug sind hier tolerierbar (kein
 * zeitkritischer Weckvorgang, nur Vorbereitung) - dasselbe Muster wie
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.PreAlarmHealthCheckWorker].
 *
 * [reschedule] ist der einzige Einstiegspunkt. Aufrufer:
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService] und
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver.BootReceiver], an denselben Stellen,
 * an denen dort bereits der Dimmer-Reschedule laeuft - beide rufen best-effort mit eigenem
 * try/catch, ein Fehler hier darf weder Wartung noch Boot-Recovery stoeren.
 */
@Singleton
class CalendarPreAlarmRefreshScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmUseCase: IAlarmUseCase
) {
    companion object {
        const val WORK_TAG = "calendar_pre_alarm_refresh"
        private val PRE_ALARM_LEAD_MILLIS = TimeUnit.HOURS.toMillis(3)
        private const val MAX_LOOKAHEAD_DAYS = 14L

        // Wie HueSmartScheduler.getNextAlarmTimes(): unbegrenztes Vorausplanen wuerde bei vielen
        // gesetzten Alarmen unnoetig viele parallele WorkManager-Jobs anlegen.
        private const val MAX_SCHEDULED = 10

        /**
         * Reine Auswahllogik: welche Weckzeiten bekommen einen Pre-Alarm-Refresh-Job? Faellt hier
         * innerhalb des Lookahead-Fensters, dedupliziert, sortiert und auf [maxScheduled] gedeckelt.
         * Getrennt von reschedule() gehalten, damit Cap/Dedup/Fenster-Grenzen ohne WorkManager
         * testbar sind.
         */
        internal fun selectUpcomingTriggerTimes(
            triggerTimes: List<Long>,
            now: Long,
            maxLookaheadDays: Long,
            maxScheduled: Int
        ): List<Long> = triggerTimes
            .filter { it > now && it < now + TimeUnit.DAYS.toMillis(maxLookaheadDays) }
            .distinct()
            .sorted()
            .take(maxScheduled)

        /** Liegt [triggerTime] bereits so nah, dass der berechnete Refresh-Zeitpunkt (Vorlauf vor
         * der Weckzeit) in der Vergangenheit oder JETZT liegt? Dann lohnt sich fuer diese Runde
         * kein eigener Job mehr - die naechste Wartung/der naechste Boot deckt es erneut ab. */
        internal fun isWithinLeadTime(triggerTime: Long, now: Long, leadMillis: Long): Boolean =
            (triggerTime - leadMillis) - now <= 0
    }

    suspend fun reschedule() {
        val workManager = WorkManager.getInstance(context)

        // ERST LESEN, DANN VERWERFEN. Umgekehrt (bis v1.22.1) stand die bestehende Planung schon
        // geloescht da, wenn der Read danach fehlschlug: alte Jobs weg, keine neuen geplant, und
        // Feature B bis zur naechsten qualifizierenden Wartung still. Der Read ist der Teil, der
        // scheitern kann - er gehoert deshalb vor den irreversiblen Schritt.
        val alarms = alarmUseCase.getAllAlarms().getOrElse { error ->
            Logger.e(
                LogTags.BACKGROUND_WORKER,
                "Pre-Alarm-Refresh: Alarme konnten nicht geladen werden - bestehende Planung bleibt unangetastet",
                error
            )
            return
        }

        val now = System.currentTimeMillis()
        val upcoming = selectUpcomingTriggerTimes(
            triggerTimes = alarms.filter { it.isActive }.map { it.triggerTime },
            now = now,
            maxLookaheadDays = MAX_LOOKAHEAD_DAYS,
            maxScheduled = MAX_SCHEDULED
        )

        // Vorherige Planung komplett verwerfen und neu aufbauen - einfacher/robuster als ein Diff;
        // reschedule() laeuft ohnehin nur alle 6h bzw. beim Boot, nicht bei jeder Kleinigkeit.
        workManager.cancelAllWorkByTag(WORK_TAG)

        var scheduled = 0
        upcoming.forEachIndexed { index, triggerTime ->
            if (isWithinLeadTime(triggerTime, now, PRE_ALARM_LEAD_MILLIS)) {
                // Alarm liegt bereits innerhalb des 3h-Vorlaufs - fuer diese Runde nichts mehr zu
                // planen, die naechste Wartung/der naechste Boot deckt es erneut ab.
                Logger.d(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: Alarm bereits innerhalb des 3h-Vorlaufs, uebersprungen")
                return@forEachIndexed
            }
            val delayMillis = (triggerTime - PRE_ALARM_LEAD_MILLIS) - now

            val workRequest = OneTimeWorkRequestBuilder<CalendarPreAlarmRefreshWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            workManager.enqueueUniqueWork(
                "${WORK_TAG}_$index",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            scheduled++
        }

        Logger.d(
            LogTags.BACKGROUND_WORKER,
            "Pre-Alarm-Refresh: $scheduled von ${upcoming.size} anstehenden Alarmen geplant"
        )
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        Logger.d(LogTags.BACKGROUND_WORKER, "Pre-Alarm-Refresh: alle Jobs abgebrochen (Master-Pause)")
    }
}
