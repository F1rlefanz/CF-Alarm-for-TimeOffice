package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

// PHASE 2 CLEANUP: Removed unused ShiftUiState and flowOf imports
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.TimeOfficeHealthHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.UnusedAppRestrictionsHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.UIConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MainViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.NavigationViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gemeinsamer Abschluss, sobald Akku-Ausnahme UND Unused-App-Restrictions erledigt oder
 * uebersprungen sind: zum vollflaechigen OEM-Warnscreen navigieren (falls das Geraet betroffen
 * UND der Screen fuer diesen Herstellertyp noch nie gezeigt wurde) oder direkt die Wartungskette
 * anstossen. Einziger verbliebener OEM-Hinweis-Weg nach der Konsolidierung (Juli 2026) - frueher
 * gab es hier zusaetzlich einen separaten, ungegateten Dialog; die richtigen, herstellerspezifischen
 * Schritte gibt es nur im Screen (siehe [OEMWarningScreen]), daher konvergiert alles hierher.
 * Aufgerufen von jeder Stelle, die "von einem Gate zurueckgekehrt" ist: Battery-Settings-Result,
 * Unused-App-Restrictions-Settings-Result, und CalendarSelectionScreen.onSave.
 */
private suspend fun proceedPastGates(
    context: Context,
    navigationViewModel: NavigationViewModel
) {
    // TimeOffice-Gate: CFAlarms Alarme haengen an einem Kalender, den TimeOffice lokal befuellt
    // (siehe TimeOfficeHealthHelper). Nur pruefbar (Akku-Ausnahme fuer ein fremdes Package), wenn
    // TimeOffice ueberhaupt installiert ist - sonst irrelevant fuer diesen Nutzer.
    if (TimeOfficeHealthHelper.isInstalled(context) &&
        !TimeOfficeHealthHelper.isBatteryExempted(context) &&
        !TimeOfficeHealthHelper.isPromptDismissed(context)
    ) {
        Logger.business(LogTags.NAVIGATION, "Gates resolved -> TimeOffice Health Check")
        navigationViewModel.navigateToTimeOfficeHealthCheck()
        return
    }

    val oemType = BatteryOptimizationHelper.getOEMType()
    if (BatteryOptimizationHelper.shouldNavigateToOemWarningScreen(context, oemType)) {
        Logger.business(LogTags.NAVIGATION, "Gates resolved -> OEM Warning screen for $oemType")
        BatteryOptimizationHelper.markOemWarningScreenShown(context, oemType)
        navigationViewModel.navigateToOEMWarning(oemType)
    } else {
        Logger.business(LogTags.NAVIGATION, "Onboarding complete -> Main")
        AlarmMaintenanceService.scheduleNext(context)
        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
    }
}

