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
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hilt injected dependencies
    @Inject lateinit var oauth2TokenManager: OAuth2TokenManager
    
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

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Logger.business(LogTags.PERMISSIONS, "Notification permission result", "granted: $isGranted")
            if (!isGranted) {
                Toast.makeText(this, "Benachrichtigungsberechtigung ist für Alarme erforderlich!", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                                viewModelFactory = viewModelFactory
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
        
        // PHASE 2: Initialize Smart Scheduling on app startup
        Logger.d(LogTags.LIFECYCLE, "MainActivity: Initializing Hue Smart Scheduling system")
    }

    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        bridgeConnectionManager.onAppForeground()
        
        Logger.d(LogTags.LIFECYCLE, "MainActivity: App resumed - Bridge manager notified")
    }
    
    override fun onPause() {
        super.onPause()
        bridgeConnectionManager.onAppBackground()
        Logger.d(LogTags.LIFECYCLE, "MainActivity: App paused - Bridge manager notified")
    }
    
    /**
     * CRITICAL FIX: Handle Calendar authorization permission result
     * 
     * This method is called when the user responds to the Calendar permission dialog
     * launched by OAuth2TokenManager. It's essential for the permission flow to work.
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Logger.d(LogTags.AUTH, "📨 ACTIVITY-RESULT: requestCode=$requestCode, resultCode=$resultCode")
        
        // Handle Calendar authorization result
        if (requestCode == OAuth2TokenManager.REQUEST_CODE_CALENDAR_AUTHORIZATION) {
            lifecycleScope.launch {
                try {
                    val success = appContainer.oauth2TokenManager.handlePermissionResult(
                        requestCode,
                        resultCode
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
