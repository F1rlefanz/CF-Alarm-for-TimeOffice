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
        Logger.business(LogTags.MAINTENANCE, "⏰ Exact Alarm triggered - starting maintenance")
        
        try {
            AlarmMaintenanceService.start(context)
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "Failed to start maintenance service", e)
        }
    }
}
