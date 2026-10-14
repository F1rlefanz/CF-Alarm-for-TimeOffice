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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.NachlaufBisHellWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmerRulesViewModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DimmerRuleConfigScreen(
    ruleId: String?,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    /** "Ort einstellen" aus dem Hinweis unter "Nachlauf nur, solange es dunkel ist" (#140). */
    onOrtEinstellen: () -> Unit,
    viewModel: DimmerRulesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val shiftNames by viewModel.shiftNames.collectAsStateWithLifecycle()
    val ortBekannt by viewModel.ortBekannt.collectAsStateWithLifecycle()
    val tageslichtGeladen by viewModel.tageslichtGeladen.collectAsStateWithLifecycle()
    val existing = remember(ruleId) { viewModel.ruleById(ruleId) }

    // Auch beim Zurueckkommen vom Ort-Bildschirm: dort kann sich der Ort gerade geaendert haben.
    LaunchedEffect(Unit) { viewModel.ladeTageslicht() }

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
                    ohneOrt = !ortBekannt,
                    beispiel = nachlaufBeispielText(beispiel, w.nachlaufBisHell),
                    onOrtEinstellen = onOrtEinstellen,
                    // Die Blockposition gibt es nur fuer eine benannte Schicht: FREI-Tage haben
                    // keine Schicht (der Resolver ignoriert das Feld dort), und fuer UNIVERSAL
                    // waere "Folge gleicher Dienste" nicht erklaerbar - die Regel gilt ja an allen.
                    schichtbezogen = shiftPattern != DimRule.SHIFT_FREE && shiftPattern != DimRule.SHIFT_UNIVERSAL,
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

// Locale.GERMAN bleibt zwingend: ohne das Argument haengt der Wochentagsname an der Systemsprache.
private val BEISPIEL_TAG_ZEIT = DateTimeFormatter.ofPattern(DateTimeFormats.DAY_TIME, Locale.GERMAN)
private val BEISPIEL_ZEIT = DateTimeFormatter.ofPattern(DateTimeFormats.TIME_ONLY, Locale.GERMAN)

private fun fmt(epoch: Long, format: DateTimeFormatter): String =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(format)

/**
 * "Nächster Wecker Do 15.10. 05:30 → gedimmt bis 06:30 (volle 60 Minuten, Sonnenaufgang ist erst
 * um 07:31)". Die Begruendung entfaellt, wenn die Rechnung keine ehrliche hergibt (siehe
 * [DimmerRulesViewModel.NachlaufBeispiel.grundArt]).
 */
@Composable
private fun nachlaufBeispielText(b: DimmerRulesViewModel.NachlaufBeispiel?, hellAb: HellAb): String? {
    if (b == null) return null
    val basis = stringResource(
        R.string.tageslicht_dimmer_beispiel,
        fmt(b.weckzeit, BEISPIEL_TAG_ZEIT),
        fmt(b.ende, BEISPIEL_ZEIT)
    )
    val ereignis = tageslichtEreignisName(hellAb)
    val grund = when (b.grundArt) {
        DimmerRulesViewModel.BeispielGrund.NACHLAUF_VOLL -> b.hellZeitpunkt?.let {
            pluralStringResource(R.plurals.tageslicht_dimmer_grund_nachlauf, b.nachlaufMin, b.nachlaufMin, ereignis, fmt(it, BEISPIEL_ZEIT))
        }
        DimmerRulesViewModel.BeispielGrund.HELL -> b.grund?.let {
            stringResource(R.string.tageslicht_dimmer_grund_hell, ereignis, fmt(it.hellZeitpunkt, BEISPIEL_ZEIT))
        }
        DimmerRulesViewModel.BeispielGrund.WECKZEIT -> b.grund?.let {
            stringResource(R.string.tageslicht_dimmer_grund_weckzeit, ereignis, fmt(it.hellZeitpunkt, BEISPIEL_ZEIT))
        }
        null -> null
    }
    return if (grund == null) basis else "$basis ($grund)"
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

/**
 * Reihenfolge der Blockpositions-Chips im Editor - alle vier, in der Reihenfolge einer Dienstfolge.
 * Als Konstante neben dem Composable, damit ein Test sie gegen das Modell halten kann: eine Position,
 * die das Modell kennt und der Editor nicht anbietet, waere aus einer Vorlage eingeschleust nicht
 * mehr abwaehlbar.
 */
internal val BLOCKPOSITIONEN = listOf(
    Blockposition.ERSTER, Blockposition.MITTLERER, Blockposition.LETZTER, Blockposition.EINZELNER
)

/**
 * Ein Chip-Tipp schaltet EINE Position um - und laesst das letzte Haekchen stehen. Ein Fenster
 * ohne jede Position gaelte nirgends und stuende trotzdem in der Regel; wer das Fenster nicht
 * will, entfernt es. Reine Funktion, damit die Regel testbar ist.
 */
internal fun blockpositionUmschalten(aktuell: Set<Blockposition>, position: Blockposition): Set<Blockposition> =
    if (position in aktuell) {
        if (aktuell.size == 1) aktuell else aktuell - position
    } else {
        aktuell + position
    }

@Composable
private fun blockpositionLabel(position: Blockposition): String = when (position) {
    Blockposition.ERSTER -> stringResource(R.string.dimmer_blockposition_erster)
    Blockposition.MITTLERER -> stringResource(R.string.dimmer_blockposition_mittlerer)
    Blockposition.LETZTER -> stringResource(R.string.dimmer_blockposition_letzter)
    Blockposition.EINZELNER -> stringResource(R.string.dimmer_blockposition_einzelner)
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
    ohneOrt: Boolean,
    beispiel: String?,
    onOrtEinstellen: () -> Unit,
    schichtbezogen: Boolean,
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
                    // Nachlauf nach der Weckzeit - eigenes Feld, nicht endOffsetMinutes (Begruendung
                    // an DimWindow.weckzeitNachlaufMinuten). Geklemmt wie im Resolver, damit das
                    // Feld nie einen Wert zeigt, der anders wirkt als angezeigt.
                    OffsetField(
                        value = window.weckzeitNachlaufMinuten,
                        label = stringResource(R.string.dimmer_end_nachlauf)
                    ) {
                        onChange(
                            window.copy(
                                weckzeitNachlaufMinuten = it.coerceIn(0, DimWindowResolver.NACHLAUF_MAX_MINUTEN)
                            )
                        )
                    }
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

