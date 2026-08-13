package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Reagiert auf Zeitzonen-Wechsel (Reise, manuelle Umstellung).
 *
 * WARUM ueberhaupt etwas zu tun ist: die Weckzeit ist WANDUHR-relativ, nicht instant-relativ.
 * `ShiftRecognitionEngine.calculateAlarmTime()` baut sie als `Schicht-Datum (lokal) + konfigurierte
 * LocalTime` und `AlarmManagerService.setAlarmFromShiftMatch` friert das per
 * `.atZone(ZoneId.systemDefault()).toEpochMilli()` als absoluten Trigger ein - ZUM ZEITPUNKT DES
 * STELLENS. Aendert sich danach die Systemzeitzone, steht der bereits gesetzte `AlarmManager`-
 * Trigger noch auf der alten Zone.
 *
 * WICHTIG - was NICHT hilft: ein blosses Re-Arming der gespeicherten Alarme
 * (`alarmUseCase.scheduleSystemAlarm`) ist ein No-Op. Dieser Pfad rechnet die gespeicherten
 * Epoch-Millis nur ueber `LocalDateTime.ofInstant(..., systemDefault())` hin und in
 * `setAlarmFromShiftMatch` mit derselben Zone zurueck - identische Millis, keine Korrektur. Die
 * Weckzeit kann NUR aus den Kalender-Events neu berechnet werden (deren lokale Darstellung sich
 * mit der Zone tatsaechlich aendert).
 *
 * Deshalb `forceSync = true`: der Wartungslauf ueberspringt sein Lade-Gate (Puffer/Datenalter) und
 * laedt+synchronisiert wirklich. Ohne dieses Flag kehrte der angestossene Lauf im Normalbetrieb
 * (Puffer >= 7 Tage) zurueck, ohne den Kalender ueberhaupt anzufassen - dieser Receiver war damit
 * wirkungslos. Der erzwungene Lauf frischt ausserdem Dimmer- und DND-Tick auf (beide arbeiten mit
 * Wanduhr-Fenstern und sind vom Zonenwechsel genauso betroffen); das erledigt der finally-Block
 * von `AlarmMaintenanceService.onStartCommand` auf jedem Pfad.
 *
 * Bewusst schlank: keine eigene Alarm-Anpassungslogik, kein Hilt noetig.
 */
class TimezoneChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return

        Logger.business(
            LogTags.MAINTENANCE,
            "🌍 Zeitzonen-Wechsel erkannt - erzwinge Wartungslauf, um Weckzeiten neu zu berechnen"
        )
        AlarmMaintenanceService.start(context, forceSync = true)
    }
}
