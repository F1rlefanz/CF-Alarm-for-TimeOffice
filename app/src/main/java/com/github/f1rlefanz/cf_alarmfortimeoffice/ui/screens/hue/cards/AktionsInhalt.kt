package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.COLOR_PRESETS
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorMode
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorSwatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.presetLabel
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForKelvin
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForPreset

/**
 * Die manuelle Einstellung: An/Aus, Helligkeit, Farbe.
 *
 * INHALT, KEINE KARTE: Den Rahmen setzt [ManuellCard] in `ModusKarten.kt`, zusammen mit der
 * Zielauswahl darueber und der Trennlinie dazwischen.
 *
 * Der Ein/Aus-Schalter ist zugleich die Ueberschrift dieses Blocks - deshalb kraeftiger gesetzt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AktionsInhalt(
    targetOn: Boolean,
    targetBrightness: Int,
    colorMode: ColorMode,
    colorKelvin: Int,
    colorPreset: HueColorConverter.ColorPreset,
    onTargetOnChange: (Boolean) -> Unit,
    onTargetBrightnessChange: (Int) -> Unit,
    onColorModeChange: (ColorMode) -> Unit,
    onColorKelvinChange: (Int) -> Unit,
    onColorPresetChange: (HueColorConverter.ColorPreset) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // Der Ein/Aus-Schalter IST die Ueberschrift dieser Karte (kraeftiger gesetzt, analog
        // zur Sunrise-Karte). Kein eigener "Aktionskonfiguration"-Titel mehr: der las sich wie
        // ein Oberbegriff fuer beides, obwohl der Sunrise-Lichtwecker eine eigene, gleichrangige
        // Karte ist. Ist Sunrise an, wird diese Karte gar nicht erst gezeigt (siehe Aufrufer).
        SwitchRow(
            title = stringResource(if (targetOn) R.string.hue_action_on else R.string.hue_action_off),
            description = stringResource(if (targetOn) R.string.hue_action_hint_on else R.string.hue_action_hint_off),
            checked = targetOn,
            onCheckedChange = onTargetOnChange,
            titleStyle = MaterialTheme.typography.titleMedium,
            titleFontWeight = FontWeight.Bold
        )

        if (targetOn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.hue_action_brightness, targetBrightness * 100 / 254),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                Slider(
                    value = targetBrightness.toFloat(),
                    onValueChange = { onTargetBrightnessChange(it.toInt()) },
                    valueRange = 1f..254f,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.hue_action_brightness_max), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Color mode selector
            Text(stringResource(R.string.hue_action_color), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // FlowRow statt Row: in einer Row quetscht sich der letzte Chip in den Rest der
            // Zeile, und passt sein Wort nicht hinein, wird es zerlegt ("Farb/e"). FlowRow
            // schiebt ihn stattdessen in die nächste Zeile. Bei Standardschrift passen alle
            // drei nebeneinander - es ändert sich also nur, was bei großer Schrift passiert.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = colorMode == ColorMode.NONE,
                    onClick = { onColorModeChange(ColorMode.NONE) },
                    label = { Text(stringResource(R.string.hue_action_color_none)) }
                )
                FilterChip(
                    selected = colorMode == ColorMode.WHITE,
                    onClick = { onColorModeChange(ColorMode.WHITE) },
                    label = { Text(stringResource(R.string.hue_action_color_white)) }
                )
                FilterChip(
                    selected = colorMode == ColorMode.COLOR,
                    onClick = { onColorModeChange(ColorMode.COLOR) },
                    label = { Text(stringResource(R.string.hue_action_color_preset)) }
                )
            }

            when (colorMode) {
                ColorMode.WHITE -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ColorSwatch(previewColorForKelvin(colorKelvin))
                        Text(stringResource(R.string.hue_action_color_temp, colorKelvin), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Slider(
                        value = colorKelvin.toFloat(),
                        onValueChange = { onColorKelvinChange(it.toInt()) },
                        valueRange = 2000f..6500f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.hue_action_color_warm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.hue_action_color_cool), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ColorMode.COLOR -> {
                    // chunked(4) unterstellte, dass vier Farbchips nebeneinander passen. Bei
                    // ~296dp Karteninnenbreite und Chips aus Farbpunkt + Wort ("Orange",
                    // "Türkis") tun sie das nicht - die vierte Spalte lief über den Rand.
                    // FlowRow bricht nach tatsächlichem Platz um statt nach fester Anzahl.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        COLOR_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = colorPreset == preset,
                                onClick = { onColorPresetChange(preset) },
                                leadingIcon = { ColorSwatch(previewColorForPreset(preset), size = 16) },
                                label = { Text(presetLabel(preset)) }
                            )
                        }
                    }
                }
                ColorMode.NONE -> { /* keep current bulb color */ }
            }
        }
    }
}
