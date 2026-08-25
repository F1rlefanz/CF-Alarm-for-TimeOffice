package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.MIN_TOUCH_TARGET
import androidx.compose.ui.res.pluralStringResource

/** Auswahl der Lampen/Gruppen, die eine Hue-Regel steuert. Aus `HueRuleConfigScreen` ausgelagert. */
@Composable
internal fun ZielAuswahlInhalt(
    lightTargets: LightTargets,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    onLightSelectionChange: (Set<String>) -> Unit,
    onGroupSelectionChange: (Set<String>) -> Unit,
    onRefreshTargets: () -> Unit,
    showValidationErrors: Boolean,
    unresolvedTargets: List<UnresolvedRuleTarget> = emptyList()
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.hue_targets_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefreshTargets) {
                Icon(Icons.Default.Refresh, stringResource(R.string.hue_targets_refresh))
            }
        }

        Text(
            stringResource(R.string.hue_targets_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Ein Ziel aus einer FREMDEN Bridge (Konfigurations-Import, Bridge-Tausch) taucht in
        // keiner der beiden Listen auf - ohne diesen Hinweis waere es schlicht unsichtbar:
        // die Regel ist gespeichert, aber nichts ist angehakt, und nichts sagt warum.
        if (unresolvedTargets.isNotEmpty()) {
            Text(
                stringResource(R.string.hue_targets_unknown, unresolvedTargets.joinToString { it.label }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Ebenfalls saveable: Der Reiter gehoert zum Formular. Bliebe er bei `remember`, sprang
        // eine Rotation zurueck auf "Gruppen", obwohl die Auswahl selbst erhalten ist - der
        // Nutzer haette den Verlust seiner Lichter-Auswahl vermutet, wo keiner ist.
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.hue_targets_tab_groups, lightTargets.groups.size)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.hue_targets_tab_lights, lightTargets.lights.size)) }
            )
        }

        when (selectedTab) {
            0 -> {
                if (lightTargets.groups.isEmpty()) {
                    Text(
                        stringResource(R.string.hue_targets_no_groups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    lightTargets.groups.forEach { group ->
                        // Ganze Zeile als Ziel - Begruendung siehe Schichtmuster-Auswahl
                        // (ShiftPatternCard); heightIn(MIN_TOUCH_TARGET) ist Pflicht, weil die
                        // Checkbox mit onCheckedChange = null ihre eigene Mindestgroesse
                        // nicht mehr mitbringt.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MIN_TOUCH_TARGET)
                                .toggleable(
                                    value = selectedGroupIds.contains(group.id),
                                    onValueChange = { isChecked ->
                                        onGroupSelectionChange(
                                            if (isChecked) selectedGroupIds + group.id else selectedGroupIds - group.id
                                        )
                                    },
                                    role = Role.Checkbox
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedGroupIds.contains(group.id),
                                onCheckedChange = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(group.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.hue_targets_group_state, stringResource(if (group.state.any_on) R.string.hue_state_on else R.string.hue_state_off)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            1 -> {
                if (lightTargets.lights.isEmpty()) {
                    Text(
                        stringResource(R.string.hue_targets_no_lights),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    lightTargets.lights.forEach { light ->
                        // Ganze Zeile als Ziel - Begruendung siehe Schichtmuster-Auswahl
                        // (ShiftPatternCard); dieselbe 48dp-Klemme wie oben.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MIN_TOUCH_TARGET)
                                .toggleable(
                                    value = selectedLightIds.contains(light.id),
                                    onValueChange = { isChecked ->
                                        onLightSelectionChange(
                                            if (isChecked) selectedLightIds + light.id else selectedLightIds - light.id
                                        )
                                    },
                                    role = Role.Checkbox
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedLightIds.contains(light.id),
                                onCheckedChange = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(light.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.hue_targets_light_state, stringResource(if (light.state.on) R.string.hue_state_on else R.string.hue_state_off)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showValidationErrors && selectedLightIds.isEmpty() && selectedGroupIds.isEmpty()) {
            Text(
                stringResource(R.string.hue_targets_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.hue_targets_selected,
                    pluralStringResource(R.plurals.hue_count_lights, selectedLightIds.size, selectedLightIds.size),
                    pluralStringResource(R.plurals.hue_count_groups, selectedGroupIds.size, selectedGroupIds.size)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
