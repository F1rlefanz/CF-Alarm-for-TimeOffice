package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.util.Log
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
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
        cleanupOldLogs(logDir)
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // PII-Schutz: In Release nur WARN+ ins Datei-Log (siehe minPriority).
        if (priority < minPriority) return
        try {
            val today = dateFormat.format(Date())
            val logFile = File(logDir, "debug_logs_$today.txt")
            val isNewFile = !logFile.exists()

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

            // Einmalige Kopfzeile mit App-Version bei jeder neuen Tagesdatei - laeuft auch
            // dann korrekt an, wenn der Prozess ueber Mitternacht durchlaeuft, ohne neu zu
            // starten (kein Verlass auf eine zufaellige "App initialisiert"-Zeile).
            if (isNewFile) {
                logFile.appendText(
                    "=== CF Alarm Log $today | v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===\n"
                )
            }
            logFile.appendText(logEntry)

        } catch (e: Exception) {
            // Ignore logging errors to prevent infinite loops
        }
    }

    companion object {
        private const val DEFAULT_RETENTION_DAYS = 8

        /** Reine, testbare Alters-Pruefung - strikt "<", ein Zeitstempel exakt an der Grenze
         * gilt NICHT als abgelaufen. */
        internal fun isExpired(
            lastModifiedMillis: Long,
            now: Long,
            retentionDays: Int = DEFAULT_RETENTION_DAYS
        ): Boolean = lastModifiedMillis < now - retentionDays * 24 * 60 * 60 * 1000L

        /**
         * Loescht alle debug_logs_*.txt-Dateien aelter als [retentionDays]. Wird sowohl beim
         * Tree-Konstruktor (App-Kaltstart) als auch aus der 6h-Wartungskette
         * (AlarmMaintenanceService) aufgerufen - ein lange laufender Prozess ohne Kaltstart
         * wuerde sonst nie erneut aufraeumen.
         */
        fun cleanupOldLogs(logDir: File, retentionDays: Int = DEFAULT_RETENTION_DAYS) {
            try {
                val now = System.currentTimeMillis()
                logDir.listFiles { _, name -> name.startsWith("debug_logs_") && name.endsWith(".txt") }
                    ?.forEach { file ->
                        if (isExpired(file.lastModified(), now, retentionDays)) {
                            file.delete()
                        }
                    }
            } catch (e: Exception) {
                // ignore cleanup errors
            }
        }
    }
}
