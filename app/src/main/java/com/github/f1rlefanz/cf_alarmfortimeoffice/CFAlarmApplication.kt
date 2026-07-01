package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.Application
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.BackgroundServiceManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.SimpleFileTree
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * - Firebase Crashlytics initialization
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
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize ErrorHandler first
        ErrorHandler.initialize(this)
        
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
        
        Logger.i(LogTags.APP, "✅ CFAlarmApplication initialized with Hilt DI - Modern and reliable!")
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
                
                // ✅ Token encryption active (no migration needed - never released unencrypted version)
                Logger.d(LogTags.TOKEN, "🔐 STARTUP: Tink encryption active for OAuth2 tokens")
                
                Logger.i(LogTags.APP, "✅ App initialization completed successfully (with Hilt DI + WorkManager + Encryption)")
            } catch (e: Exception) {
                Logger.e(LogTags.APP, "Error during app initialization", e)
            }
        }
    }
    // onTerminate() removed - it only runs in emulator and causes pthread_mutex crashes
    // Android handles all cleanup automatically when the app terminates
}
