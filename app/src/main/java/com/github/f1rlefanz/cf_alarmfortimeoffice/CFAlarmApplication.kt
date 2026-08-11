package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPauseUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DeviceLocalFlagsGuard
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.SimpleFileTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * MODERNIZED Application class with Hilt Dependency Injection
 * 
 * ARCHITECTURE UPGRADE:
 * ✅ Hilt DI for compile-time dependency resolution
 * ✅ Automatic dependency injection (no manual AppContainer)
 * ✅ Better testability and maintainability
 * 
 * FEATURES:
 * 🔄 HueBridgeConnectionManager initialization at app startup
 * 💓 Automatic connection health monitoring and recovery
 * 🚀 Guaranteed Hue Bridge availability for alarm operations
 * 📱 Background service initialization for alarm maintenance
 * 🔐 OAuth2 token system with encryption
 * 
 * Core Features:
 * - Lokaler Crash-Handler (schreibt letzten Absturz in last_crash.txt)
 * - Hilt dependency injection
 * - OAuth2 token system initialization
 * - Clean resource management
 */
@HiltAndroidApp
class CFAlarmApplication : Application() {
    
    // Application scope for long-running operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Hilt-injected dependencies
    @Inject
    lateinit var backgroundServiceManager: BackgroundServiceManager
    
    @Inject
    lateinit var shiftUseCase: IShiftUseCase

    /**
     * Der "settings"-Store. Gebraucht fuer [DeviceLocalFlagsGuard]: er enthaelt neben den echten
     * Einstellungen auch geraetelokale Onboarding-Flags, die nach einem Backup-Restore auf ein
     * neues Geraet nicht gelten duerfen.
     */
    @Inject
    @MainDataStore
    lateinit var mainDataStore: DataStore<Preferences>

    /**
     * Fuer das Aufheben einer Master-Pause beim Geraetewechsel - siehe initializeApp().
     *
     * BEWUSST `dagger.Lazy`, NICHT der Typ direkt. Der erste Wurf injizierte
     * `MasterPauseUseCase` unmittelbar - und der zieht ueber seinen Konstruktor
     * `HueSmartScheduler` in den Graphen, der wiederum `WorkManager.getInstance()` aufrief.
     * Ergebnis (am Emulator reproduziert, 11.08.2026): der Prozess, den das System VOR der ersten
     * Entsperrung fuer den directBootAware BootReceiver startet, starb mit "Unable to create
     * application / WorkManager is not initialized properly" - und damit lief der
     * Direct-Boot-Restore der Alarme nie. Dieselbe Falle, die CLAUDE.md fuer
     * `TinkEncryptionHelper` beschreibt: was am Application-Graphen haengt, wird in JEDEM
     * Prozessstart gebaut, auch im gesperrten.
     *
     * Mit `Lazy` entsteht die Instanz erst, wenn ein Geraetewechsel wirklich erkannt wurde - und
     * das setzt einen erfolgreichen Read aus dem CE-Storage voraus, also ein entsperrtes Geraet.
     * Wer diesen Wrapper entfernt, baut den Absturz zurueck.
     */
    @Inject
    lateinit var masterPauseUseCase: dagger.Lazy<MasterPauseUseCase>

