package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.AnkerEditor
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.OffsetField
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.WeitereOptionenLogik
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.nachlaufBeispielText
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.NachlaufBisHellWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Composable
fun DimmerRuleConfigScreen(
    ruleId: String?,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    /** "Ort einstellen" aus dem Hinweis unter "Nachlauf nur, solange es dunkel ist" (#140). */
    onOrtEinstellen: () -> Unit,
    viewModel: DimmerRulesViewModel = hiltViewModel()
) {
    val shiftNames by viewModel.shiftNames.collectAsStateWithLifecycle()
    val ortBekannt by viewModel.ortBekannt.collectAsStateWithLifecycle()
    val tageslichtGeladen by viewModel.tageslichtGeladen.collectAsStateWithLifecycle()
    val existing = remember(ruleId) { viewModel.ruleById(ruleId) }

    // Beobachtet den Ort, solange der Editor sichtbar ist - nicht nur beim Eintritt: eine
    // Standortabfrage ueberlebt das Verlassen des Ort-Bildschirms und schreibt evtl. erst danach.
    LaunchedEffect(Unit) { viewModel.verfolgeTageslicht() }

    // rememberSaveable statt remember: das Formular muss den Umweg ueber "Ort einstellen" (der
    // Editor verlaesst dabei die Komposition) und eine Drehung ueberstehen - sonst sind
    // ungespeicherte Eingaben weg. Die Fensterliste reist als JSON ([FensterListeSaver]).
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var shiftPattern by rememberSaveable { mutableStateOf(existing?.shiftPattern ?: DimRule.SHIFT_UNIVERSAL) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var strength by rememberSaveable { mutableIntStateOf(existing?.strength ?: DimOverlayPrefs.DEFAULT_STRENGTH) }
    var warmth by rememberSaveable { mutableIntStateOf(existing?.warmth ?: DimOverlayPrefs.DEFAULT_WARMTH) }
    val windows = rememberSaveable(saver = FensterListeSaver) {
        mutableStateListOf<DimWindow>().apply { existing?.windows?.let { addAll(it) } }
    }

    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                title = stringResource(R.string.dimmer_rule_editor_title),
                onNavigateBack = onNavigateBack,
                hilfe = HilfeThema.ANLEITUNG_DIMMER
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dimmer_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                ShiftDropdown(
                    selected = shiftPattern,
                    shiftNames = shiftNames,
                    onSelect = { shiftPattern = it }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dimmer_rule_enabled),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            // Intensitaet pro Regel - gilt fuer die Fenster DIESER Regel.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.dimmer_rule_intensity),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.dimmer_rule_intensity_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.dimmer_strength_label, strength),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = strength.toFloat(),
                        onValueChange = { strength = it.toInt() },
                        valueRange = 0f..DimOverlayPrefs.STRENGTH_MAX.toFloat()
                    )
                    Text(
                        text = stringResource(R.string.dimmer_warmth_label, warmth),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = warmth.toFloat(),
                        onValueChange = { warmth = it.toInt() },
                        valueRange = 0f..DimOverlayPrefs.WARMTH_MAX.toFloat()
                    )
                    Text(
                        text = stringResource(R.string.dimmer_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { viewModel.previewRule(strength, warmth) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_preview))
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dimmer_rule_windows),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { windows.add(DimWindow()) }) {
                        Text(stringResource(R.string.dimmer_rule_add_window))
                    }
                }
            }

            if (windows.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.dimmer_rule_no_windows_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            itemsIndexed(windows) { index, w ->
                // Neu gerechnet, sobald sich das Fenster aendert oder Ort/Wecker frisch geladen sind.
                val beispiel = remember(w, tageslichtGeladen) { viewModel.nachlaufBeispiel(w) }
                WindowEditor(
                    window = w,
                    // null = Ort noch nicht geladen: weder Kein-Ort-Hinweis noch Beispiel.
                    ohneOrt = ortBekannt?.not(),
                    beispiel = nachlaufBeispielText(beispiel, w.nachlaufBisHell),
                    onOrtEinstellen = onOrtEinstellen,
                    // Die Blockposition gibt es nur fuer eine benannte Schicht: FREI-Tage haben
                    // keine Schicht (der Resolver ignoriert das Feld dort), und fuer UNIVERSAL
                    // waere "Folge gleicher Dienste" nicht erklaerbar - die Regel gilt ja an allen.
                    schichtbezogen = shiftPattern != DimRule.SHIFT_FREE && shiftPattern != DimRule.SHIFT_UNIVERSAL,
                    onChange = { windows[index] = it },
                    onRemove = { windows.removeAt(index) }
                )
            }

            item {
                Button(
                    onClick = {
                        viewModel.saveRule(
                            DimRule(
                                id = existing?.id ?: DimRule.generateId(),
                                name = name.trim(),
                                shiftPattern = shiftPattern,
                                enabled = enabled,
                                windows = windows.toList(),
                                strength = strength,
                                warmth = warmth
                            )
                        )
                        onSaveComplete()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dimmer_rule_save))
                }
            }

            if (existing != null) {
                item {
                    OutlinedButton(
                        onClick = {
                            viewModel.deleteRule(existing.id)
                            onSaveComplete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dimmer_rule_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftDropdown(
    selected: String,
    shiftNames: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(shiftNames) {
        buildList {
            addAll(shiftNames)
            add(DimRule.SHIFT_FREE)
            add(DimRule.SHIFT_UNIVERSAL)
        }
    }
    Column {
        Text(
            text = stringResource(R.string.dimmer_rule_shift),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = dimPatternLabel(selected), modifier = Modifier.weight(1f))
                // dekorativ: der Text im selben Knopf sagt es bereits, und bedienbar ist der Knopf,
                // nicht das Icon - eine eigene Beschreibung liesse den Screenreader doppelt lesen.
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(dimPatternLabel(opt)) },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** kotlinx wie im Regel-Speicher: unbekannte Felder ueberlesen, Defaults mitschreiben. */
private val fensterJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Fensterliste des Formulars als Text fuer den Saveable-Speicher - getestet in `DimWindowTageslichtTest`. */
internal fun fensterListeAlsJson(liste: List<DimWindow>): String =
    fensterJson.encodeToString(ListSerializer(DimWindow.serializer()), liste)

internal fun fensterListeAusJson(text: String): List<DimWindow> =
    fensterJson.decodeFromString(ListSerializer(DimWindow.serializer()), text)

/**
 * Hebt die Fensterliste des Editors ueber das Verlassen der Komposition hinweg auf (Muster wie
 * `HueRuleFormStateSaver`). Ein unlesbarer Stand liefert `null` - dann baut der Editor die Liste
 * wie beim ersten Oeffnen aus der gespeicherten Regel, statt abzustuerzen.
 */
private val FensterListeSaver = Saver<SnapshotStateList<DimWindow>, String>(
    save = { fensterListeAlsJson(it.toList()) },
    restore = { text ->
        runCatching { fensterListeAusJson(text) }.getOrNull()
            ?.let { mutableStateListOf<DimWindow>().apply { addAll(it) } }
    }
)

@Composable
private fun blockpositionLabel(position: Blockposition): String = when (position) {
    Blockposition.ERSTER -> stringResource(R.string.dimmer_blockposition_erster)
    Blockposition.MITTLERER -> stringResource(R.string.dimmer_blockposition_mittlerer)
    Blockposition.LETZTER -> stringResource(R.string.dimmer_blockposition_letzter)
    Blockposition.EINZELNER -> stringResource(R.string.dimmer_blockposition_einzelner)
}

@Composable
private fun WindowEditor(
    window: DimWindow,
    ohneOrt: Boolean?,
    beispiel: String?,
    onOrtEinstellen: () -> Unit,
    schichtbezogen: Boolean,
    onChange: (DimWindow) -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnkerEditor(window = window, onChange = { aenderung -> onChange(aenderung(window)) }) {
                // Nachlauf nach der Weckzeit - eigenes Feld, nicht endOffsetMinutes (Begruendung
                // an DimWindow.weckzeitNachlaufMinuten).
                OffsetField(
                    value = window.weckzeitNachlaufMinuten,
                    label = stringResource(R.string.dimmer_end_nachlauf)
                ) { onChange(WeitereOptionenLogik.nachlaufSetzen(window, it)) }
                Text(
                    text = stringResource(R.string.dimmer_end_nachlauf_hinweis, DimWindowResolver.NACHLAUF_MAX_MINUTEN),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // #140: nur mit Nachlauf - ohne gibt es nichts zu kuerzen. Ein auf 0
                // gesetzter Nachlauf laesst die Wahl im Fenster stehen; sie wirkt dann nicht
                // (Resolver: Nachlauf 0 kehrt vor jeder Tageslicht-Frage zurueck).
                if (window.weckzeitNachlaufMinuten > 0) {
                    NachlaufBisHellWahl(
                        wahl = window.nachlaufBisHell,
                        onWahl = { onChange(window.copy(nachlaufBisHell = it)) },
                        ohneOrt = ohneOrt,
                        beispiel = beispiel,
                        onOrtEinstellen = onOrtEinstellen
                    )
                }
            }

            // Blockposition: nur bei einer benannten Schicht (siehe Aufrufer). Chips statt
            // Radio-Knoepfe, weil ein Fenster an MEHREREN Positionen gelten kann ("erster und
            // mittlere Tage"); alle vier gesetzt = das bisherige Verhalten, und genau so kommt
            // jedes bestehende Fenster hier an.
            if (schichtbezogen) {
                Text(stringResource(R.string.dimmer_window_blockposition), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BLOCKPOSITIONEN.forEach { position ->
                        FilterChip(
                            selected = position in window.blockPositionen,
                            onClick = {
                                onChange(window.copy(blockPositionen = blockpositionUmschalten(window.blockPositionen, position)))
                            },
                            label = { Text(blockpositionLabel(position)) }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.dimmer_window_blockposition_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.dimmer_window_remove))
            }
        }
    }
}
