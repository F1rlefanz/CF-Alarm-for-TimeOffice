package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Reagiert auf Zeitzonen-Wechsel. Die Weckzeit ist eine Wanduhrzeit auf dem Kalendertag; ein
 * Re-Arming der gespeicherten Millis waere ein No-Op. Deshalb `forceSync = true`: laden +
 * synchronisieren; Dimmer/DND frischt der `finally` der Wartung auf. Hergang Skill
 * cfalarm-wecker-und-boot.
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
