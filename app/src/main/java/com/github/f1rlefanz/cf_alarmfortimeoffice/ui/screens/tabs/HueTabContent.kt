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
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.AnimatedDiscoveryCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.rememberLocalNetworkPermissionGate
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import kotlinx.coroutines.launch

/**
 * Aktionen des Hue-Tabs, die den lokalen Netzwerkzugriff brauchen (ACCESS_LOCAL_NETWORK, ab
 * API 37 erzwungen). Bewusst ein Enum und kein Lambda: Nur so ueberlebt die gemerkte Absicht
 * einen Activity-Neuaufbau, waehrend der Berechtigungsdialog offen steht (siehe
 * [rememberLocalNetworkPermissionGate]).
 *
 * LIGHT_TEST gehoert dazu, seit der Lampentest ueber dasselbe Tor laeuft: Er sass zweimal in
 * dieser Datei direkt am ViewModel und blieb deshalb als einziger Knopf des Tabs ungegatet.
 */
internal enum class PendingHueAction { VALIDATE, DISCOVER, PAIR, LIGHT_TEST }

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
    // collectAsStateWithLifecycle, nicht collectAsState: Beide Flows sind reine Anzeige-Zustaende
    // dieses Tabs. Mit der Lifecycle-Variante ruht das Abo unterhalb von STARTED, statt im
    // Hintergrund weiter Recompositions auszuloesen; beim Zurueckkehren liefert der StateFlow
    // sofort seinen aktuellen Wert. Nichts hier loest einen Seiteneffekt aus, der im Hintergrund
    // laufen muesste - der Berechtigungsdialog haelt die Activity ohnehin auf STARTED.
    val uiState by hueViewModel.uiState.collectAsStateWithLifecycle()
    val discoveryStatus by hueViewModel.discoveryStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // EINE Abbildung Absicht -> ViewModel-Aufruf, hinter dem gemeinsamen Berechtigungstor: beide
    // Wege (Berechtigung liegt schon vor ODER wurde gerade im Dialog erteilt) laufen durch
    // dieselbe Stelle und koennen deshalb nicht auseinanderlaufen.
    val gate = rememberLocalNetworkPermissionGate<PendingHueAction>(
        onMessage = { hueViewModel.setError(it) }
    ) { action, bridgeId ->
        when (action) {
            PendingHueAction.VALIDATE -> hueViewModel.validateBridgeConnection()
            PendingHueAction.DISCOVER -> hueViewModel.discoverBridges()
            PendingHueAction.LIGHT_TEST -> hueViewModel.runLightTest()
            PendingHueAction.PAIR -> {
                // Die Bridge wird ueber ihre Id wieder aus der Trefferliste geholt - das ViewModel
                // ueberlebt einen Activity-Neuaufbau waehrend des Dialogs ohnehin. Ist sie
                // inzwischen weg, sagen wir das; ein stilles No-op waere hier die schlechtere
                // Antwort.
                val bridge = uiState.discoveredBridges.firstOrNull { it.id == bridgeId }
                if (bridge != null) {
                    hueViewModel.setupBridge(bridge)
                } else {
                    hueViewModel.setError(
                        "Die Bridge steht nicht mehr in der Trefferliste. Bitte erneut suchen und dann verbinden."
                    )
                }
            }
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

        // Kein separates "Verbindung verloren"-Banner mehr: Es feuerte bei bridgeIp != null und
        // DISCONNECTED/ERROR - und genau dann steht direkt darunter die Statuskarte mit
        // "Nicht verbunden" samt rotem Fehler-Icon. Die Banner-Bedingung ist eine Teilmenge der
        // Karten-Bedingung, das war also IMMER eine Doppelung (gemeldet 18.07.2026, drei
        // Warnsymbole fuer eine Aussage). Der einzige echte Mehrwert des Banners - die Folge
        // ("Lichtaktionen koennten ausfallen") - steht jetzt in der Karte selbst.

        // Connected Features & Next Steps (show first when connected)
        uiState.bridgeConnectionInfo?.let { connectionInfo ->
            if (connectionInfo.isConnected) {
                // Regel-Ziele, die auf DIESER Bridge nicht existieren (Konfigurations-Import,
                // Bridge-Tausch). Der Tab ist der Einstieg in den Hue-Bereich; stuende der Hinweis
                // nur in der Regel-Liste, muesste der Nutzer erst dorthin finden, um von einem
                // Problem zu erfahren, das er nicht vermutet. Erscheint nur nach einer echten
                // Antwort der Bridge.
                if (uiState.unresolvedTargets.isNotEmpty()) {
                    item {
                        UnresolvedTargetsCard(
                            affectedRules = uiState.unresolvedTargets.map { it.ruleName }.distinct(),
                            targetCount = uiState.unresolvedTargets.size,
                            onNavigateToSettings = onNavigateToSettings
                        )
                    }
                }

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
                            onTestConnection = { gate(PendingHueAction.LIGHT_TEST) }
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
                                    gate(PendingHueAction.LIGHT_TEST)
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
        //
        // Die Karte beantwortet genau eine Frage: "Habe ich schon eine Bridge?". Als Einstieg
        // ist das richtig - aber sobald die Suche laeuft oder Treffer da sind, beantwortet der
        // Rest des Screens sie besser, und "Noch keine Bridge eingerichtet" steht nur noch als
        // Wiederholung ueber dem eigentlichen Geschehen ("Suche unten nach deiner Bridge",
        // waehrend genau das laengst passiert).
        //
        // War dagegen schon einmal eine Bridge gekoppelt (bridgeIp gesetzt), bleibt die Karte
        // immer sichtbar: dann ist "nicht verbunden" ein echtes Problem samt "Pruefen"-Knopf,
        // kein Ausgangszustand. Dieselbe Unterscheidung trifft das Warn-Banner oben.
        val neverConfigured = uiState.bridgeConnectionInfo?.bridgeIp == null
        val discoveryStarted =
            discoveryStatus?.isComplete == false || uiState.discoveredBridges.isNotEmpty()

        if (!neverConfigured || !discoveryStarted) {
            item {
                BridgeConnectionStatusCard(
                    connectionInfo = uiState.bridgeConnectionInfo,
                    onValidateConnection = { gate(PendingHueAction.VALIDATE) }
                )
            }
        }

        // Bridge Discovery & Connection Section (only when not connected)
        //
        // Waehrend ein Scan LAEUFT, sagt die animierte "Netzwerk-Scan"-Karte oben bereits, dass
        // gesucht wird - die "Bridge-Suche"-Karte mit dem "Bridges suchen"-Knopf daneben ist dann
        // nur Doppelung (und einen zweiten Scan anzustossen, waehrend einer laeuft, ergibt keinen
        // Sinn). Deshalb blenden wir sie aus, SOLANGE gescannt wird UND noch nichts gefunden ist.
        // Sobald der Scan fertig ist, kommt sie zurueck: leer -> "Bridges suchen" fuer einen neuen
        // Versuch, Treffer -> die Bridge-Liste. Trudeln waehrend des Scans schon Bridges ein, zeigen
        // wir sie sofort. Die Bedingung ist das Spiegelbild der AnimatedDiscoveryCard oben.
        val discoveryRunning = discoveryStatus?.isComplete == false
        if (!discoveryRunning || uiState.discoveredBridges.isNotEmpty()) {
            item {
                BridgeDiscoveryCard(
                    discoveredBridges = uiState.discoveredBridges,
                    onDiscoverBridges = { gate(PendingHueAction.DISCOVER) },
                    onConnectToBridge = { bridge ->
                        gate(PendingHueAction.PAIR, bridge.id)
                    }
                )
            }
        }

        // Add some bottom padding for better UX
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UnresolvedTargetsCard(
    affectedRules: List<String>,
    targetCount: Int,
    onNavigateToSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // dekorativ: die Ueberschrift daneben sagt den Zustand aus
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "$targetCount Regel-Ziel(e) auf dieser Bridge unbekannt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Betroffen: ${affectedRules.joinToString()}. Die Lampen stammen von einer anderen " +
                    "Bridge – diese Regeln schalten dafür nichts. Was sich über den Namen " +
                    "wiederfinden ließ, wurde bereits automatisch zugeordnet.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onNavigateToSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Regeln prüfen")
            }
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
                    // dekorativ: "Bridge verbunden" steht direkt daneben
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
                        // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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
                    CompactOutlinedButton(
                        onClick = onNavigateToRuleConfig,
                        text = "Neue Regel",
                        icon = Icons.Default.Add,
                        modifier = Modifier.weight(1f)
                    )

                    CompactOutlinedButton(
                        onClick = onTestConnection,
                        text = "Test",
                        icon = Icons.Default.FlashOn,
                        modifier = Modifier.weight(1f)
                    )
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
            // dekorativ: Wert und Beschriftung stehen direkt darunter
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

    val isConnected = connectionInfo?.isConnected == true

    // TONALITÄT: Vor der Ersteinrichtung ist "nicht verbunden" kein Fehler, sondern der normale
    // Ausgangspunkt - der Nutzer hatte noch gar keine Gelegenheit, eine Bridge zu koppeln. Rotes
    // Fehler-Icon dafür liest sich, als hätte er etwas falsch gemacht. Erst wenn schon einmal eine
    // Bridge gekoppelt war (bridgeIp gesetzt), ist "nicht verbunden" wirklich ein Problem.
    // Dieselbe Unterscheidung trifft schon das Warn-Banner oben in HueTabContent.
    val neverConfigured = connectionInfo?.bridgeIp == null

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
                    imageVector = when {
                        isConnected -> Icons.Default.CheckCircle
                        neverConfigured -> Icons.Default.Lightbulb
                        else -> Icons.Default.Error
                    },
                    // dekorativ: der Zustand steht als Text daneben ("Verbunden" / "Noch keine
                    // Bridge eingerichtet" / "Nicht verbunden"), das Icon spiegelt ihn nur
                    contentDescription = null,
                    tint = when {
                        isConnected -> MaterialTheme.colorScheme.success
                        neverConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(32.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isConnected -> "Verbunden"
                            neverConfigured -> "Noch keine Bridge eingerichtet"
                            else -> "Nicht verbunden"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    when {
                        neverConfigured -> Text(
                            text = "Suche unten nach deiner Hue-Bridge, um Lichtregeln für deine Alarme anzulegen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Die Folge, nicht nur der Zustand: Das ist der Satz, der frueher als
                        // eigenes Banner darueber stand.
                        !isConnected -> Text(
                            text = "Lichtaktionen für Alarme könnten ausfallen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    connectionInfo?.bridgeIp?.let { ip ->
                        Text(
                            text = ip,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                // "Pruefen" gehoert an JEDE gekoppelte Bridge, nicht nur an eine verbundene:
                // Bei "Nicht verbunden" ist ein erneuter Versuch der einzige sinnvolle Schritt -
                // dort fehlte der Knopf, waehrend er bei "Verbunden" (wo nichts zu reparieren ist)
                // stand. Der Kommentar oben in HueTabContent versprach ihn schon.
                if (!neverConfigured) {
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
                            // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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
    onConnectToBridge: (HueBridge) -> Unit
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

            // ZUSTANDSLOGIK: Vor der Suche ist "Bridges suchen" die eine, ganzbreite Aktion -
            // damit bricht die Beschriftung auch nicht mehr um ("Bridges / suchen"), was vorher
            // passierte, weil sich der Knopf die Zeile mit "Löschen" teilte.
            //
            // Nach einem Treffer ist die gefundene Bridge der Star: Die Suche wird zur leisen
            // Nebenaktion "Erneut suchen" unter der Liste. Der frühere "Löschen"-Knopf ist weg -
            // er warf nur die Trefferliste weg, was eine neue Suche ohnehin tut, und "Löschen"
            // ohne Bezugswort las sich, als würde er die Bridge oder die Regeln entfernen.
            if (discoveredBridges.isEmpty()) {
                Button(
                    onClick = onDiscoverBridges,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bridges suchen")
                }
            } else {
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

                TextButton(
                    onClick = onDiscoverBridges,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Erneut suchen")
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
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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
                    // dekorativ: "🎉 Erfolgreich verbunden!" steht direkt darunter
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
                        // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Ohne style-Override: titleMedium (16sp) machte die Beschriftung breiter als
                    // den Knopf und trieb sie in die zweite Zeile. Die Standard-Button-Typografie
                    // passt in eine Zeile - und der Knopf ist als einziger ganzbreiter Primär-
                    // Knopf ohnehin schon deutlich genug hervorgehoben.
                    Text("Erste Hue-Regel erstellen")
                }

                // Secondary Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CompactOutlinedButton(
                        onClick = onNavigateToSettings,
                        text = "Einstellungen",
                        icon = Icons.Default.Settings,
                        modifier = Modifier.weight(1f)
                    )

                    CompactOutlinedButton(
                        onClick = {
                            // OPTIMIZATION: Manual health check + refresh lights
                            scope.launch {
                                bridgeManager.forceHealthCheck()
                            }
                            onTestConnection()
                        },
                        text = "Test",
                        icon = Icons.Default.Lightbulb,
                        modifier = Modifier.weight(1f)
                    )
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
