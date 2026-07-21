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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leitet die Dimm-Zeitfenster aus CFAlarms bestehenden Alarmen ab (kein neues Schicht-
 * Parsing): pro aktivem Alarm ein Fenster **[triggerTime − windDown, triggerTime]**. Der
 * Bildschirm dimmt also in der Wind-down-Phase vor der Weckzeit und geht zur Weckzeit wieder
 * hell. Fuer Nachtschichten (Weckzeit tagsueber) ergibt sich das Tages-Schlaffenster
 * automatisch, weil das Fenster an der Weckzeit haengt.
 *
 * Steuert EINEN rollenden, exakten Alarm auf den naechsten Fenster-Rand (analog zum
 * NachtDimmer-ScheduleManager und zu [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService]).
 * Setzt [DimOverlayPrefs.setOverlayOn]; das eigentliche Overlay traegt der
 * [DimAccessibilityService], der diese Prefs beobachtet.
 *
 * Fail-open: laesst sich der Alarm-Bestand nicht lesen, wird NICHT gedimmt (die sichere
 * Richtung – anders als beim Wecker, wo „leer" gefaehrlich waere).
 */
@Singleton
class DimScheduleUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmUseCase: IAlarmUseCase,
    private val prefs: DimOverlayPrefs
) {
    companion object {
        const val ACTION_TICK = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_SCHED_TICK"
        private const val REQ_TICK = 7710 // eigener, eindeutiger Request-Code (nur EINER)
    }

    /** Zeitplan aktivieren: Ist-Zustand anwenden + naechsten Wechsel planen. */
    suspend fun enable() {
        applyCurrentState()
        scheduleNextTransition()
    }

    /** Zeitplan deaktivieren: rollenden Alarm stornieren + Overlay aus. */
    suspend fun disable() {
        alarmManager().cancel(buildPendingIntent())
        prefs.setOverlayOn(false)
    }

    /** Setzt den Overlay-Zustand passend zum aktuellen Zeitpunkt. */
    suspend fun applyCurrentState() {
        val now = System.currentTimeMillis()
        prefs.setOverlayOn(windows().any { now in it })
    }

    /** Plant EINEN exakten Alarm auf den naechsten Fenster-Rand (Start oder Ende). */
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

    /** Aktuelle Dimm-Fenster aus den gespeicherten Alarmen. Leer, wenn Feature aus. */
    private suspend fun windows(): List<LongRange> {
        if (!prefs.snapshot().featureEnabled) return emptyList()
        val alarms = alarmUseCase.getAllAlarms().getOrElse {
            Logger.w(LogTags.DIMMER, "Alarm-Bestand nicht lesbar - kein Dimming (fail-open)")
            return emptyList()
        }
        val windDownMs = prefs.windDownMinutesNow() * 60_000L
        return alarms
            .filter { it.isActive }
            .map { (it.triggerTime - windDownMs)..it.triggerTime }
    }

    /** Fruehester Fenster-Rand echt nach [now], oder null. */
    private suspend fun computeNextTransition(now: Long): Long? =
        windows()
            .flatMap { listOf(it.first, it.last) }
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
