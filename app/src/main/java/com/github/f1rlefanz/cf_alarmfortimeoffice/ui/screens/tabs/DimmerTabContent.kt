package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAccessibilityService
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerViewModel

/**
 * Dimmer-Tab: Schicht-basiertes Screen-Dimming ein-/ausschalten, Intensitaet/Waerme/Wind-down
 * einstellen und den Bedienungshilfen-Dienst aktivieren. Enthaelt die prominente
 * Zweck-/Datenschutz-Offenlegung (Play-Console-Pflicht fuer AccessibilityService).
 */
@Composable
fun DimmerTabContent(
    modifier: Modifier = Modifier,
    viewModel: DimmerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var accessibilityActive by remember { mutableStateOf(DimAccessibilityService.isRunning()) }
    var showDisclosure by remember { mutableStateOf(false) }

    val accessibilityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Nach Rueckkehr aus den Einstellungen: Status neu lesen + Zeitplan sync.
        accessibilityActive = DimAccessibilityService.isRunning()
        viewModel.syncSchedule()
    }

    fun openAccessibilitySettings() {
        runCatching {
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(stringResource(R.string.dimmer_disclosure_title)) },
            text = { Text(stringResource(R.string.dimmer_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    openAccessibilitySettings()
                }) { Text(stringResource(R.string.dimmer_open_accessibility)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text(stringResource(R.string.dimmer_disclosure_understood))
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.dimmer_header),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dimmer_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Bedienungshilfen-Status
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (accessibilityActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val diag = DimAccessibilityService.diagnostics()
                    Text(
                        text = when {
                            !accessibilityActive -> stringResource(R.string.dimmer_status_inactive)
                            diag.isBlank() -> stringResource(R.string.dimmer_status_active_plain)
                            else -> stringResource(R.string.dimmer_status_active, diag)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (!accessibilityActive) {
                        Text(
                            text = stringResource(R.string.dimmer_disclosure_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { showDisclosure = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dimmer_open_accessibility))
                        }
                    } else {
                        // Overlay einmal kurz zeigen – testbar ohne Schicht/Alarm.
                        Text(
                            text = stringResource(R.string.dimmer_preview_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { viewModel.previewDim() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dimmer_preview))
                        }
                    }
                }
            }
        }

        // Master-Schalter
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dimmer_enable),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = state.featureEnabled,
                            onCheckedChange = { viewModel.setEnabled(it) }
                        )
                    }

                    // Verdunkelung
                    Text(
                        text = stringResource(R.string.dimmer_strength_label, state.strength),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.strength.toFloat(),
                        onValueChange = { viewModel.setStrength(it.toInt()) },
                        valueRange = 0f..DimOverlayPrefs.STRENGTH_MAX.toFloat()
                    )

                    // Waerme
                    Text(
                        text = stringResource(R.string.dimmer_warmth_label, state.warmth),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.warmth.toFloat(),
                        onValueChange = { viewModel.setWarmth(it.toInt()) },
                        valueRange = 0f..DimOverlayPrefs.WARMTH_MAX.toFloat()
                    )

                    // Wind-down-Dauer
                    Text(
                        text = stringResource(R.string.dimmer_winddown_label, state.windDownMinutes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.windDownMinutes.toFloat(),
                        onValueChange = { viewModel.setWindDownMinutes((it / 15).toInt() * 15) },
                        valueRange = DimOverlayPrefs.WINDDOWN_MIN_LIMIT.toFloat()..DimOverlayPrefs.WINDDOWN_MAX_LIMIT.toFloat()
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
