package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.DailySchedulePlanningWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.GenericHealthCheckWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.PreAlarmHealthCheckWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.AutoOffWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.SunriseStartWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * Hilt EntryPoint that lets the manually-constructed [HueSmartScheduler] singleton
 * (and its WorkManager workers) reach the Hilt-managed alarm use case.
 *
 * The scheduler keeps its getInstance()/Worker shape — instead of becoming a
 * @HiltWorker — and pulls the REAL, set alarms through this access point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HueSmartSchedulerEntryPoint {
    fun alarmUseCase(): IAlarmUseCase
    fun hueRuleUseCase(): IHueRuleUseCase
    fun hueLightUseCase(): IHueLightUseCase
}

/**
 * PHASE 2: Smart Scheduler for Hue Bridge Health Checks
 *
 * EFFICIENCY GOALS:
 * - Health checks only before actual alarm times
 * - Driven by the REAL alarms the app has scheduled (single source of truth)
 * - WorkManager for reliable background execution
 * - Further reduction: ~10-20 calls/day → ~5-15 calls/day
 *
 * FEATURES:
 * - Pre-alarm health checks (10 minutes before)
 * - Backed by IAlarmUseCase (the actually-set alarms), no hardcoded guessing
 * - Generic periodic fallback when no alarms are set
 * - Battery-optimized background tasks
 */
