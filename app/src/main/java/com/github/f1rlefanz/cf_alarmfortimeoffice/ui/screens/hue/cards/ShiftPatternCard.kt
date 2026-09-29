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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.MIN_TOUCH_TARGET
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.UNIVERSAL_SHIFT_LABEL
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.isUniversalShiftPattern

/**
 * Auswahl des Schichtmusters einer Hue-Regel.
 *
 * Neben den Namen der aktivierten Schichtdefinitionen steht hier auch
 * [HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN] ("Alle Schichten") zur Wahl (`findApplicableRules`,
 * `HueSunriseExecutor`).
 *
 * WICHTIG: Diese Karte aendert ausschliesslich die AUSWAHL. Am Abgleich selbst wird nichts
 * gedreht - der matcht EXAKTEN Definitionsnamen ODER das Universalmuster, kein Keyword und kein
 * Teiltreffer (siehe `HueRuleMatchingTest`: das einbuchstabige Keyword "S" liess die S2-Regel nie
 * und die Spaetschicht-Regel bei fast jeder Schicht feuern).
 */
@Composable
internal fun ShiftPatternCard(
    selectedShiftPattern: String,
    onShiftPatternChange: (String) -> Unit,
    availableShiftPatterns: List<String>,
    showValidationErrors: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.hue_shift_header), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.hue_shift_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (availableShiftPatterns.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.hue_shift_none_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            stringResource(R.string.hue_shift_none_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // "Alle Schichten" steht auch dann zur Wahl, wenn (noch) keine Definition aktiviert
            // ist: eine Universal-Regel bezieht sich nicht auf einen bestimmten Namen und feuert,
            // sobald irgendein Schicht-Wecker laeuft. Ohne diesen Eintrag waere der Editor in
            // diesem Zustand eine Sackgasse.
            Column(modifier = Modifier.selectableGroup()) {
                availableShiftPatterns.forEach { pattern ->
                    // Ganze Zeile als Ziel, 48dp-Klemme Pflicht: MIN_TOUCH_TARGET, ui-texte-und-layout.md.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MIN_TOUCH_TARGET)
                            .selectable(
                                selected = selectedShiftPattern == pattern,
                                onClick = { onShiftPatternChange(pattern) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedShiftPattern == pattern,
                            onClick = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(pattern, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                // Universalmuster, gleiche Zeilen-Mechanik samt 48dp-Klemme. Ruecklese ueber
                // isUniversalShiftPattern(), nicht `==`: der UseCase vergleicht ohne Gross-/
                // Kleinschreibung, sonst verloere eine "all"-Regel beim Speichern still ihr Muster.
                val universalSelected = isUniversalShiftPattern(selectedShiftPattern)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TOUCH_TARGET)
                        .selectable(
                            selected = universalSelected,
                            onClick = { onShiftPatternChange(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = universalSelected,
                        onClick = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(UNIVERSAL_SHIFT_LABEL, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.hue_shift_universal_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (showValidationErrors && selectedShiftPattern.isBlank()) {
                Text(
                    stringResource(R.string.hue_shift_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
