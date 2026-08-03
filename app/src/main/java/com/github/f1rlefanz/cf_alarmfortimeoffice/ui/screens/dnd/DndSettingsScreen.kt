package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dnd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.fmtClock
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.pickTime
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DndViewModel

/**
 * DND-Einstellungen: zwei unabhaengige Trigger ("Schlaf-Fenster folgt dem Dimmer" / "Waehrend der
 * Dienstzeit"), kein Regel-Editor - siehe [com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase].
 * Freigabe-Pruefung lebt hier (Composable-Ebene), nicht im ViewModel - Refresh bei ON_RESUME, analog
 * zur Bedienungshilfen-Karte des Dimmers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DndSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DndViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val shiftNames by viewModel.shiftNames.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isSupported = remember { DndPermissionHelper.isFeatureSupported() }
    var isGranted by remember { mutableStateOf(isSupported && DndPermissionHelper.isGranted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isSupported) {
                isGranted = DndPermissionHelper.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dnd_header)) },
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
                Text(
                    text = stringResource(R.string.dnd_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSupported) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.dnd_unsupported),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (!isGranted) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dnd_permission_required),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.dnd_permission_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(
                                onClick = { DndPermissionHelper.requestAccess(context) },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(stringResource(R.string.dnd_permission_grant))
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dnd_follow_dimmer),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.dnd_follow_dimmer_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.followDimmerEnabled,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setFollowDimmerEnabled(it) }
                        )
                    }
                }
            }

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
                                    text = stringResource(R.string.dnd_during_shift),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.dnd_during_shift_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.duringShiftEnabled,
                                enabled = isSupported && isGranted,
                                onCheckedChange = { viewModel.setDuringShiftEnabled(it) }
                            )
                        }
                        if (shiftNames.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.dnd_during_shift_exceptions),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                shiftNames.forEach { name ->
                                    FilterChip(
                                        selected = name in state.shiftExcludedShifts,
                                        onClick = { viewModel.toggleShiftExcludedShift(name) },
                                        label = { Text(name) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (shiftNames.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Rufbereitschaft",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "An Tagen mit einer dieser Schichten endet Nicht stören schon vor der regulären Zeit – du bist ab dem Cutoff erreichbar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                shiftNames.forEach { name ->
                                    FilterChip(
                                        selected = name in state.onCallShifts,
                                        onClick = { viewModel.toggleOnCallShift(name) },
                                        label = { Text(name) }
                                    )
                                }
                            }
                            if (state.onCallShifts.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Cutoff:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            pickTime(context, state.onCallCutoffMinutes) {
                                                viewModel.setOnCallCutoffMinutes(it)
                                            }
                                        }
                                    ) {
                                        Text(fmtClock(state.onCallCutoffMinutes))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                val policy = state.policy
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dnd_policy_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.dnd_policy_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_calls),
                            checked = policy.blockCalls,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockCalls(it) }
                        )
                        if (policy.blockCalls) {
                            PolicyRow(
                                label = stringResource(R.string.dnd_policy_repeat_callers),
                                hint = stringResource(R.string.dnd_policy_repeat_callers_hint),
                                checked = policy.allowRepeatCallers,
                                enabled = isSupported && isGranted,
                                onCheckedChange = { viewModel.setAllowRepeatCallers(it) }
                            )
                        }
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_messages),
                            checked = policy.blockMessages,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockMessages(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_conversations),
                            hint = stringResource(R.string.dnd_policy_conversations_hint),
                            checked = policy.blockConversations,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockConversations(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_reminders),
                            checked = policy.blockReminders,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockReminders(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_events),
                            checked = policy.blockEvents,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockEvents(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_system),
                            checked = policy.blockSystem,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockSystem(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_media),
                            hint = stringResource(R.string.dnd_policy_media_hint),
                            checked = policy.blockMedia,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockMedia(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_alarms),
                            hint = stringResource(R.string.dnd_policy_alarms_hint),
                            checked = policy.blockAlarms,
                            enabled = isSupported && isGranted,
                            onCheckedChange = { viewModel.setBlockAlarms(it) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Eine stummschaltbare Kategorie: Label + optionaler Hinweistext + Schalter. */
@Composable
private fun PolicyRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
