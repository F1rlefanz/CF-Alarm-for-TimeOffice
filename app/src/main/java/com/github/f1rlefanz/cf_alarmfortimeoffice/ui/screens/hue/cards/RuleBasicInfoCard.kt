package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import androidx.compose.ui.res.stringResource
import com.github.f1rlefanz.cf_alarmfortimeoffice.R

/** Name und Aktiv-Schalter einer Hue-Regel. Aus `HueRuleConfigScreen` ausgelagert. */
@Composable
internal fun RuleBasicInfoCard(
    ruleName: String,
    onRuleNameChange: (String) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    showValidationErrors: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.hue_rule_info_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = ruleName,
                onValueChange = onRuleNameChange,
                label = { Text(stringResource(R.string.hue_rule_name_label)) },
                placeholder = { Text(stringResource(R.string.hue_rule_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                isError = showValidationErrors && ruleName.isBlank(),
                supportingText = {
                    if (showValidationErrors && ruleName.isBlank()) {
                        Text(stringResource(R.string.hue_rule_name_required), color = MaterialTheme.colorScheme.error)
                    }
                }
            )

            SwitchRow(
                title = stringResource(R.string.hue_rule_enabled_title),
                description = stringResource(R.string.hue_rule_enabled_hint),
                checked = isEnabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}
