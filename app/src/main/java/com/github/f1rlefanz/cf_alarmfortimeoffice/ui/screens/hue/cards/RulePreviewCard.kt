package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorMode
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorSwatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.isUniversalShiftPattern
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.presetLabel
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForKelvin
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForPreset
import kotlinx.coroutines.launch

/** Zusammenfassung der Regel plus "Regel testen". Aus `HueRuleConfigScreen` ausgelagert. */
@Composable
internal fun RulePreviewCard(
    ruleName: String,
    selectedShiftPattern: String,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    targetOn: Boolean,
    targetBrightness: Int,
    isEnabled: Boolean,
    colorMode: ColorMode,
    colorKelvin: Int,
    colorPreset: HueColorConverter.ColorPreset,
    autoOffEnabled: Boolean,
    autoOffMinutes: Int,
    sunriseEnabled: Boolean,
    sunriseDurationMinutes: Int,
    sunriseStartBeforeAlarm: Boolean,
    onTestRule: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeManager = remember { HueBridgeConnectionManager.getInstance(context) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(1f) auf die Überschrift, NICHT auf den Button: eine Row misst gewichtslose
                // Kinder zuerst, der Button bekommt damit seine natürliche Breite und muss "Regel
                // testen" nicht mehr umbrechen. Die Überschrift nimmt, was übrig bleibt.
                Text(
                    "Regel-Vorschau",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                CompactButton(
                    onClick = {
                        // OPTIMIZATION: Manual health check before rule test
                        scope.launch {
                            bridgeManager.forceHealthCheck()
                        }
                        onTestRule()
                    },
                    text = "Regel testen",
                    icon = Icons.Default.PlayArrow,
                    enabled = ruleName.isNotBlank() &&
                            selectedShiftPattern.isNotBlank() &&
                            (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty())
                )
            }

            if (ruleName.isNotBlank()) {
                Text("\"$ruleName\"", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }

            if (selectedShiftPattern.isNotBlank()) {
                // Das Universalmuster ist ein Sentinel ("ALL") und ergibt eingesetzt einen
                // Satz, den es nicht gibt ("Bei ALL-Schicht") - deshalb ein eigener Wortlaut.
                Text(
                    if (isUniversalShiftPattern(selectedShiftPattern)) {
                        "Bei jeder Schicht:"
                    } else {
                        "Bei $selectedShiftPattern-Schicht:"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (selectedShiftPattern.isNotBlank()) {
                Text("Ausführung zur Weckzeit", style = MaterialTheme.typography.bodyMedium)
            }

            if (selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty()) {
                if (sunriseEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            buildString {
                                append("🌅 Sunrise über $sunriseDurationMinutes Min an ${selectedLightIds.size} Lichtern und ${selectedGroupIds.size} Gruppen")
                                append(" (${if (sunriseStartBeforeAlarm) "vor dem Alarm" else "ab Alarmzeit"})")
                                // UX FIX (D): auto-off now also applies to sunrise rules.
                                if (autoOffEnabled) append(" · Auto-Aus nach $autoOffMinutes Min")
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (colorMode) {
                            ColorMode.WHITE -> ColorSwatch(previewColorForKelvin(colorKelvin))
                            ColorMode.COLOR -> ColorSwatch(previewColorForPreset(colorPreset))
                            ColorMode.NONE -> {}
                        }
                        Text(
                            buildString {
                                append(if (targetOn) "Einschalten" else "Ausschalten")
                                append(" von ${selectedLightIds.size} Lichtern und ${selectedGroupIds.size} Gruppen")
                                if (targetOn) {
                                    append(" mit ${(targetBrightness * 100 / 254)}% Helligkeit")
                                    when (colorMode) {
                                        ColorMode.WHITE -> append(", $colorKelvin K")
                                        ColorMode.COLOR -> append(", ${presetLabel(colorPreset)}")
                                        ColorMode.NONE -> {}
                                    }
                                    if (autoOffEnabled) append(" · Auto-Aus nach $autoOffMinutes Min")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Text(
                "Status: ${if (isEnabled) "Aktiviert" else "Deaktiviert"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
