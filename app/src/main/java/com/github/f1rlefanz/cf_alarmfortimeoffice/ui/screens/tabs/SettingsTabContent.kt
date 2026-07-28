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
import androidx.compose.material.icons.filled.DoNotDisturbOn
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    authViewModel: AuthViewModel,
    onShowShiftConfig: () -> Unit,
    onShowCalendarSelection: () -> Unit,
    onShowDndSettings: () -> Unit
) {
    val context = LocalContext.current
    val authState by authViewModel.uiState.collectAsState()

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
            // CTA-Karte: weiße Fläche mit rotem Akzent (statt getöntem Container).
            val cardColor = MaterialTheme.colorScheme.surface
            val iconColor = MaterialTheme.colorScheme.primary
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
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            descriptionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // Nicht stören (DND) - eigener, getrennter Bereich (nicht im Dimmer-Tab)
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowDndSettings
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DoNotDisturbOn,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nicht stören",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Schaltet Nicht-stören automatisch nach Schicht",
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

        // Akku-Status & OEM-Warnungen (die "Letzter Sync"-Anzeige liegt jetzt im Status-Tab)

        // 1. Battery Exemption Warning
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
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                        tint = MaterialTheme.colorScheme.warning
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${BatteryOptimizationHelper.getOEMDisplayName(oemType)}-Geräte erfordern Extra-Schritte",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Für maximale Zuverlässigkeit weitere Einstellungen prüfen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.warning
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
