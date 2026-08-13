package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorSwatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForKelvin

/** Sunrise-Lichtwecker (Rampe von dim-warm auf hell-kuehl). Aus `HueRuleConfigScreen` ausgelagert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SunriseConfigCard(
    enabled: Boolean,
    durationMinutes: Int,
    startKelvin: Int,
    endKelvin: Int,
    endBrightness: Int,
    startBeforeAlarm: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onStartKelvinChange: (Int) -> Unit,
    onEndKelvinChange: (Int) -> Unit,
    onEndBrightnessChange: (Int) -> Unit,
    onStartBeforeAlarmChange: (Boolean) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SwitchRow(
                title = "Sunrise-Lichtwecker",
                description = "Sanfter Sonnenaufgang: das Licht fährt von dim-warm auf hell-kühl hoch.",
                checked = enabled,
                onCheckedChange = onEnabledChange,
                // Diese Zeile ist zugleich die Überschrift ihrer Karte - deshalb kräftiger als
                // die Schalter-Zeilen innerhalb einer Karte.
                titleStyle = MaterialTheme.typography.titleMedium,
                titleFontWeight = FontWeight.Bold
            )

            if (enabled) {
                // Gradient preview from start to end temperature
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(previewColorForKelvin(startKelvin), previewColorForKelvin(endKelvin))
                            )
                        )
                )

                Text("Dauer: $durationMinutes Minuten", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Slider(
                    value = durationMinutes.toFloat(),
                    onValueChange = { onDurationChange(it.toInt().coerceIn(1, 90)) },
                    valueRange = 1f..90f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorSwatch(previewColorForKelvin(startKelvin))
                    Text("Start: $startKelvin K (warm)", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = startKelvin.toFloat(),
                    onValueChange = { onStartKelvinChange(it.toInt()) },
                    valueRange = 2000f..6500f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ColorSwatch(previewColorForKelvin(endKelvin))
                    Text("Ziel: $endKelvin K", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = endKelvin.toFloat(),
                    onValueChange = { onEndKelvinChange(it.toInt()) },
                    valueRange = 2000f..6500f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Ziel-Helligkeit: ${endBrightness * 100 / 254}%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Slider(
                    value = endBrightness.toFloat(),
                    onValueChange = { onEndBrightnessChange(it.toInt()) },
                    valueRange = 1f..254f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Zeitpunkt", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = startBeforeAlarm,
                        onClick = { onStartBeforeAlarmChange(true) },
                        label = { Text("Vor dem Alarm") }
                    )
                    FilterChip(
                        selected = !startBeforeAlarm,
                        onClick = { onStartBeforeAlarmChange(false) },
                        label = { Text("Ab Alarmzeit") }
                    )
                }
                Text(
                    if (startBeforeAlarm) {
                        "Rampe endet zur Weckzeit (startet $durationMinutes Min früher)"
                    } else {
                        "Rampe startet zur Weckzeit"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