    override fun onCreate() {
        super.onCreate()
        
        // Initialize ErrorHandler first
        ErrorHandler.initialize(this)

        // Lokaler Crash-Handler: schreibt Abstürze in last_crash.txt (Alpha-Test-Diagnose)
        installCrashHandler()
        
        // ✅ WICHTIG: File-Logging IMMER aktivieren (DEBUG + RELEASE)
        // ROBUST gegen Direct Boot: Wird der Prozess durch LOCKED_BOOT_COMPLETED (vor der ersten
        // Entsperrung) gestartet, ist External Storage ggf. nicht gemountet (getExternalFilesDir
        // == null / Schreibfehler). Ein Fehler hier darf onCreate NICHT crashen - sonst liefe der
        // Direct-Boot-Alarm-Restore im BootReceiver nie.
        try {
            val logDir = getExternalFilesDir(null)
            // PII-Schutz: In Release nur WARN+ ins Datei-Log (INFO/business enthalten E-Mail/
            // Kalendertitel); in Debug weiterhin alles fuer die Entwicklung.
            val fileLogMinPriority = if (BuildConfig.DEBUG) android.util.Log.VERBOSE else android.util.Log.WARN
            if (logDir != null) {
                Timber.plant(SimpleFileTree(logDir, minPriority = fileLogMinPriority))
                Logger.i(LogTags.APP, "🗂️ Logs werden (8 Tage) gespeichert in: ${logDir.absolutePath}")
            }

            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
                Logger.d(LogTags.APP, "Timber initialized in DEBUG mode with file logging")
            } else {
                Logger.i(LogTags.APP, "Timber initialized in RELEASE mode with file logging")
            }
        } catch (e: Exception) {
            // Direct Boot / fehlender External Storage: File-Logging ueberspringen, nicht crashen.
            if (BuildConfig.DEBUG) {
                try { Timber.plant(Timber.DebugTree()) } catch (_: Exception) { /* ignore */ }
            }
        }
        
        // Initialize app components
        initializeApp()
        
