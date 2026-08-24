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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DimmerRuleConfigScreen(
    ruleId: String?,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    viewModel: DimmerRulesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    // collectAsStateWithLifecycle, nicht collectAsState: reiner Anzeige-Zustand (Namen der
    // Schicht-Definitionen fuers Dropdown). Das Abo darf im Hintergrund ruhen - es haengt kein
    // Seiteneffekt daran, und `WhileSubscribed` kann den Upstream nur so wirklich freigeben.
    val shiftNames by viewModel.shiftNames.collectAsStateWithLifecycle()
    val existing = remember(ruleId) { viewModel.ruleById(ruleId) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var shiftPattern by remember { mutableStateOf(existing?.shiftPattern ?: DimRule.SHIFT_UNIVERSAL) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var strength by remember { mutableIntStateOf(existing?.strength ?: DimOverlayPrefs.DEFAULT_STRENGTH) }
    var warmth by remember { mutableIntStateOf(existing?.warmth ?: DimOverlayPrefs.DEFAULT_WARMTH) }
    val windows = remember { mutableStateListOf<DimWindow>().apply { existing?.windows?.let { addAll(it) } } }

    Scaffold(
        topBar = {
            SimpleBackTopAppBar(
                title = stringResource(R.string.dimmer_rule_editor_title),
                onNavigateBack = onNavigateBack
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

            // Intensität pro Regel – gilt für die Fenster DIESER Regel (Wellness nutzt die globale Darstellung).
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
                WindowEditor(
                    window = w,
                    onChange = { windows[index] = it },
                    onRemove = { windows.removeAt(index) },
                    onPickTime = { current, cb -> pickTime(context, current, cb) }
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

/** Welches Eingabefeld ein Fenster-Rand braucht - eine Uhrzeit oder einen Minuten-Offset. */
internal enum class AnkerFeld { UHRZEIT, OFFSET }

/**
 * Die Anker, die der Fenster-Editor am START anbietet.
 *
 * WARUM [DimAnchor.ALARM] HIER STEHEN MUSS: Seit dem Ein-Modell-Umbau ist ein Fenster
 * „Weckzeit −60 → Weckzeit" der einzige Weg zu einer Einschlafhilfe vor dem Dienst - die frühere
 * Wellness-Quelle gibt es nicht mehr. Genau das versprechen der Erklärtext im Dimmer-Tab
 * (`dimmer_rules_explain`) und die Modellmigration, die migrierten Nutzern ein solches Fenster
 * anlegt. Fehlte der Knopf, gäbe es die einzige Fähigkeit, die die entfallene Quelle ersetzen
 * soll, ohne Bedienelement - und ein migriertes Fenster wäre im Editor nicht einmal darstellbar.
 *
 * [DimAnchor.ALARM_SONST_CLOCK] fehlt bewusst: er ist ein reiner ENDE-Anker (siehe sein KDoc).
 */
internal val START_ANKER = listOf(DimAnchor.CLOCK, DimAnchor.ALARM, DimAnchor.SHIFT_END)

/** Die Anker, die der Editor am ENDE anbietet - hier sind alle vier sinnvoll. */
internal val ENDE_ANKER =
    listOf(DimAnchor.CLOCK, DimAnchor.ALARM, DimAnchor.SHIFT_END, DimAnchor.ALARM_SONST_CLOCK)

/**
 * Das Eingabefeld zu einem START-Anker.
 *
 * DIE ZUORDNUNG IST EINE ZUSICHERUNG, KEIN LAYOUT-DETAIL: Bekäme ein weckzeit-relativer Start ein
 * UHRZEIT-Feld, zeigte der Editor den unbeteiligten Feld-Default (20:00) statt des echten Ankers,
 * und ein einziger Tipp darauf schriebe den Anker dauerhaft auf [DimAnchor.CLOCK] um - aus „zwei
 * Stunden vor dem Aufstehen" würde „jede Nacht ab 20:00", vor einem Spätdienst also ein Fenster
 * von 16,5 Stunden.
 */
internal fun feldFuerStartAnker(anker: DimAnchor): AnkerFeld = when (anker) {
    DimAnchor.ALARM, DimAnchor.SHIFT_END -> AnkerFeld.OFFSET
    // ALARM_SONST_CLOCK ist am Start wie CLOCK (siehe DimWindowResolver.resolveShiftWindow) -
    // eingeschleust werden kann er nur aus Daten, angeboten wird er hier nicht.
    DimAnchor.CLOCK, DimAnchor.ALARM_SONST_CLOCK -> AnkerFeld.UHRZEIT
}

@Composable
private fun ankerLabel(anker: DimAnchor): String = when (anker) {
    DimAnchor.CLOCK -> stringResource(R.string.dimmer_end_clock)
    DimAnchor.ALARM -> stringResource(R.string.dimmer_end_alarm)
    DimAnchor.SHIFT_END -> stringResource(R.string.dimmer_anchor_shiftend)
    DimAnchor.ALARM_SONST_CLOCK -> stringResource(R.string.dimmer_end_alarm_sonst_clock)
}

@Composable
private fun WindowEditor(
    window: DimWindow,
    onChange: (DimWindow) -> Unit,
    onRemove: () -> Unit,
    onPickTime: (Int, (Int) -> Unit) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Start – feste Uhrzeit (Vorabend), relativ zur Weckzeit (Einschlafhilfe) ODER
            // relativ zum Schichtende (ND-Tagschlaf). Siehe [START_ANKER].
            Text(stringResource(R.string.dimmer_window_start), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                START_ANKER.forEach { anker ->
                    AnchorButton(
                        // ALARM_SONST_CLOCK loest am Start wie CLOCK auf - dann ist der
                        // CLOCK-Knopf die ehrliche Anzeige. Sonst waere KEIN Knopf markiert.
                        selected = window.startAnchor == anker ||
                            (anker == DimAnchor.CLOCK && window.startAnchor == DimAnchor.ALARM_SONST_CLOCK),
                        label = ankerLabel(anker)
                    ) { onChange(window.copy(startAnchor = anker)) }
                }
            }
            when (feldFuerStartAnker(window.startAnchor)) {
                AnkerFeld.OFFSET -> OffsetField(
                    value = window.startOffsetMinutes,
                    label = stringResource(
                        if (window.startAnchor == DimAnchor.ALARM) R.string.dimmer_start_offset
                        else R.string.dimmer_shiftend_offset
                    )
                ) { onChange(window.copy(startOffsetMinutes = it)) }

                // Der Anker wird hier mitgeschrieben, damit ein aus Daten eingeschleustes
                // ALARM_SONST_CLOCK beim ersten Anfassen normalisiert wird - fuer die beiden
                // OFFSET-Anker ist dieser Zweig unerreichbar, sie verlieren ihn also nicht.
                AnkerFeld.UHRZEIT -> ClockField(minutes = window.startClockMinutes) {
                    onPickTime(window.startClockMinutes) {
                        onChange(window.copy(startAnchor = DimAnchor.CLOCK, startClockMinutes = it))
                    }
                }
            }

            // Ende – feste Uhrzeit, zur Weckzeit, „Weckzeit, spätestens" ODER relativ zum Schichtende
            Text(stringResource(R.string.dimmer_window_end), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ENDE_ANKER.forEach { anker ->
                    AnchorButton(
                        selected = window.endAnchor == anker,
                        label = ankerLabel(anker)
                    ) { onChange(window.copy(endAnchor = anker)) }
                }
            }
            when (window.endAnchor) {
                DimAnchor.CLOCK -> ClockField(minutes = window.endClockMinutes) {
                    onPickTime(window.endClockMinutes) { onChange(window.copy(endClockMinutes = it)) }
                }

                DimAnchor.ALARM -> OffsetField(
                    value = window.endOffsetMinutes,
                    label = stringResource(R.string.dimmer_end_offset)
                ) { onChange(window.copy(endOffsetMinutes = it)) }

                DimAnchor.SHIFT_END -> OffsetField(
                    value = window.endOffsetMinutes,
                    label = stringResource(R.string.dimmer_shiftend_offset)
                ) { onChange(window.copy(endOffsetMinutes = it)) }

                // Derselbe Uhrzeit-Waehler wie bei CLOCK - die Uhrzeit ist hier die SPAETESTE
                // Moeglichkeit, nicht das feste Ende. Ohne den erklaerenden Satz daneben ist der
                // Unterschied zu "Feste Uhrzeit" am Knopf allein nicht zu erkennen, und genau
                // dieses Missverstaendnis war der Ausloeser des Ankers.
                DimAnchor.ALARM_SONST_CLOCK -> {
                    ClockField(minutes = window.endClockMinutes) {
                        onPickTime(window.endClockMinutes) { onChange(window.copy(endClockMinutes = it)) }
                    }
                    Text(
                        text = stringResource(R.string.dimmer_end_alarm_sonst_clock_hinweis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.dimmer_window_remove))
            }
        }
    }
}

@Composable
private fun AnchorButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/**
 * Beschrifteter Zeit-Picker für „Feste Uhrzeit". Das Label bindet den Wert sichtbar an die Auswahl
 * (analog zum [OffsetField] der anderen Anker) — sonst wirkte der nackte Uhrzeit-Button auf schmalen
 * Displays losgelöst und schien zum darüberliegenden Anker-Knopf zu gehören.
 */
@Composable
private fun ClockField(minutes: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.dimmer_clock_time),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onClick) { Text(fmtClock(minutes)) }
    }
}

/** Zahlenfeld für einen Minuten-Offset (nach Wecker bzw. nach Schichtende). */
@Composable
private fun OffsetField(value: Int, label: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            v.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

