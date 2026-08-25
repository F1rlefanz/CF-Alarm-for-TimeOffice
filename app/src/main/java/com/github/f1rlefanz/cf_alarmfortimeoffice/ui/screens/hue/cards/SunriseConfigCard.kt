package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorSwatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForKelvin
import androidx.compose.ui.res.pluralStringResource

/**
 * Sunrise-Lichtwecker (Rampe von dim-warm auf hell-kuehl).
 *
 * KEIN eigener An/Aus-Schalter mehr: Der Sonnenaufgang war frueher ein Schalter INNERHALB dieser
 * Karte, stellte faktisch aber den gesamten Regel-Modus um (die manuelle Karte verschwand ja
 * mit). Seit es die [RuleModeCard] gibt, gehoert dieser Zustand dorthin - ein Zustand, ein Ort.
 * Diese Karte wird nur noch im Modus SONNENAUFGANG ueberhaupt angezeigt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SonnenaufgangInhalt(
    durationMinutes: Int,
    startKelvin: Int,
    endKelvin: Int,
    endBrightness: Int,
    startBeforeAlarm: Boolean,
    onDurationChange: (Int) -> Unit,
    onStartKelvinChange: (Int) -> Unit,
    onEndKelvinChange: (Int) -> Unit,
    onEndBrightnessChange: (Int) -> Unit,
    onStartBeforeAlarmChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(
            stringResource(R.string.hue_sunrise_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        run {
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

            Text(pluralStringResource(R.plurals.hue_sunrise_duration, durationMinutes, durationMinutes), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Slider(
                value = durationMinutes.toFloat(),
                onValueChange = { onDurationChange(it.toInt().coerceIn(1, 90)) },
                valueRange = 1f..90f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorSwatch(previewColorForKelvin(startKelvin))
                Text(stringResource(R.string.hue_sunrise_start_k, startKelvin), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = startKelvin.toFloat(),
                onValueChange = { onStartKelvinChange(it.toInt()) },
                valueRange = 2000f..6500f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorSwatch(previewColorForKelvin(endKelvin))
                Text(stringResource(R.string.hue_sunrise_end_k, endKelvin), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = endKelvin.toFloat(),
                onValueChange = { onEndKelvinChange(it.toInt()) },
                valueRange = 2000f..6500f,
                modifier = Modifier.fillMaxWidth()
            )

            Text(stringResource(R.string.hue_sunrise_end_brightness, endBrightness * 100 / 254), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Slider(
                value = endBrightness.toFloat(),
                onValueChange = { onEndBrightnessChange(it.toInt()) },
                valueRange = 1f..254f,
                modifier = Modifier.fillMaxWidth()
            )

            Text(stringResource(R.string.hue_sunrise_when), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = startBeforeAlarm,
                    onClick = { onStartBeforeAlarmChange(true) },
                    label = { Text(stringResource(R.string.hue_sunrise_before_alarm)) }
                )
                FilterChip(
                    selected = !startBeforeAlarm,
                    onClick = { onStartBeforeAlarmChange(false) },
                    label = { Text(stringResource(R.string.hue_sunrise_at_alarm)) }
                )
            }
            Text(
                if (startBeforeAlarm) {
                    pluralStringResource(R.plurals.hue_sunrise_before_hint, durationMinutes, durationMinutes)
                } else {
                    stringResource(R.string.hue_sunrise_at_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
