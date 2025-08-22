package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ViewModelFactory
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Hue Bridge Setup Screen - Wizard for Bridge Discovery & Connection
 * 
 * Provides step-by-step bridge setup process with user guidance.
 * Handles discovery, authentication, and initial configuration.
 * 
 * Features:
 * - Automated bridge discovery (online + local network)
 * - Interactive bridge selection
 * - Link button authentication guidance
 * - Connection validation and testing
 * - Error handling with retry mechanisms
 * 
 * Architecture:
 * - Wizard-style flow with clear steps
 * - Reactive state management with HueViewModel
 * - Material Design 3 components
 * - Accessibility-friendly UI
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueBridgeSetupScreen(
    viewModelFactory: ViewModelFactory,
    onNavigateBack: () -> Unit,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hueViewModel: HueViewModel = viewModel(factory = viewModelFactory)
    val uiState by hueViewModel.uiState.collectAsState()
    val discoveryStatus by hueViewModel.discoveryStatus.collectAsState()
    
    // Setup step state
    var currentStep by remember { mutableIntStateOf(1) }
    var selectedBridge by remember { mutableStateOf<HueBridge?>(null) }
    var isLinkButtonPressed by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Bridge Setup", 
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
            ) {
                // Progress indicator
                SetupProgressIndicator(
                    currentStep = currentStep,
                    totalSteps = 4
                )
                
                // Error display
                uiState.error?.let { error ->
                    ErrorMessage(
                        message = error,
                        onDismiss = { hueViewModel.clearError() }
                    )
                }
                
                // Step content
                when (currentStep) {
                    1 -> DiscoveryStep(
                        discoveredBridges = uiState.discoveredBridges,
                        discoveryStatus = discoveryStatus,
                        isLoading = uiState.isLoading,
                        onDiscoverBridges = { hueViewModel.discoverBridges() },
                        onBridgeSelected = { bridge ->
                            selectedBridge = bridge
                            currentStep = 2
                        }
                    )
                    
                    2 -> LinkButtonStep(
                        selectedBridge = selectedBridge,
                        onLinkButtonPressed = { 
                            isLinkButtonPressed = true
                            currentStep = 3
                        },
                        onBackToDiscovery = { currentStep = 1 }
                    )
                    
                    3 -> AuthenticationStep(
                        selectedBridge = selectedBridge,
                        isLinkButtonPressed = isLinkButtonPressed,
                        isLoading = uiState.isLoading,
                        onAuthenticate = { bridge ->
                            hueViewModel.setupBridge(bridge)
                        },
                        onRetry = { currentStep = 2 }
                    )
                    
                    4 -> CompletionStep(
                        bridgeConnectionInfo = uiState.bridgeConnectionInfo,
                        onComplete = onSetupComplete,
                        onTestConnection = { hueViewModel.validateBridgeConnection() }
                    )
                }
            }
            
            // Loading overlay
            if (uiState.isLoading && currentStep == 3) {
                LoadingScreen()
            }
        }
    }
}

/**
 * Setup Progress Indicator
 */
@Composable
private fun SetupProgressIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
        ) {
            Text(
                text = "Setup Progress",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            LinearProgressIndicator(
                progress = { currentStep.toFloat() / totalSteps },
                modifier = Modifier.fillMaxWidth()
            )
            
            Text(
                text = "Step $currentStep of $totalSteps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Step labels
            val stepLabels = listOf("Discovery", "Link Button", "Authentication", "Complete")
            Text(
                text = stepLabels.getOrNull(currentStep - 1) ?: "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Step 1: Bridge Discovery
 */
@Composable
private fun DiscoveryStep(
    discoveredBridges: List<HueBridge>,
    discoveryStatus: DiscoveryStatus?,
    isLoading: Boolean,
    onDiscoverBridges: () -> Unit,
    onBridgeSelected: (HueBridge) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Discover Hue Bridges",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Text(
                        text = "We'll search for Philips Hue Bridges on your network. Make sure your bridge is connected and powered on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Discovery status
                    discoveryStatus?.let { status ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                            ) {
                                Text(
                                    text = status.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                
                                if (!status.isComplete && status.progress > 0) {
                                    LinearProgressIndicator(
                                        progress = { status.progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    
                    Button(
                        onClick = onDiscoverBridges,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                        Text(if (isLoading) "Searching..." else "Start Discovery")
                    }
                }
            }
        }
        
        // Discovered bridges
        if (discoveredBridges.isNotEmpty()) {
            item {
                Text(
                    text = "Found ${discoveredBridges.size} bridge(s):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            items(discoveredBridges) { bridge ->
                BridgeSelectionCard(
                    bridge = bridge,
                    onSelect = { onBridgeSelected(bridge) }
                )
            }
        }
    }
}

/**
 * Bridge Selection Card
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeSelectionCard(
    bridge: HueBridge,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bridge.name ?: "Hue Bridge",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Select bridge"
                )
            }
            
            Text(
                text = "IP Address: ${bridge.internalipaddress}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            bridge.id?.let { id ->
                Text(
                    text = "Bridge ID: $id",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = "Tap to select this bridge",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Step 2: Link Button Instructions
 */
@Composable
private fun LinkButtonStep(
    selectedBridge: HueBridge?,
    onLinkButtonPressed: () -> Unit,
    onBackToDiscovery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Press Link Button",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Bridge info
            selectedBridge?.let { bridge ->
                Text(
                    text = "Selected Bridge: ${bridge.name ?: "Hue Bridge"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = "IP: ${bridge.internalipaddress}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Instructions
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    
                    Text(
                        text = "Important Step!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    
                    Text(
                        text = "1. Locate the round link button on top of your Hue Bridge\n\n2. Press and release the link button once\n\n3. You have 30 seconds to complete authentication\n\n4. The button will light up when pressed",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Button(
                    onClick = onLinkButtonPressed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                    Text("I pressed the link button")
                }
                
                TextButton(
                    onClick = onBackToDiscovery,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                    Text("Back to discovery")
                }
            }
        }
    }
}

/**
 * Step 3: Authentication
 */
@Composable
private fun AuthenticationStep(
    selectedBridge: HueBridge?,
    isLinkButtonPressed: Boolean,
    isLoading: Boolean,
    onAuthenticate: (HueBridge) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Authenticating",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "Connecting to bridge...",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Button(
                    onClick = { 
                        selectedBridge?.let { onAuthenticate(it) }
                    },
                    enabled = isLinkButtonPressed && selectedBridge != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                    Text("Start Authentication")
                }
                
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back to link button step")
                }
            }
        }
    }
}

/**
 * Step 4: Setup Completion
 */
@Composable
private fun CompletionStep(
    bridgeConnectionInfo: BridgeConnectionInfo?,
    onComplete: () -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Success header
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Text(
                text = "Setup Complete!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            // Connection details
            bridgeConnectionInfo?.let { info ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
                        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        Text(
                            text = "Connection Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        info.bridgeIp?.let { ip ->
                            Text(text = "Bridge IP: $ip")
                        }
                        
                        info.bridgeName?.let { name ->
                            Text(text = "Bridge Name: $name")
                        }
                        
                        Text(
                            text = "Status: ${if (info.isConnected) "Connected" else "Disconnected"}",
                            color = if (info.isConnected) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                    Text("Finish Setup")
                }
                
                OutlinedButton(
                    onClick = onTestConnection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                    Text("Test Connection")
                }
            }
        }
    }
}
