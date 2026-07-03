package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorSeverity
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.AnimatedDiscoveryCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueConnectionHealth
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import kotlinx.coroutines.launch

/**
 * Fixed Hue Tab Content with proper scrolling and layout
 * Resolved: UI overflow, scrolling issues, layout problems, missing navigation
 *
 * HILT MIGRATION: Now receives HueViewModel directly instead of ViewModelFactory
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueTabContent(
    hueViewModel: HueViewModel,
    onNavigateToRuleConfig: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by hueViewModel.uiState.collectAsState()
    val discoveryStatus by hueViewModel.discoveryStatus.collectAsState()
    val context = LocalContext.current
    var pendingHueAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingHueAction?.invoke()
        } else {
            hueViewModel.setError("Lokaler Netzwerkzugriff verweigert. Bitte in den Android-Einstellungen erteilen.")
        }
        pendingHueAction = null
    }

    fun requireLocalNetworkPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
                != PackageManager.PERMISSION_GRANTED
        ) {
            pendingHueAction = action
            localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else {
            action()
        }
    }

    // Use LazyColumn for proper scrolling and performance
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), // Single padding point
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                text = "Philips Hue Integration",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Error Display
        uiState.error?.let { error ->
            item {
                ErrorMessage(
                    message = error,
                    onDismiss = { hueViewModel.clearError() }
                )
            }
        }

        // UX FIX (E): reactive warning banner when a previously-paired bridge becomes
        // unreachable (DISCONNECTED/ERROR from HueBridgeConnectionManager.connectionStatus).
        // Only shown once a bridge was actually configured (bridgeIp != null) so this doesn't
        // fire during first-time onboarding, before any pairing happened.
        if (uiState.bridgeConnectionInfo?.bridgeIp != null &&
            (uiState.connectionHealth == HueConnectionHealth.DISCONNECTED || uiState.connectionHealth == HueConnectionHealth.ERROR)
        ) {
            item {
                ErrorMessage(
                    message = "⚠️ Verbindung zur Hue-Bridge verloren – Lichtaktionen für Alarme könnten ausfallen",
                    severity = ErrorSeverity.WARNING
                )
            }
        }

        // Connected Features & Next Steps (show first when connected)
        uiState.bridgeConnectionInfo?.let { connectionInfo ->
            if (connectionInfo.isConnected) {
                // Show appropriate interface based on rules
                if (uiState.scheduleRules.isNotEmpty()) {
                    // Rules exist: Show management overview instead of auto-navigating
                    item {
                        ConnectedManagementCard(
                            rulesCount = uiState.scheduleRules.size,
                            enabledRulesCount = uiState.scheduleRules.count { it.enabled },
                            lightsCount = uiState.lightTargets.lights.size,
                            onNavigateToRuleConfig = onNavigateToRuleConfig,
                            onNavigateToSettings = onNavigateToSettings,
                            onTestConnection = { hueViewModel.runLightTest() }
                        )
                    }
                } else {
                    // Show loading indicator briefly while rules are being loaded
                    if (uiState.isLoading) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Lade Hue-Konfiguration...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    } else {
                        // If no rules exist and not loading, show success screen for first-time setup
                        item {
                            ConnectedFeaturesCard(
                                onNavigateToRuleConfig = onNavigateToRuleConfig,
                                onNavigateToSettings = onNavigateToSettings,
                                onTestConnection = {
                                    // Meaningful test: flashes the lights + shows a Toast,
                                    // instead of a silent light-list refresh.
                                    hueViewModel.runLightTest()
                                }
                            )
                        }
                    }
                }
                // When connected, skip the rest of the setup UI
                return@LazyColumn
            }
        }

        // Discovery Card (only show when discovering and not connected)
        discoveryStatus?.let { currentDiscoveryStatus ->
            if (!currentDiscoveryStatus.isComplete) {
                item {
                    AnimatedDiscoveryCard(
                        discoveryStatus = currentDiscoveryStatus,
                        onCancel = { hueViewModel.clearDiscoveredBridges() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Connection Status Section (only when not connected)
        item {
            BridgeConnectionStatusCard(
                connectionInfo = uiState.bridgeConnectionInfo,
                onValidateConnection = { requireLocalNetworkPermission { hueViewModel.validateBridgeConnection() } }
            )
        }

        // Bridge Discovery & Connection Section (only when not connected)
        item {
            BridgeDiscoveryCard(
                discoveredBridges = uiState.discoveredBridges,
                onDiscoverBridges = { requireLocalNetworkPermission { hueViewModel.discoverBridges() } },
                onConnectToBridge = { bridge ->
                    requireLocalNetworkPermission { hueViewModel.setupBridge(bridge) }
                },
                onClearBridges = { hueViewModel.clearDiscoveredBridges() }
            )
        }

        // Add some bottom padding for better UX
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ConnectedManagementCard(
    rulesCount: Int,
    enabledRulesCount: Int,
    lightsCount: Int,
    onNavigateToRuleConfig: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTestConnection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.success,
                    modifier = Modifier.size(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bridge verbunden",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$enabledRulesCount von $rulesCount Regeln aktiv",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickStatItem(
                    icon = Icons.Default.Schedule,
                    label = "Regeln",
                    value = rulesCount.toString()
                )
                QuickStatItem(
                    icon = Icons.Default.Lightbulb,
                    label = "Lichter",
                    value = lightsCount.toString()
                )
                QuickStatItem(
                    icon = Icons.Default.CheckCircle,
                    label = "Aktiv",
                    value = enabledRulesCount.toString()
                )
            }

            // Action Buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary action - Manage rules
                Button(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Regeln verwalten")
                }

                // Secondary actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToRuleConfig,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Neue Regel")
                    }

                    OutlinedButton(
                        onClick = onTestConnection,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test")
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BridgeConnectionStatusCard(
    connectionInfo: BridgeConnectionInfo?,
    onValidateConnection: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeManager = remember { HueBridgeConnectionManager.getInstance(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (connectionInfo?.isConnected == true)
                        Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (connectionInfo?.isConnected == true)
                        MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (connectionInfo?.isConnected == true) "Verbunden" else "Nicht verbunden",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    connectionInfo?.bridgeIp?.let { ip ->
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                if (connectionInfo?.isConnected == true) {
                    OutlinedButton(
                        onClick = {
                            // OPTIMIZATION: Manual health check via bridge manager
                            scope.launch {
                                bridgeManager.forceHealthCheck()
                            }
                            onValidateConnection()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Prüfen")
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeDiscoveryCard(
    discoveredBridges: List<HueBridge>,
    onDiscoverBridges: () -> Unit,
    onConnectToBridge: (HueBridge) -> Unit,
    onClearBridges: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section Header
            Text(
                text = "Bridge-Suche",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Discovery Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDiscoverBridges,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bridges suchen")
                }

                if (discoveredBridges.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearBridges
                    ) {
                        Text("Löschen")
                    }
                }
            }

            // Discovered Bridges List
            if (discoveredBridges.isNotEmpty()) {
                Text(
                    text = "${discoveredBridges.size} Bridge${if (discoveredBridges.size != 1) "s" else ""} gefunden:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                discoveredBridges.forEach { bridge ->
                    BridgeConnectionCard(
                        bridge = bridge,
                        onConnect = { onConnectToBridge(bridge) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BridgeConnectionCard(
    bridge: HueBridge,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bridge Info
            Text(
                text = bridge.name ?: "Philips Hue Bridge",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = bridge.internalipaddress,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            // Connection Instructions
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "🔗 Link-Taste an der Bridge drücken, dann 'Jetzt verbinden' antippen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }

            // Connect Button
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Jetzt verbinden")
            }
        }
    }
}

@Composable
private fun ConnectedFeaturesCard(
    onNavigateToRuleConfig: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onTestConnection: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeManager = remember { HueBridgeConnectionManager.getInstance(context) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp), // Slightly more padding for celebration
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Celebration Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.success,
                    modifier = Modifier.size(48.dp) // Bigger celebration icon
                )
                Text(
                    text = "🎉 Erfolgreich verbunden!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }

            // Feature Description
            Text(
                text = "Ihre Philips Hue Bridge ist jetzt mit der App verbunden. Sie können Lichtregeln für Ihre Alarme erstellen!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // Quick Start Actions
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Was möchten Sie als nächstes tun?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // Primary Action - Create Rule
                Button(
                    onClick = onNavigateToRuleConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Erste Hue-Regel erstellen",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // Secondary Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Einstellungen")
                    }

                    OutlinedButton(
                        onClick = {
                            // OPTIMIZATION: Manual health check + refresh lights
                            scope.launch {
                                bridgeManager.forceHealthCheck()
                            }
                            onTestConnection()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test")
                    }
                }
            }

            // Help Text
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "💡 Tipp: Erstellen Sie für jede Schicht eine eigene Licht-Regel mit verschiedenen Farben und Helligkeiten.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
