package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.hueRuleModusErklaerung
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.hueRuleModusLabel

/**
 * Der Umschalter zwischen den drei Betriebsarten einer Regel.
 *
 * Er ist der eine neue Bedienknopf, der die Szenen-Faehigkeit fuer den Nutzer ueberhaupt
 * existieren laesst - eine Faehigkeit ohne Bedienoberflaeche gibt es nicht.
 *
 * Er ERSETZT zugleich den eigenen An/Aus-Schalter der Sonnenaufgangs-Karte. Vorher war der
 * Sonnenaufgang ein Schalter INNERHALB einer Karte, waehrend er faktisch den gesamten
 * Regel-Modus umstellte (die manuelle Karte verschwand ja). Ein Zustand, ein Ort.
 *
 * FlowRow statt Row: drei Chips mit deutschen Beschriftungen passen auf schmalen Geraeten nicht
 * zwingend nebeneinander, und ein abgeschnittener Chip waere unbedienbar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RuleModeCard(
    modus: HueRuleModus,
    onModusChange: (HueRuleModus) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Wie soll das Licht gesetzt werden?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HueRuleModus.entries.forEach { eintrag ->
                    FilterChip(
                        selected = modus == eintrag,
                        onClick = { onModusChange(eintrag) },
                        label = { Text(hueRuleModusLabel(eintrag)) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            Text(
                text = hueRuleModusErklaerung(modus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
