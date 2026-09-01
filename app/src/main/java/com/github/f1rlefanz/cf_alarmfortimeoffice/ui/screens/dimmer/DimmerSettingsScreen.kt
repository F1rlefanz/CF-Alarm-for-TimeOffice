package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Sagt es geradeheraus, wenn der Hauptschalter aus ist: Regeln in dieser Liste tun dann nichts.
 *
 * WARUM ALS KARTE UND MIT KNOPF, nicht als Fussnote: Ohne den Hinweis legt der Nutzer hier - von
 * Hand oder per Schnellstart - eine Regel an, die garantiert wirkungslos bleibt, und nichts sagt
 * es ihm. Das ist "angezeigt, wirkt nicht" aus der anderen Richtung. Ein reiner Hinweis waere die
 * halbe Loesung; wer das Problem sieht, soll es an derselben Stelle beheben koennen, statt in
 * einen anderen Reiter geschickt zu werden.
 */
@Composable
private fun HauptschalterAusKarte(onEinschalten: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dimmer_rules_master_off_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.dimmer_rules_master_off_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(onClick = onEinschalten, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dimmer_rules_master_off_action))
            }
        }
    }
}

/**
 * Der Schnellstart: drei Vorlagen, die je eine ECHTE Regel anlegen.
 *
 * WARUM ER OBEN IN DER REGELLISTE SITZT und nicht im Reiter: Was er erzeugt, ist eine Regel -
 * also gehoert er an den Ort, an dem Regeln entstehen und zu sehen sind. Der Nutzer tippt, die
 * Regel erscheint in derselben Liste, und der Editor der neuen Regel geht sofort auf. Der
 * abgeloeste Nacht-Standard war ein Schalter an ganz anderer Stelle, dessen Wirkung in dieser
 * Liste NIE auftauchte; das ist der eine Unterschied, auf den es hier ankommt.
 *
 * Die beiden schichtbezogenen Vorlagen fragen zuerst nach der Schicht ([onWaehleSchicht]) - ohne
 * Schichtname gibt es keine Regel, statt einer toten Regel auf leerem Muster.
 */
@Composable
private fun SchnellstartKarte(
    onNachtDimmen: () -> Unit,
    onWaehleSchicht: (DimmerRulesViewModel.SchnellstartVorlage) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dimmer_quickstart_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.dimmer_quickstart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            VorlagenKnopf(
                label = stringResource(R.string.dimmer_quickstart_night),
                hinweis = stringResource(R.string.dimmer_quickstart_night_hint),
                onClick = onNachtDimmen
            )
            VorlagenKnopf(
                label = stringResource(R.string.dimmer_quickstart_nightshift),
                hinweis = stringResource(R.string.dimmer_quickstart_nightshift_hint),
                onClick = {
                    onWaehleSchicht(DimmerRulesViewModel.SchnellstartVorlage.NACHTDIENST_RHYTHMUS)
                }
            )
            VorlagenKnopf(
                label = stringResource(R.string.dimmer_quickstart_exclude),
                hinweis = stringResource(R.string.dimmer_quickstart_exclude_hint),
                onClick = {
                    onWaehleSchicht(DimmerRulesViewModel.SchnellstartVorlage.SCHICHT_AUSNEHMEN)
                }
            )
        }
    }
}

