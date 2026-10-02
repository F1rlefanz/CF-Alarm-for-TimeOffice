package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.Manifest
import android.content.Context
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimBedienungshilfenWunsch
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimmerModellMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RufbereitschaftAbfrage
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.RufbereitschaftMigration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Compose-Wurzel: Auth-Gate, Einstiegs-Extras, Kalender-Autorisierung, Hue-Lebenszyklus. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Sagt, WESHALB die App geoeffnet wurde. Eine Benachrichtigung, die auf einen Zustand
         * hinweist, muss auch an die Stelle fuehren, an der man ihn aendert - sonst schickt sie
         * den Nutzer auf die Suche. Gesetzt wird das Extra vom Absender der Meldung, ausgewertet
         * in [verarbeiteEinstieg].
         */
        const val EXTRA_EINSTIEG = "com.github.f1rlefanz.cf_alarmfortimeoffice.EINSTIEG"

        /**
         * „Der Schicht-Dimmer kann nicht dimmen, weil der Bedienungshilfen-Dienst aus ist" -
         * gesetzt von `DimCorrectionNotifier.appIntent()`. Ziel ist der Status-Tab mit seiner
         * Bedienungshilfen-Karte, nicht die Android-Einstellung selbst: davor gehoert die
         * Play-Pflicht-Offenlegung, die die Karte zeigt.
         */
        const val EINSTIEG_DIMMER_BEDIENUNGSHILFEN = "dimmer_bedienungshilfen"

        /**
         * „Kalender nicht gefunden / nicht abrufbar" - gesetzt von
         * `CalendarUnavailableNotifier.zeige()`. Ziel ist der Status-Tab mit seiner Karte
         * "Kalender", und zwar FRISCH geladen: die laufende App zeigte sonst den Abruf von vor
         * dem Ausfall ("API-Zugriff OK"), und der Nutzer hielte die Warnung fuer einen Fehlalarm.
         */
        const val EINSTIEG_KALENDER_WARNUNG = "kalender_warnung"

        /**
         * Der Intent, mit dem eine Benachrichtigung die App an eine Stelle schickt - fuer JEDEN
         * Absender eines [EXTRA_EINSTIEG] derselbe.
         *
         * `ACTION_MAIN` + `CATEGORY_LAUNCHER` sind Pflicht, nicht Zierde: laeuft beim Tipp noch
         * kein Task, wird dieser Intent dessen Basis-Intent. Ist er nicht filtergleich mit dem
         * Launcher-Intent (Extras zaehlen dabei nicht), legt JEDER spaetere Start ueber das
         * Launcher-Symbol eine weitere MainActivity obendrauf - am Emulator gemessen (30.09.2026).
         * Bewusst OHNE `setPackage()`: vor Android 14 gleicht der Vergleich das Paket nicht an,
         * der Launcher setzt keins.
         *
         * `FLAG_ACTIVITY_SINGLE_TOP` gehoert zu `CLEAR_TOP`: ohne es wirft der Tipp eine laufende
         * MainActivity (Start-Modus `standard`) weg und legt sie neu an; mit ihm bekommt sie
         * `onNewIntent()`.
         *
         * Ohne [einstieg] (`null`) oeffnet er die App einfach - so bei JEDEM "App oeffnen" aus einer
         * Benachrichtigung oder der Wecker-Anzeige des Systems. Bis v1.45 bauten sieben Stellen
         * diesen Intent selbst (explizit ohne MAIN/LAUNCHER bzw. ueber
         * `getLaunchIntentForPackage` ohne SINGLE_TOP) - dieselbe Bauart wie der oben genannte
         * Fehler der Kalender-Warnung (#131, G11-17).
         */
        fun einstiegIntent(context: Context, einstieg: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .apply { if (einstieg != null) putExtra(EXTRA_EINSTIEG, einstieg) }
    }

    // Hilt injected dependencies
    @Inject lateinit var oauth2TokenManager: OAuth2TokenManager

    // Hue Bridge Connection Manager for lifecycle events — via Hilt (HueModule brueckt den
    // getInstance()-Singleton), statt ihn direkt per getInstance() an Hilt vorbeizuziehen.
    @Inject lateinit var bridgeConnectionManager: HueBridgeConnectionManager

    // Einmalige Ueberfuehrung der alten Dimmer-Konfiguration ins Ein-Modell - siehe onCreate().
    @Inject lateinit var dimmerModellMigration: DimmerModellMigration
    @Inject lateinit var dndSchedule: DndScheduleUseCase
    // Einmalige Uebernahme der alten DND-Rufbereitschaft-Auswahl ins Schicht-Flag - siehe onCreate().
    @Inject lateinit var rufbereitschaftMigration: RufbereitschaftMigration
    @Inject lateinit var rufbereitschaftAbfrage: RufbereitschaftAbfrage

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

        // EINMALIGE DIMMER-MODELLMIGRATION - der schnelle der zwei Anlaesse (zweiter: 6h-Wartung).
        // Liegt zwingend nach der ersten Entsperrung; injiziert ist nur die Referenz, gearbeitet
        // wird in der Coroutine - kein CE-Zugriff beim Graphenbau. Der Marker macht jeden weiteren
        // Aufruf zum No-op. Hergang: Skill cfalarm-dimmer-und-dnd, reference/dimmer.md.
        lifecycleScope.launch {
            if (dimmerModellMigration.migriereEinmalig()) {
                // Die Migration verschiebt DIMM-FENSTERGRENZEN, also muss auch die DND-Kette neu
                // armiert werden (Invariante seit v1.32.1). Sie kann das nicht selbst tun: `dnd/`
                // liest von `dimmer/`, niemals umgekehrt - deshalb steht dieser eine Aufruf hier.
                withContext(NonCancellable) {
                    runCatching { dndSchedule.enable() }
                        .onFailure { Logger.w(LogTags.DND, "⚠️ DND-Kette nach der Dimmer-Migration nicht neu armiert", it) }
                }
            }
            // Dieselben zwei Anlaesse wie die Dimmer-Migration (hier + 6h-Wartung), gleiche
            // Gruende. Ein uebernommenes Flag aendert den DND-Cutoff UND die Rufbereitschafts-
            // Abfrage - beide lesen es, beide werden danach neu armiert.
            if (rufbereitschaftMigration.migriereEinmalig()) {
                withContext(NonCancellable) {
                    runCatching { dndSchedule.enable() }
                        .onFailure { Logger.w(LogTags.DND, "⚠️ DND-Kette nach der Rufbereitschaft-Migration nicht neu armiert", it) }
                    runCatching { rufbereitschaftAbfrage.reschedule() }
                        .onFailure { Logger.w(LogTags.MAINTENANCE, "⚠️ Rufbereitschafts-Abfrage nach der Migration nicht neu geplant", it) }
                }
            }
        }

        // NUR beim ECHTEN Erststart dieser Activity, nicht nach einer Drehung: `onCreate` laeuft
        // dann mit DEMSELBEN Intent erneut, und der Nutzer landete jedes Mal wieder auf dem
        // Status-Tab - auch wenn er laengst woanders war. Nach Prozesstod und Wiederherstellung
        // (savedInstanceState != null) gilt dasselbe: der Wunsch von damals ist erledigt.
        if (savedInstanceState == null) {
            verarbeiteEinstieg(intent)
        }

        // ZERO-TAP-WIEDERHERSTELLUNG (#55): Abgemeldet -> Restore-Schluessel vom alten Geraet
        // suchen (gedeckelt auf 5 s), angemeldet ohne Schluessel -> einmal anlegen. Von HIER, weil
        // der CredentialManager einen Activity-Kontext braucht; das ViewModel laesst nur den
        // ersten Aufruf durch, eine Drehung loest also keinen zweiten Versuch aus.
        authViewModel.starteAnmeldeWiederherstellung(this)

        // POST_NOTIFICATIONS wird erst im Hauptbereich abgefragt (LaunchedEffect im "main"-Screen).

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
                        // collectAsStateWithLifecycle, nicht collectAsState: authState waehlt nur den
                        // anzuzeigenden Screen (loading/login/calendar_auth/main). Unterhalb von
                        // STARTED gibt es nichts zu zeichnen, das Sammeln darf also pausieren; der
                        // StateFlow ist konfliert und liefert beim Zurueckkehren sofort den aktuellen
                        // Wert. Der Auto-Re-Auth-Pfad haengt NICHT hieran - der sammelt separat und
                        // bewusst erst ab RESUMED (repeatOnLifecycle unten).
                        val authState by authViewModel.uiState.collectAsStateWithLifecycle()

                        // AUTO-RE-AUTH: Verliert die App das Token zur Laufzeit (401/403 -> invalidate),
                        // meldet das AuthViewModel das hier - denn den Zustimmungsdialog kann nur eine
                        // Activity starten (die Play-Dienste liefern einen PendingIntent). Ohne diesen Weg musste
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

                        // Nach JEDER gelungenen Kalender-Autorisierung die Termine neu abrufen: erst
                        // dieser Abruf nimmt die Warnung "Kalender-Autorisierung verloren" zurueck
                        // (sie haengt am letzten Terminabruf, nicht am Token). Ohne ihn blieb die Karte
                        // nach "Kalender-Zugriff erneuern" rot - am Fairphone viermal getippt, viermal
                        // "tut nichts". Hergang am KDoc von AuthViewModel.kalenderZugriffErneuert.
                        //
                        // STARTED genuegt: hier startet keine Activity, und das Signal ist gepuffert.
                        LaunchedEffect(Unit) {
                            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                authViewModel.kalenderZugriffErneuert.collect {
                                    calendarViewModel.refreshData(forceRefresh = true)
                                }
                            }
                        }

                        // ONBOARDING GATE: A signed-in user without a valid Calendar token is routed to
                        // CalendarAuthorizationScreen instead of the (half-broken) main UI. tokenChecked
                        // guards against flashing the gate before the initial token check has completed.
                        val screenContent = remember(
                            authState.wiederherstellungLaeuft,
                            authState.isSignedIn,
                            authState.calendarOps.calendarsLoading,
                            authState.calendarOps.tokenChecked,
                            authState.calendarOps.hasValidToken
                        ) {
                            when {
                                // Vor "login": solange der Restore-Schluessel gesucht wird, soll
                                // nicht der Anmeldeknopf zum Tippen einladen.
                                authState.wiederherstellungLaeuft -> "restore"
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
                            "restore" -> {
                                LoadingScreen(message = AuthViewModel.TEXT_WIEDERHERSTELLUNG_LAEUFT)
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
                                        authViewModel.signIn(this@MainActivity)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Die App laeuft bereits, und eine Benachrichtigung schickt sie an eine bestimmte Stelle.
     *
     * `setIntent()` ist Pflicht, nicht Kosmetik: ohne den Aufruf liefert `getIntent()` weiterhin
     * den Start-Intent - jede spaetere Auswertung (auch die nach einer Drehung) laese dann das
     * ALTE Ziel. Dieselbe Falle wie am Weckbildschirm, siehe
     * [AlarmFullScreenActivity.onNewIntent].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        verarbeiteEinstieg(intent)
    }

    /**
     * Setzt den Einstiegswunsch aus einer Benachrichtigung in Navigation um.
     *
     * Der Wunsch wird gestellt, BEVOR der Tab gewechselt wird - der Status-Tab wertet ihn beim
     * ersten Zeichnen aus, und ein noch nicht gestellter Wunsch waere dann schlicht nicht da.
     * Fuehrt ein Onboarding-Gate oder der Login-Screen den Nutzer zuerst woandershin, bleibt der
     * Wunsch stehen und wird eingeloest, sobald der Status-Tab wirklich erscheint.
     */
    private fun verarbeiteEinstieg(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_EINSTIEG)) {
            EINSTIEG_DIMMER_BEDIENUNGSHILFEN -> {
                Logger.business(
                    LogTags.NAVIGATION,
                    "Einstieg aus der Dimmer-Benachrichtigung -> Status-Tab, Bedienungshilfen-Karte"
                )
                DimBedienungshilfenWunsch.stellen()
                navigationViewModel.navigateToMainWithTab(MainTab.STATUS)
            }

            EINSTIEG_KALENDER_WARNUNG -> {
                Logger.business(
                    LogTags.NAVIGATION,
                    "Einstieg aus der Kalender-Warnung -> Status-Tab, Kalender wird neu geladen"
                )
                navigationViewModel.navigateToMainWithTab(MainTab.STATUS)
                // AUCH beim Kaltstart, nicht nur bei onNewIntent: das init-Laden des ViewModels
                // bedient sich aus dem Terminzwischenspeicher (15 min), und der kann noch den
                // Stand von vor dem Ausfall halten. Ein doppelter Abruf ist harmlos - der
                // Generation-Counter laesst den neueren gewinnen.
                calendarViewModel.refreshData(forceRefresh = true)
            }

            else -> Unit
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
    
    override fun onResume() {
        super.onResume()
        // Der EINZIGE Aufruf: onResume folgt immer auf onCreate. Bis v1.45 stand er zusaetzlich am
        // Ende von onCreate - beim Start liefen so zwei Health-Check-Coroutinen gegeneinander, und
        // beide konnten das Zeitfenster noch offen sehen (#130, G6-07).
        bridgeConnectionManager.onAppForeground()
    }
    
    override fun onPause() {
        super.onPause()
        bridgeConnectionManager.onAppBackground()
    }
    
    /** Ergebnis des von OAuth2TokenManager gestarteten Kalender-Zustimmungsdialogs. */
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
    
    // Kein onDestroy-Cleanup: Prozess-Singletons überleben Rotation; Hue-Lifecycle hängt an onResume/onPause.

}
