package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

/**
 * Exakt oder ungenau planen - fuer die NEBENKETTEN (Dimmer, DND, Rufbereitschafts-Abfrage,
 * 6h-Wartung). Der Wecker selbst geht NICHT hierueber, sondern ueber `setAlarmClock()` in
 * `AlarmManagerService.setExactOrInexact` (mit try/catch und eigenem Fallback).
 *
 * Bis v1.45 stand die SDK-Weiche samt exakt/ungenau-Verzweigung viermal wortgleich in den
 * Ketten (#131, G11-13). Explizite `SDK_INT`-Verzweigung statt `||`-Kurzschluss: minSdk ist 26,
 * `canScheduleExactAlarms()` gibt es erst ab 31 - diese Form versteht der Lint sicher.
 */
object ExakteAlarme {

    /** Darf die App exakte Alarme stellen? Unter API 31 braucht es keine Berechtigung. */
    fun erlaubt(alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * Stellt [pendingIntent] auf [zeitpunkt] (RTC_WAKEUP) - exakt, wenn erlaubt, sonst ungenau.
     * `setExactAndAllowWhileIdle()` wirft ohne die Berechtigung (API 31/32), deshalb die Frage
     * vorher. Ungenau heisst: Doze darf verschieben - fuer eine Nebenkette hinnehmbar.
     *
     * @return `true`, wenn exakt gestellt wurde - fuer das Log an der Aufrufstelle.
     */
    fun stelle(alarmManager: AlarmManager, zeitpunkt: Long, pendingIntent: PendingIntent): Boolean {
        val exakt = erlaubt(alarmManager)
        if (exakt) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeitpunkt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, zeitpunkt, pendingIntent)
        }
        return exakt
    }
}
