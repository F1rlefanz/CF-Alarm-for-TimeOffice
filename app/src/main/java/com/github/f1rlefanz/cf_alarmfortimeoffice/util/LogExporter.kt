package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for exporting debug logs
 * Helps with log analysis and debugging
 */
object LogExporter {
    
    /**
     * Gets the current debug log file
     */
    fun getLogFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "debug_logs.txt")
    }
    
    /**
     * Gets the backup log file (if exists)
     */
    fun getBackupLogFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "debug_logs_backup.txt")
    }
    
    /**
     * Creates a share intent for the log file
     * Useful for sending logs via email or other apps
     */
    fun createShareIntent(context: Context): Intent? {
        val logFile = getLogFile(context)
        
        if (!logFile.exists()) {
            return null
        }
        
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )
            
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CF Alarm Debug Logs")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Debug logs from CF Alarm app\n" +
                            "Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                            "File size: ${logFile.length() / 1024}KB"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Gets log file info for debugging
     */
    fun getLogFileInfo(context: Context): LogFileInfo {
        val logFile = getLogFile(context)
        val backupFile = getBackupLogFile(context)
        
        return LogFileInfo(
            mainFile = LogInfo(
                exists = logFile.exists(),
                path = logFile.absolutePath,
                sizeKB = if (logFile.exists()) logFile.length() / 1024 else 0,
                lastModified = if (logFile.exists()) Date(logFile.lastModified()) else null
            ),
            backupFile = LogInfo(
                exists = backupFile.exists(),
                path = backupFile.absolutePath,
                sizeKB = if (backupFile.exists()) backupFile.length() / 1024 else 0,
                lastModified = if (backupFile.exists()) Date(backupFile.lastModified()) else null
            )
        )
    }
    
    data class LogFileInfo(
        val mainFile: LogInfo,
        val backupFile: LogInfo
    )
    
    data class LogInfo(
        val exists: Boolean,
        val path: String,
        val sizeKB: Long,
        val lastModified: Date?
    )
}
