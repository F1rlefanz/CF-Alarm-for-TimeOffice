package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerViewModel

/**
 * Dimmer-Tab: EIN Hauptschalter, und dahinter die einzige Fenster-Quelle, die es noch gibt - die
 * Regeln.
 *
 * Ein Schalter: an = die Regeln greifen, aus = nichts dimmt. Alles Weitere steht in der jeweiligen
 * Regel und nirgends sonst - Hergang Skill cfalarm-dimmer-und-dnd.
 *
 * Der Status des Bedienungshilfen-Dienstes samt Pflicht-Offenlegung liegt weiterhin im Status-Tab
 * (DimmerAccessibilityCard) - hier gibt es nur die Feature-Bedienung.
 */
@Composable
fun DimmerTabContent(
    onNavigateToRules: () -> Unit,
    onNavigateToPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DimmerViewModel = hiltViewModel()
) {
    // collectAsStateWithLifecycle: Grund wie im SettingsTab.
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.dimmer_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SwitchRow(
                        title = stringResource(R.string.dimmer_enabled),
                        description = stringResource(R.string.dimmer_enabled_hint),
                        checked = state.dimEnabled,
                        onCheckedChange = { viewModel.setDimEnabled(it) },
                        titleStyle = MaterialTheme.typography.titleMedium
                    )

                    HorizontalDivider()

                    Text(
                        text = stringResource(R.string.dimmer_rules_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onNavigateToRules,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_manage_rules))
                    }
                    Text(
                        text = stringResource(R.string.dimmer_preview_timeline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onNavigateToPreview,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_preview_timeline))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
