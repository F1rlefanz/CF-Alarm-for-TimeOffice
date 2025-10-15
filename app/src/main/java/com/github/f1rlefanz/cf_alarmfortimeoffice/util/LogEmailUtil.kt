package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility-Klasse für das Versenden von Log-Dateien per E-Mail
 */
object LogEmailUtil {
    
    private const val TARGET_EMAIL = "trashmail126@yahoo.de"
    private const val SUBJECT_PREFIX = "CF-Alarm Debug Logs"
    
    /**
     * Sendet die Log-Datei per E-Mail über die Standard-E-Mail-App
     * 
     * @param context Android Context
     * @return Result mit Erfolg oder Fehler
     */
    fun sendLogFileViaEmail(context: Context): Result<Unit> {
        return try {
            // 1. Log-Datei finden
            val logFile = File(context.getExternalFilesDir(null), "debug_logs.txt")
            
            if (!logFile.exists() || logFile.length() == 0L) {
                return Result.failure(Exception("Keine Log-Datei gefunden oder Datei ist leer"))
            }
            
            Logger.i(LogTags.APP, "📧 Bereite E-Mail-Versand vor: ${logFile.absolutePath}, Größe: ${logFile.length()} Bytes")
            
            // 2. FileProvider URI erstellen für sicheren Zugriff
            val fileUri: Uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    logFile
                )
            } catch (e: Exception) {
                Logger.e(LogTags.APP, "❌ Fehler beim Erstellen der FileProvider URI", e)
                return Result.failure(Exception("Fehler beim Zugriff auf Log-Datei: ${e.message}"))
            }
            
            // 3. E-Mail Intent erstellen
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(TARGET_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, createEmailSubject())
                putExtra(Intent.EXTRA_TEXT, createEmailBody(context, logFile))
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // 4. E-Mail-App öffnen
            val chooserIntent = Intent.createChooser(emailIntent, "Log-Datei per E-Mail senden")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            context.startActivity(chooserIntent)
            
            Logger.i(LogTags.APP, "✅ E-Mail-App geöffnet für Log-Versand")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.APP, "❌ Fehler beim E-Mail-Versand", e)
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
    private fun createEmailBody(context: Context, logFile: File): String {
        return buildString {
            appendLine("CF-Alarm Debug Logs")
            appendLine("==================")
            appendLine()
            appendLine("App-Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build-Typ: ${if (BuildConfig.DEBUG) "Debug" else "Release"}")
            appendLine("Android-Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Gerät: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine()
            appendLine("Log-Datei: ${logFile.name}")
            appendLine("Größe: ${formatFileSize(logFile.length())}")
            appendLine("Zeitstempel: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN).format(Date())}")
            appendLine()
            appendLine("Die Log-Datei ist als Anhang beigefügt.")
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
        val logFile = File(context.getExternalFilesDir(null), "debug_logs.txt")
        return logFile.exists() && logFile.length() > 0
    }
    
    /**
     * Gibt Informationen über die Log-Datei zurück
     */
    fun getLogFileInfo(context: Context): String? {
        val logFile = File(context.getExternalFilesDir(null), "debug_logs.txt")
        return if (logFile.exists()) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMAN)
            val lastModified = dateFormat.format(Date(logFile.lastModified()))
            "Größe: ${formatFileSize(logFile.length())}, Aktualisiert: $lastModified"
        } else {
            null
        }
    }
}
