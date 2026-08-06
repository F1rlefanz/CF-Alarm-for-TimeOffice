package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
            putExtra("shift_start_time_formatted", formatAlarmTime(shiftMatch.calendarEvent.startTime))
            putExtra("shift_pattern", shiftMatch.shiftDefinition.id)
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
            // Der Offset ist KEIN Kollisionsschutz gegen createAlarmPendingIntent()'s requestCode
            // (=alarmId): PendingIntent.getActivity() und .getBroadcast() liegen im System auf
            // getrennten Namensraeumen (Android unterscheidet den "Typ" der Operation als Teil des
            // Schluessels), zwei verschiedene Alarme koennen wegen der Injektivitaet von x -> x+10000
            // auch untereinander nicht kollidieren. Bleibt trotzdem stehen: harmlos, und ein zweiter
            // getActivity()-Aufruf mit requestCode=alarmId anderswo (aktuell: keiner) wuerde sonst
            // real kollidieren.
            alarmId + 10000,
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

    /**
     * Bricht ALLE schwebenden Snooze-Alarme ab (Instanz-Variante fuer Aufrufer ohne Context,
     * z.B. AlarmUseCase).
     *
     * NUR fuer ausdruecklichen Nutzer-Willen ("Hintergrunddienste pausieren", "Automatische
     * Alarme aus", "alle Alarme loeschen") - NICHT fuer datengetriebene Aufraeumzweige und
     * bewusst NICHT an `deleteAlarm(id)` gehaengt: dort raeumt u.a. der BootReceiver abgelaufene
     * Alarme weg, und der Ursprungsalarm eines schwebenden Snooze IST abgelaufen - genau dieser
     * Weg haette den Snooze wieder mitgeloescht. Siehe [Companion.cancelAllSnoozes].
     */
    fun cancelAllSnoozes() = cancelAllSnoozes(application)

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

        /** Snooze-Dauer in Minuten - EINE Quelle fuer Vollbild-Button UND Notification-Button. */
        const val SNOOZE_MINUTES = 5L

        /**
         * Plant einen Snooze-Alarm in [minutes] Minuten. Der EINE Weg fuer beide Snooze-Ausloeser:
         * "5 Min spaeter" im Vollbild UND der Snooze-Button der Sperrbildschirm-Benachrichtigung
         * (letzterer ist der Notausgang, wenn das Vollbild gar nicht erst sichtbar wird).
         *
         * Nutzt bewusst [snoozeAlarmAction], NICHT [enhancedAlarmAction]: teilte sich der Snooze
         * den PendingIntent-Slot mit seinem Ursprungsalarm, raeumte ihn der Maintenance-Sync beim
         * Loeschen des gefeuerten Alarms mit ab - der Nutzer haette "schlummern" gedrueckt und waere
         * nie wieder geweckt worden. requestCode = alarmId, damit ein zweiter Snooze denselben Slot
         * ersetzt statt zu stapeln.
         *
         * BERECHTIGUNG: setAlarmClock() ist NICHT von der Exact-Alarm-Berechtigung ausgenommen (das
         * behauptete dieser Kommentar frueher). Auf API 31/32 haengt sie an SCHEDULE_EXACT_ALARM,
         * das der Nutzer in den Systemeinstellungen entziehen kann - dann wirft AlarmManager eine
         * SecurityException. Ungefangen riss die aus dem Snooze-Button der Notification den
         * kompletten AlarmSoundService-Prozess mit: kein Snooze, kein Ton, keine Meldung. Deshalb
         * hier zweistufig: bei fehlender Berechtigung inexakt per setAndAllowWhileIdle() planen
         * (ein um Minuten verzoegerter Snooze ist besser als kein Snooze), und alles in try/catch.
         *
         * SHOW-INTENT: Der erste Parameter von [AlarmManager.AlarmClockInfo] ist der Intent, den
         * Uhr-Widgets/Sperrbildschirm ANZEIGEN bzw. per send() ausloesen - dort gehoert eine
         * Activity hin, nicht der Broadcast-PendingIntent, der den Wecker AUSLOEST (sonst klingelt
         * der Wecker sofort, sobald jemand die Wecker-Affordance antippt). Gleiche
         * requestCode-Konvention (alarmId + 10000) wie [createShowAlarmIntent] und
         * [rescheduleFromDirectBoot].
         */
        fun scheduleSnooze(
            context: Context,
            alarmId: Int,
            shiftName: String,
            shiftStartTimeFormatted: String,
            minutes: Long = SNOOZE_MINUTES
        ) {
            val triggerTime = System.currentTimeMillis() + minutes * 60 * 1000L

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AlarmReceiver.EXTRA_SHIFT_NAME, shiftName)
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                // Der Schichtbeginn aendert sich durchs Schlummern nicht - unveraendert
                // aus dem urspruenglichen Alarm durchreichen statt hier neu ("jetzt +
                // Minuten") zu berechnen.
                putExtra(AlarmReceiver.EXTRA_SHIFT_START_TIME, shiftStartTimeFormatted)
                setPackage(context.packageName)
                action = snoozeAlarmAction(alarmId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context, alarmId, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val showIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("alarm_id", alarmId)
                putExtra("shift_name", shiftName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                setPackage(context.packageName)
            }
            val showPendingIntent = PendingIntent.getActivity(
                context, alarmId + 10000, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()

            try {
                if (canScheduleExact) {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent),
                        pendingIntent
                    )
                } else {
                    Logger.w(
                        LogTags.ALARM_MANAGER,
                        "⚠️ Keine Exact-Alarm-Berechtigung - Snooze wird inexakt geplant (kann sich um Minuten verzoegern)"
                    )
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
                // Erst NACH erfolgreicher Planung vormerken: der Eintrag ist die einzige Spur, ueber
                // die ein schwebender Snooze spaeter noch abgebrochen werden kann.
                rememberPendingSnooze(context, alarmId, triggerTime)

                Logger.business(
                    LogTags.ALARM_MANAGER,
                    "😴 Snooze-Alarm gesetzt: $shiftName in $minutes Min (id=$alarmId)"
                )
            } catch (e: SecurityException) {
                Logger.e(
                    LogTags.ALARM_MANAGER,
                    "❌ Snooze konnte nicht geplant werden - Alarm-Berechtigung verweigert (id=$alarmId)",
                    e
                )
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze konnte nicht geplant werden (id=$alarmId)", e)
            }
        }

        // ---------------------------------------------------------------------------------------
        // Schwebende Snooze-Alarme: eigener Cancel-Weg
        //
        // Der Snooze liegt bewusst in einem EIGENEN PendingIntent-Slot (siehe [snoozeAlarmAction]),
        // damit ihn der Maintenance-Sync nicht mit abraeumt. Genau daraus folgte aber ein Loch:
        // [cancelSystemAlarm] baut ausschliesslich [enhancedAlarmAction] und trifft den Snooze-Slot
        // damit NIE. Ein bereits gestellter Snooze lief deshalb durch JEDE App-seitige Abschaltung
        // hindurch (Master-Pause, "Automatische Alarme aus", deleteAllAlarms) und klingelte mitten
        // in einer Pause, die der Nutzer gerade eingeschaltet hatte.
        //
        // Die Snooze-IDs sind sonst nirgends persistiert (der gefeuerte Ursprungsalarm ist zu dem
        // Zeitpunkt schon aus dem Repository geraeumt), deshalb dieser winzige eigene Merker im
        // DEVICE-PROTECTED Storage - dieselbe Wahl und derselbe Grund wie beim DirectBootAlarmStore
        // (synchron, ohne Coroutine, auch vor der ersten Entsperrung lesbar).
        // ---------------------------------------------------------------------------------------

        private const val SNOOZE_PREFS_NAME = "pending_snoozes"
        private const val KEY_SNOOZE_ENTRIES = "entries"
        private const val SNOOZE_ENTRY_SEPARATOR = "|"

        private fun snoozePrefs(context: Context): SharedPreferences =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(SNOOZE_PREFS_NAME, Context.MODE_PRIVATE)

        /** "id|triggerTime" - reine Funktion, damit das Format testbar bleibt. */
        internal fun encodeSnoozeEntry(alarmId: Int, triggerTime: Long): String =
            "$alarmId$SNOOZE_ENTRY_SEPARATOR$triggerTime"

        /** null bei kaputtem/fremdem Eintrag - ein Lesefehler darf nie den Cancel-Weg sprengen. */
        internal fun parseSnoozeEntry(entry: String): Pair<Int, Long>? {
            val parts = entry.split(SNOOZE_ENTRY_SEPARATOR)
            if (parts.size != 2) return null
            val id = parts[0].toIntOrNull() ?: return null
            val triggerTime = parts[1].toLongOrNull() ?: return null
            return id to triggerTime
        }

        /**
         * Selbstreinigung: Eintraege, deren Snooze-Zeit verstrichen ist, sind erledigt (der Alarm
         * ist gefeuert oder wurde gestoppt) und wuerden den Merker sonst unbegrenzt wachsen lassen.
         */
        internal fun pruneSnoozeEntries(entries: Set<String>, now: Long): Set<String> =
            entries.filter { raw ->
                val parsed = parseSnoozeEntry(raw)
                parsed != null && parsed.second > now
            }.toSet()

        internal fun snoozeIdsOf(entries: Set<String>): List<Int> =
            entries.mapNotNull { parseSnoozeEntry(it)?.first }.distinct()

        private fun rememberPendingSnooze(context: Context, alarmId: Int, triggerTime: Long) {
            try {
                val prefs = snoozePrefs(context)
                val existing = prefs.getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet()
                val kept = pruneSnoozeEntries(existing, System.currentTimeMillis())
                    .filterNot { parseSnoozeEntry(it)?.first == alarmId }
                    .toSet()
                prefs.edit()
                    .putStringSet(KEY_SNOOZE_ENTRIES, kept + encodeSnoozeEntry(alarmId, triggerTime))
                    .apply()
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht geschrieben werden", e)
            }
        }

        private fun forgetPendingSnooze(context: Context, alarmId: Int) {
            try {
                val prefs = snoozePrefs(context)
                val existing = prefs.getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet()
                val kept = pruneSnoozeEntries(existing, System.currentTimeMillis())
                    .filterNot { parseSnoozeEntry(it)?.first == alarmId }
                    .toSet()
                prefs.edit().putStringSet(KEY_SNOOZE_ENTRIES, kept).apply()
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht bereinigt werden", e)
            }
        }

        /**
         * Bricht den schwebenden Snooze-Alarm zu [alarmId] ab. Baut denselben PendingIntent wie
         * [scheduleSnooze] (requestCode = alarmId, action = [snoozeAlarmAction]) - nur so trifft
         * der Cancel den richtigen Slot.
         */
        fun cancelSnooze(context: Context, alarmId: Int) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                    setPackage(context.packageName)
                    action = snoozeAlarmAction(alarmId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, alarmId, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                forgetPendingSnooze(context, alarmId)
                Logger.business(LogTags.ALARM_MANAGER, "🚫 Schwebender Snooze abgebrochen (id=$alarmId)")
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze konnte nicht abgebrochen werden (id=$alarmId)", e)
            }
        }

        /**
         * Bricht ALLE vorgemerkten Snooze-Alarme ab.
         *
         * NUR bei ausdruecklichem Nutzer-Willen aufrufen (Master-Pause, "Automatische Alarme aus",
         * "alle Alarme loeschen"). NICHT in datengetriebenen Aufraeumzweigen wie "Kalender liefert
         * gerade keine Events" - genau davor schuetzt der eigene Snooze-Slot, und ein leerer
         * Kalender-Abruf ist direkt nach dem Boot/ohne Netz der Normalfall.
         */
        fun cancelAllSnoozes(context: Context) {
            val ids = try {
                snoozeIdsOf(snoozePrefs(context).getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet())
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht gelesen werden", e)
                emptyList()
            }
            if (ids.isEmpty()) return
            Logger.business(LogTags.ALARM_MANAGER, "🚫 ${ids.size} schwebende Snooze-Alarme werden abgebrochen")
            ids.forEach { cancelSnooze(context, it) }
        }

        /**
         * DE-SICHERES Neusetzen eines Alarms aus dem Direct-Boot-Spiegel.
         *
         * Baut exakt denselben PendingIntent wie der regulaere Pfad (gleiche requestCode=id und
         * action), sodass ein spaeteres regulaeres Rescheduling nach Entsperrung den Alarm via
         * FLAG_UPDATE_CURRENT aktualisiert statt zu duplizieren. Braucht KEIN Hilt/CE/Kalender und
         * darf im Direct-Boot laufen. Nur fuer Alarme in der Zukunft.
         *
         * setAlarmClock() ist NICHT von der Exact-Alarm-Berechtigung ausgenommen (das behauptete
         * dieser Kommentar frueher): auf API 31/32 kann der Nutzer sie entziehen, dann fliegt eine
         * SecurityException. Sie darf hier NIEMALS nach oben durchschlagen - der Aufrufer
         * (BootReceiver) restauriert in derselben Schleife weitere Alarme, und ein Absturz im
         * Boot-Fenster liesse alle folgenden ungesetzt. Fallback wie beim Snooze: inexakt planen.
         */
        fun rescheduleFromDirectBoot(
            context: Context,
            id: Int,
            triggerTime: Long,
            shiftName: String,
            shiftStartTimeFormatted: String
        ) {
            if (triggerTime <= System.currentTimeMillis()) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("alarm_id", id)
                putExtra("shift_name", shiftName)
                putExtra("shift_start_time_formatted", shiftStartTimeFormatted)
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
            // Gleicher +10000-Offset wie createShowAlarmIntent() - siehe Kommentar dort, warum das
            // kein aktiver Kollisionsschutz ist (getrennter PendingIntent-Namensraum getActivity()
            // vs. getBroadcast()), sondern nur die requestCode-Konvention konsistent haelt.
            val showPendingIntent = PendingIntent.getActivity(
                context, id + 10000, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
                if (canScheduleExact) {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent),
                        pendingIntent
                    )
                } else {
                    Logger.w(
                        LogTags.ALARM_MANAGER,
                        "⚠️ DIRECT-BOOT: Keine Exact-Alarm-Berechtigung - Alarm id=$id wird inexakt geplant"
                    )
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
                    )
                }
                Logger.business(
                    LogTags.ALARM_MANAGER,
                    "🔐 DIRECT-BOOT: Alarm neu gesetzt - id=$id, $shiftName @ $shiftStartTimeFormatted"
                )
            } catch (e: SecurityException) {
                Logger.e(
                    LogTags.ALARM_MANAGER,
                    "❌ DIRECT-BOOT: Alarm id=$id konnte nicht gesetzt werden - Berechtigung verweigert",
                    e
                )
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ DIRECT-BOOT: Alarm id=$id konnte nicht gesetzt werden", e)
            }
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
