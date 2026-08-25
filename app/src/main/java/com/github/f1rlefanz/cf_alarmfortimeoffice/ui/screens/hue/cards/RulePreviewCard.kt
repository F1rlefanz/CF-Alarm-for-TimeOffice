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
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorMode
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueRuleFormState
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorSwatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.isUniversalShiftPattern
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.presetLabel
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForKelvin
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.previewColorForPreset
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.validate
import kotlinx.coroutines.launch

/**
 * Zusammenfassung der Regel plus "Regel testen".
 *
 * Nimmt den [HueRuleFormState] am Stueck statt 15 Einzelparameter: Diese Karte muss den Zustand
 * ohnehin VOLLSTAENDIG beschreiben, jeder neue Modus und jedes neue Feld ginge sonst hier
 * lautlos verloren - und ausgerechnet die Zusammenfassung schwiege dann ueber das, was die Regel
 * wirklich tut.
 */
@Composable
internal fun RulePreviewCard(
    form: HueRuleFormState,
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
                    // Dieselbe Bedingung wie beim Speichern - der Test soll nicht anbieten, was
                    // als Regel nicht gueltig waere.
                    enabled = form.validate().isEmpty()
                )
            }

            if (form.name.isNotBlank()) {
                Text("\"${form.name}\"", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }

            if (form.shiftPattern.isNotBlank()) {
                // Das Universalmuster ist ein Sentinel ("ALL") und ergibt eingesetzt einen
                // Satz, den es nicht gibt ("Bei ALL-Schicht") - deshalb ein eigener Wortlaut.
                Text(
                    if (isUniversalShiftPattern(form.shiftPattern)) {
                        "Bei jeder Schicht:"
                    } else {
                        "Bei ${form.shiftPattern}-Schicht:"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (form.shiftPattern.isNotBlank()) {
                Text("Ausführung zur Weckzeit", style = MaterialTheme.typography.bodyMedium)
            }

            if (form.hatZiel) {
                val autoOffZusatz = if (form.autoOffEnabled) " · Auto-Aus nach ${form.autoOffMinutes} Min" else ""

                when (form.modus) {
                    HueRuleModus.SZENE -> Text(
                        buildString {
                            append("🎬 Szene «${form.szene?.sceneName.orEmpty()}»")
                            append(" im Raum ${form.szene?.groupName.orEmpty()}")
                            append(autoOffZusatz)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    HueRuleModus.SONNENAUFGANG -> Text(
                        buildString {
                            append("🌅 Sunrise über ${form.sunrise.durationMinutes} Min an ")
                            append("${form.selectedLightIds.size} Lichtern und ${form.selectedGroupIds.size} Gruppen")
                            append(" (${if (form.sunrise.startBeforeAlarm) "vor dem Alarm" else "ab Alarmzeit"})")
                            append(autoOffZusatz)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )

                    HueRuleModus.MANUELL -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (form.colorMode) {
                            ColorMode.WHITE -> ColorSwatch(previewColorForKelvin(form.colorKelvin))
                            ColorMode.COLOR -> ColorSwatch(previewColorForPreset(form.colorPreset))
                            ColorMode.NONE -> {}
                        }
                        Text(
                            buildString {
                                append(if (form.on) "Einschalten" else "Ausschalten")
                                append(" von ${form.selectedLightIds.size} Lichtern und ${form.selectedGroupIds.size} Gruppen")
                                if (form.on) {
                                    append(" mit ${(form.brightness * 100 / 254)}% Helligkeit")
                                    when (form.colorMode) {
                                        ColorMode.WHITE -> append(", ${form.colorKelvin} K")
                                        ColorMode.COLOR -> append(", ${presetLabel(form.colorPreset)}")
                                        ColorMode.NONE -> {}
                                    }
                                    append(autoOffZusatz)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Text(
                "Status: ${if (form.enabled) "Aktiviert" else "Deaktiviert"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (form.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
