package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

// PHASE 2 CLEANUP: Removed unused ShiftUiState and flowOf imports
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.UIConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MainViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.NavigationViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.delay

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

    // MEMORY LEAK FIX: Consolidated State Collection
    // Reduziert individuelle collectAsState() auf strukturierte Sammlung
    val authState by authViewModel.uiState.collectAsState()
    val calendarState by calendarViewModel.uiState.collectAsState()
    val mainState by mainViewModel.uiState.collectAsState()
    val navigationState by navigationViewModel.navigationState.collectAsState()

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
        if (calendarState.availableCalendars.isEmpty()) {
            Logger.d(LogTags.UI, "Loading calendar data due to empty calendar list")
            calendarViewModel.refreshData()
        }

        // 3. NAVIGATION: Handle after data operations complete
        if (calendarState.availableCalendars.isNotEmpty()) {
            delay(100) // Minimal delay for UI stability
            val hasBatteryExemption =
                com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.isExempted(
                    context
                )
            navigationViewModel.handleAuthenticationSuccess(
                mainState.hasSelectedCalendars,
                hasBatteryExemption
            )
        }
    }

    // PHASE 1 MIGRATION: daysAhead is now fixed at 14 days
    // Removed daysAhead configuration effect as per PROJEKT-BRIEFING 4.0
    // Events are now loaded with fixed 14 days lookahead
    // This simplifies the architecture and prevents reactivity loops

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
                    onSave = {
                        // PHASE 1 MIGRATION: After calendar selection, navigate to battery exemption
                        if (!com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.isExempted(
                                context
                            )
                        ) {
                            Logger.business(
                                LogTags.NAVIGATION,
                                "Calendar saved -> Battery Exemption needed"
                            )
                            navigationViewModel.navigateToBatteryExemption()
                        } else {
                            // Check for OEM warning
                            val oemType =
                                com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.getOEMType()
                            if (com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.shouldShowOEMWarning(
                                    oemType
                                )
                            ) {
                                Logger.business(
                                    LogTags.NAVIGATION,
                                    "Battery exempted -> OEM Warning for $oemType"
                                )
                                navigationViewModel.navigateToOEMWarning(oemType)
                            } else {
                                Logger.business(LogTags.NAVIGATION, "Onboarding complete -> Main")
                                // Initialize maintenance service after successful onboarding
                                com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService.scheduleNext(
                                    context
                                )
                                navigationViewModel.navigateToMainWithTab(MainTab.HOME)
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
                    val isExempted =
                        com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.isExempted(
                            context
                        )
                    Logger.d(LogTags.BATTERY, "Battery exemption result: $isExempted")

                    if (isExempted) {
                        // Check for OEM warning
                        val oemType =
                            com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.getOEMType()
                        if (com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.shouldShowOEMWarning(
                                oemType
                            )
                        ) {
                            Logger.business(
                                LogTags.NAVIGATION,
                                "Battery exempted -> OEM Warning for $oemType"
                            )

                            // Show OEM warning dialog
                            com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.showOEMWarningDialog(
                                context,
                                oemType
                            )
                            navigationViewModel.navigateToMainWithTab(MainTab.HOME)
                        } else {
                            Logger.business(LogTags.NAVIGATION, "Battery exempted -> Main")
                            // Initialize maintenance service after successful onboarding
                            com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService.scheduleNext(
                                context
                            )
                            navigationViewModel.navigateToMainWithTab(MainTab.HOME)
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
                    onComplete = {
                        showEducationalDialog = true
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

            is NavigationState.OEMWarning -> {
                // PHASE 1 MIGRATION: OEM Warning Screen
                val oemWarningState = navigationState as NavigationState.OEMWarning

                OEMWarningScreen(
                    oemType = oemWarningState.oemType,
                    onComplete = {
                        Logger.business(LogTags.NAVIGATION, "OEM Warning acknowledged -> Main")
                        // Initialize maintenance service after successful onboarding
                        com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService.scheduleNext(
                            context
                        )
                        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
                    }
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
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueRuleConfigScreen(
                    ruleId = hueRuleState.ruleId,
                    hueViewModel = hueViewModel,
                    shiftViewModel = shiftViewModel,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onSaveComplete = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.HueSettings -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueSettingsScreen(
                    hueViewModel = hueViewModel,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onEditRule = { ruleId -> navigationViewModel.navigateToHueRuleConfig(ruleId) },
                    onCreateNewRule = { navigationViewModel.navigateToHueRuleConfig() }
                )
            }

            is NavigationState.MainContent -> {
                val mainContentState = navigationState as NavigationState.MainContent
                MainContentScreen(
                    authViewModel = authViewModel,
                    calendarViewModel = calendarViewModel,
                    shiftViewModel = shiftViewModel,
                    alarmViewModel = alarmViewModel,
                    mainViewModel = mainViewModel,
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
                    onShowHueRuleConfig = { navigationViewModel.navigateToHueRuleConfig() },
                    onShowHueSettings = { navigationViewModel.navigateToHueSettings() }
                )
            }
        }
    }
}

