package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.rememberLocalNetworkPermissionGate
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeKnopf
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.hue.LichtregelnWeg
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel

/**
 * Aktionen dieses Bildschirms, die den lokalen Netzwerkzugriff brauchen (ACCESS_LOCAL_NETWORK,
 * ab API 37 erzwungen). Enum statt Lambda, damit die Absicht einen Activity-Neuaufbau waehrend
 * des offenen Berechtigungsdialogs ueberlebt - siehe [rememberLocalNetworkPermissionGate].
 */
internal enum class HueSettingsNetzAktion { VALIDATE, LIGHT_TEST }

/**
 * Die Seite „Hue-Einstellungen“: Bridge-Karte (pruefen, testen, vergessen), Zahlen und der Weg zu den
 * Lichtregeln - die legt man seit #255 nur noch am Schichttyp an (SPEC E8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueSettingsScreen(
    hueViewModel: HueViewModel,
    onNavigateBack: () -> Unit,
    onShowShiftConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by hueViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        hueViewModel.refreshRules()
        hueViewModel.refreshLightTargets()
    }

    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Beide netzbeduerftigen Knoepfe dieses Bildschirms (Verbindung pruefen, Lampentest) laufen
    // durch dasselbe Tor wie der Hue-Tab. Ohne das scheiterten sie ab Android 17 mit einer
    // generischen Netzwerkmeldung, ohne dass je der Systemdialog erschien.
    val gate = rememberLocalNetworkPermissionGate<HueSettingsNetzAktion>(
        onMessage = { hueViewModel.setError(it) }
    ) { action, _ ->
        when (action) {
            HueSettingsNetzAktion.VALIDATE -> hueViewModel.validateBridgeConnection()
            HueSettingsNetzAktion.LIGHT_TEST -> hueViewModel.runLightTest()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hue_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hue_back))
                    }
                },
                actions = {
                    HilfeKnopf(HilfeThema.ANLEITUNG_HUE)
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.error?.let { error ->
                item {
                    ErrorMessage(
                        message = error,
                        onDismiss = { hueViewModel.clearError() }
                    )
                }
            }

            item {
                BridgeStatusCard(
                    connectionInfo = uiState.bridgeConnectionInfo,
                    onValidate = { gate(HueSettingsNetzAktion.VALIDATE) },
                    onTest = { gate(HueSettingsNetzAktion.LIGHT_TEST) },
                    onForgetBridge = { hueViewModel.forgetBridge() }
                )
            }
            
            item {
                StatsCard(
                    rulesCount = uiState.scheduleRules.size,
                    enabledCount = uiState.scheduleRules.count { it.enabled },
                    lightsCount = uiState.lightTargets.lights.size,
                    groupsCount = uiState.lightTargets.groups.size,
                    scenesCount = uiState.lightTargets.scenes.size
                )
            }
            
            // Lichtregeln legt man am Schichttyp an (#255, SPEC E8) - hier nur der Weg dorthin.
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        LichtregelnWeg(onShowShiftConfig)
                    }
                }
            }
        }
    }
}

@Composable
private fun BridgeStatusCard(
    connectionInfo: com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo?,
    onValidate: () -> Unit,
    onTest: () -> Unit,
    onForgetBridge: () -> Unit
) {
    // Confirmation dialog before actually disconnecting/forgetting the bridge.
    var showForgetDialog by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connectionInfo?.isConnected == true)
                MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    // dekorativ: der Text daneben sagt den Zustand bereits aus
                    // ("Verbunden mit ..." bzw. "Nicht verbunden")
                    if (connectionInfo?.isConnected == true) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (connectionInfo?.isConnected == true) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hue_bridge_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (connectionInfo?.isConnected == true) stringResource(R.string.hue_bridge_connected, connectionInfo.bridgeIp.orEmpty())
                        else stringResource(R.string.hue_bridge_disconnected),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (connectionInfo?.isConnected != true) {
                        Text(
                            stringResource(R.string.hue_bridge_disconnected_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            KnopfZeile(modifier = Modifier.fillMaxWidth()) {
                CompactOutlinedButton(
                    onClick = onValidate,
                    text = stringResource(R.string.hue_bridge_check),
                    icon = Icons.Default.Refresh
                )
                CompactOutlinedButton(
                    onClick = onTest,
                    text = stringResource(R.string.hue_bridge_test),
                    icon = Icons.Default.FlashOn
                )
            }

            if (connectionInfo?.bridgeIp != null) {
                OutlinedButton(
                    onClick = { showForgetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
                    Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.hue_bridge_forget))
                }
            }
        }
    }

    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.hue_bridge_forget_title)) },
            text = {
                Text(
                    stringResource(R.string.hue_bridge_forget_body)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetDialog = false
                        onForgetBridge()
                    }
                ) {
                    Text(stringResource(R.string.hue_bridge_forget_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetDialog = false }) {
                    Text(stringResource(R.string.hue_cancel))
                }
            }
        )
    }
}

@Composable
private fun StatsCard(
    rulesCount: Int,
    enabledCount: Int,
    lightsCount: Int,
    groupsCount: Int,
    scenesCount: Int
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.hue_stats_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // Vier Spalten nur, wenn "verfügbar" darin ganz Platz hat - bei 320 dp mit Schrift 1,3
            // liefen die Beschriftungen ineinander ("verfügbarverfügbarverfügba|r", Emulator
            // 14.10.2026). Darunter 2 x 2, jede Kachel gleich breit.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val jeZeile = if (maxWidth / LocalDensity.current.fontScale < VIER_KACHELN_AB) 2 else 4
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = jeZeile,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val kachel = Modifier.weight(1f)
                    StatItem(Icons.Default.Schedule, stringResource(R.string.hue_stats_rules), "$enabledCount/$rulesCount", stringResource(R.string.hue_stats_active), kachel)
                    StatItem(Icons.Default.Lightbulb, stringResource(R.string.hue_stats_lights), lightsCount.toString(), stringResource(R.string.hue_stats_available), kachel)
                    StatItem(Icons.Default.Group, stringResource(R.string.hue_stats_groups), groupsCount.toString(), stringResource(R.string.hue_stats_available), kachel)
                    StatItem(Icons.Default.Movie, stringResource(R.string.hue_stats_scenes), scenesCount.toString(), stringResource(R.string.hue_stats_available), kachel)
                }
            }
        }
    }
}

/** Ab dieser Breite (dp / Schriftskalierung) passen die vier Kacheln nebeneinander. */
private val VIER_KACHELN_AB = 230.dp

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    label: String, 
    value: String, 
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // dekorativ: Label und Wert stehen als Text direkt darunter
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
