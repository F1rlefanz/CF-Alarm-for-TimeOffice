package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility-Klasse für das Versenden von Log-Dateien per E-Mail
 */
object LogEmailUtil {
    
    private const val TARGET_EMAIL = "cfischer@csj.de"
    private const val SUBJECT_PREFIX = "CF-Alarm Debug Logs"
    
    /**
     * Sendet die Log-Datei(en) per E-Mail über die Standard-E-Mail-App
     * Inkl. Backup-Datei falls vorhanden
     * 
     * @param context Android Context
     * @return Result mit Erfolg oder Fehler
     */
    fun sendLogFileViaEmail(context: Context): Result<Unit> {
        return try {
            val logDir = context.getExternalFilesDir(null)
            val filesToSend = mutableListOf<Uri>()
            
            if (logDir != null) {
                val logFiles = logDir.listFiles { _, name -> name.startsWith("debug_logs_") && name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                    
                for (file in logFiles) {
                    if (file.exists() && file.length() > 0L) {
                        try {
                            val fileUri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            filesToSend.add(fileUri)
                        } catch (e: Exception) {
                            Logger.e(LogTags.APP, "❌ Fehler beim Erstellen der FileProvider URI für Log", e)
                        }
                    }
                }
            }
            
            if (filesToSend.isEmpty()) {
                return Result.failure(Exception("Keine Log-Dateien gefunden oder Dateien sind leer"))
            }
            
            Logger.i(LogTags.APP, "📧 Bereite E-Mail-Versand vor: ${filesToSend.size} Datei(en)")
            
            // 3. E-Mail Intent erstellen
            val emailIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(TARGET_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, createEmailSubject())
                putExtra(Intent.EXTRA_TEXT, createEmailBody(filesToSend.size))
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(filesToSend))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // 4. App-Chooser öffnen (inkl. WhatsApp, Telegram, etc.)
            val chooserIntent = Intent.createChooser(emailIntent, "Logs senden")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            context.startActivity(chooserIntent)
            
            Logger.i(LogTags.APP, "✅ Sharing-Dialog geöffnet für Log-Versand")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.APP, "❌ Fehler beim Versand", e)
            Result.failure(e)
        }
    }
    
    /**
     * Erstellt den E-Mail-Betreff mit Zeitstempel und App-Version
     */
    private fun createEmailSubject(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMAN)
        val timestamp = dateFormat.format(Date())
        return "$SUBJECT_PREFIX - $timestamp - v${BuildConfig.VERSION_NAME}"
    }
    
    /**
     * Erstellt den E-Mail-Body mit System-Informationen
     */
    private fun createEmailBody(fileCount: Int): String {
        return buildString {
            appendLine("CF-Alarm Debug Logs")
            appendLine("==================")
            appendLine()
            appendLine("App-Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build-Typ: ${if (BuildConfig.DEBUG) "Debug" else "Release"}")
            appendLine("Android-Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Gerät: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine()
            appendLine("Anzahl der Log-Dateien: $fileCount (letzte 8 Tage)")
            appendLine("Zeitstempel: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN).format(Date())}")
            appendLine()
            appendLine("Die Log-Dateien sind als Anhang beigefügt.")
        }
    }
    
    /**
     * Formatiert die Dateigröße human-readable
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Bytes"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.GERMAN, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
    
    /**
     * Prüft ob eine Log-Datei existiert und Daten enthält
     */
    fun hasLogFile(context: Context): Boolean {
        val logDir = context.getExternalFilesDir(null) ?: return false
        val logFiles = logDir.listFiles { _, name -> name.startsWith("debug_logs_") && name.endsWith(".txt") }
        return logFiles != null && logFiles.any { it.length() > 0 }
    }
    
    /**
     * Löscht alle debug_logs_*.txt-Dateien AUSSER der von heute. Bewusst NICHT an
     * [sendLogFileViaEmail] gekoppelt - die heutige Datei wird oft mehrfach am selben Tag neu
     * verschickt, waehrend eine laufende Untersuchung fortschreitet; ein "loeschen nach dem
     * Teilen" wuerde genau diese Datei zwischen zwei Sendungen wegreissen. "Ausser heute" ist
     * die einzige Regel, die unabhaengig von der Tageszeit beim Antippen garantiert nichts noch
     * Gebrauchtes loescht - kein Zeitfenster, kein Uhrzeit-Grenzfall.
     *
     * @return Anzahl der geloeschten Dateien
     */
    fun deleteOldLogs(context: Context): Int {
        val logDir = context.getExternalFilesDir(null) ?: return 0
        val todayFileName = "debug_logs_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.txt"

        val deleted = logDir.listFiles { _, name ->
            name.startsWith("debug_logs_") && name.endsWith(".txt") && name != todayFileName
        }?.count { it.delete() } ?: 0

        Logger.i(LogTags.FILE_SYSTEM, "🗑️ Manuelle Log-Bereinigung: $deleted Datei(en) gelöscht (heutige Datei behalten)")
        return deleted
    }

    /**
     * Gibt Informationen über die Log-Datei zurück
     */
    fun getLogFileInfo(context: Context): String? {
        val logDir = context.getExternalFilesDir(null) ?: return null
        val logFiles = logDir.listFiles { _, name -> name.startsWith("debug_logs_") && name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            
        if (logFiles.isNullOrEmpty()) return null
        
        val totalSize = logFiles.sumOf { it.length() }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN)
        val lastModified = dateFormat.format(Date(logFiles.first().lastModified()))
        
        return "${logFiles.size} Dateien (max 8 Tage), Gesamt: ${formatFileSize(totalSize)}, Aktualisiert: $lastModified"
    }
}
