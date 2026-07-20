package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Reagiert auf Zeitzonen-Wechsel (Reise, manuelle Umstellung): `AlarmManagerService
 * .setAlarmFromShiftMatch` wandelt die Schicht-`LocalDateTime` per
 * `.atZone(ZoneId.systemDefault()).toEpochMilli()` in einen absoluten Trigger-Zeitpunkt um -
 * ZUM ZEITPUNKT DES STELLENS. Aendert sich danach die Systemzeitzone, bleibt der bereits
 * gesetzte `AlarmManager`-Trigger auf der alten Zone stehen; der Wecker klingelt dann zur
 * falschen Uhrzeit in der neuen Zone.
 *
 * Bewusst schlank: keine eigene Alarm-Anpassungslogik, kein Hilt noetig - nur der bereits
 * bestehende, getestete Wartungslauf wird angestossen, der ohnehin bei jedem Durchlauf
 * `LocalDateTime -> aktuelle Zone -> Epoch` frisch berechnet (gleicher Mechanismus, den
 * [BootReceiver]s Post-Recovery-Check bei niedriger Alarmzahl schon nutzt).
 */
class TimezoneChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return

        Logger.business(
            LogTags.MAINTENANCE,
            "🌍 Zeitzonen-Wechsel erkannt - stosse Wartungslauf an, um Alarme neu zu berechnen"
        )
        AlarmMaintenanceService.start(context)
    }
}
