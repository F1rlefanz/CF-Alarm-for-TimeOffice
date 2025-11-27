package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

// PHASE 2 CLEANUP: ShiftViewModel import removed (unused parameter)
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    authViewModel: AuthViewModel,
    onShowShiftConfig: () -> Unit,
    onShowCalendarSelection: () -> Unit
) {
    val context = LocalContext.current
    val authState by authViewModel.uiState.collectAsState()

    // PHASE 1 MIGRATION: Get maintenance time as state
    var lastMaintenanceTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        // Load maintenance time in background
        lastMaintenanceTime = AlarmMaintenanceService.getLastMaintenanceTime(context)
    }

    // Refresh maintenance time periodically
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30000) // Update every 30 seconds
            lastMaintenanceTime = AlarmMaintenanceService.getLastMaintenanceTime(context)
        }
    }

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
                    Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }

        // 🔧 STUFE 2 FIX: Calendar Authorization/Re-Authorization Card
        // Shows when user needs to authorize Calendar OR re-authorize due to invalid token
        if (authState.userAuth.isSignedIn &&
            (!authState.calendarOps.hasSelectedCalendars || authState.calendarOps.needsTokenReauthorization)
        ) {

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
                    Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }

        // 🔄 Phase 1: Sync-Intervall and Offline-Puffer cards removed
        // Fixed 6h maintenance interval via AlarmMaintenanceService
        // Fixed 14-day lookahead with 7-day buffer check

        // ⚠️ Phase 2: Battery Status, Last Sync & OEM Warnings

        // 1. Last Maintenance Status Card
        val currentTime = System.currentTimeMillis()
        val timeSinceLastMaintenance = if (lastMaintenanceTime > 0) {
            currentTime - lastMaintenanceTime
        } else {
            -1L
        }

        val lastMaintenanceText = when {
            lastMaintenanceTime == 0L -> "Noch nie ausgeführt"
            timeSinceLastMaintenance < 0 -> "Unbekannt"
            timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(1) -> {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(timeSinceLastMaintenance)
                "Vor $minutes Minuten"
            }

            timeSinceLastMaintenance < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(timeSinceLastMaintenance)
                "Vor $hours Stunden"
            }

            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(timeSinceLastMaintenance)
                "Vor $days Tagen"
            }
        }

        val statusColor = when {
            lastMaintenanceTime == 0L -> MaterialTheme.colorScheme.tertiary
            timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(12) -> MaterialTheme.colorScheme.primary
            timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(24) -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                    Icons.Default.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = statusColor
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Letzter Sync",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        lastMaintenanceText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )
                    if (timeSinceLastMaintenance > TimeUnit.HOURS.toMillis(24)) {
                        Text(
                            "⚠️ Langer Zeitraum - bitte prüfen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 2. Battery Exemption Warning
        if (!BatteryOptimizationHelper.isExempted(context)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                onClick = {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        BatteryOptimizationHelper.requestExemption(activity)
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "⚠️ Akku-Optimierung aktiv",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "App nicht von Akku-Optimierung ausgenommen. Wecker könnten ausfallen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 3. OEM-specific warning card (persistent)
        val oemType = BatteryOptimizationHelper.getOEMType()
        if (BatteryOptimizationHelper.shouldShowOEMWarning(oemType)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                onClick = {
                    try {
                        val helpUrl = BatteryOptimizationHelper.getOEMHelpURL(oemType)
                        val intent = Intent(Intent.ACTION_VIEW, helpUrl.toUri())
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Ignore if URL cannot be opened
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${BatteryOptimizationHelper.getOEMDisplayName(oemType)}-Geräte erfordern Extra-Schritte",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            "Für maximale Zuverlässigkeit weitere Einstellungen prüfen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
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
