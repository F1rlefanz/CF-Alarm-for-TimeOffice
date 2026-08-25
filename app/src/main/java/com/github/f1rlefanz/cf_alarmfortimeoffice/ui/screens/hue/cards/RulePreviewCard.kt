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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
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
import androidx.compose.ui.res.pluralStringResource

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
                    stringResource(R.string.hue_preview_header),
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
                    text = stringResource(R.string.hue_preview_test),
                    icon = Icons.Default.PlayArrow,
                    // Dieselbe Bedingung wie beim Speichern - der Test soll nicht anbieten, was
                    // als Regel nicht gueltig waere.
                    enabled = form.validate().isEmpty()
                )
            }

            if (form.name.isNotBlank()) {
                Text(stringResource(R.string.hue_preview_quoted_name, form.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }

            if (form.shiftPattern.isNotBlank()) {
                // Das Universalmuster ist ein Sentinel ("ALL") und ergibt eingesetzt einen
                // Satz, den es nicht gibt ("Bei ALL-Schicht") - deshalb ein eigener Wortlaut.
                Text(
                    if (isUniversalShiftPattern(form.shiftPattern)) {
                        stringResource(R.string.hue_preview_any_shift)
                    } else {
                        stringResource(R.string.hue_preview_shift, form.shiftPattern)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (form.shiftPattern.isNotBlank()) {
                Text(stringResource(R.string.hue_preview_at_alarm), style = MaterialTheme.typography.bodyMedium)
            }

            if (form.hatZiel) {
                // Die Texte werden VOR den buildString-Bloecken aufgeloest: stringResource ist
                // @Composable und darf in einem gewoehnlichen Lambda nicht aufgerufen werden.
                val autoOffZusatz = if (form.autoOffEnabled) {
                    pluralStringResource(R.plurals.hue_preview_autooff_suffix, form.autoOffMinutes, form.autoOffMinutes)
                } else ""

                when (form.modus) {
                    HueRuleModus.SZENE -> Text(
                        stringResource(
                            R.string.hue_preview_scene,
                            form.szene?.sceneName.orEmpty(),
                            form.szene?.groupName.orEmpty()
                        ) + autoOffZusatz,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    HueRuleModus.SONNENAUFGANG -> Text(
                        stringResource(
                            R.string.hue_preview_sunrise,
                            pluralStringResource(R.plurals.hue_sunrise_duration_bare, form.sunrise.durationMinutes, form.sunrise.durationMinutes),
                            pluralStringResource(R.plurals.hue_count_lights_dativ, form.selectedLightIds.size, form.selectedLightIds.size),
                            pluralStringResource(R.plurals.hue_count_groups_dativ, form.selectedGroupIds.size, form.selectedGroupIds.size),
                            stringResource(
                                if (form.sunrise.startBeforeAlarm) R.string.hue_preview_sunrise_before
                                else R.string.hue_preview_sunrise_at
                            )
                        ) + autoOffZusatz,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    HueRuleModus.MANUELL -> {
                        val kopf = stringResource(
                            R.string.hue_preview_manual,
                            stringResource(if (form.on) R.string.hue_action_on else R.string.hue_action_off),
                            pluralStringResource(R.plurals.hue_count_lights_dativ, form.selectedLightIds.size, form.selectedLightIds.size),
                            pluralStringResource(R.plurals.hue_count_groups_dativ, form.selectedGroupIds.size, form.selectedGroupIds.size)
                        )
                        val helligkeit = if (form.on) {
                            stringResource(R.string.hue_preview_manual_brightness, form.brightness * 100 / 254)
                        } else ""
                        val farbe = when {
                            !form.on -> ""
                            form.colorMode == ColorMode.WHITE ->
                                stringResource(R.string.hue_preview_manual_kelvin, form.colorKelvin)
                            form.colorMode == ColorMode.COLOR ->
                                stringResource(R.string.hue_preview_manual_color, presetLabel(form.colorPreset))
                            else -> ""
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (form.colorMode) {
                                ColorMode.WHITE -> ColorSwatch(previewColorForKelvin(form.colorKelvin))
                                ColorMode.COLOR -> ColorSwatch(previewColorForPreset(form.colorPreset))
                                ColorMode.NONE -> {}
                            }
                            Text(
                                kopf + helligkeit + farbe + if (form.on) autoOffZusatz else "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Text(
                stringResource(
                    R.string.hue_preview_status,
                    stringResource(
                        if (form.enabled) R.string.hue_preview_status_on
                        else R.string.hue_preview_status_off
                    )
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (form.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
