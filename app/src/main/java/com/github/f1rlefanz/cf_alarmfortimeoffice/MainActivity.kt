package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.CalendarAuthorizationScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.LoginScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.MainScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DebugLogInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MainViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.NavigationViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * MainActivity - FULLY MIGRATED to Hilt DI
 * 
 * MIGRATION COMPLETE:
 * ✅ @AndroidEntryPoint for Hilt injection
 * ✅ ViewModels via Hilt's viewModels() delegate
 * ✅ No more AppContainer or ViewModelFactory
 * ✅ Clean Architecture with proper DI
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hilt injected dependencies
    @Inject lateinit var oauth2TokenManager: OAuth2TokenManager
    
    // OPTIMIZATION: Hue Bridge Connection Manager for lifecycle events
    private val bridgeConnectionManager by lazy { 
        HueBridgeConnectionManager.getInstance(this)
    }
    
    // HILT MIGRATION: ViewModels via Hilt's viewModels() delegate
    // ✅ Automatically scoped to Activity lifecycle
    // ✅ Dependencies injected by Hilt
    // ✅ No manual ViewModelFactory needed
    private val authViewModel: AuthViewModel by viewModels()
    private val calendarViewModel: CalendarViewModel by viewModels()
    private val shiftViewModel: ShiftViewModel by viewModels()
    private val alarmViewModel: AlarmViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    private val navigationViewModel: NavigationViewModel by viewModels()
    private val hueViewModel: HueViewModel by viewModels()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Logger.business(LogTags.PERMISSIONS, "Notification permission result", "granted: $isGranted")
            if (!isGranted) {
                Toast.makeText(this, "Ohne Benachrichtigungen erscheinen keine Wecker-Hinweise. Du kannst sie jederzeit in den System-Einstellungen aktivieren.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // HILT MIGRATION: No more AppContainer needed - dependencies injected automatically
        Logger.d(LogTags.AUTH, "🔍 OAUTH2-DIAGNOSTIC: Hilt DI active - dependencies auto-injected")

        // POST_NOTIFICATIONS wird NICHT mehr hier (vor dem Login, kontextlos) abgefragt, sondern
        // erst wenn der Nutzer den Hauptbereich erreicht (LaunchedEffect im "main"-Screen unten) -
        // dort ist der Bezug zum Wecker gegeben. Verhindert die Dialog-Kaskade beim Erststart.

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
                    // ONBOARDING GATE: A signed-in user without a valid Calendar token is routed to
                    // CalendarAuthorizationScreen instead of the (half-broken) main UI. tokenChecked
                    // guards against flashing the gate before the initial token check has completed.
                    val screenContent = remember(
                        authState.isSignedIn,
                        authState.calendarOps.calendarsLoading,
                        authState.calendarOps.tokenChecked,
                        authState.calendarOps.hasValidToken
                    ) {
                        when {
                            authState.calendarOps.calendarsLoading && !authState.isSignedIn -> "loading"
                            !authState.isSignedIn -> "login"
                            !authState.calendarOps.tokenChecked -> "loading"
                            authState.calendarOps.needsCalendarAuthorization -> "calendar_auth"
                            else -> "main"
                        }
                    }

                    when (screenContent) {
                        "loading" -> {
                            LoadingScreen(message = "Lade Anmeldestatus...")
                        }
                        "calendar_auth" -> {
                            CalendarAuthorizationScreen(
                                authViewModel = authViewModel,
                                onSignOut = { authViewModel.signOut(this@MainActivity) }
                            )
                        }
                        "main" -> {
                            // Kontextuelle Notification-Abfrage: erst im Hauptbereich (nach Login/Setup),
                            // nicht mehr kontextlos vor dem Login. Feuert einmalig beim Erreichen von "main".
                            LaunchedEffect(Unit) { checkNotificationPermission() }
                            MainScreen(
                                authViewModel = authViewModel,
                                calendarViewModel = calendarViewModel,
                                shiftViewModel = shiftViewModel,
                                alarmViewModel = alarmViewModel,
                                mainViewModel = mainViewModel,
                                navigationViewModel = navigationViewModel,
                                hueViewModel = hueViewModel
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
        
        // OnePlus-Hinweis beim ersten Start zeigen (DataStore-Lesevorgang -> Coroutine)
        lifecycleScope.launch {
            BatteryOptimizationHelper.checkAndShowHintIfNeeded(this@MainActivity)
        }
        
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
                    val success = oauth2TokenManager.handlePermissionResult(
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
                    kotlinx.coroutines.delay(50.milliseconds)
                    
                    // CRITICAL FIX: Use public cleanup methods instead of protected onCleared()
                    // Start with high-level coordinators, then data-layer ViewModels
                    navigationViewModel.cleanupResources()
                    mainViewModel.cleanupResources()
                    
                    // Small delay between batches
                    kotlinx.coroutines.delay(25.milliseconds)
                    
                    // Core business logic ViewModels  
                    authViewModel.cleanupResources()
                    shiftViewModel.cleanupResources()
                    
                    // Small delay before final cleanup
                    kotlinx.coroutines.delay(25.milliseconds)
                    
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
