package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
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
    val shiftNames by viewModel.shiftNames.collectAsState()
    val existing = remember(ruleId) { viewModel.ruleById(ruleId) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var shiftPattern by remember { mutableStateOf(existing?.shiftPattern ?: DimRule.SHIFT_UNIVERSAL) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    val windows = remember { mutableStateListOf<DimWindow>().apply { existing?.windows?.let { addAll(it) } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dimmer_rule_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
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
                                windows = windows.toList()
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
            // Start – immer feste Uhrzeit (Vorabend)
            Text(stringResource(R.string.dimmer_window_start), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                onPickTime(window.startClockMinutes) {
                    onChange(window.copy(startAnchor = DimAnchor.CLOCK, startClockMinutes = it))
                }
            }) {
                Text(fmtClock(window.startClockMinutes))
            }

            // Ende – feste Uhrzeit ODER relativ zur Weckzeit
            Text(stringResource(R.string.dimmer_window_end), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EndModeButton(
                    selected = window.endAnchor == DimAnchor.CLOCK,
                    label = stringResource(R.string.dimmer_end_clock)
                ) { onChange(window.copy(endAnchor = DimAnchor.CLOCK)) }
                EndModeButton(
                    selected = window.endAnchor == DimAnchor.ALARM,
                    label = stringResource(R.string.dimmer_end_alarm)
                ) { onChange(window.copy(endAnchor = DimAnchor.ALARM)) }
            }

            when (window.endAnchor) {
                DimAnchor.CLOCK -> {
                    OutlinedButton(onClick = {
                        onPickTime(window.endClockMinutes) { onChange(window.copy(endClockMinutes = it)) }
                    }) {
                        Text(fmtClock(window.endClockMinutes))
                    }
                }

                DimAnchor.ALARM -> {
                    var offsetText by remember(window.endOffsetMinutes) {
                        mutableStateOf(window.endOffsetMinutes.toString())
                    }
                    OutlinedTextField(
                        value = offsetText,
                        onValueChange = { v ->
                            offsetText = v
                            v.toIntOrNull()?.let { onChange(window.copy(endOffsetMinutes = it)) }
                        },
                        label = { Text(stringResource(R.string.dimmer_end_offset)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
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
private fun EndModeButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private fun fmtClock(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

private fun pickTime(context: Context, currentMinutes: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, h, m -> onPicked(h * 60 + m) },
        currentMinutes / 60,
        currentMinutes % 60,
        true
    ).show()
}
