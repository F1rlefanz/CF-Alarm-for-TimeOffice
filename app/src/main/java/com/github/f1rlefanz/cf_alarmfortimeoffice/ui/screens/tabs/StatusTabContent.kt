package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState

@Composable
fun StatusTabContent(
    authState: AuthState,
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    alarmState: AlarmUiState,
    calendarViewModel: CalendarViewModel?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        Text(
            "System-Status",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Auth Status
        StatusCard(
            title = "Authentifizierung",
            isOk = authState.isSignedIn,
            details = if (authState.isSignedIn) {
                "Angemeldet als ${authState.userEmail ?: "Unbekannt"}"
            } else {
                "Nicht angemeldet"
            }
        )

        // Kalender Status
        StatusCard(
            title = "Kalender",
            isOk = calendarState.selectedCalendarIds.isNotEmpty(),
            details = when {
                calendarState.selectedCalendarIds.isEmpty() -> "Kein Kalender ausgewählt"
                calendarState.availableCalendars.isEmpty() -> "Keine Kalender verfügbar"
                else -> "${calendarState.selectedCalendarIds.size} Kalender ausgewählt"
            }
        )

        // Schicht-Konfiguration Status
        StatusCard(
            title = "Schicht-Konfiguration",
            isOk = shiftState.currentShiftConfig != null,
            details = if (shiftState.currentShiftConfig != null) {
                "${shiftState.currentShiftConfig.definitions.size} Schichttypen definiert"
            } else {
                "Keine Konfiguration verfügbar"
            }
        )

        // Schicht-Erkennung Status
        StatusCard(
            title = "Schicht-Erkennung",
            isOk = shiftState.recognizedShifts.isNotEmpty(),
            details = when {
                shiftState.recognizedShifts.isEmpty() -> "Keine Schichten erkannt"
                else -> "${shiftState.recognizedShifts.size} Schichten erkannt"
            }
        )

        // Alarm Status
        StatusCard(
            title = "Alarme",
            isOk = alarmState.hasActiveAlarms,
            details = when {
                !alarmState.hasActiveAlarms -> "Keine aktiven Alarme"
                else -> "${alarmState.activeAlarms.size} Alarme gesetzt"
            }
        )

        // Debug-Informationen
        if (calendarState.events.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    Text(
                        "Debug-Informationen",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Events geladen: ${calendarState.events.size}")
                    Text("Schichten erkannt: ${shiftState.recognizedShifts.size}")
                    Text("Nächste Schicht: ${shiftState.upcomingShift?.shiftType?.displayName ?: "Keine"}")
                }
            }
        }
        
        // Cache-Statistiken und Offline-Status
        CacheStatusCard(calendarViewModel = calendarViewModel)
    }
}

@Composable
private fun StatusCard(
    title: String,
    isOk: Boolean,
    details: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOk) 
                MaterialTheme.colorScheme.secondaryContainer
            else 
                MaterialTheme.colorScheme.errorContainer
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
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isOk)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun CacheStatusCard(calendarViewModel: CalendarViewModel?) {
    val context = LocalContext.current
    var cacheStats by remember { mutableStateOf("Cache-Statistiken laden...") }
    val isOffline by remember { 
        derivedStateOf { !isNetworkAvailable(context) }
    }
    
    // Nur einmal laden, nicht bei jeder Recomposition
    LaunchedEffect(calendarViewModel) {
        calendarViewModel?.let { viewModel ->
            try {
                viewModel.getCacheStats()
                cacheStats = "Cache-Statistiken in Log ausgegeben"
            } catch (e: Exception) {
                cacheStats = "Cache-Statistiken nicht verfügbar"
            }
        } ?: run {
            cacheStats = "Cache-Statistiken nicht verfügbar (kein ViewModel)"
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOffline) 
                MaterialTheme.colorScheme.errorContainer
            else 
                MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isOffline) Icons.Default.CloudOff else Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = if (isOffline)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onTertiaryContainer
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isOffline) "Offline-Modus" else "Cache-Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isOffline) "Offline - verwende gespeicherte Daten" else "Online - Cache aktiv",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Cache Actions
                if (calendarViewModel != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        IconButton(
                            onClick = { 
                                calendarViewModel.getCacheStats()
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Cache-Stats aktualisieren",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider()
            
            // Cache Statistics
            Text(
                "Cache-Details:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                cacheStats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Cache Actions Row
            if (calendarViewModel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    OutlinedButton(
                        onClick = { calendarViewModel.clearEventCache() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cache leeren")
                    }
                    
                    Button(
                        onClick = { calendarViewModel.refreshData(forceRefresh = true) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Neu laden")
                    }
                }
            }
        }
    }
}

/**
 * Überprüft die Netzwerkverbindung
 */
private fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } else {
        @Suppress("DEPRECATION") // Legacy Android support: activeNetworkInfo deprecated but no alternative for API < 23
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        activeNetworkInfo?.isConnected == true
    }
}
