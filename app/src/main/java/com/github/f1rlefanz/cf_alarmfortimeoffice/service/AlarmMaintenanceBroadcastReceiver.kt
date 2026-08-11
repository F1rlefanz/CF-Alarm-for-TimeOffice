package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * AlarmMaintenanceBroadcastReceiver
 * 
 * Receives Exact Alarm triggers and starts AlarmMaintenanceService
 * 
 * ARCHITECTURE:
 * - Triggered by AlarmManager every 6 hours
 * - Starts AlarmMaintenanceService as foreground service
 * - Minimal logic - just a bridge between AlarmManager and Service
 */
class AlarmMaintenanceBroadcastReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        // forceSync WEITERREICHEN, nicht verschlucken: der Nachhol-Alarm aus
        // AlarmMaintenanceService.start() (wenn ein Vordergrund-Start aus dem Hintergrund abgelehnt
        // wurde) traegt das Flag im Intent. Ohne dieses Durchreichen liefe der nachgeholte Lauf
        // OHNE Erzwingen zurueck, ohne den Kalender anzufassen - genau der wirkungslose Lauf, den
        // TimezoneChangeReceiver vermeiden will. Die regulaere 6h-Kette setzt kein Extra und
        // bekommt weiterhin false.
        val forceSync = intent.getBooleanExtra(AlarmMaintenanceService.EXTRA_FORCE_SYNC, false)
        Logger.business(
            LogTags.MAINTENANCE,
            "⏰ Exact Alarm triggered - starting maintenance (forceSync=$forceSync)"
        )

        try {
            AlarmMaintenanceService.start(context, forceSync = forceSync)
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "Failed to start maintenance service", e)
        }
    }
}
