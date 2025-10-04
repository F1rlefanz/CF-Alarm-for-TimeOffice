package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.AppContainer
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.LoginScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.MainScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DebugLogInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.observer.CalendarObserverManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.ModernOAuth2TokenManager
import androidx.core.content.edit

// Firebase Crashlytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

class MainActivity : ComponentActivity() {

    // Dependency Container
    private lateinit var appContainer: AppContainer
    
    // OPTIMIZATION: Hue Bridge Connection Manager for lifecycle events
    private val bridgeConnectionManager by lazy { 
        HueBridgeConnectionManager.getInstance(this)
    }
    
    // ViewModels werden by lazy initialisiert für bessere Performance
    private val viewModelFactory by lazy { ViewModelFactory(appContainer) }
    
    private val authViewModel by lazy { 
        ViewModelProvider(this, viewModelFactory)[AuthViewModel::class.java]
    }
    
    private val calendarViewModel by lazy { 
        ViewModelProvider(this, viewModelFactory)[CalendarViewModel::class.java]
    }
    
    private val shiftViewModel by lazy { 
        ViewModelProvider(this, viewModelFactory)[ShiftViewModel::class.java]
    }
    
    private val alarmViewModel by lazy { 
        ViewModelProvider(this, viewModelFactory)[AlarmViewModel::class.java]
    }
    
