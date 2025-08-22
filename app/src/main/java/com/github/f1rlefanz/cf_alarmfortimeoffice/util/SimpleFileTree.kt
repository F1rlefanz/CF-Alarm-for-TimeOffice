package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple File-based Timber Tree for persistent logging
 * Stores logs in a file that survives app restarts and device reboots
 */
class SimpleFileTree(private val logFile: File) : Timber.Tree() {
    
    companion object {
        private const val MAX_LOG_SIZE = 50 * 1024 * 1024 // 50MB max size
        private const val LOG_ROTATION_SIZE = 40 * 1024 * 1024 // Rotate at 40MB
    }
    
    init {
        // Ensure parent directory exists
        logFile.parentFile?.mkdirs()
        
        // Rotate log file if it's getting too large
        if (logFile.exists() && logFile.length() > LOG_ROTATION_SIZE) {
            rotateLogFile()
        }
    }
    
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        try {
            // Skip logging if file is too large (safety check)
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                return
            }
            
            val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val priorityChar = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "A"
                else -> "?"
            }
            
            val logEntry = buildString {
                append("$timestamp $priorityChar/$tag: $message")
                
                // Add exception if present
                if (t != null) {
                    append("\n")
                    append(t.stackTraceToString())
                }
                
                append("\n")
            }
            
            // Append to file
            logFile.appendText(logEntry)
            
        } catch (e: Exception) {
            // Ignore logging errors to prevent infinite loops
            // In a real app, you might want to report this to a crash reporter
        }
    }
    
    private fun rotateLogFile() {
        try {
            val backupFile = File(logFile.parent, "${logFile.nameWithoutExtension}_backup.${logFile.extension}")
            
            // Remove old backup if it exists
            if (backupFile.exists()) {
                backupFile.delete()
            }
            
            // Move current log to backup
            logFile.renameTo(backupFile)
            
            // Create new log file
            logFile.createNewFile()
            
            // Add rotation marker
            logFile.appendText("=== Log rotated at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} ===\n")
            
        } catch (e: Exception) {
            // If rotation fails, just continue with the current file
        }
    }
}
