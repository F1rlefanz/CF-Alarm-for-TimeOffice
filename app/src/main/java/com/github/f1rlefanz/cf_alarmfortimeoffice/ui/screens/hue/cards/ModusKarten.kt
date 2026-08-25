package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.ColorMode

/**
 * EINE Karte je Betriebsart - das ist der Punkt dieser Datei.
 *
 * Vorher lag unter dem Modus-Umschalter je nach Modus etwas anderes: die Szene brachte EINE Karte
 * mit, die Raum UND Licht enthielt, waehrend Manuell und Sonnenaufgang sich auf ZWEI Karten
 * verteilten - die Zielauswahl schob sich zwischen den Umschalter und das eigentliche
 * Einstellen. Derselbe Gedanke ("was schaltet diese Regel, und wie") sah damit in drei Modi
 * verschieden aus, und in zwei davon las sich die Lampenwahl wie ein eigener Arbeitsschritt.
 *
 * Jetzt gilt fuer alle drei dasselbe: Umschalter, darunter genau eine Karte, die den ganzen
 * Modus traegt. Die Trennlinie darin sagt "erst wohin, dann wie" - ohne dass es zwei Dinge
 * werden.
 *
 * Die Inhalte selbst sind unveraendert und liegen weiter bei sich ([ZielAuswahlInhalt],
 * [AktionsInhalt], [SonnenaufgangInhalt]); hier steht nur der Rahmen.
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
    ModusKarte {
        ZielAuswahlInhalt(
            lightTargets = lightTargets,
            selectedLightIds = selectedLightIds,
            selectedGroupIds = selectedGroupIds,
            onLightSelectionChange = onLightSelectionChange,
            onGroupSelectionChange = onGroupSelectionChange,
            onRefreshTargets = onRefreshTargets,
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
    ModusKarte {
        ZielAuswahlInhalt(
            lightTargets = lightTargets,
            selectedLightIds = selectedLightIds,
            selectedGroupIds = selectedGroupIds,
            onLightSelectionChange = onLightSelectionChange,
            onGroupSelectionChange = onGroupSelectionChange,
            onRefreshTargets = onRefreshTargets,
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
 * Der gemeinsame Rahmen. Der Innenabstand sitzt HIER, einmal - sonst haetten zwei
 * aneinandergesetzte Inhalte in der Mitte den doppelten Abstand.
 */
@Composable
private fun ModusKarte(inhalt: @Composable () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            inhalt()
        }
    }
}