@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    calendarViewModel: CalendarViewModel,
    shiftViewModel: ShiftViewModel,
    alarmViewModel: AlarmViewModel,
    mainViewModel: MainViewModel,
    navigationViewModel: NavigationViewModel,
    hueViewModel: HueViewModel
) {
    // PHASE 1 MIGRATION: Get context for battery exemption checks
    val context = LocalContext.current
    // Scope für den (jetzt suspend) OEM-Warndialog, dessen "shown"-Flag im DataStore liegt
    val coroutineScope = rememberCoroutineScope()

    // MEMORY LEAK FIX: Consolidated State Collection
    // Reduziert individuelle collectAsState() auf strukturierte Sammlung
    //
    // collectAsStateWithLifecycle, nicht collectAsState: Diese vier Zustaende steuern
    // ausschliesslich Vordergrund-Verhalten (Screen-Auswahl, BackHandler, die Gate-Kette im
    // LaunchedEffect unten). Unterhalb von STARTED pausiert das Sammeln - genau richtig hier,
    // denn der Gate-Effect startet Activities (Akku-Ausnahme, Settings) bzw. navigiert, und
    // beides ist aus dem Hintergrund ohnehin nicht erlaubt. Alle vier Quellen sind StateFlows
    // (konfliert bzw. SharingStarted.Lazily), beim Zurueckkehren kommt also sofort der aktuelle
    // Wert an; es geht kein Zustand verloren.
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val calendarState by calendarViewModel.uiState.collectAsStateWithLifecycle()
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val navigationState by navigationViewModel.navigationState.collectAsStateWithLifecycle()

    // PHASE 2 CLEANUP: Removed unused shiftState collection
    // ShiftViewModel is passed directly to screens that need it

    // PERFORMANCE FIX: Separate LaunchedEffects to prevent reactivity loops
    // Split authentication handling from daysAhead observation

    // 1. AUTHENTICATION & CALENDAR LOADING - Stable dependencies only
    LaunchedEffect(
        authState.isSignedIn,
        mainState.hasSelectedCalendars,
        calendarState.availableCalendars.size,
        calendarState.isLoading
    ) {
        if (!authState.isSignedIn) return@LaunchedEffect

        // DEBOUNCING: Stabilization time for simultaneous events
        delay(UIConstants.UI_STABILITY_DELAY_MS)

        Logger.d(
            LogTags.UI,
            "Processing auth-based side effects: calendars=${calendarState.availableCalendars.size}, hasSelected=${mainState.hasSelectedCalendars}, loading=${calendarState.isLoading}"
        )

        // PERFORMANCE: Prevent operations during loading
        if (calendarState.isLoading) {
            Logger.d(
                LogTags.UI,
                "Calendar operation already in progress, skipping duplicate side effect"
            )
            return@LaunchedEffect
        }

        // 1. CALENDAR DATA: Load only if really needed
        //
        // WICHTIG: NICHT nachladen, wenn der letzte Versuch mit einem Fehler endete.
        // Dieser Effect haengt an calendarState.isLoading. Schlaegt das Laden fehl, springt
        // isLoading zurueck auf false -> Effect laeuft erneut -> Liste immer noch leer ->
        // refreshData() -> isLoading true -> ... Eine Endlosschleife, die bei jedem Durchlauf
        // erneut die Google-API anfragt. Genau das passierte bei einem 401 (totes Token):
        // Log vom 14.07. zeigt "Loading calendar data due to empty calendar list" im
        // Sekundentakt.
        //
        // Der Fehler steht bereits in der UI; der Nutzer loest den naechsten Versuch bewusst
        // aus (Pull-to-Refresh / Neuanmeldung).
        if (calendarState.availableCalendars.isEmpty() && calendarState.error == null) {
            Logger.d(LogTags.UI, "Loading calendar data due to empty calendar list")
            calendarViewModel.refreshData()
        } else if (calendarState.error != null) {
            Logger.d(
                LogTags.UI,
                "Skipping calendar reload - last attempt failed: ${calendarState.error}"
            )
        }

        // 3. NAVIGATION: Handle after data operations complete
        if (calendarState.availableCalendars.isNotEmpty()) {
            delay(100) // Minimal delay for UI stability
            val hasBatteryExemption = BatteryOptimizationHelper.isExempted(context)
            val batteryPromptDismissed = BatteryOptimizationHelper.isBatteryPromptDismissed(context)
            // Kurzschluss nur, solange das Akku-Gate noch OFFEN ist - dann kommt es zuerst und der
            // Unused-App-Check waere ein unnoetiger Async-Call. Erledigt ist es aber auch dann,
            // wenn der Nutzer "Spaeter" getippt hat: `hasBatteryExemption` allein als Bedingung
            // liess den Unused-App-Schritt fuer jeden Nutzer, der die Akku-Ausnahme abgelehnt hat,
            // dauerhaft ausfallen (siehe handleAuthenticationSuccess).
            val batteryGateResolved = hasBatteryExemption || batteryPromptDismissed
            val needsUnusedAppRestrictionsPrompt = batteryGateResolved &&
                UnusedAppRestrictionsHelper.isRestricted(context) &&
                !UnusedAppRestrictionsHelper.isDismissed(context)
            // Gleiche Pruefung wie proceedPastGates() (siehe oben) - dort nur erreichbar,
            // wenn der Nutzer gerade aktiv durch Battery/UnusedAppRestrictions zurueckkommt.
            // handleAuthenticationSuccess() laeuft dagegen bei JEDEM App-Vordergrund und ist
            // der einzige Weg, Bestandsnutzer zu erreichen, die die frueheren Gates schon vor
            // diesem Feature durchlaufen hatten.
            val needsTimeOfficeHealthPrompt = TimeOfficeHealthHelper.isInstalled(context) &&
                !TimeOfficeHealthHelper.isBatteryExempted(context) &&
                !TimeOfficeHealthHelper.isPromptDismissed(context)
            navigationViewModel.handleAuthenticationSuccess(
                mainState.hasSelectedCalendars,
                hasBatteryExemption,
                batteryPromptDismissed,
                needsUnusedAppRestrictionsPrompt,
                needsTimeOfficeHealthPrompt
            )
        }
    }

    // PHASE 1 MIGRATION: daysAhead is now fixed at 14 days
    // Removed daysAhead configuration effect as per PROJEKT-BRIEFING 4.0
    // Events are now loaded with fixed 14 days lookahead
    // This simplifies the architecture and prevents reactivity loops

    // Onboarding-Abschluss an genau einer Stelle: Wartungskette anstossen, dann Home. Sowohl
    // "Verstanden" als auch der Zurueck-Weg muessen das tun - wer hier nur navigiert, laesst
    // einen Nutzer ohne 6h-Wartung zurueck und damit ohne neu angelegte Wecker.
    // scheduleNext() ist idempotent (ein Request-Code, ein PendingIntent), ein zweiter Aufruf
    // ersetzt den bestehenden Alarm also nur.
    val finishOnboarding: () -> Unit = {
        Logger.business(LogTags.NAVIGATION, "OEM Warning acknowledged -> Main")
        com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService.scheduleNext(
            context
        )
        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
    }

    // ANDROID-ZURUECK: Die App navigiert ueber einen eigenen NavigationState, nicht ueber
    // Navigation-Compose - es gibt also keinen Backstack, der Zurueck von allein eine Ebene
    // hoch fuehren wuerde. Ohne BackHandler landet jeder Druck beim Default der Activity und
    // beendet die App: aus "Kalender-Events" sprang der Nutzer direkt auf den Android-Home-
    // screen. Wer einen neuen NavigationState ergaenzt, muss ihn hier mitbedenken; der
    // else-Zweig faengt jeden Unterscreen ab, die Sonderfaelle stehen davor.
    //
    // Auf dem Home-Tab bleibt der Handler bewusst AUS: dort ist Zurueck tatsaechlich "App
    // verlassen", und das erledigt der Systemdefault inkl. Predictive-Back-Animation besser
    // als jeder Nachbau.
    val onHomeTab = (navigationState as? NavigationState.MainContent)?.selectedTab == MainTab.HOME
    BackHandler(enabled = !onHomeTab) {
        when (navigationState) {
            // Ein Nicht-Home-Tab ist eine Ebene tiefer: Zurueck fuehrt auf Home, nicht aus der
            // App (Android-Konvention fuer Bottom-Navigation).
            is NavigationState.MainContent -> navigationViewModel.changeTab(MainTab.HOME)

            // Zurueck heisst hier dasselbe wie "Spaeter": ein blosses navigateBackToMain()
            // wuerde handleAuthenticationSuccess() sofort wieder hierher schicken - Zurueck
            // saehe aus, als passiere nichts.
            is NavigationState.BatteryExemption -> {
                coroutineScope.launch { BatteryOptimizationHelper.setBatteryPromptDismissed(context) }
                navigationViewModel.dismissBatteryPrompt()
            }

            // Gleiche Semantik wie Battery, NICHT wie OEM: der Nutzer hat hier ggf. nichts
            // geaendert (reiner Settings-Screen, kein Bestaetigungs-Dialog), Zurueck muss also
            // wie "Spaeter" wirken, nicht wie "Verstanden".
            is NavigationState.UnusedAppRestrictions -> {
                coroutineScope.launch { UnusedAppRestrictionsHelper.setDismissed(context) }
                navigationViewModel.navigateToMainWithTab(MainTab.HOME)
            }

            // Gleiche Semantik wie die beiden Gates oben.
            is NavigationState.TimeOfficeHealthCheck -> {
                coroutineScope.launch { TimeOfficeHealthHelper.setPromptDismissed(context) }
                navigationViewModel.navigateToMainWithTab(MainTab.HOME)
            }

            // Wie "Verstanden" - siehe finishOnboarding.
            is NavigationState.OEMWarning -> finishOnboarding()

            // Zwei Einstiegspfade mit unterschiedlichem Rueckziel (siehe
            // NavigationState.HueRuleConfig.cameFromSettingsList): navigateBackToMain() alleine
            // wuerde IMMER direkt zu MainContent aufloesen und damit HueSettings ueberspringen,
            // wenn der Nutzer tatsaechlich ueber die Regel-Liste hierher kam. Muss mit dem
            // Save/Zurueck-Verhalten im HueRuleConfig-Screen-Block unten konsistent bleiben.
            is NavigationState.HueRuleConfig -> {
                val state = navigationState as NavigationState.HueRuleConfig
                if (state.cameFromSettingsList) {
                    navigationViewModel.navigateToHueSettings(state.returnToTab)
                } else {
                    navigationViewModel.navigateToMainWithTab(state.returnToTab)
                }
            }

            // Gleiche Semantik wie HueRuleConfig oben.
            is NavigationState.DimmerRuleConfig -> {
                val state = navigationState as NavigationState.DimmerRuleConfig
                if (state.cameFromSettingsList) {
                    navigationViewModel.navigateToDimmerSettings(state.returnToTab)
                } else {
                    navigationViewModel.navigateToMainWithTab(state.returnToTab)
                }
            }

            else -> navigationViewModel.navigateBackToMain()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (navigationState) {
            is NavigationState.ShiftConfig -> {
                ShiftConfigScreen(
                    shiftViewModel = shiftViewModel,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.CalendarSelection -> {
                CalendarSelectionScreen(
                    calendarViewModel = calendarViewModel,
                    onRequestAuthorization = {
                        // Der einzige Weg aus "Token verworfen": Zustimmungsdialog anstossen.
                        // Braucht die Activity, sonst kann Google keinen Dialog zeigen.
                        authViewModel.requestCalendarAuthorization(context as? android.app.Activity)
                    },
                    onSave = {
                        // PHASE 1 MIGRATION: After calendar selection, navigate to battery exemption
                        if (!BatteryOptimizationHelper.isExempted(context)) {
                            Logger.business(
                                LogTags.NAVIGATION,
                                "Calendar saved -> Battery Exemption needed"
                            )
                            navigationViewModel.navigateToBatteryExemption()
                        } else {
                            // Unused-App-Restrictions-Check ist async (ListenableFuture) -
                            // deshalb ab hier in eine Coroutine.
                            coroutineScope.launch {
                                if (UnusedAppRestrictionsHelper.isRestricted(context) &&
                                    !UnusedAppRestrictionsHelper.isDismissed(context)
                                ) {
                                    Logger.business(
                                        LogTags.NAVIGATION,
                                        "Battery exempted -> Unused App Restrictions needed"
                                    )
                                    navigationViewModel.navigateToUnusedAppRestrictions()
                                } else {
                                    proceedPastGates(context, navigationViewModel)
                                }
                            }
                        }
                    },
                    onCancel = {
                        navigationViewModel.navigateBackToMain()
                    }
                )
            }

            is NavigationState.BatteryExemption -> {
                // PHASE 1 MIGRATION: Battery Exemption Screen
                var showEducationalDialog by remember { mutableStateOf(false) }

                // Activity Result Launcher for battery exemption
                val batteryExemptionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    // After returning from settings, check if exemption was granted
                    val isExempted = BatteryOptimizationHelper.isExempted(context)
                    Logger.d(LogTags.BATTERY, "Battery exemption result: $isExempted")

                    if (isExempted) {
                        coroutineScope.launch {
                            if (UnusedAppRestrictionsHelper.isRestricted(context) &&
                                !UnusedAppRestrictionsHelper.isDismissed(context)
                            ) {
                                Logger.business(
                                    LogTags.NAVIGATION,
                                    "Battery exempted -> Unused App Restrictions needed"
                                )
                                navigationViewModel.navigateToUnusedAppRestrictions()
                            } else {
                                proceedPastGates(context, navigationViewModel)
                            }
                        }
                    } else {
                        // User didn't grant exemption, show educational dialog
                        showEducationalDialog = true
                    }
                }

                if (showEducationalDialog) {
                    BatteryEducationalDialog(
                        onDismiss = { showEducationalDialog = false }
                    )
                }

                BatteryOnboardingScreen(
                    // Hiess frueher onComplete - der Name log: der Knopf "Warum ist das noetig?"
                    // schliesst nichts ab, er erklaert.
                    onExplain = {
                        showEducationalDialog = true
                    },
                    onSkip = {
                        coroutineScope.launch { BatteryOptimizationHelper.setBatteryPromptDismissed(context) }
                        navigationViewModel.dismissBatteryPrompt()
                    },
                    onRequestExemption = {
                        // Launch battery exemption request
                        try {
                            @Suppress("BatteryLife")
                            val intent =
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .apply {
                                        data = "package:${context.packageName}".toUri()
                                    }
                            batteryExemptionLauncher.launch(intent)
                            Logger.d(LogTags.BATTERY, "Battery exemption request launched")
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.BATTERY,
                                "Failed to request battery exemption, opening settings",
                                e
                            )
                            // Fallback: Open battery optimization settings
                            try {
                                val intent =
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                batteryExemptionLauncher.launch(intent)
                            } catch (e2: Exception) {
                                Logger.e(LogTags.BATTERY, "Failed to open battery settings", e2)
                            }
                        }
                    }
                )
            }

            is NavigationState.UnusedAppRestrictions -> {
                val unusedAppRestrictionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    // Kein strukturiertes Ergebnis (reiner Settings-Screen) - immer neu pruefen,
                    // was als naechstes kommt (OEM-Screen oder fertig).
                    coroutineScope.launch { proceedPastGates(context, navigationViewModel) }
                }

                UnusedAppRestrictionsOnboardingScreen(
                    onOpenSettings = {
                        try {
                            unusedAppRestrictionsLauncher.launch(
                                UnusedAppRestrictionsHelper.createSettingsIntent(context)
                            )
                            Logger.d(
                                LogTags.UNUSED_APP_RESTRICTIONS,
                                "Unused-app-restrictions settings opened"
                            )
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.UNUSED_APP_RESTRICTIONS,
                                "Failed to open unused-app-restrictions settings",
                                e
                            )
                        }
                    },
                    onSkip = {
                        coroutineScope.launch {
                            UnusedAppRestrictionsHelper.setDismissed(context)
                        }
                        Logger.business(
                            LogTags.NAVIGATION,
                            "Unused-App-Restrictions prompt skipped (Spaeter) -> Home"
                        )
                        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
                    }
                )
            }

            is NavigationState.TimeOfficeHealthCheck -> {
                val timeOfficeSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    // Kein strukturiertes Ergebnis (reiner Settings-Screen einer fremden App) -
                    // immer neu pruefen, was als naechstes kommt (OEM-Screen oder fertig).
                    coroutineScope.launch { proceedPastGates(context, navigationViewModel) }
                }

                TimeOfficeHealthOnboardingScreen(
                    onOpenSettings = {
                        try {
                            timeOfficeSettingsLauncher.launch(
                                TimeOfficeHealthHelper.createAppInfoIntent(context)
                            )
                            Logger.d(
                                LogTags.TIMEOFFICE_HEALTH,
                                "TimeOffice app-info settings opened"
                            )
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.TIMEOFFICE_HEALTH,
                                "Failed to open TimeOffice app-info settings",
                                e
                            )
                        }
                    },
                    onSkip = {
                        coroutineScope.launch {
                            TimeOfficeHealthHelper.setPromptDismissed(context)
                        }
                        Logger.business(
                            LogTags.NAVIGATION,
                            "TimeOffice-Health prompt skipped (Spaeter) -> Home"
                        )
                        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
                    }
                )
            }

            is NavigationState.OEMWarning -> {
                // PHASE 1 MIGRATION: OEM Warning Screen
                val oemWarningState = navigationState as NavigationState.OEMWarning

                OEMWarningScreen(
                    oemType = oemWarningState.oemType,
                    onComplete = finishOnboarding
                )
            }

            is NavigationState.EventList -> {
                EventListScreen(
                    calendarViewModel = calendarViewModel,
                    onBack = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.HueRuleConfig -> {
                val hueRuleState = navigationState as NavigationState.HueRuleConfig
                // Zurueck UND Speichern muessen zum tatsaechlichen Einstiegspunkt fuehren: kam
                // der Nutzer direkt vom Hue-Tab (kein HueSettings dazwischen), landet er wieder
                // dort - nicht auf einer Liste, die er nie geoeffnet hat. Kam er ueber
                // HueSettings, geht es dorthin zurueck. Muss mit dem BackHandler-Fall fuer
                // HueRuleConfig oben konsistent bleiben.
                val backToEntryPoint: () -> Unit = {
                    if (hueRuleState.cameFromSettingsList) {
                        navigationViewModel.navigateToHueSettings(hueRuleState.returnToTab)
                    } else {
                        navigationViewModel.navigateToMainWithTab(hueRuleState.returnToTab)
                    }
                }
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueRuleConfigScreen(
                    ruleId = hueRuleState.ruleId,
                    hueViewModel = hueViewModel,
                    shiftViewModel = shiftViewModel,
                    onNavigateBack = backToEntryPoint,
                    onSaveComplete = backToEntryPoint
                )
            }

            is NavigationState.HueSettings -> {
                val hueSettingsState = navigationState as NavigationState.HueSettings
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueSettingsScreen(
                    hueViewModel = hueViewModel,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onEditRule = { ruleId ->
                        navigationViewModel.navigateToHueRuleConfig(
                            ruleId = ruleId,
                            fromTab = hueSettingsState.returnToTab,
                            cameFromSettingsList = true
                        )
                    },
                    onCreateNewRule = {
                        navigationViewModel.navigateToHueRuleConfig(
                            fromTab = hueSettingsState.returnToTab,
                            cameFromSettingsList = true
                        )
                    }
                )
            }

            is NavigationState.DimmerSettings -> {
                val dimmerSettingsState = navigationState as NavigationState.DimmerSettings
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.DimmerSettingsScreen(
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onEditRule = { ruleId ->
                        navigationViewModel.navigateToDimmerRuleConfig(
                            ruleId = ruleId,
                            fromTab = dimmerSettingsState.returnToTab,
                            cameFromSettingsList = true
                        )
                    },
                    onCreateRule = {
                        navigationViewModel.navigateToDimmerRuleConfig(
                            fromTab = dimmerSettingsState.returnToTab,
                            cameFromSettingsList = true
                        )
                    }
                )
            }

            is NavigationState.DimmerRuleConfig -> {
                val dimRuleState = navigationState as NavigationState.DimmerRuleConfig
                // Gleiche Semantik wie HueRuleConfig oben - aktuell fuehrt nur der Pfad ueber
                // DimmerSettings hierher (cameFromSettingsList defaultet auf true), aber der
                // Verzweig deckt auch einen kuenftigen Direktpfad korrekt ab.
                val backToEntryPoint: () -> Unit = {
                    if (dimRuleState.cameFromSettingsList) {
                        navigationViewModel.navigateToDimmerSettings(dimRuleState.returnToTab)
                    } else {
                        navigationViewModel.navigateToMainWithTab(dimRuleState.returnToTab)
                    }
                }
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.DimmerRuleConfigScreen(
                    ruleId = dimRuleState.ruleId,
                    onNavigateBack = backToEntryPoint,
                    onSaveComplete = backToEntryPoint
                )
            }

            is NavigationState.MainContent -> {
                val mainContentState = navigationState as NavigationState.MainContent

                // DIE 6h-WARTUNGSKETTE WIRD HIER GESTELLT, NICHT AN DEN GATE-AUSGAENGEN.
                //
                // Bis v1.26.2 stand scheduleNext() nur in zwei der Ausgaenge (proceedPastGates()
                // und finishOnboarding()). Wer ein Gate mit "Spaeter" oder Zurueck verliess - ein
                // ausdruecklich vorgesehener, persistierter Weg - bekam die Kette NIE gestellt.
                // Danach entstanden Alarme nur noch, solange der Nutzer die App selbst oeffnete;
                // neue Schichten aus dem Dienstplan wurden nicht verweckert, und erst ein Reboot
                // reparierte den Zustand. Genau der Betriebsfall, den diese App nicht verlangt.
                //
                // An den Ausgaengen einzeln nachzuziehen waere die fragile Loesung gewesen: der
                // naechste neue Ausgang vergisst es wieder. Hier kommt JEDER Weg vorbei, und
                // scheduleNext() ist idempotent (ein Request-Code, ein PendingIntent) - ein
                // zweiter Aufruf ersetzt den bestehenden Alarm nur. Der Schluessel `Unit` laesst
                // es genau einmal je Eintritt in den Hauptbereich laufen, nicht bei jeder
                // Rekomposition.
                LaunchedEffect(Unit) {
                    AlarmMaintenanceService.scheduleNext(context)
                    Logger.d(LogTags.NAVIGATION, "6h-Wartungskette beim Eintritt in den Hauptbereich gestellt")
                }

                MainContentScreen(
                    authViewModel = authViewModel,
                    calendarViewModel = calendarViewModel,
                    shiftViewModel = shiftViewModel,
                    alarmViewModel = alarmViewModel,
                    hueViewModel = hueViewModel,
                    selectedTab = mainContentState.selectedTab,
                    onSelectedTabChange = { tab -> navigationViewModel.changeTab(tab) },
                    onShowShiftConfig = { navigationViewModel.navigateToShiftConfig(mainContentState.selectedTab) },
                    onShowCalendarSelection = {
                        navigationViewModel.navigateToCalendarSelection(
                            mainContentState.selectedTab
                        )
                    },
                    onShowEventList = { navigationViewModel.navigateToEventList(mainContentState.selectedTab) },
                    // Direkter Einstieg vom Hue-Tab (kein HueSettings dazwischen) -
                    // cameFromSettingsList = false, damit Zurueck/Speichern spaeter wieder
                    // hierher fuehrt statt auf die nie geoeffnete Regel-Liste.
                    onShowHueRuleConfig = {
                        navigationViewModel.navigateToHueRuleConfig(
                            fromTab = mainContentState.selectedTab,
                            cameFromSettingsList = false
                        )
                    },
                    onShowHueSettings = { navigationViewModel.navigateToHueSettings() },
                    onShowDimmerSettings = { navigationViewModel.navigateToDimmerSettings() },
                    onShowDimmerPreview = { navigationViewModel.navigateToDimmerPreview() },
                    onShowDndSettings = { navigationViewModel.navigateToDndSettings() }
                )
            }

            is NavigationState.DimmerPreview -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.DimmerPreviewScreen(
                    onNavigateBack = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.DndSettings -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dnd.DndSettingsScreen(
                    onNavigateBack = { navigationViewModel.navigateBackToMain() }
                )
            }
        }
    }
}

