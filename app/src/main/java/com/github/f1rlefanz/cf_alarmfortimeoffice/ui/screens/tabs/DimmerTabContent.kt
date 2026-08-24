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
import androidx.compose.ui.text.font.FontWeight
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
 * WARUM SO KARG: bis v1.33.x standen hier drei gleichrangige Karten (Wellness/Wind-down,
 * Nacht-Standard, Schicht-Regeln) mit 13 Bedienelementen, obwohl zwei davon Sonderfaelle waren,
 * die sich seit dem Ende-Anker „Weckzeit, spaetestens" als gewoehnliche Regel ausdruecken lassen.
 * Drei Schalter, die einander ueberlagern, sind fuer den Nutzer nicht auseinanderzuhalten - und
 * jeder von ihnen konnte das Dimmen aus einem anderen Grund unterdruecken. Jetzt gilt: Schalter an
 * = die Regeln greifen, Schalter aus = nichts dimmt. Alles Weitere - wann, wie dunkel, wie warm -
 * steht in der jeweiligen Regel und nirgends sonst.
 *
 * Die globalen Verdunkelung-/Waerme-Regler sind mit der Wellness-Quelle entfallen: es gibt keine
 * Fenster mehr, die sie faerben wuerden. Jede Regel bringt ihre eigenen Werte mit (Regel-Editor),
 * und dort sitzt auch die kurze Probe-Verdunkelung.
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
    // collectAsStateWithLifecycle, nicht collectAsState: der Flow ist
    // `stateIn(SharingStarted.WhileSubscribed(5_000))` ueber mehrere DataStore-Quellen.
    // `collectAsState` sammelt weiter, solange die Composition lebt - also auch, waehrend die App
    // im Hintergrund ist; der 5s-Timeout lief dadurch NIE ab und war eine Attrappe. Mit der
    // Lifecycle-Variante endet das Abo beim Verlassen des Vordergrunds und der Timeout wirkt.
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
