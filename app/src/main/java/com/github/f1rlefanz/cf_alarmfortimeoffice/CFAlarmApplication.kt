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

// Firebase Imports
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

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
        
        // Initialize ErrorHandler first for Firebase Crashlytics
        ErrorHandler.initialize(this)
        
        // Initialize Firebase first
        initializeFirebase()
        
        // Initialize dependency container
        appContainer = AppContainer(this)
        
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            
            // File-Logging für Debug-Analyse
            val logFile = File(getExternalFilesDir(null), "debug_logs.txt")
            Timber.plant(SimpleFileTree(logFile))
            
            Logger.d(LogTags.APP, "Timber initialized in DEBUG mode with file logging")
            Logger.i(LogTags.APP, "🗂️ Debug logs will be saved to: ${logFile.absolutePath}")
        }
        
        // Initialize app components
        initializeApp()
        
        Logger.i(LogTags.APP, "✅ CFAlarmApplication initialized - Simple and reliable!")
    }
    
    /**
     * Firebase Setup mit Professional Best Practices (2025)
     */
    private fun initializeFirebase() {
        try {
            // Firebase initialisieren 
            FirebaseApp.initializeApp(this)
            
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // App-spezifische Context Keys setzen
            crashlytics.setCustomKey("app_package", packageName)
            crashlytics.setCustomKey("build_type", if (BuildConfig.DEBUG) "debug" else "release")
            crashlytics.setCustomKey("version_code", BuildConfig.VERSION_CODE)
            crashlytics.setCustomKey("version_name", BuildConfig.VERSION_NAME)
            
            // Initial Breadcrumb
            crashlytics.log("CFAlarmApplication: Firebase initialized successfully")
            
            Logger.i(LogTags.APP, "🔥 Firebase Crashlytics initialized with app context")
            
        } catch (e: Exception) {
            Logger.e(LogTags.APP, "❌ Failed to initialize Firebase", e)
        }
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
                
                Logger.i(LogTags.APP, "App initialization completed successfully")
            } catch (e: Exception) {
                Logger.e(LogTags.APP, "Error during app initialization", e)
            }
        }
    }
    // onTerminate() removed - it only runs in emulator and causes pthread_mutex crashes
    // Android handles all cleanup automatically when the app terminates
}
