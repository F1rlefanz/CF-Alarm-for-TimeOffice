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
 *     Alarm) - OHNE dass dafuer eine Regel angelegt werden muss. Ausgeschlossen sind explizit
 *     markierte Schichten ([DimOverlayPrefs.nightDefaultExcludedShifts]) UND jeder Tag, den eine
 *     vorhandene Regel (Punkt 2) ohnehin schon abdeckt. Details in
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
    private val prefs: DimOverlayPrefs,
    private val correctionNotifier: DimCorrectionNotifier
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

    /**
     * Räumt den laufenden Overlay-Zustand + die Korrektur-Notification weg und storniert den
     * rollenden [REQ_TICK]-Alarm - symmetrisch zu [enable]. Rührt bewusst KEINEN
     * [DimOverlayPrefs.Toggles]-Wert an, damit ein späteres [enable] die exakt vorherige
     * Konfiguration wiederherstellt.
     */
    suspend fun disable() {
        prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
        correctionNotifier.cancel()
        alarmManager().cancel(buildPendingIntent())
    }

    /**
     * Wendet den Ist-Zustand an UND die Dimmer-Korrektur-Notification (Feature C), falls per
     * [DimOverlayPrefs.correctionNotificationEnabled] aktiviert. Ein evtl. gesetzter
     * [DimOverlayPrefs.Override] (Heller/Dunkler/Pause) wird nur angewendet, solange er noch zur
     * gerade aktiven Fenster-Spanne gehört ([DimWindowResolver.isOverrideStale]) - ein stale
     * gewordener Override wird hier automatisch weggeräumt, kein zusätzlicher Timer nötig (der
     * ohnehin rollende Tick ruft diese Funktion an jeder Fenstergrenze neu auf).
     */
    suspend fun applyCurrentState() {
        val now = System.currentTimeMillis()
        // Aktive Spanne (überlappen mehrere, gewinnt die dunkelste – Logik in DimWindowResolver).
        val active = DimWindowResolver.activeSpan(windows(), now)
        val override = prefs.overrideNow()
        val stale = DimWindowResolver.isOverrideStale(active?.range?.last, active?.strength, override.windowEnd, override.windowStrength)
        if (stale && (override.strengthDelta != 0 || override.paused || override.windowEnd != 0L)) {
            prefs.clearOverride()
        }

        if (active == null) {
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
            correctionNotifier.cancel()
            return
        }

        val effectiveDelta = if (stale) 0 else override.strengthDelta
        val isPaused = !stale && override.paused
        val effectiveStrength = DimWindowResolver.applyStrengthDelta(active.strength, effectiveDelta, DimOverlayPrefs.STRENGTH_MAX)

        if (isPaused) {
            // paused=true => gar kein Overlay berechnen (nicht nur strength=0 - der Nutzer hat
            // das Dimmen bewusst ausgesetzt).
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
        } else {
            prefs.setActiveOverlay(true, effectiveStrength, active.warmth)
        }

        if (prefs.correctionNotificationEnabledNow()) {
            correctionNotifier.show(effectiveStrength, active.warmth, isPaused)
        } else {
            correctionNotifier.cancel()
        }
    }

    /**
     * Aktive Spanne JETZT, ohne Seiteneffekt - fuer [DimNotificationService], damit der den
     * korrekten `windowEnd`-Schluessel fuer einen Override kennt, ohne die Fensterlogik zu
     * duplizieren.
     */
    suspend fun activeSpanNow(): DimWindowResolver.DimSpan? = DimWindowResolver.activeSpan(windows(), System.currentTimeMillis())

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
        //    Regel angelegt werden muss. Ausgeschlossen sind: explizit vom Nutzer markierte
        //    Schichten (Toggle an der Karte) UND - falls "Regeln" aktiv ist - jeder Tag, den Punkt 2
        //    ohnehin schon abdeckt (rulesActive steuert dieselbe Ausschliesslichkeit wie dort).
        if (toggles.nightDefaultEnabled) {
            val excludedShifts = prefs.nightDefaultExcludedShiftsNow()
            out += DimWindowResolver.buildDefaultNightSpans(
                alarms = slots,
                horizonDays = HORIZON_DAYS,
                today = LocalDate.now(zone),
                zone = zone,
                startClockMinutes = prefs.nightDefaultStartMinutesNow(),
                freeDayEndClockMinutes = prefs.nightDefaultFreeEndMinutesNow(),
                strength = prefs.nightDefaultStrengthNow(),
                warmth = prefs.nightDefaultWarmthNow(),
                isExcluded = { shiftName ->
                    if (shiftName != null) {
                        shiftName in excludedShifts || (rulesActive && dimRuleUseCase.findRuleForShift(shiftName, rules) != null)
                    } else {
                        rulesActive && dimRuleUseCase.findRuleForFreeDay(rules) != null
                    }
                },
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
