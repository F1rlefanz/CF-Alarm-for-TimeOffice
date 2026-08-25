package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.AnimatedDiscoveryCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.rememberLocalNetworkPermissionGate
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue.BridgeConnectionStatusCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue.BridgeDiscoveryCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue.ConnectedFeaturesCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue.ConnectedManagementCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue.UnresolvedTargetsCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel

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
    // Vor dem Tor aufgeloest: im Callback ist stringResource nicht erlaubt, und
    // context.getString() waere dort nicht konfigurationssicher (Lint).
    val bridgeWegText = stringResource(R.string.hue_tab_bridge_gone)

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
                        bridgeWegText
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
                text = stringResource(R.string.hue_tab_title),
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
                                        text = stringResource(R.string.hue_tab_loading),
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
