package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import androidx.annotation.StringRes
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerViewModel

/**
 * Dimmer-Tab: den Dimmer ein-/ausschalten, zu den schicht-gekoppelten Regeln abbiegen und
 * Intensitaet/Waerme einstellen. Der Status des Bedienungshilfen-Dienstes
 * samt Pflicht-Offenlegung liegt im Status-Tab (DimmerAccessibilityCard) — hier gibt es nur die
 * Feature-Bedienung plus einen Vorschau-Knopf zum Ausprobieren.
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
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onNavigateToPreview, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dimmer_preview_timeline))
                }
            }
        }

        // EIN Schalter, EINE Fenster-Quelle (die Schicht-Regeln). Die frueheren Karten
        // "Wellness/Wind-down" und "Nacht-Standard" sind mit ihren Quellen entfallen; der
        // eigentliche Neubau dieser Oberflaeche kommt in der naechsten Phase - hier steht
        // absichtlich nur so viel, dass die Bedienung nicht verschwindet.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SwitchRow(
                        title = stringResource(R.string.dimmer_rules),
                        description = stringResource(R.string.dimmer_rules_hint),
                        checked = state.dimEnabled,
                        onCheckedChange = { viewModel.setDimEnabled(it) },
                        titleStyle = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = onNavigateToRules,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_manage_rules))
                    }

                    Text(
                        text = stringResource(R.string.dimmer_appearance_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CommitOnReleaseSlider(
                        labelRes = R.string.dimmer_strength_label,
                        value = state.strength,
                        valueRange = 0f..DimOverlayPrefs.STRENGTH_MAX.toFloat(),
                        onCommit = { viewModel.setStrength(it) }
                    )
                    CommitOnReleaseSlider(
                        labelRes = R.string.dimmer_warmth_label,
                        value = state.warmth,
                        valueRange = 0f..DimOverlayPrefs.WARMTH_MAX.toFloat(),
                        onCommit = { viewModel.setWarmth(it) }
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


        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * PURE, TESTBAR: Rastet einen rohen Slider-Wert auf ein Vielfaches von [step] ein.
 *
 * `step <= 1` heisst "keine Rasterung" - dann bleibt es beim reinen Abschneiden, genau wie vorher
 * bei den Staerke-/Waerme-Reglern.
 */
internal fun quantizeToStep(raw: Float, step: Int): Int =
    if (step <= 1) raw.toInt() else (raw.toInt() / step) * step

/**
 * Regler, der seinen Wert erst beim LOSLASSEN nach oben meldet.
 *
 * WARUM: `Slider.onValueChange` feuert bei JEDEM Touch-Frame, nicht erst beim Loslassen. Haengt
 * daran direkt ein ViewModel-Setter, laeuft dessen komplette Kette dutzende Male pro
 * Reglerbewegung - beim Wind-down-Regler ist das je Frame ein DataStore-Schreiben PLUS
 * `DimScheduleUseCase.enable()`, das `windows()` zweimal komplett neu berechnet (alle Alarme, alle
 * Dimmer-Regeln), den Override-Mutex nimmt, das aktive Overlay schreibt, die
 * Korrektur-Notification neu postet/abraeumt und den exakten Tick-Alarm neu setzt. Sichtbar wurde
 * das als ruckelnder Regler, flackernde Notification und mehrere gleichzeitige
 * `applyCurrentState()`-Laeufe, die um denselben Override-Mutex konkurrieren.
 *
 * Der angezeigte Wert lebt deshalb lokal; nach oben geht genau EIN Wert pro Bewegung. Der
 * [LaunchedEffect] uebernimmt Aenderungen, die von AUSSEN kommen (ein anderer Setter, ein vom
 * Repository geklemmter Wert, Master-Pause) - waehrend des Ziehens schreibt niemand, er kommt dem
 * Nutzer also nicht in die Hand.
 */
@Composable
private fun CommitOnReleaseSlider(
    @StringRes labelRes: Int,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit,
    step: Int = 1
) {
    var pending by remember { mutableIntStateOf(value) }
    LaunchedEffect(value) { pending = value }

    Text(
        text = stringResource(labelRes, pending),
        style = MaterialTheme.typography.bodyMedium
    )
    Slider(
        value = pending.toFloat(),
        onValueChange = { pending = quantizeToStep(it, step) },
        onValueChangeFinished = { onCommit(pending) },
        valueRange = valueRange
    )
}
