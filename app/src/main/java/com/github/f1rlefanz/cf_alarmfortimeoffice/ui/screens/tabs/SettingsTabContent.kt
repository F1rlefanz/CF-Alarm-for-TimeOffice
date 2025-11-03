package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.worker.BackgroundTokenRefreshWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    authViewModel: AuthViewModel,
    shiftViewModel: ShiftViewModel? = null,
    onShowShiftConfig: () -> Unit,
    onShowCalendarSelection: () -> Unit
) {
    val context = LocalContext.current
    val authState by authViewModel.uiState.collectAsState()
    val shiftState by (shiftViewModel?.uiState?.collectAsState()
        ?: MutableStateFlow(null).collectAsState())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        // Fehleranzeige am Anfang des Contents
        authState.error?.let { errorMessage ->
            ErrorMessage(
                message = errorMessage,
                onDismiss = { authViewModel.clearError() }
            )
        }

        Text(
            "Einstellungen",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Kalender-Einstellungen
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowCalendarSelection
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Kalender auswählen",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Wähle die Kalender für Schichterkennung",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null
                )
            }
        }

        // 🔧 STUFE 2 FIX: Calendar Authorization/Re-Authorization Card
        // Shows when user needs to authorize Calendar OR re-authorize due to invalid token
        if (authState.userAuth.isSignedIn &&
            (!authState.calendarOps.hasSelectedCalendars || authState.calendarOps.needsTokenReauthorization)) {

            // Dynamically adapt card appearance based on state
            val needsReauth = authState.calendarOps.needsTokenReauthorization
            val cardColor = if (needsReauth) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
            val iconColor = if (needsReauth) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
            val titleText = if (needsReauth) {
                "Kalender-Zugriff erneuern"
            } else {
                "Calendar-Berechtigung"
            }
            val descriptionText = if (needsReauth) {
                "Token abgelaufen - Kalender-Zugriff erneut autorisieren"
            } else {
                "Kalender-Zugriff autorisieren für Schichterkennung"
            }
            val icon = if (needsReauth) {
                Icons.Default.Refresh
            } else {
                Icons.Default.Security
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // CRITICAL FIX: Pass Activity context for permission dialog
                    val activity = context as? android.app.Activity
                    authViewModel.requestCalendarAuthorization(activity)
                },
                colors = CardDefaults.cardColors(
                    containerColor = cardColor
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = iconColor
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.titleMedium,
                            color = iconColor
                        )
                        Text(
                            descriptionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = iconColor
                        )
                    }
                    if (authState.calendarOps.calendarsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = iconColor
                        )
                    } else {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = iconColor
                        )
                    }
                }
            }
        }

        // Schicht-Konfiguration
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowShiftConfig
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Work,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Schicht-Konfiguration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Definiere Schichttypen und Erkennungsmuster",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null
                )
            }
        }

        // 🔄 OPTIMIERT: Sync-Intervall OBEN (primäre Funktion)
        shiftViewModel?.let { viewModel ->
            shiftState?.currentShiftConfig?.let { config ->
                var intervalExpanded by remember { mutableStateOf(false) }
                val intervalOptions = listOf(1, 3, 6, 12)

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SpacingConstants.PADDING_CARD),
                        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sync-Intervall",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Wie oft automatisch synchronisieren",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            ExposedDropdownMenuBox(
                                expanded = intervalExpanded,
                                onExpandedChange = { intervalExpanded = !intervalExpanded }
                            ) {
                                OutlinedTextField(
                                    value = "Alle ${config.syncIntervalHours}h",
                                    onValueChange = { },
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                                        .width(120.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = intervalExpanded,
                                    onDismissRequest = { intervalExpanded = false }
                                ) {
                                    intervalOptions.forEach { hours ->
                                        DropdownMenuItem(
                                            text = { Text("Alle ${hours}h") },
                                            onClick = {
                                                viewModel.updateSyncInterval(hours)
                                                
                                                // Reschedule WorkManager with new interval
                                                BackgroundTokenRefreshWorker.scheduleTokenRefresh(
                                                    context, 
                                                    hours.toLong()
                                                )
                                                
                                                intervalExpanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                        )
                                    }
                                }
                            }
                        }
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        
                        // Dynamischer Text - passt sich an Dropdown an
                        Column {
                            Text(
                                "Automatische Online-Synchronisation",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Bei Internetverbindung alle ${config.syncIntervalHours} Stunden im Hintergrund",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 📦 Offline-Puffer UNTEN (Fallback-Funktion)
        shiftViewModel?.let { viewModel ->
            shiftState?.currentShiftConfig?.let { config ->
                var expanded by remember { mutableStateOf(false) }
                val daysOptions = listOf(7, 14, 21, 30, 60)

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SpacingConstants.PADDING_CARD),
                        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Offline-Puffer",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Events für die nächsten ${config.daysAhead} Tage vorgeladen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded }
                            ) {
                                OutlinedTextField(
                                    value = "${config.daysAhead} Tage",
                                    onValueChange = { },
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    modifier = Modifier
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                                        .width(120.dp)
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    daysOptions.forEach { days ->
                                        DropdownMenuItem(
                                            text = { Text("$days Tage") },
                                            onClick = {
                                                viewModel.updateDaysAhead(days)
                                                expanded = false
                                            },
                                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                        )
                                    }
                                }
                            }
                        }
                        
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        
                        // Fallback-Info
                        Column {
                            Text(
                                "Offline Backup",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Bei fehlender Internetverbindung als Fallback",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL))


        // Account-Bereich
        Text(
            "Account",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Abmelden
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { authViewModel.signOut() }, // MEMORY LEAK FIX: No context parameter needed
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Abmelden",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
