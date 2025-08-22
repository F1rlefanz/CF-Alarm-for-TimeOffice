package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmReceiver
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Enhanced AlarmManager service with maximum reliability and doze mode compatibility.
 *
 * Key Features:
 * - Uses setAlarmClock() for maximum doze mode reliability
 * - Integrated battery optimization management
 * - Structured error handling with Result types
 * - Resource management and cleanup
 * - Comprehensive debugging capabilities
 * - 🚀 PHASE 3: Proactive Token-Refresh-Integration
 *
 * Architecture: Clean separation of concerns with dependency injection support
 */
class AlarmManagerService(
    private val application: Application,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val wakeLockManager: WakeLockManager
) {

    private val alarmManager = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    data class AlarmStatus(
        val systemAlarmSet: Boolean,
        val canScheduleExactAlarms: Boolean,
        val alarmStatusMessage: String?,
        val batteryOptimizationExempt: Boolean = false,
        val recommendedActions: List<String> = emptyList()
    )

    data class NextAlarmInfo(
        val triggerTime: Long,
        val formattedTime: String,
        val isAlarmClockType: Boolean = false
    )

    /**
     * Enhanced permission check including battery optimization status
     */
    fun checkAlarmPermissions(): AlarmPermissionStatus {
        val canScheduleExact = canScheduleExactAlarms()
        val batteryExempt = batteryOptimizationManager.isIgnoringBatteryOptimizations()

        val overallStatus = when {
            canScheduleExact && batteryExempt -> AlarmPermissionLevel.OPTIMAL
            canScheduleExact && !batteryExempt -> AlarmPermissionLevel.GOOD_BUT_RISKY
            !canScheduleExact && batteryExempt -> AlarmPermissionLevel.MISSING_EXACT_ALARM
            else -> AlarmPermissionLevel.CRITICAL_MISSING
        }

        return AlarmPermissionStatus(
            level = overallStatus,
            canScheduleExactAlarms = canScheduleExact,
            batteryOptimizationExempt = batteryExempt,
            recommendations = buildRecommendations(canScheduleExact, batteryExempt)
        )
    }

    private fun buildRecommendations(
        canScheduleExact: Boolean,
        batteryExempt: Boolean
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (!canScheduleExact) {
            recommendations.add("Aktiviere 'Exakte Alarme' in den App-Einstellungen")
        }

        if (!batteryExempt) {
            recommendations.add("Aktiviere Akkuoptimierung-Ausnahme für zuverlässige Alarme")
        }

        return recommendations
    }

    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // API < 31 needs no permission
        }
    }

    fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                application.startActivity(intent)
            }
        }
    }

    fun getNextAlarmInfo(): NextAlarmInfo? {
        return try {
            val nextAlarmClockInfo = alarmManager.nextAlarmClock
            if (nextAlarmClockInfo != null) {
                val triggerTime = nextAlarmClockInfo.triggerTime
                val formattedTime = formatAlarmTime(
                    java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(triggerTime),
                        ZoneId.systemDefault()
                    )
                )
                NextAlarmInfo(
                    triggerTime = triggerTime,
                    formattedTime = formattedTime,
                    isAlarmClockType = true // AlarmClock API used
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_MANAGER, "Error getting next alarm info", e)
            null
        }
    }

    /**
     * Setzt einen Alarm für eine Schicht mit EINDEUTIGEM Request Code
     *
     * @param shiftMatch Die Schicht-Informationen
     * @param autoAlarmEnabled Ob Auto-Alarm aktiviert ist
     * @param alarmId EINDEUTIGE ID für diesen Alarm (z.B. aus der Datenbank)
     */
    fun setAlarmFromShiftMatch(
        shiftMatch: ShiftMatch,
        autoAlarmEnabled: Boolean,
        alarmId: Int // NEU: Eindeutige Alarm-ID!
    ): AlarmStatus {
        Logger.business(
            LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: setAlarmFromShiftMatch called",
            "autoAlarmEnabled=$autoAlarmEnabled, shift=${shiftMatch.shiftDefinition.name}, alarmTime=${shiftMatch.calculatedAlarmTime}, alarmId=$alarmId"
        )

        if (!autoAlarmEnabled) {
            Logger.d(LogTags.ALARM_MANAGER, "Auto-Alarm disabled, not setting alarm")
            return createAlarmStatus(
                systemAlarmSet = false,
                message = "Auto-Alarm deaktiviert"
            )
        }

        val permissionStatus = checkAlarmPermissions()
        Logger.business(
            LogTags.ALARM_MANAGER,
            "🔧 ALARM DEBUG: Permissions check",
            "canScheduleExactAlarms=${permissionStatus.canScheduleExactAlarms}"
        )

        if (!permissionStatus.canScheduleExactAlarms) {
            Logger.w(
                LogTags.ALARM_MANAGER,
                "❌ No permission for exact alarms - requesting permission"
            )
            // Auto-request permission for better UX
            requestExactAlarmPermission()
            return createAlarmStatus(
                systemAlarmSet = false,
                message = "Alarm-Berechtigung fehlt - Berechtigung angefordert"
            )
        }

        return try {
            val alarmTimeMillis = shiftMatch.calculatedAlarmTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val currentTimeMillis = System.currentTimeMillis()
            val timeDifferenceMinutes = (alarmTimeMillis - currentTimeMillis) / (1000 * 60)

            Logger.business(
                LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Timing",
                "currentTime=$currentTimeMillis, alarmTime=$alarmTimeMillis, differenceMinutes=$timeDifferenceMinutes"
            )

            if (alarmTimeMillis <= currentTimeMillis) {
                Logger.w(
                    LogTags.ALARM_MANAGER,
                    "🔧 ALARM DEBUG: Alarm time is in the past! alarmTime=${shiftMatch.calculatedAlarmTime}, current=${java.time.LocalDateTime.now()}"
                )
                return createAlarmStatus(
                    systemAlarmSet = false,
                    message = "Alarm-Zeit liegt in der Vergangenheit"
                )
            }

            // Erstelle enhanced alarm intent
            val alarmIntent = createEnhancedAlarmIntent(alarmId, shiftMatch)
            val pendingIntent = createAlarmPendingIntent(alarmId, alarmIntent)

            Logger.business(
                LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Setting alarm",
                "requestCode=$alarmId, pendingIntent=$pendingIntent"
            )

            // KRITISCHE VERBESSERUNG: Verwende setAlarmClock() für maximale Doze-Mode Zuverlässigkeit
            val showIntent = createShowAlarmIntent(alarmId, shiftMatch)
            val alarmClockInfo = AlarmManager.AlarmClockInfo(alarmTimeMillis, showIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)

            val formattedTime = formatAlarmTime(shiftMatch.calculatedAlarmTime)
            Logger.business(
                LogTags.ALARM_MANAGER, "✅ ALARM DEBUG: System alarm set successfully",
                "${shiftMatch.shiftDefinition.name} at $formattedTime (ID: $alarmId)"
            )

            // Verify alarm was set by checking next alarm
            val nextAlarmInfo = getNextAlarmInfo()
            Logger.business(
                LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Next alarm verification",
                "nextAlarm=${nextAlarmInfo?.formattedTime ?: "none"}"
            )

            createAlarmStatus(
                systemAlarmSet = true,
                message = "Alarm gesetzt für $formattedTime"
            )

        } catch (e: SecurityException) {
            Logger.e(
                LogTags.ALARM_MANAGER,
                "🔧 ALARM DEBUG: SecurityException when setting alarm",
                e
            )
            createAlarmStatus(
                systemAlarmSet = false,
                message = "Alarm-Berechtigung verweigert: ${e.message}"
            )
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Exception when setting alarm", e)
            createAlarmStatus(
                systemAlarmSet = false,
                message = "Fehler beim Alarm setzen: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Creates enhanced alarm intent with comprehensive shift information
     */
    private fun createEnhancedAlarmIntent(alarmId: Int, shiftMatch: ShiftMatch): Intent {
        return Intent(application, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", alarmId)
            putExtra("shift_name", shiftMatch.shiftDefinition.name)
            putExtra("alarm_time", formatAlarmTime(shiftMatch.calculatedAlarmTime))
            putExtra("shift_pattern", shiftMatch.shiftDefinition.id)
            putExtra("shift_start_time", shiftMatch.calendarEvent.startTime.toString())
            putExtra("shift_end_time", shiftMatch.calendarEvent.endTime.toString())
            putExtra("alarm_type", "setAlarmClock") // Track which API was used
            setPackage(application.packageName)
            action = "com.github.f1rlefanz.cf_alarmfortimeoffice.ENHANCED_ALARM_$alarmId"
        }
    }

    /**
     * Creates PendingIntent for alarm with proper flags
     */
    private fun createAlarmPendingIntent(alarmId: Int, alarmIntent: Intent): PendingIntent {
        return PendingIntent.getBroadcast(
            application,
            alarmId,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Creates show intent for AlarmClockInfo (when user taps on system alarm)
     */
    private fun createShowAlarmIntent(alarmId: Int, shiftMatch: ShiftMatch): PendingIntent {
        val showIntent = Intent(application, MainActivity::class.java).apply {
            putExtra("alarm_id", alarmId)
            putExtra("shift_name", shiftMatch.shiftDefinition.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(application.packageName)
        }

        return PendingIntent.getActivity(
            application,
            alarmId + 10000, // Offset to avoid conflicts
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Creates AlarmStatus with comprehensive information
     */
    private fun createAlarmStatus(
        systemAlarmSet: Boolean,
        message: String,
        recommendations: List<String> = emptyList()
    ): AlarmStatus {
        return AlarmStatus(
            systemAlarmSet = systemAlarmSet,
            canScheduleExactAlarms = canScheduleExactAlarms(),
            alarmStatusMessage = message,
            batteryOptimizationExempt = batteryOptimizationManager.isIgnoringBatteryOptimizations(),
            recommendedActions = recommendations
        )
    }

    /**
     * Enhanced alarm cancellation with proper cleanup
     */
    fun cancelSystemAlarm(alarmId: Int): AlarmStatus {
        return try {
            Logger.business(
                LogTags.ALARM_MANAGER,
                "🗑️ ENHANCED ALARM: Cancelling alarm ID: $alarmId"
            )

            val alarmIntent = Intent(application, AlarmReceiver::class.java).apply {
                setPackage(application.packageName)
                action = "com.github.f1rlefanz.cf_alarmfortimeoffice.ENHANCED_ALARM_$alarmId"
            }

            val pendingIntent = PendingIntent.getBroadcast(
                application,
                alarmId,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Cancel the alarm
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            Logger.business(
                LogTags.ALARM_MANAGER,
                "✅ ENHANCED ALARM: System alarm cancelled (ID: $alarmId)"
            )

            createAlarmStatus(
                systemAlarmSet = false,
                message = "Alarm abgebrochen"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_MANAGER, "❌ Error cancelling system alarm", e)
            createAlarmStatus(
                systemAlarmSet = false,
                message = "Fehler beim Alarm abbrechen: ${e.localizedMessage}"
            )
        }
    }

    private fun formatAlarmTime(alarmTime: java.time.LocalDateTime): String {
        val formatter = DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
        return alarmTime.format(formatter)
    }

    /**
     * 🔍 ENHANCED DEBUG: Comprehensive alarm status for debugging
     */
    fun getEnhancedAlarmDebugInfo(): String {
        val permissionStatus = checkAlarmPermissions()
        val batteryStatus = batteryOptimizationManager.getBasicStatus()
        val nextAlarm = getNextAlarmInfo()

        return buildString {
            appendLine("=== ENHANCED ALARM DEBUG INFO ===")
            appendLine("Permission Level: ${permissionStatus.level}")
            appendLine("Can schedule exact alarms: ${permissionStatus.canScheduleExactAlarms}")
            appendLine("Battery optimization exempt: ${permissionStatus.batteryOptimizationExempt}")
            appendLine("Next alarm: ${nextAlarm?.formattedTime ?: "None"}")
            appendLine("Next alarm type: ${if (nextAlarm?.isAlarmClockType == true) "AlarmClock" else "Regular"}")
            appendLine("Alarm manager: $alarmManager")
            appendLine("App package: ${application.packageName}")
            appendLine()
            appendLine("=== RECOMMENDATIONS ===")
            permissionStatus.recommendations.forEach { recommendation ->
                appendLine("- $recommendation")
            }
            appendLine()
            appendLine("=== BATTERY OPTIMIZATION ===")
            appendLine(batteryStatus)
            appendLine("==================================")
        }
    }

    companion object {
        private const val ALARM_REQUEST_CODE = 1001
    }
}

/**
 * Alarm permission status with actionable recommendations
 */
data class AlarmPermissionStatus(
    val level: AlarmPermissionLevel,
    val canScheduleExactAlarms: Boolean,
    val batteryOptimizationExempt: Boolean,
    val recommendations: List<String>
)

/**
 * Alarm permission levels for user guidance
 */
enum class AlarmPermissionLevel {
    OPTIMAL,           // All permissions granted, battery optimization exempt
    GOOD_BUT_RISKY,    // Exact alarms allowed but no battery optimization exemption
    MISSING_EXACT_ALARM, // Battery exempt but no exact alarm permission
    CRITICAL_MISSING   // Missing both critical permissions
}
