package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmReceiver
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        val batteryExempt = BatteryOptimizationHelper.isExempted(application)
        val canUseFullScreen = canUseFullScreenIntent()

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
            canUseFullScreenIntent = canUseFullScreen,
            recommendations = buildRecommendations(canScheduleExact, batteryExempt, canUseFullScreen)
        )
    }

    private fun buildRecommendations(
        canScheduleExact: Boolean,
        batteryExempt: Boolean,
        canUseFullScreen: Boolean
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (!canScheduleExact) {
            recommendations.add("Aktiviere 'Exakte Alarme' in den App-Einstellungen")
        }

        if (!batteryExempt) {
            recommendations.add("Aktiviere Akkuoptimierung-Ausnahme für zuverlässige Alarme")
        }

        if (!canUseFullScreen) {
            recommendations.add("Erlaube 'Vollbild-Benachrichtigungen' - ohne sie erscheint der Wecker nur als Banner statt als Vollbild")
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

    /**
     * Darf die App eine Vollbild-Benachrichtigung zeigen?
     *
     * Seit Android 14 (API 34) wird USE_FULL_SCREEN_INTENT zwar bei der Installation gewaehrt,
     * der Play Store entzieht sie danach aber allen Apps, die er nicht als Wecker- oder
     * Telefonie-App einstuft. Ohne die Berechtigung degradiert das System den Full-Screen-Intent
     * stillschweigend zu einer Heads-up-Notification: der Wecker klingelt, aber der Weck-Screen
     * kommt nie hoch - und nichts weist darauf hin.
     *
     * Wichtig fuer die Erwartungshaltung: Selbst MIT Berechtigung zeigt Android laut Doku bewusst
     * nur ein Banner, solange das Geraet entsperrt und in Benutzung ist ("While the user is using
     * the device, the system UI might display a heads-up notification instead of launching your
     * full-screen intent"). Das Vollbild ist fuer das gesperrte/dunkle Geraet gedacht - also fuer
     * den echten Weckfall. Ein Test mit entsperrtem Handy in der Hand beweist hier nichts.
     */
    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = application.getSystemService(NotificationManager::class.java)
            notificationManager.canUseFullScreenIntent()
        } else {
            true // < API 34: Berechtigung wird mit der Installation gewaehrt
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

    /**
     * Oeffnet die Systemeinstellung "Vollbild-Benachrichtigungen" fuer diese App.
     * Der einzige Weg, die von Play entzogene Berechtigung zurueckzuholen.
     */
    fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canUseFullScreenIntent()) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:${application.packageName}")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            try {
                application.startActivity(intent)
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Einstellung für Vollbild-Benachrichtigungen nicht erreichbar", e)
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
            action = enhancedAlarmAction(alarmId)
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
            batteryOptimizationExempt = BatteryOptimizationHelper.isExempted(application),
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
                action = enhancedAlarmAction(alarmId)
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
        val nextAlarm = getNextAlarmInfo()

        return buildString {
            appendLine("=== ENHANCED ALARM DEBUG INFO ===")
            appendLine("Permission Level: ${permissionStatus.level}")
            appendLine("Can schedule exact alarms: ${permissionStatus.canScheduleExactAlarms}")
            appendLine("Battery optimization exempt: ${permissionStatus.batteryOptimizationExempt}")
            // false => Wecker erscheint nur als Banner, der Weck-Screen kommt nicht von selbst.
            appendLine("Can use full-screen intent: ${permissionStatus.canUseFullScreenIntent}")
            appendLine("Next alarm: ${nextAlarm?.formattedTime ?: "None"}")
            appendLine("Next alarm type: ${if (nextAlarm?.isAlarmClockType == true) "AlarmClock" else "Regular"}")
            appendLine("Alarm manager: $alarmManager")
            appendLine("App package: ${application.packageName}")
            appendLine()
            appendLine("=== RECOMMENDATIONS ===")
            permissionStatus.recommendations.forEach { recommendation ->
                appendLine("- $recommendation")
            }
            appendLine("==================================")
        }
    }

    companion object {
        private const val ALARM_REQUEST_CODE = 1001

        /**
         * Action-String des Alarm-PendingIntents. MUSS an allen Stellen identisch sein
         * (Setzen, Abbrechen, Direct-Boot-Restore), sonst adressieren sie verschiedene
         * Alarm-Slots und es entstehen Doppel-Alarme.
         */
        fun enhancedAlarmAction(alarmId: Int): String =
            "com.github.f1rlefanz.cf_alarmfortimeoffice.ENHANCED_ALARM_$alarmId"

        /**
         * Action-String des SNOOZE-PendingIntents - bewusst verschieden von
         * [enhancedAlarmAction].
         *
         * Ein Snooze darf NICHT denselben PendingIntent-Slot belegen wie sein Ursprungsalarm:
         * PendingIntents matchen ueber requestCode + Action. Waeren beide gleich, wuerde
         * [cancelSystemAlarm] den schwebenden Snooze mit abraeumen - und genau das passiert
         * regelmaessig, weil der Maintenance-Sync gefeuerte (= in der Vergangenheit liegende)
         * Alarme loescht und dabei cancelSystemAlarm aufruft. Der Nutzer haette gedrueckt
         * "5 Minuten schlummern" und waere nie wieder geweckt worden.
         *
         * Die Action bleibt pro alarmId stabil, damit ein zweiter Snooze denselben Slot
         * ersetzt statt einen weiteren Wecker zu stapeln.
         */
        fun snoozeAlarmAction(alarmId: Int): String =
            "com.github.f1rlefanz.cf_alarmfortimeoffice.SNOOZE_ALARM_$alarmId"

        /**
         * DE-SICHERES Neusetzen eines Alarms aus dem Direct-Boot-Spiegel.
         *
         * Baut exakt denselben PendingIntent wie der regulaere Pfad (gleiche requestCode=id und
         * action), sodass ein spaeteres regulaeres Rescheduling nach Entsperrung den Alarm via
         * FLAG_UPDATE_CURRENT aktualisiert statt zu duplizieren. Braucht KEIN Hilt/CE/Kalender:
         * setAlarmClock() ist von der Exact-Alarm-Berechtigung ausgenommen und darf im Direct-Boot
         * laufen. Nur fuer Alarme in der Zukunft.
         */
        fun rescheduleFromDirectBoot(
            context: Context,
            id: Int,
            triggerTime: Long,
            shiftName: String,
            alarmTimeFormatted: String
        ) {
            if (triggerTime <= System.currentTimeMillis()) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("alarm_id", id)
                putExtra("shift_name", shiftName)
                putExtra("alarm_time", alarmTimeFormatted)
                putExtra("alarm_type", "directBootRestore")
                setPackage(context.packageName)
                action = enhancedAlarmAction(id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val showIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("alarm_id", id)
                putExtra("shift_name", shiftName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                setPackage(context.packageName)
            }
            val showPendingIntent = PendingIntent.getActivity(
                context, id + 10000, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent),
                pendingIntent
            )
            Logger.business(
                LogTags.ALARM_MANAGER,
                "🔐 DIRECT-BOOT: Alarm neu gesetzt - id=$id, $shiftName @ $alarmTimeFormatted"
            )
        }
    }
}

/**
 * Alarm permission status with actionable recommendations
 */
data class AlarmPermissionStatus(
    val level: AlarmPermissionLevel,
    val canScheduleExactAlarms: Boolean,
    val batteryOptimizationExempt: Boolean,
    /**
     * false = der Weck-Screen kommt nicht von selbst hoch, der Wecker erscheint nur als Banner.
     * Ab Android 14 entzieht der Play Store diese Berechtigung nach der Installation, wenn er
     * die App nicht als Wecker-App einstuft.
     */
    val canUseFullScreenIntent: Boolean = true,
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