/** Ein Vorlagen-Knopf mit der Erklaerung darunter - der Knopf allein sagt nicht, was entsteht. */
@Composable
private fun VorlagenKnopf(label: String, hinweis: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // fillMaxWidth statt Chip-Reihe: die Mindesthoehe des Buttons haelt das 48dp-Touchziel,
        // und die drei Beschriftungen brauchen die volle Breite ohne Umbruch mitten im Wort.
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
        Text(
            text = hinweis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Schicht-Auswahl fuer die beiden schichtbezogenen Vorlagen. Ohne erkannte Schichten gibt es
 * nichts auszuwaehlen - dann sagt der Dialog, was zu tun ist, statt eine leere Liste zu zeigen.
 */
@Composable
private fun SchichtAuswahlDialog(
    schichtNamen: List<String>,
    @StringRes regelNameRes: Int,
    onWaehlen: (schichtName: String, regelName: String) -> Unit,
    onAbbrechen: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text(stringResource(R.string.dimmer_quickstart_pick_shift)) },
        text = {
            if (schichtNamen.isEmpty()) {
                Text(stringResource(R.string.dimmer_quickstart_no_shifts))
            } else {
                // verticalScroll statt LazyColumn: der Dialog gibt seinem Inhalt keine feste
                // Hoehe, und eine LazyColumn in unbeschraenkter Hoehe wirft zur Laufzeit.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    schichtNamen.forEach { name ->
                        // Der Regelname wird HIER in der Komposition aufgeloest, nicht im
                        // Klick-Handler ueber den Context: nur so folgt er einer
                        // Konfigurationsaenderung (Sprachwechsel) - und nur so bleibt der
                        // Nutzertext im Bildschirm statt im ViewModel.
                        val regelName = stringResource(regelNameRes, name)
                        TextButton(
                            onClick = { onWaehlen(name, regelName) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAbbrechen) {
                Text(stringResource(R.string.dimmer_quickstart_cancel))
            }
        }
    )
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

    val schichtNamen by viewModel.shiftNames.collectAsStateWithLifecycle()
    val nachtRegelName = stringResource(R.string.dimmer_quickstart_night_rulename)
    // Ohne diesen Zustand koennte der Nutzer hier Regeln bauen, die garantiert nichts tun -
    // "angelegt, wirkt nicht". Siehe DimmerRulesViewModel.dimmerAn.
    val dimmerAn by viewModel.dimmerAn.collectAsStateWithLifecycle()

    // Welche Vorlage auf ihre Schicht wartet; null = kein Dialog offen.
    var vorlageBrauchtSchicht by remember {
        mutableStateOf<DimmerRulesViewModel.SchnellstartVorlage?>(null)
    }

    // Eine angelegte Regel MUSS der Nutzer zu Gesicht bekommen - sonst waere der Schnellstart
    // wieder das unsichtbare Verhalten, das der Umbau gerade abgeschafft hat. Deshalb geht der
    // Editor der neuen Regel sofort auf; abgemeldet wird das Signal danach, damit ein spaeteres
    // Neuzusammensetzen den Editor nicht erneut aufreisst.
    val neueRegelId by viewModel.neueRegelId.collectAsStateWithLifecycle()
    LaunchedEffect(neueRegelId) {
        neueRegelId?.let { id ->
            viewModel.neueRegelGeoeffnet()
            onEditRule(id)
        }
    }

    // Der Schnellstart legt KEINE zweite aktive Regel auf demselben Muster an - sie waere tot
    // (die Auswahl nimmt den ersten Treffer). Ohne diesen Hinweis waere der Knopf aber schlicht
    // wirkungslos, und "nichts passiert" ist keine bessere Auskunft als eine tote Regel. Deshalb
    // benennt der Dialog die vorhandene Regel und bietet den einen Weg an, der wirklich hilft:
    // sie zu oeffnen und dort zu aendern.
    val blockiert by viewModel.schnellstartBlockiert.collectAsStateWithLifecycle()
    blockiert?.let { info ->
        val unbenannt = stringResource(R.string.dimmer_rule_unnamed)
        AlertDialog(
            onDismissRequest = { viewModel.schnellstartHinweisGesehen() },
            title = { Text(stringResource(R.string.dimmer_quickstart_exists_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dimmer_quickstart_exists_body,
                        info.regelName.ifBlank { unbenannt }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.schnellstartHinweisGesehen()
                        onEditRule(info.regelId)
                    }
                ) { Text(stringResource(R.string.dimmer_quickstart_exists_open)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.schnellstartHinweisGesehen() }) {
                    Text(stringResource(R.string.dimmer_quickstart_cancel))
                }
            }
        )
    }

    vorlageBrauchtSchicht?.let { vorlage ->
        SchichtAuswahlDialog(
            schichtNamen = schichtNamen,
            regelNameRes = when (vorlage) {
                DimmerRulesViewModel.SchnellstartVorlage.NACHTDIENST_RHYTHMUS ->
                    R.string.dimmer_quickstart_nightshift_rulename
                else -> R.string.dimmer_quickstart_exclude_rulename
            },
            onWaehlen = { schichtName, regelName ->
                vorlageBrauchtSchicht = null
                viewModel.legeVorlageAn(vorlage, regelName, schichtName)
            },
            onAbbrechen = { vorlageBrauchtSchicht = null }
        )
    }

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
            // Unten mehr Freiraum: der FAB liegt AUSSERHALB des Scaffold-innerPadding und
            // schwebte sonst ueber der letzten Regel - bei genau einer Regel also ueber der
            // einzigen. Derselbe Wert und derselbe Grund wie in HomeTabContent.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!dimmerAn) {
                item {
                    HauptschalterAusKarte(onEinschalten = { viewModel.schalteDimmerEin() })
                }
            }

            item {
                SchnellstartKarte(
                    onNachtDimmen = {
                        viewModel.legeVorlageAn(
                            DimmerRulesViewModel.SchnellstartVorlage.NACHT_DIMMEN,
                            nachtRegelName
                        )
                    },
                    onWaehleSchicht = { vorlage -> vorlageBrauchtSchicht = vorlage }
                )
            }
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
