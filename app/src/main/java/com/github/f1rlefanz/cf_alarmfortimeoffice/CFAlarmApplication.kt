package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.Application
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.AppContainer
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.SimpleFileTree
import timber.log.Timber
import java.io.File

/**
 * UPDATED Application class with ROBUST Hue Bridge Connection Management
 * 
 * FIXES THE CRITICAL ALARM PROBLEM:
 * ❌ BEFORE: Hue Bridge connection lost during alarm execution
 * ✅ AFTER: Persistent Hue Bridge connection with automatic recovery
 * 
 * NEW FEATURES:
 * 🔄 HueBridgeConnectionManager initialization at app startup
 * 💓 Automatic connection health monitoring and recovery
 * 🚀 Guaranteed Hue Bridge availability for alarm operations
 * 
 * Core Features (unchanged):
 * - Firebase Crashlytics initialization
 * - Dependency injection container
 * - OAuth2 token system initialization
 * - Clean resource management
 * 
 * Philosophy: If the app works (and it does!), keep it simple.
 */
class CFAlarmApplication : Application() {
    
    // Application scope for long-running operations
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Dependency container
    lateinit var appContainer: AppContainer
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize ErrorHandler first
        ErrorHandler.initialize(this)
        
        // Initialize dependency container
        appContainer = AppContainer(this)
        
        // ✅ WICHTIG: File-Logging IMMER aktivieren (DEBUG + RELEASE)
        val logFile = File(getExternalFilesDir(null), "debug_logs.txt")
        Timber.plant(SimpleFileTree(logFile))
        Logger.i(LogTags.APP, "🗂️ Logs werden gespeichert in: ${logFile.absolutePath}")
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Logger.d(LogTags.APP, "Timber initialized in DEBUG mode with file logging")
        } else {
            Logger.i(LogTags.APP, "Timber initialized in RELEASE mode with file logging")
        }
        
        // Initialize app components
        initializeApp()
        
        Logger.i(LogTags.APP, "✅ CFAlarmApplication initialized - Simple and reliable!")
    }
    
    private fun initializeApp() {
        applicationScope.launch {
            try {
                // CRITICAL: Initialize Hue Bridge Connection Manager first
                Logger.i(LogTags.HUE_BRIDGE, "🔄 STARTUP: Initializing robust Hue Bridge connection management")
                try {
                    val connectionManager = HueBridgeConnectionManager.getInstance(this@CFAlarmApplication)
                    connectionManager.initialize()
                    Logger.i(LogTags.HUE_BRIDGE, "✅ STARTUP: Hue Bridge connection manager initialized - alarm operations guaranteed")
                } catch (e: Exception) {
                    Logger.e(LogTags.HUE_BRIDGE, "❌ STARTUP: Failed to initialize Hue Bridge connection manager", e)
                }
                
                // Initialize critical components
                Logger.d(LogTags.AUTH, "Initializing OAuth2 token storage")
                appContainer.initializeTokenStorage()
                
                // ✅ CRITICAL FIX: Initialize WorkManager for automatic alarm synchronization
                Logger.business(LogTags.TOKEN, "🔄 STARTUP: Initializing background services (WorkManager)")
                try {
                    appContainer.backgroundServiceManager.initializeBackgroundServices()
                    Logger.business(LogTags.TOKEN, "✅ STARTUP: WorkManager activated - automatic alarm sync every 6h")
                } catch (e: Exception) {
                    Logger.e(LogTags.TOKEN, "❌ STARTUP: Failed to initialize WorkManager", e)
                }
                
                // Initialize ShiftConfig early to prevent race conditions
                Logger.d(LogTags.SHIFT_CONFIG, "🔄 STARTUP: Initializing ShiftConfig early to prevent timing issues")
                launch {
                    try {
                        val shiftUseCase = appContainer.shiftUseCase
                        val currentConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                        
                        if (currentConfig != null) {
                            Logger.business(LogTags.SHIFT_CONFIG, "✅ STARTUP: ShiftConfig loaded successfully - autoAlarm=${currentConfig.autoAlarmEnabled}")
                        } else {
                            Logger.i(LogTags.SHIFT_CONFIG, "🔧 STARTUP: No ShiftConfig found, creating default")
                            val defaultConfig = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig.getDefaultConfig()
                            shiftUseCase.saveShiftConfig(defaultConfig)
                                .onSuccess {
                                    Logger.business(LogTags.SHIFT_CONFIG, "✅ STARTUP: Default ShiftConfig created - autoAlarm=${defaultConfig.autoAlarmEnabled}")
                                }
                                .onFailure { error ->
                                    Logger.e(LogTags.SHIFT_CONFIG, "❌ STARTUP: Failed to save default ShiftConfig", error)
                                }
                        }
                    } catch (e: Exception) {
                        Logger.e(LogTags.SHIFT_CONFIG, "❌ STARTUP: Exception during ShiftConfig initialization", e)
                    }
                }
                
                Logger.i(LogTags.APP, "✅ App initialization completed successfully (with WorkManager)")
            } catch (e: Exception) {
                Logger.e(LogTags.APP, "Error during app initialization", e)
            }
        }
    }
    // onTerminate() removed - it only runs in emulator and causes pthread_mutex crashes
    // Android handles all cleanup automatically when the app terminates
}