    private val mainViewModel by lazy { 
        ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]
    }
    
    private val navigationViewModel by lazy { 
        ViewModelProvider(this, viewModelFactory)[NavigationViewModel::class.java]
    }

    // PERFORMANCE FIX: Deduplication für Calendar Permission Requests
    @Volatile
    private var isPermissionRequestInProgress = false
    @Volatile
    private var lastPermissionCheckTime = 0L

    // Permission Launchers
    private val requestCalendarPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            isPermissionRequestInProgress = false // RESET permission request flag
            
            if (isGranted) {
                Logger.business(LogTags.PERMISSIONS, "Calendar permission granted")
                
                // Start Calendar Observer after permission is granted
                CalendarObserverManager.startCalendarObserver(this)
                
                // PERFORMANCE FIX: Only load if not already loaded recently
                val calendarState = calendarViewModel.uiState.value
                if (calendarState.availableCalendars.isEmpty() && !calendarState.isLoading) {
                    calendarViewModel.loadAvailableCalendars()
                }
            } else {
                Toast.makeText(this, "Kalenderberechtigung ist erforderlich!", Toast.LENGTH_LONG).show()
            }
        }

    // Launcher für Notification-Berechtigung
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Logger.business(LogTags.PERMISSIONS, "Notification permission result", "granted: $isGranted")
            if (!isGranted) {
                Toast.makeText(this, "Benachrichtigungsberechtigung ist für Alarme erforderlich!", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Firebase Crashlytics Setup
        setupFirebaseCrashlytics()

        // Dependency Container abrufen
        appContainer = (application as CFAlarmApplication).appContainer
        
        // CRITICAL DIAGNOSTIC: Check OAuth2 integration after container setup - BACKGROUND
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val diagnosticResults = appContainer.diagnoseOAuth2Integration()
                Logger.business(LogTags.AUTH, "🔍 OAUTH2-DIAGNOSTIC: System integration check:")
                diagnosticResults.split("\n").forEach { line ->
                    Logger.business(LogTags.AUTH, "  $line")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ OAUTH2-DIAGNOSTIC: Failed to run diagnostics", e)
            }
        }

        // Prüfe Notification-Berechtigung
        checkNotificationPermission()
        
        // OPTION 4: Prüfe Calendar-Berechtigung sofort beim Start (Primary Check)
        // Note: Erfolgt nach setContent, da checkCalendarPermission() calendarViewModel nutzt
        // checkCalendarPermission() wird am Ende von onCreate() aufgerufen
        
        // Debug: Test File-Logging (nur im Debug-Build) - MOVED TO BACKGROUND
        try {
            val isDebugBuild = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (isDebugBuild) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // DebugLogInfo.addTestLogEntries() // REMOVED: Test log entries no longer needed
                        DebugLogInfo.logFileInfo(this@MainActivity)
                    } catch (e: Exception) {
                        Logger.w("MainActivity", "Debug logging failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("MainActivity", "Debug build check failed", e)
        }

        setContent {
            CFAlarmForTimeOfficeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // MEMORY LEAK FIX: Consolidated State Collection für MainActivity
                    // Reduziert excessive Recompositions durch weniger collectAsState() calls
                    val authState by authViewModel.uiState.collectAsState()

                    // PERFORMANCE OPTIMIZATION: Memoized Screen Selection
                    // Verhindert unnötige Recompositions bei State-Changes
                    val screenContent = remember(authState.isSignedIn, authState.calendarOps.calendarsLoading) {
                        when {
                            authState.calendarOps.calendarsLoading && !authState.isSignedIn -> "loading"
                            authState.isSignedIn -> "main"
                            else -> "login"
                        }
                    }

                    when (screenContent) {
                        "loading" -> {
                            LoadingScreen(message = "Lade Anmeldestatus...")
                        }
                        "main" -> {
                            MainScreen(
                                authViewModel = authViewModel,
                                calendarViewModel = calendarViewModel,
                                shiftViewModel = shiftViewModel,
                                alarmViewModel = alarmViewModel,
                                mainViewModel = mainViewModel,
                                navigationViewModel = navigationViewModel,
                                viewModelFactory = viewModelFactory,
                                onRequestCalendarPermission = { checkCalendarPermission() }
                            )
                        }
                        "login" -> {
                            LoginScreen(
                                authViewModel = authViewModel,
                                onSignIn = { 
                                    // MODERN AUTH: Use CredentialAuthManager with context
                                    authViewModel.signIn(this@MainActivity)
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // OnePlus-Hinweis beim ersten Start zeigen
        BatteryOptimizationHelper.checkAndShowHintIfNeeded(this)
        
        // OPTIMIZATION: Initialize Hue Bridge lifecycle tracking
        bridgeConnectionManager.onAppForeground()
        
        // ⚡ SMART MAINTENANCE CHAIN Level 3: Start Calendar Observer (only if permission granted)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            CalendarObserverManager.startCalendarObserver(this)
        } else {
            Logger.d(LogTags.PERMISSIONS, "Calendar Observer not started - READ_CALENDAR permission required")
        }
        
        // PHASE 2: Initialize Smart Scheduling on app startup
        Logger.d(LogTags.LIFECYCLE, "MainActivity: Initializing Hue Smart Scheduling system")
        
        // OPTION 4: PRIMARY CHECK - Calendar Permission sofort beim App-Start prüfen
        // Erfolgt nach setContent, damit calendarViewModel verfügbar ist
        checkCalendarPermission()
    }

    /**
     * Firebase Crashlytics Setup mit Professional Best Practices
     */
    private fun setupFirebaseCrashlytics() {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Privacy-compliant User ID: Generate app-specific UUID instead of hardware ID
            // SECURITY FIX: Replaced ANDROID_ID with privacy-compliant solution
            val sharedPrefs = getSharedPreferences("app_analytics", MODE_PRIVATE)
            val userId = sharedPrefs.getString("user_uuid", null) ?: run {
                val newUuid = java.util.UUID.randomUUID().toString().take(12)
                sharedPrefs.edit {
                    putString("user_uuid", newUuid)
                }
                newUuid
            }
            crashlytics.setUserId("user_$userId")
            
            // Custom Keys für App Context
            crashlytics.setCustomKey("app_version", packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
            val isDebugBuild = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            crashlytics.setCustomKey("build_type", if (isDebugBuild) "debug" else "release")
            crashlytics.setCustomKey("target_sdk", applicationInfo.targetSdkVersion)
            
            // Breadcrumb-Log
            crashlytics.log("MainActivity: Firebase Crashlytics initialized")
            
            Logger.business("Crashlytics", "Firebase Crashlytics setup completed with user context")
            
        } catch (e: Exception) {
            Logger.e("Crashlytics", "Failed to setup Crashlytics", e)
        }
    }

    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun checkCalendarPermission() {
        // PERFORMANCE FIX: Deduplication für mehrfache Permission-Checks
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCheck = currentTime - lastPermissionCheckTime
        
        if (isPermissionRequestInProgress || timeSinceLastCheck < 2000) {
            Logger.d(LogTags.PERMISSIONS, "Permission check throttled - in progress: $isPermissionRequestInProgress, time since last: ${timeSinceLastCheck}ms")
            return
        }
        
        lastPermissionCheckTime = currentTime
        
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED -> {
                Logger.business(LogTags.PERMISSIONS, "Calendar permission already granted")
                
                // Start Calendar Observer if not already active
                if (!CalendarObserverManager.isObserverActive()) {
                    CalendarObserverManager.startCalendarObserver(this)
                }
                
                // PERFORMANCE FIX: Only load if not already loaded and not loading
                val calendarState = calendarViewModel.uiState.value
                if (calendarState.availableCalendars.isEmpty() && !calendarState.isLoading) {
                    calendarViewModel.loadAvailableCalendars()
                }
            }
            else -> {
                Logger.d(LogTags.PERMISSIONS, "Requesting calendar permission")
                isPermissionRequestInProgress = true
                requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        }
    }
    
    // OPTIMIZATION: App lifecycle events for Hue Bridge efficiency + Calendar Observer
    override fun onResume() {
        super.onResume()
        bridgeConnectionManager.onAppForeground()
        
        // ⚡ LEVEL 3: Restart Calendar Observer in case it was stopped (only if permission granted)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            if (!CalendarObserverManager.isObserverActive()) {
                CalendarObserverManager.startCalendarObserver(this)
            }
        }
        
        Logger.d(LogTags.LIFECYCLE, "MainActivity: App resumed - Bridge manager + Calendar Observer notified")
    }
    
    override fun onPause() {
        super.onPause()
        bridgeConnectionManager.onAppBackground()
        // Note: Calendar Observer bleibt aktiv für Background-Updates
        Logger.d(LogTags.LIFECYCLE, "MainActivity: App paused - Bridge manager notified")
    }
    
    /**
     * CRITICAL FIX: Handle Calendar authorization permission result
     * 
     * This method is called when the user responds to the Calendar permission dialog
     * launched by ModernOAuth2TokenManager. It's essential for the permission flow to work.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Logger.d(LogTags.AUTH, "📨 ACTIVITY-RESULT: requestCode=$requestCode, resultCode=$resultCode")
        
        // Handle Calendar authorization result
        if (requestCode == ModernOAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION) {
            lifecycleScope.launch {
                try {
                    val success = appContainer.modernOAuth2TokenManager.handlePermissionResult(
                        requestCode,
                        resultCode,
                        data
                    )
                    
                    if (success) {
                        Logger.business(LogTags.AUTH, "✅ PERMISSION-FIXED: Calendar permission granted successfully")
                        Toast.makeText(
                            this@MainActivity,
                            "Kalenderzugriff wurde erteilt!",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Trigger calendar reload after successful authorization
                        calendarViewModel.loadAvailableCalendars()
                    } else {
                        Logger.w(LogTags.AUTH, "⚠️ PERMISSION-FIXED: Calendar permission denied by user")
                        Toast.makeText(
                            this@MainActivity,
                            "Kalenderzugriff wurde verweigert. Bitte erteile die Berechtigung in den Einstellungen.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Logger.e(LogTags.AUTH, "❌ PERMISSION-FIXED: Error handling permission result", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Fehler bei der Kalenderautorisierung: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            Logger.d(LogTags.LIFECYCLE, "MainActivity: Starting comprehensive cleanup to prevent mutex errors...")
            
            // ⚡ LEVEL 3: Stop Calendar Observer when activity is destroyed
            CalendarObserverManager.stopCalendarObserver(this)
            
            // CRITICAL FIX: Enhanced cleanup with proper sequencing
            lifecycleScope.launch {
                try {
                    // CRITICAL FIX: Stop Hue Bridge first to cancel all network operations
                    bridgeConnectionManager.cleanup()
                    
                    // Give a moment for network operations to complete
                    kotlinx.coroutines.delay(50)
                    
                    // CRITICAL FIX: Use public cleanup methods instead of protected onCleared()
                    // Start with high-level coordinators, then data-layer ViewModels
                    navigationViewModel.cleanupResources()
                    mainViewModel.cleanupResources()
                    
                    // Small delay between batches
                    kotlinx.coroutines.delay(25)
                    
                    // Core business logic ViewModels  
                    authViewModel.cleanupResources()
                    shiftViewModel.cleanupResources()
                    
                    // Small delay before final cleanup
                    kotlinx.coroutines.delay(25)
                    
                    // Data-heavy ViewModels last
                    calendarViewModel.cleanupResources()
                    alarmViewModel.cleanupResources()
                    
                    Logger.d(LogTags.LIFECYCLE, "MainActivity: Sequential cleanup completed successfully")
                } catch (e: Exception) {
                    Logger.e(LogTags.LIFECYCLE, "Error during MainActivity ViewModel cleanup", e)
                }
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during MainActivity cleanup", e)
        }
        
        // FINAL PROTECTION: Force garbage collection hint after cleanup
        try {
            System.gc()
        } catch (_: Exception) {
            // Ignore GC errors - using _ to indicate intentionally unused parameter
        }
    }

}
