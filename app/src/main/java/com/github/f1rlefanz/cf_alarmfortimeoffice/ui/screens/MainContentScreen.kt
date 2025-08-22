package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.HomeTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.StatusTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.SettingsTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.HueTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContentScreen(
    authViewModel: AuthViewModel,
    calendarViewModel: CalendarViewModel,
    shiftViewModel: ShiftViewModel,
    alarmViewModel: AlarmViewModel,
    mainViewModel: MainViewModel,
    viewModelFactory: ViewModelFactory,
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    onShowShiftConfig: () -> Unit,
    onShowCalendarSelection: () -> Unit,
    onShowEventList: () -> Unit,
    onShowHueRuleConfig: () -> Unit,
    onShowHueSettings: () -> Unit,
    onTestHueConnection: () -> Unit
) {
    val authState by authViewModel.uiState.collectAsState()
    val calendarState by calendarViewModel.uiState.collectAsState()
    val shiftState by shiftViewModel.uiState.collectAsState()
    val alarmState by alarmViewModel.uiState.collectAsState()
    val skipState by alarmViewModel.skipState.collectAsState()
    val manualAlarmState by alarmViewModel.manualAlarmState.collectAsState() // NEU

    Scaffold(
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = selectedTab == MainTab.HOME,
                    onClick = { onSelectedTabChange(MainTab.HOME) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Info, contentDescription = "Status") },
                    label = { Text("Status") },
                    selected = selectedTab == MainTab.STATUS,
                    onClick = { onSelectedTabChange(MainTab.STATUS) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") },
                    label = { Text("Einstellungen") },
                    selected = selectedTab == MainTab.SETTINGS,
                    onClick = { onSelectedTabChange(MainTab.SETTINGS) }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Lightbulb, contentDescription = "Hue") },
                    label = { Text("Hue") },
                    selected = selectedTab == MainTab.HUE,
                    onClick = { onSelectedTabChange(MainTab.HUE) }
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
                        manualAlarmState = manualAlarmState, // NEU
                        onRefresh = { mainViewModel.forceRefreshCalendarEvents() },
                        onSkipNextAlarm = alarmViewModel::skipNextAlarm,
                        onCancelSkip = alarmViewModel::cancelSkip,
                        onSelectManualAlarmDate = alarmViewModel::selectManualAlarmDate, // NEU
                        onSelectManualAlarmShift = alarmViewModel::selectManualAlarmShift, // NEU
                        onCreateManualAlarm = alarmViewModel::createManualAlarm, // NEU
                        onDeleteManualAlarm = alarmViewModel::deleteManualAlarm, // NEU
                        onClearManualAlarmError = alarmViewModel::clearManualAlarmError, // NEU
                        onShowEventList = onShowEventList
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
                        shiftViewModel = shiftViewModel,
                        onShowShiftConfig = onShowShiftConfig,
                        onShowCalendarSelection = onShowCalendarSelection
                    )
                }
                MainTab.HUE -> {
                    HueTabContent(
                        viewModelFactory = viewModelFactory,
                        onNavigateToRuleConfig = onShowHueRuleConfig,
                        onNavigateToSettings = onShowHueSettings
                    )
                }
            }
        }
    }
}
