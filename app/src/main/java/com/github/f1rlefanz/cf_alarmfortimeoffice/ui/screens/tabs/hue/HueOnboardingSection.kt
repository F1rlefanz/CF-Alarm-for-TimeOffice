package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import kotlinx.coroutines.launch

/**
 * Die Karten fuer den Weg ZUR Bridge: Verbindungszustand, Suche und Kopplung.
 *
 * Aus `HueTabContent` ausgelagert (reine Verschiebung). Welche davon wann sichtbar ist,
 * entscheidet weiterhin allein der Aufrufer.
 */
@Composable
internal fun BridgeConnectionStatusCard(
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
internal fun BridgeDiscoveryCard(
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
internal fun BridgeConnectionCard(
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
