package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorMode
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.hueRuleModusLabel

/**
 * EINE Karte je Betriebsart, und sie traegt den Namen der Betriebsart.
 *
 * Vorher lag unter dem Modus-Umschalter je nach Modus etwas anderes: die Szene brachte EINE Karte
 * mit, die Raum UND Licht enthielt, waehrend Manuell und Sonnenaufgang sich auf ZWEI Karten
 * verteilten - die Zielauswahl schob sich zwischen den Umschalter und das eigentliche
 * Einstellen. Und selbst danach stand ueber der Karte "Zielauswahl" statt des gewaehlten Modus:
 * wer "Manuell" antippte, fand darunter keine Karte, die "Manuell" hiess.
 *
 * Jetzt gilt fuer alle drei dasselbe: Umschalter, darunter genau eine Karte, deren Ueberschrift
 * die Auswahl wiederholt. Die Trennlinie darin sagt "erst wohin, dann wie" - ohne dass es zwei
 * Dinge werden.
 *
 * Die Ueberschrift kommt aus [hueRuleModusLabel] - DERSELBEN Quelle wie die Beschriftung des
 * Umschalters und des Abzeichens in der Regel-Liste. Ein eigener Text hier waere eine zweite
 * Wahrheit, und irgendwann hiesse der Chip anders als die Karte darunter.
 *
 * Die Inhalte selbst liegen weiter bei sich ([ZielAuswahlInhalt], [AktionsInhalt],
 * [SonnenaufgangInhalt], [SceneSelectionCard]); hier steht nur der Rahmen.
 */
@Composable
internal fun ManuellCard(
    lightTargets: LightTargets,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    onLightSelectionChange: (Set<String>) -> Unit,
    onGroupSelectionChange: (Set<String>) -> Unit,
    onRefreshTargets: () -> Unit,
    showValidationErrors: Boolean,
    unresolvedTargets: List<UnresolvedRuleTarget>,
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
    ModusKarte(
        modus = HueRuleModus.MANUELL,
        onRefreshTargets = onRefreshTargets,
        refreshBeschreibung = stringResource(R.string.hue_targets_refresh)
    ) {
        ZielAuswahlInhalt(
            lightTargets = lightTargets,
            selectedLightIds = selectedLightIds,
            selectedGroupIds = selectedGroupIds,
            onLightSelectionChange = onLightSelectionChange,
            onGroupSelectionChange = onGroupSelectionChange,
            showValidationErrors = showValidationErrors,
            unresolvedTargets = unresolvedTargets
        )
        HorizontalDivider()
        AktionsInhalt(
            targetOn = targetOn,
            targetBrightness = targetBrightness,
            colorMode = colorMode,
            colorKelvin = colorKelvin,
            colorPreset = colorPreset,
            onTargetOnChange = onTargetOnChange,
            onTargetBrightnessChange = onTargetBrightnessChange,
            onColorModeChange = onColorModeChange,
            onColorKelvinChange = onColorKelvinChange,
            onColorPresetChange = onColorPresetChange
        )
    }
}

/** Dasselbe fuer den Sonnenaufgang: erst wohin, dann wie die Rampe laeuft. */
@Composable
internal fun SonnenaufgangCard(
    lightTargets: LightTargets,
    selectedLightIds: Set<String>,
    selectedGroupIds: Set<String>,
    onLightSelectionChange: (Set<String>) -> Unit,
    onGroupSelectionChange: (Set<String>) -> Unit,
    onRefreshTargets: () -> Unit,
    showValidationErrors: Boolean,
    unresolvedTargets: List<UnresolvedRuleTarget>,
    sunrise: SunriseConfig,
    onDurationChange: (Int) -> Unit,
    onStartKelvinChange: (Int) -> Unit,
    onEndKelvinChange: (Int) -> Unit,
    onEndBrightnessChange: (Int) -> Unit,
    onStartBeforeAlarmChange: (Boolean) -> Unit
) {
    ModusKarte(
        modus = HueRuleModus.SONNENAUFGANG,
        onRefreshTargets = onRefreshTargets,
        refreshBeschreibung = stringResource(R.string.hue_targets_refresh)
    ) {
        ZielAuswahlInhalt(
            lightTargets = lightTargets,
            selectedLightIds = selectedLightIds,
            selectedGroupIds = selectedGroupIds,
            onLightSelectionChange = onLightSelectionChange,
            onGroupSelectionChange = onGroupSelectionChange,
            showValidationErrors = showValidationErrors,
            unresolvedTargets = unresolvedTargets
        )
        HorizontalDivider()
        SonnenaufgangInhalt(
            durationMinutes = sunrise.durationMinutes,
            startKelvin = sunrise.startKelvin,
            endKelvin = sunrise.endKelvin,
            endBrightness = sunrise.endBrightness,
            startBeforeAlarm = sunrise.startBeforeAlarm,
            onDurationChange = onDurationChange,
            onStartKelvinChange = onStartKelvinChange,
            onEndKelvinChange = onEndKelvinChange,
            onEndBrightnessChange = onEndBrightnessChange,
            onStartBeforeAlarmChange = onStartBeforeAlarmChange
        )
    }
}

/**
 * Der gemeinsame Rahmen: Ueberschrift = gewaehlte Betriebsart, daneben das Aktualisieren-Symbol,
 * darunter der Inhalt.
 *
 * Der Innenabstand sitzt HIER, einmal - sonst haetten zwei aneinandergesetzte Inhalte in der
 * Mitte den doppelten Abstand.
 */
@Composable
internal fun ModusKarte(
    modus: HueRuleModus,
    onRefreshTargets: () -> Unit,
    refreshBeschreibung: String,
    inhalt: @Composable () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    hueRuleModusLabel(modus),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRefreshTargets) {
                    Icon(Icons.Default.Refresh, refreshBeschreibung)
                }
            }
            inhalt()
        }
    }
}
