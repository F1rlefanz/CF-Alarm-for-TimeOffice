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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerViewModel

/**
 * Dimmer-Tab: Wellness-Wind-down und schicht-gekoppelte Regeln ein-/ausschalten sowie
 * Intensitaet/Waerme einstellen. Der Status des Bedienungshilfen-Dienstes samt Pflicht-
 * Offenlegung liegt im Status-Tab (DimmerAccessibilityCard) — hier gibt es nur die
 * Feature-Bedienung plus einen Vorschau-Knopf zum Ausprobieren.
 */
@Composable
fun DimmerTabContent(
    onNavigateToRules: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DimmerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

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

        // Wellness (Wind-down) – eigener Bereich mit seinem Wind-down-Regler
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dimmer_wellness),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.dimmer_wellness_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.wellnessEnabled,
                            onCheckedChange = { viewModel.setWellnessEnabled(it) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.dimmer_winddown_label, state.windDownMinutes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.windDownMinutes.toFloat(),
                        onValueChange = { viewModel.setWindDownMinutes((it / 15).toInt() * 15) },
                        valueRange = DimOverlayPrefs.WINDDOWN_MIN_LIMIT.toFloat()..DimOverlayPrefs.WINDDOWN_MAX_LIMIT.toFloat()
                    )

                    // Darstellung der Wellness-Abdunkelung (Schicht-Regeln bringen eigene Werte mit)
                    Text(
                        text = stringResource(R.string.dimmer_appearance_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.dimmer_strength_label, state.strength),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.strength.toFloat(),
                        onValueChange = { viewModel.setStrength(it.toInt()) },
                        valueRange = 0f..DimOverlayPrefs.STRENGTH_MAX.toFloat()
                    )
                    Text(
                        text = stringResource(R.string.dimmer_warmth_label, state.warmth),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = state.warmth.toFloat(),
                        onValueChange = { viewModel.setWarmth(it.toInt()) },
                        valueRange = 0f..DimOverlayPrefs.WARMTH_MAX.toFloat()
                    )
                    Spacer(Modifier.height(4.dp))
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

        // Schicht-Regeln – eigener Bereich
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dimmer_rules),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.dimmer_rules_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.rulesEnabled,
                            onCheckedChange = { viewModel.setRulesEnabled(it) }
                        )
                    }
                    Button(
                        onClick = onNavigateToRules,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_manage_rules))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
