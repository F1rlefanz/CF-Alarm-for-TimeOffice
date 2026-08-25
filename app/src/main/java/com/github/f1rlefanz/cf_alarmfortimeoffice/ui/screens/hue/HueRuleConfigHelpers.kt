package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter

/**
 * Gemeinsame Hilfen des Hue-Regel-Editors: Farbdarstellung, Farb-Presets und die Beschriftung
 * des Schichtmusters.
 *
 * Ausgelagert aus `HueRuleConfigScreen`, weil die einzelnen Karten (Unterpaket `cards/`) sie
 * teilen. Reine Verschiebung - keine Verhaltensaenderung; nur die Sichtbarkeit musste von
 * `private` auf `internal` wachsen (Kotlin kennt kein package-private).
 */

/**
 * Materials Mindestgroesse fuer ein Bedienelement.
 *
 * Wird explizit gesetzt, weil RadioButton/Checkbox mit `onClick = null` (die ZEILE ist klickbar,
 * nicht der Knopf) ihre eingebaute Mindestgroesse NICHT mehr mitbringen. Ohne diese Klemme
 * schrumpfen die Zeilen auf ~32dp - breiter als vorher, aber flacher als Materials Minimum.
 *
 * Liegt hier statt in einer der Karten, weil sie an DREI Stellen gebraucht wird
 * (Schichtmuster-Auswahl, Gruppen-Auswahl, Lichter-Auswahl). Wer sie entfernt, macht die
 * Bedienzeilen wieder zu flach.
 */
internal val MIN_TOUCH_TARGET = 48.dp

/** Color mode for a Hue rule action: keep current color, set a white temperature, or a preset color. */
internal enum class ColorMode { NONE, WHITE, COLOR }

/** Preset colors offered in COLOR mode (the whites live in WHITE/temperature mode). */
internal val COLOR_PRESETS = listOf(
    HueColorConverter.ColorPreset.RED,
    HueColorConverter.ColorPreset.ORANGE,
    HueColorConverter.ColorPreset.YELLOW,
    HueColorConverter.ColorPreset.GREEN,
    HueColorConverter.ColorPreset.CYAN,
    HueColorConverter.ColorPreset.BLUE,
    HueColorConverter.ColorPreset.PURPLE,
    HueColorConverter.ColorPreset.PINK
)

/**
 * Anzeigename des Universalmusters.
 *
 * Bewusst "Alle Schichten" (nicht "Alle Tage" wie beim Dimmer): der Dimmer meint Kalendertage
 * inklusive freier, eine Hue-Regel laeuft ausschliesslich zur Weckzeit einer erkannten Schicht.
 */
internal val UNIVERSAL_SHIFT_LABEL: String
    @Composable get() = stringResource(R.string.hue_shift_universal_label)

/**
 * Ist [pattern] das Universalmuster?
 *
 * Vergleich gegen [HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN] und `ignoreCase` - exakt derselbe
 * Massstab wie `findApplicableRules`/`HueSunriseExecutor`. Ein eigenes Literal an dieser Stelle
 * waere eine zweite Wahrheit: liefe es je auseinander, zeigte der Editor "nichts ausgewaehlt"
 * fuer eine Regel, die zur Laufzeit sehr wohl feuert.
 */
internal fun isUniversalShiftPattern(pattern: String): Boolean =
    pattern.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true)

/**
 * Beschriftung eines gespeicherten Schichtmusters. Das Universalmuster ist ein Sentinel ("ALL")
 * und darf dem Nutzer nie roh angezeigt werden.
 */
@Composable
internal fun hueShiftPatternLabel(pattern: String): String =
    if (isUniversalShiftPattern(pattern)) UNIVERSAL_SHIFT_LABEL else pattern

/**
 * Beschriftung eines Regel-Modus. Liegt hier, weil sie an ZWEI Stellen gebraucht wird: der
 * Modus-Umschalter im Editor und das Abzeichen in der Regel-Liste. Zwei eigene Formulierungen
 * waeren zwei Wahrheiten - und die Regel-Liste sagte irgendwann etwas anderes als der Editor.
 */
@Composable
internal fun hueRuleModusLabel(modus: HueRuleModus): String = stringResource(
    when (modus) {
        HueRuleModus.SZENE -> R.string.hue_mode_scene
        HueRuleModus.MANUELL -> R.string.hue_mode_manual
        HueRuleModus.SONNENAUFGANG -> R.string.hue_mode_sunrise
    }
)

/** Ein Satz, der den Modus erklaert - im Umschalter unter der jeweiligen Beschriftung. */
@Composable
internal fun hueRuleModusErklaerung(modus: HueRuleModus): String = stringResource(
    when (modus) {
        HueRuleModus.SZENE -> R.string.hue_mode_scene_explain
        HueRuleModus.MANUELL -> R.string.hue_mode_manual_explain
        HueRuleModus.SONNENAUFGANG -> R.string.hue_mode_sunrise_explain
    }
)

@Composable
internal fun presetLabel(preset: HueColorConverter.ColorPreset): String = stringResource(
    when (preset) {
        HueColorConverter.ColorPreset.WARM_WHITE -> R.string.hue_color_warm_white
        HueColorConverter.ColorPreset.COOL_WHITE -> R.string.hue_color_cool_white
        HueColorConverter.ColorPreset.RED -> R.string.hue_color_red
        HueColorConverter.ColorPreset.GREEN -> R.string.hue_color_green
        HueColorConverter.ColorPreset.BLUE -> R.string.hue_color_blue
        HueColorConverter.ColorPreset.YELLOW -> R.string.hue_color_yellow
        HueColorConverter.ColorPreset.PURPLE -> R.string.hue_color_purple
        HueColorConverter.ColorPreset.ORANGE -> R.string.hue_color_orange
        HueColorConverter.ColorPreset.PINK -> R.string.hue_color_pink
        HueColorConverter.ColorPreset.CYAN -> R.string.hue_color_cyan
    }
)

/**
 * Recovers the closest preset for a stored hue, so an edited COLOR rule round-trips back to
 * its chip. Stored COLOR values come from [COLOR_PRESETS], so the match is exact in practice.
 */
internal fun nearestPreset(hue: Int): HueColorConverter.ColorPreset {
    return COLOR_PRESETS.minByOrNull { preset ->
        val presetHue = HueColorConverter.getPresetColor(preset).hue ?: 0
        val diff = kotlin.math.abs(presetHue - hue)
        minOf(diff, 65536 - diff) // circular hue distance
    } ?: HueColorConverter.ColorPreset.RED
}

/** Display color for a preset, via the Hue→RGB conversion utility. */
internal fun previewColorForPreset(preset: HueColorConverter.ColorPreset): Color {
    val (r, g, b) = HueColorConverter.hueColorToRgb(HueColorConverter.getPresetColor(preset))
    return Color(r, g, b)
}

/** Approximate display color for a white color temperature (warm↔cool interpolation). */
internal fun previewColorForKelvin(kelvin: Int): Color {
    val t = ((kelvin - 2000).toFloat() / (6500 - 2000)).coerceIn(0f, 1f)
    val r = (255 + (201 - 255) * t).toInt()
    val g = (166 + (226 - 166) * t).toInt()
    val b = (87 + (255 - 87) * t).toInt()
    return Color(r, g, b)
}

/**
 * Small round color preview dot.
 *
 * Kein contentDescription: der Punkt steht ausnahmslos neben Text, der dieselbe Information
 * ausspricht ("2700 K", "Türkis") - dekorativ.
 */
@Composable
internal fun ColorSwatch(color: Color, size: Int = 20) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    )
}
