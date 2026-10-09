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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SimpleBackTopAppBar
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe.HilfeThema
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
@Composable
fun DndSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DndViewModel = hiltViewModel()
) {
    // DND steuert DndScheduleUseCase (Tick-Alarm), nicht diese Abos - sie duerfen unter STARTED ruhen.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shiftNames by viewModel.shiftNames.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isSupported = remember { DndPermissionHelper.isFeatureSupported() }
    var isGranted by remember { mutableStateOf(isSupported && DndPermissionHelper.isGranted(context)) }
    val bedienbar = isSupported && isGranted

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
            SimpleBackTopAppBar(
                title = stringResource(R.string.dnd_header),
                onNavigateBack = onNavigateBack,
                hilfe = HilfeThema.ANLEITUNG_NICHT_STOEREN
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        SwitchRow(
                            title = stringResource(R.string.dnd_follow_dimmer),
                            description = stringResource(R.string.dnd_follow_dimmer_hint),
                            checked = state.followDimmerEnabled,
                            onCheckedChange = { viewModel.setFollowDimmerEnabled(it) },
                            enabled = bedienbar,
                            titleStyle = MaterialTheme.typography.titleMedium
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
                        SwitchRow(
                            title = stringResource(R.string.dnd_during_shift),
                            description = stringResource(R.string.dnd_during_shift_hint),
                            checked = state.duringShiftEnabled,
                            onCheckedChange = { viewModel.setDuringShiftEnabled(it) },
                            enabled = bedienbar,
                            titleStyle = MaterialTheme.typography.titleMedium
                        )
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
                                text = "An Rufbereitschafts-Tagen endet Nicht stören schon vor der regulären Zeit – du bist ab dem Cutoff erreichbar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Rufbereitschaft legt der Schicht-Editor fest (ShiftDefinition.isOnCall) - hier nur Auskunft.
                            Text(
                                text = if (state.onCallShifts.isEmpty()) {
                                    "Noch keine Schicht als Rufbereitschaft markiert. Das legst du " +
                                        "im Schicht-Editor fest (Schalter „Rufbereitschaft“ an der " +
                                        "jeweiligen Schicht)."
                                } else {
                                    "Als Rufbereitschaft markiert: " +
                                        state.onCallShifts.sorted().joinToString(", ") +
                                        " – änderbar im Schicht-Editor."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockCalls(it) }
                        )
                        if (policy.blockCalls) {
                            PolicyRow(
                                label = stringResource(R.string.dnd_policy_repeat_callers),
                                hint = stringResource(R.string.dnd_policy_repeat_callers_hint),
                                checked = policy.allowRepeatCallers,
                                enabled = bedienbar,
                                onCheckedChange = { viewModel.setAllowRepeatCallers(it) }
                            )
                        }
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_messages),
                            checked = policy.blockMessages,
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockMessages(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_conversations),
                            hint = stringResource(R.string.dnd_policy_conversations_hint),
                            checked = policy.blockConversations,
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockConversations(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_reminders),
                            checked = policy.blockReminders,
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockReminders(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_events),
                            checked = policy.blockEvents,
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockEvents(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_system),
                            checked = policy.blockSystem,
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockSystem(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_media),
                            hint = stringResource(R.string.dnd_policy_media_hint),
                            checked = policy.blockMedia,
                            enabled = bedienbar,
                            onCheckedChange = { viewModel.setBlockMedia(it) }
                        )
                        PolicyRow(
                            label = stringResource(R.string.dnd_policy_alarms),
                            hint = stringResource(R.string.dnd_policy_alarms_hint),
                            checked = policy.blockAlarms,
                            enabled = bedienbar,
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
