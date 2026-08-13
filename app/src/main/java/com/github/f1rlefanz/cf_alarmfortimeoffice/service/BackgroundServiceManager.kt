package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.ICalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background Service Manager - MEMORY LEAK FIXED VERSION (v2.1)
 *
 * FIXED CRITICAL MEMORY LEAK:
 * ✅ Replaced problematic Singleton pattern with Hilt Dependency Injection
 * ✅ Eliminated static Context references that could cause memory leaks
 * ✅ Proper lifecycle management through Hilt SingletonComponent
 * ✅ Thread-safe without volatile variables or manual synchronization
 *
 * ARCHITECTURE:
 * - Focus on reliable core functionality
 * - Background token refresh worker management
 * - Clean service lifecycle management
 * - Simple alarm failure handling
 *
 * DEPENDENCY INJECTION:
 * - @ApplicationContext ensures proper Context scoping
 * - @Singleton provides single instance without memory leak risks
 * - Hilt manages lifecycle automatically
 *
 * Philosophy: If the service works (and it does!), keep it simple and secure.
 *
 * @author CF-Alarm Development Team
 * @since Memory Leak Fix v2.1 (August 2025)
 */
@Singleton
class BackgroundServiceManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val calendarSelectionRepository: ICalendarSelectionRepository,
    private val masterPausePrefs: com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
) {

    /**
     * `by lazy`, NICHT als sofortiger Initializer - und das ist kein Stilentscheid.
     *
     * Diese Klasse ist das ERSTE @Inject-Feld von [CFAlarmApplication] (im generierten
     * Hilt-Code das erste `inject...`), sie entsteht also bei JEDEM Prozessstart in der
     * Feld-Injektion - auch in dem, den Android VOR der ersten Entsperrung fuer den
     * directBootAware BootReceiver startet. `getSharedPreferences()` auf dem normalen
     * (CREDENTIAL-ENCRYPTED) Application-Context wirft dort ab targetSdk 26:
     * "SharedPreferences in credential encrypted storage are not available until after user
     * (id 0) is unlocked". Aus der Feld-Injektion heraus wird daraus "Unable to create
     * application" - der Prozess stirbt, BEVOR `BootReceiver.onReceive()` laeuft, und der
     * Direct-Boot-Restore der Alarme und der schwebenden Snoozes findet NIE statt.
     *
     * Das ist derselbe Absturz, der am 11.08.2026 ueber `HueSmartScheduler`/WorkManager
     * gefunden wurde - dieselbe Fehlerklasse, zweite Stelle. Am EMULATOR ist er hier nicht
     * sichtbar: ohne Bildschirmsperre gilt der Nutzer schon beim LOCKED_BOOT_COMPLETED als
     * entsperrt, CE-Storage ist lesbar und die Exception bleibt aus. Auf einem Geraet MIT
     * PIN (Fairphone) nicht. Wer hier wieder einen sofortigen Initializer hinschreibt, baut
     * einen Absturz, den kein Emulator ohne Bildschirmsperre und kein Unit-Test zeigt.
     *
     * Alle Zugriffe liegen in Funktionen, die erst nach dem Entsperren laufen - `by lazy`
     * aendert das Verhalten also nicht, es verschiebt nur den Zeitpunkt.
     */
    private val preferences by lazy {
        context.getSharedPreferences("background_services", Context.MODE_PRIVATE)
    }

    /**
     * Initializes background services - PHASE 1 MIGRATION
     * BackgroundTokenRefreshWorker removed, replaced by AlarmMaintenanceService
     */
    fun initializeBackgroundServices() {
        Logger.business(
            LogTags.TOKEN,
            "🚀 Initializing background services (Phase 1 Migration - Worker removed)"
        )

        try {
            // Mark services as started
            preferences.edit {
                putLong("services_started_at", System.currentTimeMillis())
                putString("device_info", "${Build.MANUFACTURER} ${Build.MODEL}")
                putString("version", "Phase1-ExactAlarm")
            }

            Logger.business(
                LogTags.TOKEN,
                "✅ Background services initialized (AlarmMaintenanceService via AuthViewModel)"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to initialize background services", e)
        }
    }
    
    /**
     * Startet einen sofortigen Wartungslauf — aber nur, wenn es etwas zu warten gibt.
     *
     * Wird vom Auth-Callback gerufen, und der feuert auf ZWEI Wegen:
     *   1. Erst-Onboarding — die Kalender-Auswahl kommt erst NACH der Anmeldung. Ein Lauf hier
     *      hatte nie eine Chance: er endete zuverlaessig mit "W No calendars selected, skipping"
     *      und schob dem Nutzer eine Notification hin ("Bitte oeffne die App und waehle die
     *      gewuenschten Kalender aus") — waehrend der genau davorstand und das gerade tat.
     *      Dazu ein sichtbarer Vordergrund-Service mitten im Onboarding, fuer nichts.
     *   2. Re-Autorisierung (Token weggefallen, Nutzer stimmt neu zu) — hier sind die Kalender
     *      laengst gewaehlt, und der Sofort-Lauf ist der Sinn der Sache: direkt nachsynchronisieren.
     *
     * Die Kalender-Auswahl trennt beide Faelle sauber. Ist sie leer, ist Onboarding im Gang; die
     * 6h-Kette wird ohnehin an jedem Onboarding-Ausgang per [scheduleInitialAlarmMaintenance]
     * gestellt — hier faellt also nichts aus, nur der Leerlauf weg.
     *
     * Die Notification bleibt damit ehrlich: Sie kann jetzt nur noch einen Nutzer erreichen, der
     * das Onboarding abgeschlossen und danach alle Kalender abgewaehlt hat — dann stimmt sie auch.
     */
    suspend fun initializeMaintenanceService() {
        if (masterPausePrefs.pausedNow()) {
            Logger.business(LogTags.MAINTENANCE, "Master-Pause aktiv - Initialisierung uebersprungen")
            return
        }
        // .value statt suspend-Read: Der StateFlow des Repositories haelt die Auswahl bereits
        // im Speicher (aus dem DataStore gespiegelt), und der Auth-Callback ist kein Coroutine-
        // Kontext.
        if (calendarSelectionRepository.selectedCalendarIds.value.isEmpty()) {
            Logger.business(
                LogTags.MAINTENANCE,
                "⏭️ Kein Wartungslauf: noch keine Kalender ausgewaehlt (Onboarding laeuft)"
            )
            return
        }

        Logger.business(LogTags.MAINTENANCE, "🚀 Initializing AlarmMaintenanceService")

        try {
            // Start service which will schedule itself
            AlarmMaintenanceService.start(context)

            Logger.business(LogTags.MAINTENANCE, "✅ AlarmMaintenanceService initialized")
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ Failed to initialize maintenance service", e)
        }
    }
    
    /**
     * PHASE 1 MIGRATION: Schedules initial AlarmMaintenanceService after login
     * This replaces the old BackgroundTokenRefreshWorker
     */
    fun scheduleInitialAlarmMaintenance() {
        Logger.business(LogTags.MAINTENANCE, "📅 INITIAL ALARM: Scheduling first maintenance run")
        
        try {
            // Schedule the next maintenance run
            AlarmMaintenanceService.scheduleNext(context)
            
            // Store initial scheduling info
            preferences.edit {
                putLong("initial_alarm_scheduled", System.currentTimeMillis())
                putBoolean("alarm_maintenance_enabled", true)
            }
            
            Logger.business(LogTags.MAINTENANCE, "✅ INITIAL ALARM: First maintenance run scheduled")
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ INITIAL ALARM: Failed to schedule maintenance", e)
        }
    }

    /**
     * Handles alarm failure events
     */
    @Suppress("unused") // Public API - may be used in future alarm failure handling
    fun onAlarmFailureDetected(alarmId: Int, failureReason: String) {
        Logger.business(
            LogTags.ALARM,
            "🚨 Alarm failure detected",
            "ID: $alarmId, Reason: $failureReason"
        )

        // Log failure for tracking
        preferences.edit {
            putLong("last_alarm_failure", System.currentTimeMillis())
            putString("last_failure_reason", failureReason)
        }
    }

    /**
     * Stops all background services - PHASE 1 MIGRATION
     * AlarmMaintenanceService stops automatically, no manual cancellation needed
     */
    @Suppress("unused") // Public API - used for cleanup on logout or app reset
    fun stopAllBackgroundServices() {
        try {
            Logger.business(LogTags.TOKEN, "🛑 Background services cleanup (Phase 1)")
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error stopping background services", e)
        }
    }
}
