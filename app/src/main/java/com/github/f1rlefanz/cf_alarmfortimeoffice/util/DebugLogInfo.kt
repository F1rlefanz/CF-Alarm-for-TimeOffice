package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import timber.log.Timber

/**
 * Debug utilities for log file information
 */
object DebugLogInfo {
    
    /**
     * Logs current log file information to Timber
     * Useful for debugging and verification
     */
    fun logFileInfo(context: Context) {
        val info = LogExporter.getLogFileInfo(context)
        
        Timber.d("📁 DEBUG LOG FILE INFO:")
        Timber.d("Main Log: exists=${info.mainFile.exists}, size=${info.mainFile.sizeKB}KB")
        Timber.d("Main Log Path: ${info.mainFile.path}")
        
        if (info.backupFile.exists) {
            Timber.d("Backup Log: exists=${info.backupFile.exists}, size=${info.backupFile.sizeKB}KB")
            Timber.d("Backup Log Path: ${info.backupFile.path}")
        }
        
        Timber.d("Total log size: ${info.mainFile.sizeKB + info.backupFile.sizeKB}KB")
    }
}
