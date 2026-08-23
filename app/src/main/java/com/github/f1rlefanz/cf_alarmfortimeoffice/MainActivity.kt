package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.CalendarPermissionOutcome
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.CalendarAuthorizationScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.LoginScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.MainScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CFAlarmForTimeOfficeTheme
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
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    // Hue Bridge Connection Manager for lifecycle events — via Hilt (HueModule brueckt den
    // getInstance()-Singleton), statt ihn direkt per getInstance() an Hilt vorbeizuziehen.
    @Inject lateinit var bridgeConnectionManager: HueBridgeConnectionManager

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

        // RANDLOS IST NICHT MEHR OPTIONAL: ab targetSdk 35 zeichnet Android jede App unter
        // Status- und Navigationsleiste hindurch, ab targetSdk 36 gibt es das Opt-out
        // `windowOptOutEdgeToEdgeEnforcement` nicht mehr - das Projekt steht auf 37. Ohne die
        // Inset-Behandlung unten lag der "Spaeter"-Knopf der Onboarding-Gates und das
        // "Verstanden" der OEM-Warnung UNTER der Navigationsleiste: der Erststart endete in
        // einer Sackgasse. `enableEdgeToEdge()` setzt dazu die passende Kontrastierung der
        // Systemleisten-Symbole in hellem wie dunklem Design.
        enableEdgeToEdge()

        // HILT MIGRATION: No more AppContainer needed - dependencies injected automatically
        Logger.d(LogTags.AUTH, "🔍 OAUTH2-DIAGNOSTIC: Hilt DI active - dependencies auto-injected")

        // POST_NOTIFICATIONS wird NICHT mehr hier (vor dem Login, kontextlos) abgefragt, sondern
        // erst wenn der Nutzer den Hauptbereich erreicht (LaunchedEffect im "main"-Screen unten) -
        // dort ist der Bezug zum Wecker gegeben. Verhindert die Dialog-Kaskade beim Erststart.

        setContent {
            CFAlarmForTimeOfficeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Die Insets liegen bewusst INNEN, nicht am Surface: so faerbt der
                    // Hintergrund weiterhin bis unter die Systemleisten (sonst blitzte dort das
                    // helle Fenster-Hintergrundbild des AppCompat-Themes durch, auch im dunklen
                    // Design), waehrend jeder Inhalt im bedienbaren Bereich bleibt.
                    //
                    // KEINE DOPPELTE POLSTERUNG: `safeDrawingPadding()` VERBRAUCHT die Insets.
                    // Die Scaffolds weiter unten (MainContentScreen und die Unterscreens) fragen
                    // dieselben Insets ab und bekommen deshalb null - sie polstern nicht ein
                    // zweites Mal nach.
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        // MEMORY LEAK FIX: Consolidated State Collection für MainActivity
                        // Reduziert excessive Recompositions durch weniger collectAsState() calls
                        //
                        // collectAsStateWithLifecycle, nicht collectAsState: authState waehlt nur den
                        // anzuzeigenden Screen (loading/login/calendar_auth/main). Unterhalb von
                        // STARTED gibt es nichts zu zeichnen, das Sammeln darf also pausieren; der
                        // StateFlow ist konfliert und liefert beim Zurueckkehren sofort den aktuellen
                        // Wert. Der Auto-Re-Auth-Pfad haengt NICHT hieran - der sammelt separat und
                        // bewusst erst ab RESUMED (repeatOnLifecycle unten).
                        val authState by authViewModel.uiState.collectAsStateWithLifecycle()

                        // AUTO-RE-AUTH: Verliert die App das Token zur Laufzeit (401/403 -> invalidate),
                        // meldet das AuthViewModel das hier - denn den Zustimmungsdialog kann nur eine
                        // Activity starten (GoogleAuthUtil liefert einen Intent). Ohne diesen Weg musste
                        // der Nutzer die Rückkehr selbst antippen.
                        //
                        // RESUMED, nicht STARTED: Einen Activity-Start aus dem Hintergrund verwirft
                        // Android stillschweigend. Das Signal ist gepuffert (CONFLATED) und wird erst
                        // zugestellt, wenn die App wieder vorne ist - dann greift der Start auch.
                        //
                        // Keine Schleifengefahr: Bricht der Nutzer den Dialog ab, wird kein Token
                        // geschrieben, also entsteht auch kein neuer Verlust-Übergang. Er landet auf dem
                        // CalendarAuthorizationScreen und entscheidet per Knopf selbst.
                        LaunchedEffect(Unit) {
                            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                authViewModel.reauthRequired.collect {
                                    Logger.business(
                                        LogTags.AUTH,
                                        "🔐 AUTO-RE-AUTH: Token verloren - starte Zustimmungsdialog automatisch"
                                    )
                                    authViewModel.requestCalendarAuthorization(this@MainActivity)
                                }
                            }
                        }

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
                                    onSignOut = { authViewModel.signOut() }
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
                    // DREI ERGEBNISSE, DREI AUSSAGEN: frueher war alles ausser Erfolg eine
                    // "Verweigerung" - auch der Fall, in dem der Prozess waehrend des
                    // Zustimmungsdialogs starb und die App den Merker der schwebenden
                    // Autorisierung verlor. Der Nutzer bekam dann nach seiner ZUSTIMMUNG die
                    // Aufforderung, die Berechtigung doch in den Einstellungen zu erteilen.
                    when (oauth2TokenManager.handlePermissionResult(requestCode, resultCode)) {
                        CalendarPermissionOutcome.GRANTED -> {
                            Logger.business(LogTags.AUTH, "✅ PERMISSION-FIXED: Calendar permission granted successfully")
                            Toast.makeText(
                                this@MainActivity,
                                "Kalenderzugriff wurde erteilt!",
                                Toast.LENGTH_SHORT
                            ).show()

                            // Trigger calendar reload after successful authorization
                            calendarViewModel.loadAvailableCalendars()
                        }

                        CalendarPermissionOutcome.GRANTED_AFTER_RESTART -> {
                            // Der wartende Callback ist mit dem alten Prozess gestorben; an ihm
                            // haengen hasValidToken, der Kalender-Reload und der Start der
                            // Wartungskette. Deshalb hier bewusst OHNE Activity nachziehen: die
                            // Zustimmung liegt vor, der Weg laeuft ohne Dialog durch und setzt
                            // den Auth-Zustand - ein erneuter Dialogstart aus einer gerade erst
                            // wiederhergestellten Activity waere dagegen riskant.
                            Logger.business(LogTags.AUTH, "✅ PERMISSION-FIXED: Zustimmung nach Prozesstod erkannt - Auth-Zustand wird nachgezogen")
                            Toast.makeText(
                                this@MainActivity,
                                "Kalenderzugriff wurde erteilt!",
                                Toast.LENGTH_SHORT
                            ).show()

                            authViewModel.requestCalendarAuthorization()
                        }

                        CalendarPermissionOutcome.DENIED -> {
                            Logger.w(LogTags.AUTH, "⚠️ PERMISSION-FIXED: Calendar permission denied by user")
                            Toast.makeText(
                                this@MainActivity,
                                "Kalenderzugriff wurde verweigert. Bitte erteile die Berechtigung in den Einstellungen.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        CalendarPermissionOutcome.UNKNOWN -> {
                            // Weder Zustimmung noch Ablehnung nachweisbar - also auch keine der
                            // beiden Aussagen treffen, sondern um einen erneuten Versuch bitten.
                            Logger.w(LogTags.AUTH, "⚠️ PERMISSION-FIXED: Ergebnis der Kalender-Autorisierung nicht zuordenbar")
                            Toast.makeText(
                                this@MainActivity,
                                "Kalenderzugriff konnte nicht abgeschlossen werden. Bitte versuche es noch einmal.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
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
    
    // Kein onDestroy-Cleanup mehr: der fruehere Block lief auf dem bereits gecancelten
    // lifecycleScope (also faktisch nie) und haette, wuerde er laufen, Prozess-Singletons
    // (bridgeConnectionManager, ViewModels) bei jeder Rotation lahmgelegt. ViewModel-Aufraeumung
    // uebernimmt das Framework via onCleared(); Hue-Lifecycle haengt an onResume/onPause. (Audit)

}
