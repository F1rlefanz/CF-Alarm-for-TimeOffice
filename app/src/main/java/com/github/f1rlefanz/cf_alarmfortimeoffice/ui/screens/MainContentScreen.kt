package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ManualAlarmCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.HomeTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.HueTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.SettingsTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.StatusTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MainViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContentScreen(
    authViewModel: AuthViewModel,
    calendarViewModel: CalendarViewModel,
    shiftViewModel: ShiftViewModel,
    alarmViewModel: AlarmViewModel,
    mainViewModel: MainViewModel,
    hueViewModel: HueViewModel,
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    onShowShiftConfig: () -> Unit,
    onShowCalendarSelection: () -> Unit,
    onShowEventList: () -> Unit,
    onShowHueRuleConfig: () -> Unit,
    onShowHueSettings: () -> Unit
) {
    val context = LocalContext.current
    val authState by authViewModel.uiState.collectAsState()
    val calendarState by calendarViewModel.uiState.collectAsState()
    val shiftState by shiftViewModel.uiState.collectAsState()
    val alarmState by alarmViewModel.uiState.collectAsState()
    val skipState by alarmViewModel.skipState.collectAsState()
    val manualAlarmState by alarmViewModel.manualAlarmState.collectAsState() // NEU

    val snackbarHostState = remember { SnackbarHostState() }

    // Manueller Alarm ist die Ausnahme, nicht der Normalfall -> aus der Home-Hauptflaeche in ein
    // per FAB geoeffnetes Bottom-Sheet ausgelagert (Home bleibt auf die Uebersicht fokussiert).
    var showManualAlarmSheet by remember { mutableStateOf(false) }

    // FEHLER-SICHTBARKEIT: Lade-/Sync-Fehler der Kern-Pipeline nicht mehr still verschlucken -
    // in einer Wecker-App darf der Nutzer einen Leerzustand nicht faelschlich fuer die Wahrheit
    // halten. Fehler werden als Snackbar gezeigt und danach geleert (sonst blieben sie in State
    // haengen und die Snackbar liesse sich nicht erneut ausloesen).
    LaunchedEffect(calendarState.error) {
        calendarState.error?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Wiederholen",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                mainViewModel.forceRefreshCalendarEvents()
            }
            calendarViewModel.clearError()
        }
    }
    LaunchedEffect(shiftState.error) {
        shiftState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            shiftViewModel.clearError()
        }
    }
    LaunchedEffect(alarmState.error) {
        alarmState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            alarmViewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CF-Alarm for TimeOffice",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selectedTab == MainTab.HOME,
                    onClick = { onSelectedTabChange(MainTab.HOME) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Info, contentDescription = "Status") },
                    label = { Text("Status", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selectedTab == MainTab.STATUS,
                    onClick = { onSelectedTabChange(MainTab.STATUS) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") },
                    label = { Text("Einstellungen", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selectedTab == MainTab.SETTINGS,
                    onClick = { onSelectedTabChange(MainTab.SETTINGS) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Lightbulb, contentDescription = "Hue") },
                    label = { Text("Hue", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selectedTab == MainTab.HUE,
                    onClick = { onSelectedTabChange(MainTab.HUE) }
                )
            }
        },
        floatingActionButton = {
            // Nur im Home-Tab: manuellen Alarm als Sekundaer-Aktion anbieten.
            if (selectedTab == MainTab.HOME) {
                ExtendedFloatingActionButton(
                    onClick = { showManualAlarmSheet = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Manueller Alarm") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Tab-Inhalt mit eigener Fehlerbehandlung
            when (selectedTab) {
                MainTab.HOME -> {
                    HomeTabContent(
                        calendarState = calendarState,
                        shiftState = shiftState,
                        alarmState = alarmState,
                        skipState = skipState,
                        onRefresh = {
                            // Perform manual refresh
                            mainViewModel.forceRefreshCalendarEvents()
                            
                            // Phase 1: BackgroundTokenRefreshWorker removed
                            // Manual refresh now only triggers calendar data reload
                            // Token refresh handled by AlarmMaintenanceService
                        },
                        onSkipNextAlarm = alarmViewModel::skipNextAlarm,
                        onCancelSkip = alarmViewModel::cancelSkip,
                        onShowEventList = onShowEventList,
                        onReauthorize = {
                            authViewModel.requestCalendarAuthorization(context as? android.app.Activity)
                        }
                    )
                }
                MainTab.STATUS -> {
                    StatusTabContent(
                        authState = authState,
                        calendarState = calendarState,
                        shiftState = shiftState,
                        alarmState = alarmState,
                        calendarViewModel = calendarViewModel
                    )
                }
                MainTab.SETTINGS -> {
                    SettingsTabContent(
                        authViewModel = authViewModel,
                        onShowShiftConfig = onShowShiftConfig,
                        onShowCalendarSelection = onShowCalendarSelection
                    )
                }
                MainTab.HUE -> {
                    HueTabContent(
                        hueViewModel = hueViewModel,
                        onNavigateToRuleConfig = onShowHueRuleConfig,
                        onNavigateToSettings = onShowHueSettings
                    )
                }
            }
        }
    }

    // Bottom-Sheet fuer den manuellen Alarm (per FAB im Home-Tab geoeffnet).
    if (showManualAlarmSheet) {
        ModalBottomSheet(
            onDismissRequest = { showManualAlarmSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ManualAlarmCard(
                manualAlarmState = manualAlarmState,
                onSelectDate = alarmViewModel::selectManualAlarmDate,
                onSelectShift = alarmViewModel::selectManualAlarmShift,
                onCreate = alarmViewModel::createManualAlarm,
                onDelete = alarmViewModel::deleteManualAlarm,
                onClearError = alarmViewModel::clearManualAlarmError,
                onNavigateToSettings = {
                    showManualAlarmSheet = false
                    onShowShiftConfig()
                }
            )
        }
    }
}
