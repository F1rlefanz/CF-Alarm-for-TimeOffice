package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel

/** Anzeige-Label für ein [DimRule.shiftPattern] (Sondermuster übersetzt, sonst der Name). */
@Composable
internal fun dimPatternLabel(pattern: String): String = when (pattern) {
    DimRule.SHIFT_FREE -> stringResource(R.string.dimmer_pattern_free)
    DimRule.SHIFT_UNIVERSAL -> stringResource(R.string.dimmer_pattern_universal)
    else -> pattern
}

/**
 * Der Hinweis an einer Regel, die an einzelnen Tagen hinter einer anderen zurueckstehen muss.
 *
 * WARUM ES DEN TEXT GEBEN MUSS: An einem Tag mit mehreren Diensten (Fruehdienst + anschliessende
 * Rufbereitschaft) gilt genau EINE Regel - die des Dienstes, der als erster weckt. Die uebrigen
 * standen bis dahin unveraendert als aktiv in dieser Liste und wirkten trotzdem nicht; sichtbar
 * war das nur im Logcat, das kein Nutzer liest. Der Text nennt deshalb die WIRKUNG ("wirkt an
 * diesen Tagen nicht"), den Grund in Alltagssprache (ein anderer Dienst beginnt frueher) und einen
 * Ausweg, den es wirklich gibt: die konkurrierende Regel ausschalten.
 *
 * Kein Fachbegriff, keine Datumsliste - die Zahl der Tage genuegt fuer die Frage "betrifft mich
 * das ueberhaupt", und die Vorschau ("Naechste Abschnitte") zeigt anschliessend den echten Verlauf.
 */
@Composable
private fun verdraengtHinweis(info: DimmerRulesViewModel.RegelVerdraengt): String {
    val unbenannt = stringResource(R.string.dimmer_rule_unnamed)
    val gewinner = info.gewinnerNamen
        .map { name -> "»${name.ifBlank { unbenannt }}«" }
        .let { if (it.isEmpty()) unbenannt else it.joinToString(" bzw. ") }
    val tage = if (info.tage == 1) {
        "an einem der nächsten ${DimWindowResolver.KONFLIKT_HORIZONT_TAGE} Tage"
    } else {
        "an ${info.tage} der nächsten ${DimWindowResolver.KONFLIKT_HORIZONT_TAGE} Tage"
    }
    return "Wirkt $tage nicht: dort beginnt ein anderer Dienst früher, und pro Tag gilt nur eine " +
        "Regel - dann greift $gewinner. Soll an diesen Tagen diese Regel gelten, schalte " +
        "$gewinner aus."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DimmerSettingsScreen(
    onNavigateBack: () -> Unit,
    onEditRule: (String) -> Unit,
    onCreateRule: () -> Unit,
    viewModel: DimmerRulesViewModel = hiltViewModel()
) {
    // collectAsStateWithLifecycle, nicht collectAsState: reine Listen-Anzeige ohne Seiteneffekt -
    // das Abo darf unterhalb von STARTED ruhen (Speichern/Loeschen laeuft ueber das ViewModel).
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    // Welche Regel an welchen Tagen hinter einer anderen zurueckstehen muss. Neu gerechnet, sobald
    // sich die Regeln aendern (Anlegen/Bearbeiten/Loeschen/Ein-Aus) - sonst zeigte die Karte nach
    // einer Aenderung den Stand von davor. Reine Auskunft, kein Seiteneffekt auf den Scheduler.
    val verdraengt by viewModel.verdraengteRegeln.collectAsStateWithLifecycle()
    LaunchedEffect(rules) { viewModel.refreshVerdraengteRegeln() }

    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                title = stringResource(R.string.dimmer_rules_title),
                onNavigateBack = onNavigateBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateRule) {
                // Eigenstaendig bedienbar und der einzige Inhalt des Knopfes: ohne Beschreibung
                // liest der Screenreader nur "Schaltflaeche". Benannt wird die AKTION.
                Icon(Icons.Default.Add, contentDescription = "Neue Regel anlegen")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rules.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.dimmer_rules_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                Card(
                    onClick = { onEditRule(rule.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.name.ifBlank { stringResource(R.string.dimmer_rule_unnamed) },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(
                                    R.string.dimmer_rule_subtitle,
                                    dimPatternLabel(rule.shiftPattern),
                                    rule.windows.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Nur wenn die Regel wirklich verdraengt wird - die Renderstelle und
                            // der Zustand dahinter existieren ausschliesslich fuereinander.
                            verdraengt[rule.id]?.let { info ->
                                Text(
                                    text = verdraengtHinweis(info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (!rule.enabled) {
                            Text(
                                text = stringResource(R.string.dimmer_rule_off),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
