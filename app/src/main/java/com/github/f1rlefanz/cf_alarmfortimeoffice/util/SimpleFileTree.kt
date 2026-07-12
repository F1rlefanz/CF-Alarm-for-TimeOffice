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
/**
 * @param minPriority Nur Logs ab dieser Prioritaet (android.util.Log-Konstanten) werden
 *   persistiert. In Release WARN, damit INFO/business-Logs mit PII (E-Mail, Kalendertitel)
 *   NICHT im Klartext auf External Storage landen (widersprach sonst der Datenschutzerklaerung).
 */
class SimpleFileTree(
    private val logDir: File,
    private val minPriority: Int = Log.VERBOSE
) : Timber.Tree() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    init {
        // Ensure parent directory exists
        logDir.mkdirs()
        
        // Clean up old logs on initialization
        cleanupOldLogs()
    }
    
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // PII-Schutz: In Release nur WARN+ ins Datei-Log (siehe minPriority).
        if (priority < minPriority) return
        try {
            val today = dateFormat.format(Date())
            val logFile = File(logDir, "debug_logs_$today.txt")
            
            // Skip logging if file is too large (safety check, 20MB per day is plenty)
            if (logFile.exists() && logFile.length() > 20 * 1024 * 1024) {
                return
            }
            
            val timestamp = timeFormat.format(Date())
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
        }
    }
    
    private fun cleanupOldLogs() {
        try {
            // Calculate timestamp for 8 days ago
            val eightDaysAgo = System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L
            val files = logDir.listFiles { _, name -> name.startsWith("debug_logs_") && name.endsWith(".txt") }
            files?.forEach { file ->
                if (file.lastModified() < eightDaysAgo) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // ignore cleanup errors
        }
    }
}
