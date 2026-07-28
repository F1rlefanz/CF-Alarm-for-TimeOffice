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
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berechnet die Dimm-Zeitfenster aus DREI unabhängigen Quellen und steuert EINEN rollenden,
 * exakten Alarm auf den nächsten Fenster-Rand (Muster wie NachtDimmer-ScheduleManager /
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService]):
 *
 *  1. Wellness (global): `[Weckzeit − Wind-down, Weckzeit]` je aktivem Alarm.
 *  2. Regeln: pro Kalendertag die passende [DimRule] (Schicht-Tag → Schicht/UNIVERSAL, freier Tag
 *     → FREI/UNIVERSAL; siehe [DimRuleUseCase]). CLOCK↔CLOCK-Fenster = lückenlos jede Nacht,
 *     ALARM/SHIFT_END schicht-relativ, leere Fensterliste = Unterdrückung. Details in
 *     [DimWindowResolver.buildRuleSpans].
 *  3. Nacht-Standard (seit v1.17.0, [DimOverlayPrefs.Toggles.nightDefaultEnabled]): ab einer festen
 *     Uhrzeit bis zum naechsten Wecker (bzw. bis zu einer zweiten festen Uhrzeit an Tagen ohne
 *     Alarm) - OHNE dass dafuer eine Regel angelegt werden muss. Eine vorhandene Regel (Punkt 2)
 *     ersetzt diesen Standard fuer ihren Tag komplett. Details in
 *     [DimWindowResolver.buildDefaultNightSpans].
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
        private const val HORIZON_DAYS = 14
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
        // Aktive Spanne (überlappen mehrere, gewinnt die dunkelste – Logik in DimWindowResolver).
        val active = DimWindowResolver.activeSpan(windows(), now)
        if (active != null) {
            prefs.setActiveOverlay(true, active.strength, active.warmth)
        } else {
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
        }
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

    // --- Fenster-Berechnung (reine Zeitmathematik in DimWindowResolver) ---

    /** Vorschau fuer die UI (siehe [DimWindowResolver.mergeToTimeline]) - identische Berechnung wie
     * der echte Scheduler, aber ohne jeden Seiteneffekt. */
    suspend fun previewTimeline(): List<DimWindowResolver.ResolvedInterval> =
        DimWindowResolver.mergeToTimeline(windows())

    private suspend fun windows(): List<DimWindowResolver.DimSpan> {
        val toggles = prefs.togglesNow()
        if (!toggles.wellnessEnabled && !toggles.rulesEnabled && !toggles.nightDefaultEnabled) return emptyList()

        val alarms = alarmUseCase.getAllAlarms().getOrElse {
            Logger.w(LogTags.DIMMER, "Alarm-Bestand nicht lesbar - kein Dimming (fail-open)")
            return emptyList()
        }.filter { it.isActive }

        val out = mutableListOf<DimWindowResolver.DimSpan>()
        val gStrength = prefs.strengthNow()
        val gWarmth = prefs.warmthNow()

        // 1. Wellness (global): Wind-down vor jeder Weckzeit – mit der globalen Intensität.
        if (toggles.wellnessEnabled) {
            val windDownMs = prefs.windDownMinutesNow() * MIN_MS
            alarms.forEach {
                out += DimWindowResolver.DimSpan((it.triggerTime - windDownMs)..it.triggerTime, gStrength, gWarmth)
            }
        }

        // 2. Regeln: pro Kalendertag die passende Regel (Anker-Semantik in
        //    DimWindowResolver.buildRuleSpans). CLOCK↔CLOCK = jede Nacht (lückenlos, ermöglicht
        //    „immer 22–7 außer ND"), ALARM/SHIFT_END = schicht-relativ, leere Fensterliste = ND-Ausnahme.
        val rules = if (toggles.rulesEnabled) dimRuleUseCase.getAllRules() else emptyList()
        val rulesActive = toggles.rulesEnabled && rules.any { it.enabled }
        val slots = alarms.map { DimWindowResolver.AlarmSlot(it.triggerTime, it.shiftName, it.shiftEndTime) }
        if (rulesActive) {
            out += DimWindowResolver.buildRuleSpans(
                alarms = slots,
                horizonDays = HORIZON_DAYS,
                today = LocalDate.now(zone),
                zone = zone,
                ruleForShift = { name -> dimRuleUseCase.findRuleForShift(name, rules) },
                ruleForFreeDay = { dimRuleUseCase.findRuleForFreeDay(rules) },
            )
        }

        // 3. Nacht-Standard: ab fester Uhrzeit bis zum naechsten Wecker, ohne dass dafuer eine
        //    Regel angelegt werden muss. Nur an Tagen wirksam, die KEINE Regel aus Punkt 2 haben
        //    (rulesActive steuert dieselbe Ausschliesslichkeit wie dort - ist "Regeln" aus, gibt es
        //    keine Ausnahmen und der Standard gilt lückenlos jede Nacht).
        if (toggles.nightDefaultEnabled) {
            out += DimWindowResolver.buildDefaultNightSpans(
                alarms = slots,
                horizonDays = HORIZON_DAYS,
                today = LocalDate.now(zone),
                zone = zone,
                startClockMinutes = prefs.nightDefaultStartMinutesNow(),
                freeDayEndClockMinutes = prefs.nightDefaultFreeEndMinutesNow(),
                strength = gStrength,
                warmth = gWarmth,
                ruleForShift = { name -> if (rulesActive) dimRuleUseCase.findRuleForShift(name, rules) else null },
                ruleForFreeDay = { if (rulesActive) dimRuleUseCase.findRuleForFreeDay(rules) else null },
            )
        }
        return out
    }

    private suspend fun computeNextTransition(now: Long): Long? =
        windows()
            .flatMap { listOf(it.range.first, it.range.last) }
            .filter { it > now }
            .minOrNull()

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
