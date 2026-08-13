package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow

/**
 * Eigene Karte "Automatisch ausschalten" (auto-off) - Schalter als Kartenueberschrift, plus
 * Dauer-Slider.
 *
 * WARUM EIGENE KARTE: Auto-Aus ist querschnittlich - es schaltet aus, was auch immer die Regel
 * angeschaltet hat, egal ob die manuelle Aktion oder der Sunrise-Lichtwecker. Frueher (UX FIX D)
 * sass es in [ActionConfigCard] und wurde bei aktivem Sunrise dort mit hineingezogen; das las
 * sich, als gehoerte es nur zum manuellen Einschalten. Als eigene Karte hinter Aktions- und
 * Sunrise-Karte gehoert es sichtbar keinem der beiden Wege allein.
 *
 * Der Aufrufer zeigt die Karte nur, wenn die Regel die Lichter ueberhaupt anschaltet
 * (`sunriseEnabled || targetOn`) - bei einer reinen Ausschalt-Regel gibt es nichts nachzuschalten.
 *
 * @param sunriseActive nur fuer den Hinweistext: bei Sunrise haengt das Aus hinter der Rampe
 *        (siehe SUNRISE_TEST_DURATION-Logik), nicht ab der Regelausfuehrung.
 */
@Composable
internal fun AutoOffCard(
    autoOffEnabled: Boolean,
    autoOffMinutes: Int,
    sunriseActive: Boolean,
    onAutoOffEnabledChange: (Boolean) -> Unit,
    onAutoOffMinutesChange: (Int) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SwitchRow(
                title = "Automatisch ausschalten",
                description = "Schaltet die Lichter wieder aus, nachdem die Regel sie eingeschaltet hat.",
                checked = autoOffEnabled,
                onCheckedChange = onAutoOffEnabledChange,
                // Diese Zeile ist zugleich die Ueberschrift ihrer Karte - deshalb kraeftiger als
                // die Schalter-Zeilen innerhalb einer Karte (analog zur Sunrise-Karte).
                titleStyle = MaterialTheme.typography.titleMedium,
                titleFontWeight = FontWeight.Bold
            )
            if (autoOffEnabled) {
                Text(
                    "Ausschalten nach: $autoOffMinutes Minuten",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = autoOffMinutes.toFloat(),
                    onValueChange = { onAutoOffMinutesChange(it.toInt().coerceIn(1, 120)) },
                    valueRange = 1f..120f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (sunriseActive) {
                        "Gemessen ab dem Ende des Sonnenaufgangs."
                    } else {
                        "Gemessen ab der Regelausführung (Weckzeit)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
