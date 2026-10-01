package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Ein Hinweis, dessen Kurzsatz immer sichtbar ist und dessen Rest sich aufklappen laesst.
 *
 * Gemeinsamer Baustein fuer den Schichterkennungs-Hinweis (Schicht-Konfiguration) und den
 * "Ueberspringen oder freigeben"-Hinweis (Wecker-Tab) - bis v1.45 zwei Kopien desselben Aufbaus
 * (#132, G11-05). Die Gruende stehen damit an EINER Stelle:
 *  - Der Kurzsatz bleibt sichtbar: er ist die eigentliche Regel; der volle Text fuellt bei grosser
 *    Systemschrift die halbe Seite.
 *  - Der Umschalter sagt, WAS er zeigt ([aufklappText]) - nicht "Details" oder "i". Material3 gibt
 *    einem TextButton von sich aus das 48dp-Beruehrungsziel.
 *  - `rememberSaveable`, damit eine Drehung den aufgeklappten Zustand nicht wieder zuklappt.
 *  - Weisse Flaeche mit Markenrand statt `surfaceVariant` (hell identisch mit dem Hintergrund,
 *    also unsichtbar) - ui-texte-und-layout.md.
 */
@Composable
fun AufklappHinweis(kurz: String, voll: String, aufklappText: String) {
    var ausgeklappt by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Info,
                // dekorativ: der Hinweistext daneben traegt die Aussage
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (ausgeklappt) voll else kurz,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { ausgeklappt = !ausgeklappt }) {
                    Text(
                        if (ausgeklappt) "Weniger anzeigen" else aufklappText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
