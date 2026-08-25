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
 * Auswahl des Schichtmusters einer Hue-Regel. Aus `HueRuleConfigScreen` ausgelagert.
 *
 * Neben den Namen der aktivierten Schichtdefinitionen steht hier auch
 * [HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN] ("Alle Schichten") zur Wahl. Der UseCase wertet dieses
 * Muster seit v1.11.0 aus (`findApplicableRules`, `HueSunriseExecutor`), der Editor bot es bis
 * v1.24.0 nicht an - es war nur ueber eine von Hand gebaute Regel erreichbar.
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
                    // Die GANZE Zeile ist das Ziel, nicht nur der Knopf: `selectable` am Row
                    // + `onClick = null` am RadioButton ist das Compose-Standardmuster
                    // dafuer. Vorher traf nur der Knopf selbst (~48dp am linken Rand) - auf
                    // dem Handy fummelig, und ein Tipp auf den Namen tat schlicht nichts.
                    // Nebeneffekt: selectable fasst die Zeile semantisch zu EINEM Element
                    // zusammen, TalkBack liest also "S2, Optionsfeld" statt eines
                    // unbeschrifteten Knopfs neben losem Text.
                    //
                    // heightIn(48dp) ist dabei PFLICHT und kein Schoenheitsfehler: ein
                    // RadioButton mit onClick = null ist nicht mehr klickbar und bringt
                    // daher auch seine eigene 48dp-Mindestgroesse nicht mehr mit. Ohne die
                    // Zeile hier schrumpfte die Reihe auf ~32dp (am Emulator gemessen) -
                    // die Zeile waere breiter, aber flacher als Materials Minimum.
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

                // Universalmuster. Gleiche Zeilen-Mechanik wie oben, inklusive der
                // 48dp-Klemme (der RadioButton hat auch hier onClick = null).
                //
                // Die Ruecklese-Bedingung geht ueber isUniversalShiftPattern(), NICHT ueber
                // `== UNIVERSAL_SHIFT_PATTERN`: der Abgleich im UseCase ist
                // gross-/kleinschreibungsunabhaengig, eine von Hand oder von einer aelteren
                // Version gespeicherte Regel mit "all" wuerde sonst zur Laufzeit feuern, im
                // Editor aber als "nichts ausgewaehlt" erscheinen - und beim Speichern still
                // ihr Muster verlieren.
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