        Logger.i(LogTags.APP, "✅ CFAlarmApplication initialized with Hilt DI - Modern and reliable!")
    }
    
    private fun initializeApp() {
        applicationScope.launch {
            try {
                // ZUERST: geraetelokale Onboarding-Flags pruefen. Muss VOR allem anderen laufen,
                // damit die Onboarding-Gates (Akku-Ausnahme, "Pause bei Nichtnutzung") auf einem
                // per Backup wiederhergestellten Geraet wieder greifen. Best-effort - ein Fehler
                // hier darf den Start nicht aufhalten.
                try {
                    val deviceChanged = DeviceLocalFlagsGuard.resetIfDeviceChanged(mainDataStore)
                    // `.get()` erst NACH dem erfolgreichen CE-Read und nur bei erkanntem Wechsel -
                    // siehe die Begruendung am Feld: die Konstruktion zieht WorkManager in den
                    // Graphen und darf in einem Direct-Boot-Prozess nicht passieren.
                    if (deviceChanged && masterPauseUseCase.get().paused.first()) {
                        // Eine Master-Pause darf einen Geraetewechsel nicht ueberleben (sonst bleibt
                        // der Wecker auf dem neuen Geraet still). Aufgehoben wird sie ueber
                        // resume() und NICHT durch Loeschen des Flags: pause() schreibt auch den
                        // Device-Protected-Spiegel, den der BootReceiver vor der ersten Entsperrung
                        // liest, und reisst 6h-Wartung, Dimmer-/DND-Tick, Hue-Planung und
                        // Pre-Alarm-Refresh ab. Nur resume() baut das alles wieder auf.
                        Logger.w(
                            LogTags.MASTER_PAUSE,
                            "🔄 GERAETEWECHSEL: aktive Master-Pause wird aufgehoben - sie gilt fuer " +
                                "das alte Geraet. Hintergrundketten werden neu aufgebaut."
                        )
                        masterPauseUseCase.get().resume()
                    }

                    // Spiegel und CE-Wahrheit des Pausenzustands abgleichen. Steht hier, weil der
                    // erfolgreiche DeviceLocalFlagsGuard-Read oben belegt, dass der CE-Storage
                    // lesbar ist (also entsperrt) - in einem Direct-Boot-Prozess kommt dieser Zweig
                    // gar nicht so weit.
                    masterPauseUseCase.get().reconcileDirectBootMirror()
                } catch (e: Exception) {
                    Logger.w(LogTags.APP, "⚠️ STARTUP: Geraete-Marker konnte nicht geprueft werden", e)
                }

                // CRITICAL: Initialize Hue Bridge Connection Manager first
                Logger.i(LogTags.HUE_BRIDGE, "🔄 STARTUP: Initializing robust Hue Bridge connection management")
                try {
                    val connectionManager = HueBridgeConnectionManager.getInstance(this@CFAlarmApplication)
                    connectionManager.initialize()
                    Logger.i(LogTags.HUE_BRIDGE, "✅ STARTUP: Hue Bridge connection manager initialized - alarm operations guaranteed")
                } catch (e: Exception) {
                    Logger.e(LogTags.HUE_BRIDGE, "❌ STARTUP: Failed to initialize Hue Bridge connection manager", e)
                }

                // ✅ CRITICAL FIX: Initialize WorkManager for automatic alarm synchronization
                Logger.business(LogTags.TOKEN, "🔄 STARTUP: Initializing background services")
                try {
                    backgroundServiceManager.initializeBackgroundServices()
                    Logger.business(LogTags.TOKEN, "✅ STARTUP: Background-Services aktiv – Wartung/Alarm-Sync via Exact Alarm (6h)")
                } catch (e: Exception) {
                    Logger.e(LogTags.TOKEN, "❌ STARTUP: Failed to initialize WorkManager", e)
                }
                
                // Initialize ShiftConfig early to prevent race conditions
                Logger.d(LogTags.SHIFT_CONFIG, "🔄 STARTUP: Initializing ShiftConfig early to prevent timing issues")
                launch {
                    try {
                        val currentConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                        
                        if (currentConfig != null) {
                            Logger.business(LogTags.SHIFT_CONFIG, "✅ STARTUP: ShiftConfig loaded successfully - autoAlarm=${currentConfig.autoAlarmEnabled}")
                        } else {
                            // KEIN Default-Fallback, der SCHREIBT. Dieselbe Fehlerklasse hatte drei
                            // Schreibstellen (hier, ShiftViewModel.loadShiftConfig() und
                            // CalendarViewModel.createAlarmsFromLoadedEvents()); alle drei sind
                            // geschlossen.
                            //
                            // `null` bedeutet hier NICHT mehr "noch nie konfiguriert": seit
                            // ShiftConfigRepository die Faelle trennt, liefert der
                            // Nicht-konfiguriert-Fall die Standardkonfiguration als ERFOLG. Ein
                            // Fehlschlag heisst also: vorhanden, aber unlesbar - und genau dort
                            // ist Ueberschreiben Datenverlust. Dieser Pfad laeuft bei JEDEM
                            // Kaltstart im applicationScope, auch bei rein hintergrund-getriebenen
                            // Prozessstarts, also ohne dass ein Nutzer etwas davon mitbekaeme.
                            Logger.e(
                                LogTags.SHIFT_CONFIG,
                                "❌ STARTUP: Schicht-Konfiguration nicht lesbar - sie wird NICHT mit " +
                                    "Standardwerten ueberschrieben. Rohdaten liegen als shift_config_broken."
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e(LogTags.SHIFT_CONFIG, "❌ STARTUP: Exception during ShiftConfig initialization", e)
                    }
                }
                
                // ✅ Token encryption active (no migration needed - never released unencrypted version)
                Logger.d(LogTags.TOKEN, "🔐 STARTUP: Tink encryption active for OAuth2 tokens")
                
                Logger.i(LogTags.APP, "✅ App initialization completed successfully (with Hilt DI + WorkManager + Encryption)")
            } catch (e: Exception) {
                Logger.e(LogTags.APP, "Error during app initialization", e)
            }
        }
    }
    /**
     * Installiert einen einfachen Uncaught-Exception-Handler, der den letzten
     * Absturz (Stacktrace + Zeit + App-Version) in last_crash.txt schreibt.
     * Ermöglicht Alpha-Testern, Abstürze per "Logs senden" zu melden – ohne Cloud-SDK.
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(getExternalFilesDir(null), "last_crash.txt")
                val timestamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
                ).format(java.util.Date())
                val stackTrace = java.io.StringWriter().also { sw ->
                    throwable.printStackTrace(java.io.PrintWriter(sw))
                }.toString()
                crashFile.writeText(
                    "CF Alarm – letzter Absturz\n" +
                        "Zeit: $timestamp\n" +
                        "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Thread: ${thread.name}\n\n" +
                        stackTrace
                )
            } catch (_: Throwable) {
                // Crash-Logging darf den Absturz nicht verschlimmern
            }
            // An den vorherigen Handler delegieren (System-Default beendet die App sauber)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    // onTerminate() removed - it only runs in emulator and causes pthread_mutex crashes
    // Android handles all cleanup automatically when the app terminates
}
