package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berechnet die Dimm-Zeitfenster aus ZWEI unabhängigen Quellen und steuert EINEN rollenden,
 * exakten Alarm auf den nächsten Fenster-Rand (Muster wie NachtDimmer-ScheduleManager /
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService]):
 *
 *  1. Wellness (global): `[Weckzeit − Wind-down, Weckzeit]` je aktivem Alarm.
 *  2. Regeln: pro Schicht-Tag die passende [DimRule] (siehe [DimRuleUseCase]) und deren
 *     [DimWindow]s, aufgelöst relativ zur Weckzeit bzw. auf feste Uhrzeit. Freie Tage (kein
 *     Alarm) über die FREI-Regel.
 *
 * Overlay ist an, wenn `now` in irgendeinem Fenster liegt (Vereinigung). Fail-open: lässt sich
 * der Alarm-Bestand nicht lesen, wird NICHT gedimmt.
 */
@Singleton
class DimScheduleUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmUseCase: IAlarmUseCase,
    private val dimRuleUseCase: DimRuleUseCase,
    private val prefs: DimOverlayPrefs
) {
    companion object {
        const val ACTION_TICK = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_SCHED_TICK"
        private const val REQ_TICK = 7710
        private const val FREE_DAY_HORIZON_DAYS = 14
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MIN_MS = 60_000L
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Ist-Zustand anwenden + nächsten Wechsel planen. Self-cleaning, wenn nichts aktiv ist. */
    suspend fun enable() {
        applyCurrentState()
        scheduleNextTransition()
    }

    suspend fun applyCurrentState() {
        val now = System.currentTimeMillis()
        prefs.setOverlayOn(windows().any { now in it })
    }

    suspend fun scheduleNextTransition() {
        val am = alarmManager()
        val pi = buildPendingIntent()
        val next = computeNextTransition(System.currentTimeMillis())
        if (next == null) {
            am.cancel(pi)
            return
        }
        val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        if (canBeExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        Logger.d(LogTags.DIMMER, "Naechster Dimm-Wechsel geplant: ${java.util.Date(next)}")
    }

    // --- Fenster-Berechnung ---

    private suspend fun windows(): List<LongRange> {
        val toggles = prefs.togglesNow()
        if (!toggles.wellnessEnabled && !toggles.rulesEnabled) return emptyList()

        val alarms = alarmUseCase.getAllAlarms().getOrElse {
            Logger.w(LogTags.DIMMER, "Alarm-Bestand nicht lesbar - kein Dimming (fail-open)")
            return emptyList()
        }.filter { it.isActive }

        val out = mutableListOf<LongRange>()

        // 1. Wellness (global): Wind-down vor jeder Weckzeit.
        if (toggles.wellnessEnabled) {
            val windDownMs = prefs.windDownMinutesNow() * MIN_MS
            alarms.forEach { out += (it.triggerTime - windDownMs)..it.triggerTime }
        }

        // 2. Regeln: pro Schicht-Tag die passende Regel, plus FREI-Regel für freie Tage.
        if (toggles.rulesEnabled) {
            val rules = dimRuleUseCase.getAllRules()
            if (rules.any { it.enabled }) {
                val shiftDates = HashSet<LocalDate>()
                alarms.forEach { alarm ->
                    shiftDates += Instant.ofEpochMilli(alarm.triggerTime).atZone(zone).toLocalDate()
                    val rule = dimRuleUseCase.findRuleForShift(alarm.shiftName, rules) ?: return@forEach
                    rule.windows.forEach { w -> resolveShiftWindow(w, alarm.triggerTime)?.let { out += it } }
                }
                val freeRule = dimRuleUseCase.findRuleForFreeDay(rules)
                if (freeRule != null && freeRule.windows.isNotEmpty()) {
                    val today = LocalDate.now(zone)
                    for (d in 0 until FREE_DAY_HORIZON_DAYS) {
                        val date = today.plusDays(d.toLong())
                        if (date in shiftDates) continue
                        freeRule.windows.forEach { w -> resolveFreeWindow(w, date)?.let { out += it } }
                    }
                }
            }
        }
        return out
    }

    private suspend fun computeNextTransition(now: Long): Long? =
        windows()
            .flatMap { listOf(it.first, it.last) }
            .filter { it > now }
            .minOrNull()

    /** Fenster eines Schicht-Tags (mit Weckzeit [alarmEpoch]). */
    private fun resolveShiftWindow(w: DimWindow, alarmEpoch: Long): LongRange? {
        val start = when (w.startAnchor) {
            DimAnchor.ALARM -> alarmEpoch + w.startOffsetMinutes * MIN_MS
            DimAnchor.CLOCK -> clockAtOrBefore(alarmEpoch, w.startClockMinutes)
        }
        val end = when (w.endAnchor) {
            DimAnchor.ALARM -> alarmEpoch + w.endOffsetMinutes * MIN_MS
            DimAnchor.CLOCK -> {
                var e = clockOnDateOf(alarmEpoch, w.endClockMinutes)
                while (e <= start) e += DAY_MS
                e
            }
        }
        return if (end > start) start..end else null
    }

    /** Fenster eines freien Tags – nur CLOCK-Anker sinnvoll (kein Wecker). */
    private fun resolveFreeWindow(w: DimWindow, date: LocalDate): LongRange? {
        if (w.startAnchor != DimAnchor.CLOCK || w.endAnchor != DimAnchor.CLOCK) return null
        val base = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = base + w.startClockMinutes * MIN_MS
        var end = base + w.endClockMinutes * MIN_MS
        if (end <= start) end += DAY_MS // über Mitternacht
        return start..end
    }

    /** Die Uhrzeit [clockMinutes] auf dem Kalendertag von [referenceEpoch], aber nicht nach der Referenz. */
    private fun clockAtOrBefore(referenceEpoch: Long, clockMinutes: Int): Long {
        var t = clockOnDateOf(referenceEpoch, clockMinutes)
        if (t > referenceEpoch) t -= DAY_MS
        return t
    }

    private fun clockOnDateOf(referenceEpoch: Long, clockMinutes: Int): Long {
        val date = Instant.ofEpochMilli(referenceEpoch).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() + clockMinutes * MIN_MS
    }

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, DimScheduleReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQ_TICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
