package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/**
 * Enhanced BroadcastReceiver with Hilt DI.
 *
 * ROLLE: Entscheiden, ob geweckt wird - nicht, WIE geweckt wird.
 *
 * Der Receiver prueft den Skip-Status, startet den [AlarmSoundService] und stoesst die
 * Hue-Regeln an. Ton, Vibration, Audio-Fokus, Notification und Full-Screen-Intent gehoeren
 * vollstaendig dem Service. Der Receiver postet bewusst KEINE eigene Notification (das war die
 * Quelle des zweiten Klingeltons) und startet die Activity NICHT direkt (das ist seit
 * Android 10 kein erlaubter Background-Activity-Start und wird stillschweigend verworfen).
 *
 * CORE FEATURES:
 * - Reliable wake lock management
 * - Skip-Check vor dem Wecken, Direct-Boot-fest
 * - 🎨 HUE INTEGRATION: Automatic light control based on shift patterns
 * - 🏗️ HILT DI: Modern dependency injection
 *
 * Die Alarm-Wartung (genügend zukünftige Alarme vorhalten) übernimmt seit
 * Briefing 4.0 vollständig der AlarmMaintenanceService (Exact Alarm alle 6h,
 * zeitbasierter 7-Tage-Puffer); die frühere count-basierte Opportunistic-Variante
 * im Receiver wurde als redundant entfernt.
 *
 * Philosophy: If the alarm works (and it does!), keep it simple.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var skipUseCase: IAlarmSkipUseCase
    @Inject lateinit var hueRuleUseCase: IHueRuleUseCase
    @Inject lateinit var shiftUseCase: IShiftUseCase
    @Inject lateinit var alarmUseCase: IAlarmUseCase

    companion object {
        const val EXTRA_SHIFT_NAME = "shift_name"

        /**
         * STILLE SCHICHT: true, wenn dieser Alarm keinen Ton/Vibration/Vollbild/Hue ausloesen
         * darf (ShiftDefinition.isSilent, uebernommen in AlarmInfo.isSilent). Reine Funktion,
         * damit die eigentliche Gate-Entscheidung ohne Android-Kontext testbar ist - der
         * umgebende onReceive-Fluss bleibt wegen goAsync()/Hilt Android-gebunden und ungetestet
         * (gleiche Konvention wie AlarmSoundService).
         *
         * Fail-safe wie der Skip-Check daneben: fehlt die AlarmInfo (Lookup fehlgeschlagen,
         * Direct Boot vor Entsperrung, o.ae.), gilt der Alarm NICHT als still - im Zweifel
         * wecken statt versehentlich stumm bleiben.
         */
        fun isSilentAlarm(alarmInfo: AlarmInfo?): Boolean = alarmInfo?.isSilent == true

        /**
         * MUSS "shift_start_time_formatted" heissen. AlarmManagerService.
         * createEnhancedAlarmIntent(), scheduleSnooze() und rescheduleFromDirectBoot()
         * schreiben die Uhrzeit unter genau diesem Schluessel (frueher "shift_time" - ein
         * Schluessel, den niemand je gesetzt hat; die Uhrzeit war dadurch immer leer).
         *
         * Der Wert ist der tatsaechliche SCHICHTBEGINN (Kalender-Event-Start), NICHT die
         * Weckzeit. Bis v1.20.0 stand hier "alarm_time" befuellt mit
         * ShiftMatch.calculatedAlarmTime (der Weckzeit) - die Notification/das Vollbild
         * zeigten "Deine Schicht beginnt um" dann faelschlich die Weckzeit (bei S2 z.B. die
         * Default-Weckzeit 14:30 statt des echten Schichtbeginns). Wer hier wieder die
         * Weckzeit eintraegt, holt sich den Fehler zurueck. Der eigentlich dominante Bugherd war
         * NICHT die Erstplanung (AlarmManagerService.createEnhancedAlarmIntent), sondern das weit
         * haeufigere Re-Arming ueber AlarmUseCase.scheduleSystemAlarm() (jeder syncAlarms()-Zweig,
         * also praktisch jeder App-Start/jede 6h-Wartung) - siehe CLAUDE.md "Wecker".
         */
        const val EXTRA_SHIFT_START_TIME = "shift_start_time_formatted"
        const val EXTRA_ALARM_ID = "alarm_id"

        private const val SKIP_CHANNEL_ID = "skip_channel"
        private const val WAKE_LOCK_TAG = "CFAlarm:WakeLock"
        private const val WAKE_LOCK_TIMEOUT = 60000L // 1 Minute
    }

    // Coroutine Scope für die asynchrone onReceive-Verarbeitung (goAsync)
    private val receiverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Schicht"

        // ANR-SCHUTZ: goAsync() statt runBlocking auf dem Main-Thread.
        // Die gesamte Verarbeitung (Skip-Check, Alarm-Start, Hue-Regeln) läuft asynchron
        // auf einem Background-Dispatcher; pendingResult.finish() wird im finally garantiert.
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                // DIRECT BOOT: Vor der ersten Entsperrung ist der Skip-Status (CE-DataStore) nicht
                // lesbar - dann NICHT blockieren, sondern klingeln (lieber wecken als still skippen).
                val userUnlocked = try {
                    (context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked
                } catch (e: Exception) {
                    true // im Zweifel wecken
                }

                // CRITICAL: Skip-Check VOR Alarm-Trigger (nur bei entsperrtem Storage)
                if (userUnlocked) {
                    try {
                        Logger.business(
                            LogTags.ALARM_RECEIVER,
                            "🔍 SKIP-CHECK: Checking skip status for alarm $alarmId ($shiftName)"
                        )

                        val skipResult = skipUseCase.checkAndProcessSkip(alarmId)

                        when (skipResult.getOrNull()) {
                            SkipProcessResult.ALARM_SKIPPED -> {
                                Logger.business(
                                    LogTags.ALARM_RECEIVER,
                                    "⏭️ SKIP-SUCCESS: Alarm $alarmId ($shiftName) SKIPPED by user"
                                )
                                showSkipNotification(context, shiftName)
                                return@launch // EARLY RETURN: Alarm nicht ausführen
                            }

                            SkipProcessResult.ALARM_EXECUTED -> {
                                Logger.business(
                                    LogTags.ALARM_RECEIVER,
                                    "✅ SKIP-CHECK: Alarm $alarmId ($shiftName) will execute normally"
                                )
                                // Continue with normal alarm logic below
                            }

                            null -> {
                                Logger.w(
                                    LogTags.ALARM_RECEIVER,
                                    "⚠️ SKIP-CHECK: Check failed, executing alarm $alarmId normally"
                                )
                                // Continue with normal alarm logic
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(
                            LogTags.ALARM_RECEIVER,
                            "❌ SKIP-CHECK: Error during skip check for alarm $alarmId, executing alarm normally",
                            e
                        )
                        // Continue with normal alarm logic
                    }
                } else {
                    Logger.business(
                        LogTags.ALARM_RECEIVER,
                        "🔐 DIRECT BOOT: Nutzer noch nicht entsperrt - Skip-Check uebersprungen, Alarm $alarmId klingelt"
                    )
                }

                // Existing alarm logic continues here...
                Logger.business(LogTags.ALARM_RECEIVER, "📱 ALARM TRIGGERED! Shift: $shiftName")

                // STILLE SCHICHT: Ton/Vibration/Vollbild-Wecker UND Hue bleiben aus, wenn diese
                // Schicht als still markiert ist. Der Zeit-Anker selbst (dieser Broadcast) feuert
                // trotzdem normal weiter - DND/Dimmer/Feature A und die AlarmInfo in
                // getAllAlarms() sind davon unberuehrt, nur die Wecker-Instanz
                // (AlarmSoundService) und executeHueRulesForAlarm() werden nicht angestossen.
                if (userUnlocked) {
                    val alarmInfo = try {
                        alarmUseCase.getAllAlarms().getOrNull()?.find { it.id == alarmId }
                    } catch (e: Exception) {
                        Logger.w(
                            LogTags.ALARM_RECEIVER,
                            "⚠️ SILENT-CHECK: Konnte AlarmInfo fuer $alarmId nicht laden, wecke sicherheitshalber normal",
                            e
                        )
                        null
                    }

                    if (isSilentAlarm(alarmInfo)) {
                        Logger.business(
                            LogTags.ALARM_RECEIVER,
                            "🔕 STILLE SCHICHT: Alarm $alarmId ($shiftName) ist als still markiert - kein Ton/Vibration/Vollbild/Hue"
                        )
                        return@launch
                    }
                }

                // Wake Lock to ensure device wakes up
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    acquire(WAKE_LOCK_TIMEOUT)
                }

                try {
                    val shiftStartTime = intent.getStringExtra(EXTRA_SHIFT_START_TIME).orEmpty()

                    // 🔊 EINZIGER Einstiegspunkt fuer Ton UND Sichtbarkeit.
                    // Der Service startet den MediaPlayer, holt den Audio-Fokus und postet die
                    // Notification mit dem Full-Screen-Intent. Der Receiver postet bewusst KEINE
                    // eigene Notification mehr und startet die Activity NICHT mehr direkt:
                    // - Die fruehere zweite Notification (2001) brachte ueber ihren Channel einen
                    //   zweiten Klingelton mit.
                    // - Ein direktes startActivity() aus einem Receiver ist seit Android 10 kein
                    //   erlaubter Background-Activity-Start (AlarmManager-Broadcasts stehen NICHT
                    //   auf der Ausnahmeliste) und wird stillschweigend verworfen. Der einzige
                    //   sanktionierte Weg ist der vom System gesendete Full-Screen-PendingIntent.
                    startAlarmSoundService(context, shiftName, shiftStartTime, alarmId)

                    Logger.business(
                        LogTags.ALARM_RECEIVER,
                        "✅ Alarm $alarmId for $shiftName triggered successfully!"
                    )

                    // 🎨 HUE INTEGRATION - Execute matching light rules
                    // Läuft NACH dem Alarm-Start, damit ein langsamer Netzwerkaufruf
                    // Sound + Full-Screen-Intent nicht verzögert.
                    // Direct Boot: Hue braucht Netz + CE/Hue-Storage - vor Entsperrung ueberspringen.
                    if (userUnlocked) {
                        executeHueRulesForAlarm(shiftName)
                    }

                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_RECEIVER, "❌ Error handling alarm", e)
                } finally {
                    // Release wake lock
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                }
            } finally {
                // GARANTIERT: BroadcastReceiver-Lebenszyklus beenden
                pendingResult.finish()
            }
        }
    }

    private fun showSkipNotification(context: Context, shiftName: String) {
        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel MUSS vor dem Posten existieren: seit Android 8 verwirft der
            // NotificationManager stillschweigend jede Notification auf einem unbekannten
            // Channel. Frueher wurde hier auf "skip_channel" gepostet, ohne ihn je anzulegen -
            // die Skip-Bestaetigung war damit unsichtbar.
            notificationManager.createNotificationChannel(
                android.app.NotificationChannel(
                    SKIP_CHANNEL_ID,
                    "Übersprungene Alarme",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Bestätigung, dass ein Alarm wie gewünscht übersprungen wurde"
                }
            )

            val notification = NotificationCompat.Builder(context, SKIP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle("Alarm übersprungen")
                .setContentText("$shiftName-Alarm wurde wie gewünscht übersprungen")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(30000) // 30 Sekunden
                .build()

            notificationManager.notify(9999, notification)
            Logger.business(LogTags.ALARM_RECEIVER, "✅ Skip notification shown")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "Error showing skip notification", e)
        }
    }

    /**
     * 🎨 HUE INTEGRATION: Execute matching light rules for alarm
     *
     * Creates a synthetic ShiftMatch from available alarm data and executes
     * any applicable Hue rules configured for this shift pattern.
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of appContainer
     * NOTE: context parameter removed - all dependencies injected via Hilt
     */
    private suspend fun executeHueRulesForAlarm(shiftName: String) {
        try {
            Logger.business(
                LogTags.ALARM_RECEIVER,
                "🎨 Starting Hue rule execution for shift: $shiftName"
            )

            // Läuft als suspend-Aufruf in der onReceive-Coroutine (kein runBlocking mehr)
            try {
                // Try to find matching shift definition using injected shiftUseCase
                val shiftConfigResult = shiftUseCase.getCurrentShiftConfig()

                if (shiftConfigResult.isSuccess) {
                    val shiftConfig = shiftConfigResult.getOrNull()
                    // Gestaffelt nach Genauigkeit - siehe ShiftConfig.findDefinitionFor().
                    // Ein unscharfes find{} stand mal hier und hat die Regeln fast jeder
                    // Schicht auf "Spaetschicht" umgebogen.
                    val matchingShiftDef = shiftConfig?.findDefinitionFor(shiftName)

                    if (matchingShiftDef != null) {
                        // Create synthetic ShiftMatch for Hue rules
                        val syntheticShiftMatch = createSyntheticShiftMatch(
                            shiftDefinition = matchingShiftDef,
                            shiftName = shiftName
                        )

                        // Execute Hue rules for this shift using injected hueRuleUseCase
                        val currentTime = LocalTime.now()
                        val executionResult = hueRuleUseCase.executeRulesForAlarm(
                            shift = syntheticShiftMatch,
                            alarmTime = currentTime
                        )

                        if (executionResult.isSuccess) {
                            val result = executionResult.getOrNull()
                            if (result != null && result.rulesExecuted > 0) {
                                Logger.business(
                                    LogTags.ALARM_RECEIVER,
                                    "🎨✅ Hue rules executed successfully: ${result.rulesExecuted} rules, " +
                                            "${result.successfulActions}/${result.actionsExecuted} actions successful"
                                )

                                if (result.errors.isNotEmpty()) {
                                    Logger.w(
                                        LogTags.ALARM_RECEIVER,
                                        "🎨⚠️ Some Hue actions failed: ${result.errors}"
                                    )
                                }
                            } else {
                                Logger.d(
                                    LogTags.ALARM_RECEIVER,
                                    "🎨💡 No Hue rules configured for shift: $shiftName"
                                )
                            }
                        } else {
                            Logger.w(
                                LogTags.ALARM_RECEIVER,
                                "🎨❌ Hue rule execution failed",
                                executionResult.exceptionOrNull()
                            )
                        }
                    } else {
                        Logger.d(
                            LogTags.ALARM_RECEIVER,
                            "🎨💡 No shift definition found for: $shiftName (skipping Hue rules)"
                        )
                    }
                } else {
                    Logger.w(
                        LogTags.ALARM_RECEIVER,
                        "🎨⚠️ Could not load shift configuration for Hue rules",
                        shiftConfigResult.exceptionOrNull()
                    )
                }

            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_RECEIVER, "🎨❌ Exception during Hue rule execution", e)
            }

        } catch (e: Exception) {
            // Don't let Hue errors crash the alarm
            Logger.e(LogTags.ALARM_RECEIVER, "🎨❌ Critical error in Hue integration", e)
        }
    }

    /**
     * Creates a synthetic ShiftMatch from available alarm data
     *
     * Since the AlarmReceiver doesn't have access to the original ShiftMatch,
     * we reconstruct the essential information needed for Hue rule execution.
     */
    private fun createSyntheticShiftMatch(
        shiftDefinition: ShiftDefinition,
        shiftName: String
    ): ShiftMatch {
        val now = LocalDateTime.now()

        // Create synthetic calendar event
        val syntheticCalendarEvent = CalendarEvent(
            id = "synthetic_$shiftName",
            title = shiftName,
            startTime = now,
            endTime = now.plusHours(8), // Assume 8-hour shift
            calendarId = "synthetic",
            isAllDay = false
        )

        // Calculate synthetic alarm time (now, since the alarm just triggered)
        val calculatedAlarmTime = now

        return ShiftMatch(
            shiftDefinition = shiftDefinition,
            calendarEvent = syntheticCalendarEvent,
            calculatedAlarmTime = calculatedAlarmTime
        )
    }

    /**
     * Starts AlarmSoundService to handle audio playback independently
     * 
     * CRITICAL: Service must start BEFORE Activity to ensure sound begins immediately
     * and survives Activity lifecycle events.
     *
     * @param context Context for starting the service
     * @param shiftName Name of the shift for notification display
     * @param shiftStartTime Formatierte Startzeit der Schicht (Notification-Text)
     * @param alarmId Unique alarm identifier
     */
    private fun startAlarmSoundService(
        context: Context,
        shiftName: String,
        shiftStartTime: String,
        alarmId: Int
    ) {
        try {
            val serviceIntent = Intent(context, AlarmSoundService::class.java).apply {
                action = AlarmSoundService.ACTION_START_ALARM
                putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, shiftName)
                putExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME, shiftStartTime)
                putExtra(AlarmSoundService.EXTRA_ALARM_ID, alarmId)
            }

            // minSdk = 26, startForegroundService ist immer verfuegbar.
            context.startForegroundService(serviceIntent)
            Logger.business(
                LogTags.ALARM_RECEIVER,
                "✅ AlarmSoundService started as foreground service (API ${Build.VERSION.SDK_INT})"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "❌ Failed to start AlarmSoundService", e)
        }
    }
}
