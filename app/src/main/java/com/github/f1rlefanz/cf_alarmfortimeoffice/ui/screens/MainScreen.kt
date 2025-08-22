package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.UIConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    calendarViewModel: CalendarViewModel,
    shiftViewModel: ShiftViewModel,
    alarmViewModel: AlarmViewModel,
    mainViewModel: MainViewModel,
    navigationViewModel: NavigationViewModel,
    viewModelFactory: ViewModelFactory,
    onRequestCalendarPermission: () -> Unit
) {
    // MEMORY LEAK FIX: Consolidated State Collection
    // Reduziert 6 individuelle collectAsState() auf strukturierte Sammlung
    val authState by authViewModel.uiState.collectAsState()
    val calendarState by calendarViewModel.uiState.collectAsState()
    val mainState by mainViewModel.uiState.collectAsState()
    val navigationState by navigationViewModel.navigationState.collectAsState()
    
    // PERFORMANCE: Nur sammeln wenn wirklich benötigt (für spezifische Screens)
    val shiftState by remember(navigationState) {
        if (navigationState is NavigationState.ShiftConfig || navigationState is NavigationState.MainContent) {
            shiftViewModel.uiState
        } else {
            flowOf(ShiftUiState()) // Empty state wenn nicht benötigt
        }
    }.collectAsState(initial = ShiftUiState())

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
        
        Logger.d(LogTags.UI, "Processing auth-based side effects: calendars=${calendarState.availableCalendars.size}, hasSelected=${mainState.hasSelectedCalendars}, loading=${calendarState.isLoading}")
        
        // PERFORMANCE: Prevent operations during loading
        if (calendarState.isLoading) {
            Logger.d(LogTags.UI, "Calendar operation already in progress, skipping duplicate side effect")
            return@LaunchedEffect
        }
        
        // 1. CALENDAR DATA: Load only if really needed
        if (calendarState.availableCalendars.isEmpty()) {
            Logger.d(LogTags.UI, "Loading calendar data due to empty calendar list")
            calendarViewModel.refreshData()
            
            // 2. PERMISSION: Only request if refresh doesn't start loading process
            delay(200) // Brief delay to check if loading started
            if (calendarState.availableCalendars.isEmpty()) {
                Logger.d(LogTags.UI, "Requesting calendar permission as fallback")
                onRequestCalendarPermission()
            }
        }
        
        // 3. NAVIGATION: Handle after data operations complete
        if (calendarState.availableCalendars.isNotEmpty()) {
            delay(100) // Minimal delay for UI stability
            navigationViewModel.handleAuthenticationSuccess(mainState.hasSelectedCalendars)
        }
    }

    // 2. DAYSAHEAD CONFIGURATION - Separate effect with debouncing
    // REACTIVITY FIX: Use remember to track previous value and prevent loops
    val previousDaysAhead = remember { mutableStateOf<Int?>(null) }
    
    LaunchedEffect(shiftState.currentShiftConfig?.daysAhead) {
        val currentDaysAhead = shiftState.currentShiftConfig?.daysAhead
        
        // LOOP PREVENTION: Only react to actual changes
        if (authState.isSignedIn && 
            currentDaysAhead != null && 
            currentDaysAhead != previousDaysAhead.value &&
            mainState.hasSelectedCalendars) {
            
            // DEBOUNCING: Wait for configuration to stabilize
            delay(300) // Longer delay for daysAhead changes
            
            // DOUBLE-CHECK: Ensure value hasn't changed during delay
            if (currentDaysAhead == shiftState.currentShiftConfig?.daysAhead) {
                Logger.d(LogTags.UI, "DaysAhead configuration changed from ${previousDaysAhead.value} to $currentDaysAhead - refreshing events")
                previousDaysAhead.value = currentDaysAhead
                calendarViewModel.refreshEventsWithNewDaysAhead()
            }
        } else if (currentDaysAhead != null && previousDaysAhead.value == null) {
            // INITIAL VALUE: Set without triggering refresh
            previousDaysAhead.value = currentDaysAhead
            Logger.d(LogTags.UI, "Initial daysAhead configuration set to $currentDaysAhead")
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
                    onSave = {
                        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
                    },
                    onCancel = {
                        navigationViewModel.navigateBackToMain()
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
                    viewModelFactory = viewModelFactory,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onSaveComplete = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.HueSettings -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueSettingsScreen(
                    viewModelFactory = viewModelFactory,
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
                    viewModelFactory = viewModelFactory,
                    selectedTab = mainContentState.selectedTab,
                    onSelectedTabChange = { tab -> navigationViewModel.changeTab(tab) },
                    onShowShiftConfig = { navigationViewModel.navigateToShiftConfig(mainContentState.selectedTab) },
                    onShowCalendarSelection = { navigationViewModel.navigateToCalendarSelection(mainContentState.selectedTab) },
                    onShowEventList = { navigationViewModel.navigateToEventList(mainContentState.selectedTab) },
                    onShowHueRuleConfig = { navigationViewModel.navigateToHueRuleConfig() },
                    onShowHueSettings = { navigationViewModel.navigateToHueSettings() },
                    onTestHueConnection = { 
                        // Test wird direkt in HueTabContent gemacht
                    }
                )
            }
        }
    }
}