class HueSmartScheduler private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: HueSmartScheduler? = null

        fun getInstance(context: Context): HueSmartScheduler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HueSmartScheduler().also {
                    INSTANCE = it
                    it.initialize(context.applicationContext)
                }
            }
        }

        // Smart scheduling constants
        private const val PRE_ALARM_HEALTH_CHECK_WORK = "pre_alarm_health_check"
        private const val SUNRISE_START_WORK = "sunrise_start"
        private const val AUTO_OFF_WORK = "hue_auto_off"
        private const val DAILY_SCHEDULE_WORK = "daily_schedule_planning"
        private val PRE_ALARM_CHECK_WINDOW = 10.minutes
        private const val MAX_LOOKAHEAD_DAYS = 7
        private val ALARM_CHANGE_DEBOUNCE = 1500.milliseconds

        /** Start-Backoff des Auto-Off-Retries; verdoppelt sich pro Fehlversuch. */
        private const val AUTO_OFF_BACKOFF_MINUTES = 5L
    }

    private lateinit var workManager: WorkManager

    /** Application context, retained to resolve Hilt dependencies via the EntryPoint. */
    private lateinit var appContext: Context

    /**
     * Dedicated coroutine scope for background scheduling.
     *
     * SupervisorJob: a failure in one scheduling job is isolated and does NOT
     * tear down the whole scope (replaces the previous CoroutineScope(Dispatchers.IO)).
     * cleanup() only cancels the children, so this process-lifetime singleton
     * stays reusable after an Activity teardown/restart cycle.
     */
    private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Collector that reschedules Hue health checks whenever the real alarms change. */
    private var alarmObserverJob: Job? = null

    /**
     * Initialize with application context (called once by getInstance)
     */
    private fun initialize(context: Context) {
        appContext = context
        workManager = WorkManager.getInstance(context)
    }

    /**
     * Resolve the Hilt-managed alarm use case via EntryPoint.
     * Returns null only if the context/Hilt is not ready yet (should not happen post-startup).
     */
    private fun resolveAlarmUseCase(): IAlarmUseCase? {
        if (!::appContext.isInitialized) return null
        return try {
            EntryPointAccessors
                .fromApplication(appContext, HueSmartSchedulerEntryPoint::class.java)
                .alarmUseCase()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to resolve alarm use case via EntryPoint", e)
            null
        }
    }

    /**
     * Resolve the Hilt-managed Hue rule use case via EntryPoint (for sunrise scheduling).
     */
    private fun resolveHueRuleUseCase(): IHueRuleUseCase? {
        if (!::appContext.isInitialized) return null
        return try {
            EntryPointAccessors
                .fromApplication(appContext, HueSmartSchedulerEntryPoint::class.java)
                .hueRuleUseCase()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to resolve Hue rule use case via EntryPoint", e)
            null
        }
    }

    /**
     * MAIN API: Initialize smart scheduling system
     */
    fun initializeSmartScheduling() {
        if (!::workManager.isInitialized) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ SMART-SCHEDULER: WorkManager not initialized, scheduler not ready")
            return
        }

        Logger.i(LogTags.HUE_BRIDGE, "🧠 SMART-SCHEDULER: Initializing intelligent health check scheduling")

        // Schedule daily planning work
        scheduleDailyPlanning()

        // Initial schedule calculation
        schedulerScope.launch {
            try {
                calculateAndScheduleNextHealthChecks()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_BRIDGE, "Failed to initialize smart scheduling", e)
            }
        }

        // Keep the schedule in sync with the REAL alarms as they change
        observeAlarmChanges()

        Logger.i(LogTags.HUE_BRIDGE, "✅ SMART-SCHEDULER: Smart scheduling initialized")
    }

    /**
     * REACTIVE: Observe the app's real alarms and recalculate the Hue health-check
     * schedule whenever they change (alarm set, skipped, manual change, cleanup).
     *
     * The first (replayed) StateFlow value simply reconfirms the schedule the initial
     * calculation already applied; debounce coalesces bursts of rapid changes.
     */
    @OptIn(FlowPreview::class)
    private fun observeAlarmChanges() {
        if (alarmObserverJob?.isActive == true) return
        val alarmUseCase = resolveAlarmUseCase()
        if (alarmUseCase == null) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ SMART-SCHEDULER: Alarm use case unavailable, cannot observe alarm changes")
            return
        }
        alarmObserverJob = schedulerScope.launch {
            alarmUseCase.activeAlarms
                .debounce(ALARM_CHANGE_DEBOUNCE)
                .collect { alarms ->
                    Logger.i(LogTags.HUE_BRIDGE, "🔔 SMART-SCHEDULER: Alarms changed (${alarms.size}), recalculating Hue schedule")
                    calculateAndScheduleNextHealthChecks()
                }
        }
    }

    /**
     * OPTIMIZATION: Calculate next alarm times and schedule health checks accordingly
     */
    suspend fun calculateAndScheduleNextHealthChecks() = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_BRIDGE, "🔮 SMART-SCHEDULER: Calculating next alarm times for health check scheduling")

        try {
            // Keep pre-alarm sunrise ramps and auto-off jobs in sync with the real alarms
            // (isolated so a Hue-side failure never disrupts the critical health-check
            // scheduling below).
            try {
                scheduleSunriseStarts()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_BRIDGE, "Failed to schedule sunrise starts", e)
            }
            try {
                scheduleAutoOffs()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_BRIDGE, "Failed to schedule auto-off jobs", e)
            }

            // Get next alarm times from the REAL, set alarms
            val nextAlarmTimes = getNextAlarmTimes()

            if (nextAlarmTimes.isEmpty()) {
                Logger.d(LogTags.HUE_BRIDGE, "📅 SMART-SCHEDULER: No upcoming alarms found, using fallback schedule")
                scheduleGenericHealthChecks()
                return@withContext
            }

            // Cancel existing scheduled health checks
            workManager.cancelUniqueWork(PRE_ALARM_HEALTH_CHECK_WORK)

            // Schedule health checks for each upcoming alarm
            nextAlarmTimes.forEachIndexed { index, alarmTime ->
                schedulePreAlarmHealthCheck(alarmTime, index)
            }

            Logger.i(LogTags.HUE_BRIDGE, "✅ SMART-SCHEDULER: Scheduled ${nextAlarmTimes.size} pre-alarm health checks")

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ SMART-SCHEDULER: Failed to calculate alarm schedule", e)
            scheduleGenericHealthChecks() // Fallback
        }
    }

    /**
     * CORE FEATURE: Next health-check times derived from the alarms the app has
     * ACTUALLY scheduled (single source of truth = IAlarmUseCase).
     *
     * No more hardcoded shift-pattern guessing: when there are no real alarms the
     * caller falls back to a generic periodic health check.
     */
    private suspend fun getNextAlarmTimes(): List<LocalDateTime> = withContext(Dispatchers.IO) {
        try {
            val alarmUseCase = resolveAlarmUseCase()
            if (alarmUseCase == null) {
                Logger.w(LogTags.HUE_BRIDGE, "⚠️ SMART-SCHEDULER: Alarm use case unavailable, no real alarms to schedule for")
                return@withContext emptyList()
            }

            val alarms = alarmUseCase.getAllAlarms().getOrElse { error ->
                Logger.e(LogTags.HUE_BRIDGE, "Failed to load real alarms for Hue scheduling", error)
                return@withContext emptyList()
            }

            val now = LocalDateTime.now()
            val maxTime = now.plusDays(MAX_LOOKAHEAD_DAYS.toLong())

            alarms
                .filter { it.isActive }
                .map { it.triggerTime.toLocalDateTime() }
                .filter { it.isAfter(now) && it.isBefore(maxTime) }
                .distinct()
                .sorted()
                .take(10) // Limit to next 10 alarms
                .also {
                    Logger.i(LogTags.HUE_BRIDGE, "🎯 SMART-SCHEDULER: ${it.size} real alarm(s) drive Hue health checks")
                }

        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to get alarm times", e)
            emptyList<LocalDateTime>()
        }
    }

    /** Convert epoch millis (AlarmInfo.triggerTime) into a local date-time. */
    private fun Long.toLocalDateTime(): LocalDateTime =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /**
     * WORKMANAGER: Schedule pre-alarm health check
     */
    private fun schedulePreAlarmHealthCheck(alarmTime: LocalDateTime, index: Int) {
        val checkTime = alarmTime.minus(PRE_ALARM_CHECK_WINDOW.toJavaDuration())
        val now = LocalDateTime.now()

        if (checkTime.isBefore(now)) {
            Logger.d(LogTags.HUE_BRIDGE, "⏰ SMART-SCHEDULER: Skipping past alarm time: $alarmTime")
            return
        }

        val delayMillis = JavaDuration.between(now, checkTime).toMillis()

        val workRequest = OneTimeWorkRequestBuilder<PreAlarmHealthCheckWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                "alarm_time" to alarmTime.toString(),
                "check_index" to index
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()

        workManager.enqueueUniqueWork(
            "${PRE_ALARM_HEALTH_CHECK_WORK}_$index",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Logger.d(LogTags.HUE_BRIDGE, "⏰ SMART-SCHEDULER: Scheduled health check for $checkTime (${delayMillis/1000/60} minutes from now)")
    }

    /**
     * SUNRISE: Schedule pre-alarm sunrise ramps for upcoming alarms.
     *
     * For each upcoming alarm whose shift has an enabled "start before alarm" sunrise rule,
     * a one-time [SunriseStartWorker] is scheduled at (alarmTime - sunriseDuration) so the
     * ramp finishes exactly at the alarm. Sunrises configured to start AT the alarm are
     * handled by the at-alarm path instead.
     */
    private suspend fun scheduleSunriseStarts() {
        val ruleUseCase = resolveHueRuleUseCase()
        if (ruleUseCase == null) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ SMART-SCHEDULER: Rule use case unavailable, cannot schedule sunrise")
            return
        }
        val alarmUseCase = resolveAlarmUseCase() ?: return

        // Clear previously scheduled sunrise starts before recomputing (tag-based, so it
        // also catches indexed entries from a prior, larger alarm set).
        workManager.cancelAllWorkByTag(SUNRISE_START_WORK)

        val alarms = alarmUseCase.getAllAlarms().getOrElse { error ->
            Logger.e(LogTags.HUE_BRIDGE, "Failed to load alarms for sunrise scheduling", error)
            return
        }

        val now = LocalDateTime.now()
        val maxTime = now.plusDays(MAX_LOOKAHEAD_DAYS.toLong())

        val upcoming = alarms
            .filter { it.isActive }
            .map { it.shiftName to it.triggerTime.toLocalDateTime() }
            .filter { it.second.isAfter(now) && it.second.isBefore(maxTime) }
            .sortedBy { it.second }
            .take(10)

        var scheduled = 0
        upcoming.forEachIndexed { index, (shiftName, alarmTime) ->
            val leadMinutes = ruleUseCase.getPreAlarmSunriseLeadMinutes(shiftName) ?: return@forEachIndexed
            val sunriseStart = alarmTime.minusMinutes(leadMinutes.toLong())

            if (sunriseStart.isBefore(now)) {
                Logger.d(LogTags.HUE_BRIDGE, "🌅 SMART-SCHEDULER: Sunrise start for $shiftName already passed, skipping")
                return@forEachIndexed
            }

            val delayMillis = JavaDuration.between(now, sunriseStart).toMillis()

            val workRequest = OneTimeWorkRequestBuilder<SunriseStartWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("shift_name" to shiftName))
                .addTag(SUNRISE_START_WORK)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()

            workManager.enqueueUniqueWork(
                "${SUNRISE_START_WORK}_$index",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            scheduled++
            Logger.i(LogTags.HUE_BRIDGE, "🌅 SMART-SCHEDULER: Scheduled sunrise for $shiftName at $sunriseStart (${delayMillis / 1000 / 60} min from now, lead ${leadMinutes}min)")
        }

        if (scheduled == 0) {
            Logger.d(LogTags.HUE_BRIDGE, "🌅 SMART-SCHEDULER: No pre-alarm sunrises to schedule")
        }
    }

    /**
     * AUTO-OFF: Schedule "turn lights off again" jobs for upcoming alarms.
     *
     * For each upcoming alarm whose shift has an enabled rule that switches lights ON with a
     * configured auto-off duration, an [AutoOffWorker] is scheduled at (alarmTime + duration).
     * Turning already-off lights off again is a harmless no-op, so a skipped alarm causes no harm.
     *
     * KEIN pauschales cancelAllWorkByTag(AUTO_OFF_WORK) mehr!
     *
     * Der frühere Ablauf loeschte ALLE Auto-Off-Jobs und legte danach nur fuer Alarme in der
     * ZUKUNFT neue an. Ein Alarm, der gerade geklingelt hatte, lag damit in der Vergangenheit -
     * sein noch ausstehender Auto-Off-Job wurde geloescht und nie wieder angelegt. Und genau
     * DANN laeuft die Methode: der Maintenance-Sync raeumt den vergangenen Alarm weg,
     * activeAlarms feuert, der Observer ruft hier rein. Folge: die Lampen gingen an und blieben
     * fuer immer an - unabhaengig davon, ob die Bridge erreichbar war.
     *
     * Stattdessen: jeder Alarm bekommt einen stabilen, aus seiner ID abgeleiteten Work-Namen.
     * ExistingWorkPolicy.REPLACE haelt ihn aktuell, solange der Alarm existiert; ein bereits
     * gefeuerter Alarm wird schlicht nicht mehr angefasst und sein Auto-Off laeuft in Ruhe zu
     * Ende.
     *
     * Verwaiste Jobs (Schicht nachtraeglich aus dem Kalender geloescht) werden bewusst NICHT
     * abgeraeumt: sie feuern einmal, schalten bereits ausgeschaltete Lampen aus - ein
     * harmloser No-op - und sind danach weg. Das ist deutlich billiger als eine
     * Aufraeum-Buchhaltung, die einen Prozesstod ueberleben muesste, und kann im Gegensatz zu
     * ihr keinen noch benoetigten Job erwischen.
     */
    private suspend fun scheduleAutoOffs() {
        val ruleUseCase = resolveHueRuleUseCase() ?: return
        val alarmUseCase = resolveAlarmUseCase() ?: return

        val alarms = alarmUseCase.getAllAlarms().getOrElse { error ->
            Logger.e(LogTags.HUE_BRIDGE, "Failed to load alarms for auto-off scheduling", error)
            return
        }

        val now = LocalDateTime.now()
        val maxTime = now.plusDays(MAX_LOOKAHEAD_DAYS.toLong())

        val upcoming = alarms
            .filter { it.isActive }
            .filter {
                val t = it.triggerTime.toLocalDateTime()
                t.isAfter(now) && t.isBefore(maxTime)
            }
            .sortedBy { it.triggerTime }
            .take(10)

        var scheduled = 0

        upcoming.forEach { alarm ->
            val shiftName = alarm.shiftName
            val alarmTime = alarm.triggerTime.toLocalDateTime()
            val targets = ruleUseCase.getAutoOffActions(shiftName)
            if (targets.isEmpty()) return@forEach

            // One worker per distinct delay (it switches off all targets sharing that delay).
            targets.groupBy { it.delayMinutes }.forEach { (delayMinutes, group) ->
                val offTime = alarmTime.plusMinutes(delayMinutes.toLong())
                if (offTime.isBefore(now)) return@forEach
                val delayMillis = JavaDuration.between(now, offTime).toMillis()

                val lightIds = group.filter { !it.isGroup }.map { it.targetId }.distinct().toTypedArray()
                val groupIds = group.filter { it.isGroup }.map { it.targetId }.distinct().toTypedArray()

                // Stabiler Name aus der Alarm-ID (nicht aus dem Listen-Index!): ein Index
                // verschiebt sich, sobald ein frueherer Alarm wegfaellt, und wuerde denselben
                // Job unter neuem Namen ein zweites Mal anlegen.
                val workName = "${AUTO_OFF_WORK}_${alarm.id}_$delayMinutes"

                val workRequest = OneTimeWorkRequestBuilder<AutoOffWorker>()
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("light_ids" to lightIds, "group_ids" to groupIds))
                    .addTag(AUTO_OFF_WORK)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        AUTO_OFF_BACKOFF_MINUTES,
                        TimeUnit.MINUTES
                    )
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                    .build()

                workManager.enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
                scheduled++
                Logger.i(LogTags.HUE_BRIDGE, "💡 SMART-SCHEDULER: Scheduled auto-off for $shiftName at $offTime (${lightIds.size} lights, ${groupIds.size} groups, +${delayMinutes}min)")
            }
        }

        if (scheduled == 0) {
            Logger.d(LogTags.HUE_BRIDGE, "💡 SMART-SCHEDULER: No auto-off jobs to schedule")
        }
    }

    /**
     * FALLBACK: Generic health checks when no alarms are found
     */
    private fun scheduleGenericHealthChecks() {
        Logger.d(LogTags.HUE_BRIDGE, "🔄 SMART-SCHEDULER: Using fallback generic health check schedule")

        // Schedule a health check every 6 hours as fallback
        val workRequest = PeriodicWorkRequestBuilder<GenericHealthCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()

        workManager.enqueueUniquePeriodicWork(
            "generic_health_checks",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }

    /**
     * DAILY PLANNING: Schedule daily recalculation of health checks
     */
    private fun scheduleDailyPlanning() {
        val dailyWork = PeriodicWorkRequestBuilder<DailySchedulePlanningWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()

        workManager.enqueueUniquePeriodicWork(
            DAILY_SCHEDULE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWork
        )

        Logger.d(LogTags.HUE_BRIDGE, "📅 SMART-SCHEDULER: Daily planning work scheduled")
    }

    /**
     * PUBLIC API: Manual trigger for schedule recalculation
     */
    fun recalculateSchedule() {
        Logger.i(LogTags.HUE_BRIDGE, "🔄 SMART-SCHEDULER: Manual schedule recalculation triggered")

        schedulerScope.launch {
            calculateAndScheduleNextHealthChecks()
        }
    }

    /**
     * CLEANUP: Cancel all scheduled work
     */
    fun cleanup() {
        // Cancel only in-flight jobs, NOT the scope's SupervisorJob itself,
        // so the singleton remains reusable after an Activity teardown/restart.
        schedulerScope.coroutineContext.cancelChildren()
        alarmObserverJob = null
        if (::workManager.isInitialized) {
            workManager.cancelUniqueWork(PRE_ALARM_HEALTH_CHECK_WORK)
            workManager.cancelAllWorkByTag(SUNRISE_START_WORK)
            workManager.cancelAllWorkByTag(AUTO_OFF_WORK)
            workManager.cancelUniqueWork(DAILY_SCHEDULE_WORK)
            Logger.d(LogTags.HUE_BRIDGE, "🧹 SMART-SCHEDULER: Cleanup completed")
        }
    }
}
