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
            Text("Regel-Informationen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = ruleName,
                onValueChange = onRuleNameChange,
                label = { Text("Regel-Name") },
                placeholder = { Text("z.B. Morgenlicht Frühschicht") },
                modifier = Modifier.fillMaxWidth(),
                isError = showValidationErrors && ruleName.isBlank(),
                supportingText = {
                    if (showValidationErrors && ruleName.isBlank()) {
                        Text("Regel-Name ist erforderlich", color = MaterialTheme.colorScheme.error)
                    }
                }
            )

            SwitchRow(
                title = "Regel aktivieren",
                description = "Regel wird automatisch ausgeführt, wenn Bedingungen erfüllt sind",
                checked = isEnabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}
