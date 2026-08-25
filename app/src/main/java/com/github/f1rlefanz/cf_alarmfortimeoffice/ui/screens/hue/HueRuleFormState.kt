package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.modus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Der gesamte Zustand des Regel-Editors in EINEM Objekt - und bewusst ohne jede
 * Compose-Abhaengigkeit, damit er sich ohne Geraet pruefen laesst (Vorbild: `DimWindowResolver`,
 * `ShiftCodeSuggester`, `HueTargetReconciler`).
 *
 * WARUM NICHT WIE BISHER 19 EINZELFELDER:
 *  - Der Umbau von Formular zu Regel und zurueck lag als zwei lange Funktionen IM Composable und
 *    war damit nur am Geraet pruefbar. Der Rundlauf `rule.toFormState().toRule(...) == rule` ist
 *    jetzt ein Test statt einer Hoffnung.
 *  - Die Ausschliesslichkeit der Modi ist STRUKTURELL statt per Flag: [toRule] liest
 *    ausschliesslich die Felder des aktiven [modus]. Eine im Manuell-Modus eingestellte
 *    Helligkeit kann konstruktiv nicht in eine Szenen-Aktion lecken. Vorher haing dasselbe an
 *    einem Geflecht aus `if (!sunriseEnabled && targetOn && ...)`-Bedingungen an vier Stellen.
 *  - Fuer die Rotation reicht ein einziges `rememberSaveable(stateSaver = HueRuleFormStateSaver)`.
 *    Ein handgeschriebener `listSaver` ueber 19 Felder verliert bei der naechsten Erweiterung
 *    still eines; hier faellt genau das im Rundlauf-Test auf.
 */
@Immutable
@Serializable
internal data class HueRuleFormState(
    val name: String = "",
    val shiftPattern: String = "",
    val enabled: Boolean = true,
    val modus: HueRuleModus = HueRuleModus.MANUELL,

    // Ziele der Modi MANUELL und SONNENAUFGANG
    val selectedLightIds: Set<String> = emptySet(),
    val selectedGroupIds: Set<String> = emptySet(),

    // Ziele des Modus SZENE. MEHRERE sind ausdruecklich erlaubt: eine Regel darf das Wohnzimmer
    // auf "Nachtlicht" und das Schlafzimmer auf "Lesen" setzen. Die Kette darunter kann das
    // laengst - `convertRuleToLightActions` laeuft ueber alle Aktionen, `autoOffTargetsOf()`
    // flatMapt und dedupliziert, der Ziel-Abgleich behandelt jede Aktion einzeln. Die frueher
    // einzelne Auswahl war eine reine Oberflaechen-Begrenzung.
    //
    // HOECHSTENS EINE Szene JE RAUM: zwei Szenen auf derselben Gruppe waeren zwei PUTs auf
    // denselben Endpunkt, der zweite gewaenne - eine Einstellung, die sich selbst widerspricht.
    val szenen: List<SzenenAuswahl> = emptyList(),

    // Modus MANUELL
    val on: Boolean = true,
    val brightness: Int = 128,
    val colorMode: ColorMode = ColorMode.NONE,
    val colorKelvin: Int = 2700,
    val colorPreset: HueColorConverter.ColorPreset = HueColorConverter.ColorPreset.RED,

    // Modus SONNENAUFGANG
    val sunrise: SunriseConfig = SunriseConfig(),

    // Querschnittlich: betrifft, was auch immer das Licht angeschaltet hat
    val autoOffEnabled: Boolean = false,
    val autoOffMinutes: Int = 30,

    val showValidationErrors: Boolean = false
) {
    /**
     * Schaltet diese Regel ueberhaupt etwas AN? Nur dann gibt es etwas nachzuschalten - dieselbe
     * Bedingung entscheidet ueber die Sichtbarkeit der Auto-Aus-Karte.
     */
    val schaltetEin: Boolean
        get() = when (modus) {
            HueRuleModus.SZENE -> true
            HueRuleModus.SONNENAUFGANG -> true
            HueRuleModus.MANUELL -> on
        }

    /** Hat der aktive Modus ein Ziel? */
    val hatZiel: Boolean
        get() = when (modus) {
            HueRuleModus.SZENE -> szenen.isNotEmpty()
            else -> selectedLightIds.isNotEmpty() || selectedGroupIds.isNotEmpty()
        }
}

/**
 * Eine ausgewaehlte Szene mit ihrem Raum. BEIDE Namen werden mitgefuehrt, nicht nur die Ids: sie
 * sind der Anker, an dem sich die Auswahl auf einer anderen Bridge wiederfinden laesst
 * (`HueTargetReconciler`). An der Bridge des Nutzers gemessen gibt es „Nachtlicht" neun Mal -
 * ohne den Raum waere der Szenenname allein wertlos.
 */
@Immutable
@Serializable
internal data class SzenenAuswahl(
    val sceneId: String,
    val sceneName: String,
    val groupId: String,
    val groupName: String
)

/** Was am Formular noch fehlt. Reihenfolge = Reihenfolge der Karten im Editor. */
internal enum class HueRuleFormFehler { NAME_FEHLT, SCHICHT_FEHLT, ZIEL_FEHLT }

/**
 * Baut aus dem Formularzustand die Regel.
 *
 * @param lightNames/[groupNames] die Namen, wie sie die Bridge JETZT meldet.
 * @param storedNames die bereits in der Regel gespeicherten Namen, nach targetId. Der Rueckfall
 * darauf ist kein Beiwerk: Beim Bearbeiten einer Regel, deren Ids auf DIESER Bridge unbekannt
 * sind, liefert die Bridge-Liste keinen Namen - ein blosses `bridgeName` wuerde beim Speichern
 * genau den Anker loeschen, der die Regel noch retten kann.
 */
internal fun HueRuleFormState.toRule(
    id: String,
    lightNames: Map<String, String>,
    groupNames: Map<String, String>,
    storedNames: Map<String, String>
): HueScheduleRule {
    val autoOffDauer = if (schaltetEin && autoOffEnabled) autoOffMinutes else null

    val aktionen: List<HueLightAction> = when (modus) {
        HueRuleModus.SZENE -> {
            // JE AUSGEWAEHLTER SZENE eine Aktion, und jede traegt ausschliesslich Szene + Gruppe.
            //
            // `on = true` ist hier eine gespeicherte ZUSAGE, kein gesendeter Wert: Der
            // Ausfuehrungspfad schickt nur `{"scene": ...}`, aber `autoOffTargetsOf()` filtert auf
            // `on == true` - ohne das verloere jede Szenenregel ihr Auto-Aus.
            szenen.map { s ->
                HueLightAction(
                    targetType = TargetType.GROUP,
                    targetId = s.groupId,
                    targetName = groupNames[s.groupId] ?: s.groupName,
                    actionType = ActionType.TURN_ON,
                    on = true,
                    duration = autoOffDauer,
                    isGroup = true,
                    sceneId = s.sceneId,
                    sceneName = s.sceneName
                )
            }
        }

        HueRuleModus.SONNENAUFGANG -> {
            // Die Rampe besitzt Farbe und Helligkeit; die Aktion traegt nur das Ziel.
            zieleAlsAktionen { id2, typ, istGruppe ->
                HueLightAction(
                    targetType = typ,
                    targetId = id2,
                    targetName = namenFuer(id2, istGruppe, lightNames, groupNames, storedNames),
                    actionType = ActionType.TURN_ON,
                    on = true,
                    duration = autoOffDauer,
                    isGroup = istGruppe
                )
            }
        }

        HueRuleModus.MANUELL -> {
            val farbTemperatur = if (on && colorMode == ColorMode.WHITE) {
                HueColorConverter.kelvinToHueMireds(colorKelvin)
            } else null
            val presetFarbe = if (on && colorMode == ColorMode.COLOR) {
                HueColorConverter.getPresetColor(colorPreset)
            } else null

            zieleAlsAktionen { id2, typ, istGruppe ->
                HueLightAction(
                    targetType = typ,
                    targetId = id2,
                    targetName = namenFuer(id2, istGruppe, lightNames, groupNames, storedNames),
                    actionType = if (on) ActionType.TURN_ON else ActionType.TURN_OFF,
                    on = on,
                    brightness = if (on) brightness else null,
                    hue = presetFarbe?.hue,
                    saturation = presetFarbe?.saturation,
                    colorTemperature = farbTemperatur,
                    duration = autoOffDauer,
                    isGroup = istGruppe
                )
            }
        }
    }

    return HueScheduleRule(
        id = id,
        name = name,
        shiftPattern = shiftPattern,
        enabled = enabled,
        timeRanges = listOf(HueTimeRange(actions = aktionen)),
        sunrise = if (modus == HueRuleModus.SONNENAUFGANG) sunrise.copy(enabled = true) else null
    )
}

/** Liest eine gespeicherte Regel zurueck ins Formular. Gegenstueck zu [toRule]. */
internal fun HueScheduleRule.toFormState(): HueRuleFormState {
    val ersteAktion = lightActions.firstOrNull()
    val modus = this.modus

    val szenen = lightActions.filter { it.isScene }.map {
        SzenenAuswahl(
            sceneId = it.sceneId.orEmpty(),
            sceneName = it.sceneName.orEmpty(),
            groupId = it.targetId,
            groupName = it.targetName.orEmpty()
        )
    }

    val farbModus = when {
        modus != HueRuleModus.MANUELL -> ColorMode.NONE
        ersteAktion?.colorTemperature != null -> ColorMode.WHITE
        ersteAktion?.hue != null -> ColorMode.COLOR
        else -> ColorMode.NONE
    }

    val autoOff = lightActions.firstNotNullOfOrNull { it.duration }?.takeIf { it > 0 }

    return HueRuleFormState(
        name = name,
        shiftPattern = shiftPattern,
        enabled = enabled,
        modus = modus,
        selectedLightIds = lightActions.filter { !it.isScene && !it.isGroup }
            .map { it.targetId }.toSet(),
        selectedGroupIds = lightActions.filter { !it.isScene && it.isGroup }
            .map { it.targetId }.toSet(),
        szenen = szenen,
        on = if (modus == HueRuleModus.MANUELL) ersteAktion?.on ?: true else true,
        brightness = ersteAktion?.brightness ?: 128,
        colorMode = farbModus,
        colorKelvin = ersteAktion?.colorTemperature
            ?.let { HueColorConverter.hueMiredsToKelvin(it) } ?: 2700,
        colorPreset = ersteAktion?.hue?.let { nearestPreset(it) }
            ?: HueColorConverter.ColorPreset.RED,
        sunrise = sunrise ?: SunriseConfig(),
        autoOffEnabled = autoOff != null,
        autoOffMinutes = autoOff ?: 30
    )
}

/** Was noch fehlt, damit gespeichert werden kann. Leer = speicherbar. */
internal fun HueRuleFormState.validate(): List<HueRuleFormFehler> = buildList {
    if (name.isBlank()) add(HueRuleFormFehler.NAME_FEHLT)
    if (shiftPattern.isBlank()) add(HueRuleFormFehler.SCHICHT_FEHLT)
    if (!hatZiel) add(HueRuleFormFehler.ZIEL_FEHLT)
}

/**
 * Saver fuer die Rotation: serialisiert den ganzen Zustand ueber kotlinx zu EINEM String.
 *
 * Bewusst kein `listSaver` ueber die Einzelfelder - der muesste bei jedem neuen Feld von Hand
 * nachgezogen werden, und vergisst man es, verschwindet nach dem Drehen genau dieses eine Feld,
 * lautlos. Hier deckt der Rundlauf-Test das ab.
 */
internal val HueRuleFormStateSaver: Saver<HueRuleFormState, String> = Saver(
    save = { runCatching { formStateJson.encodeToString(it) }.getOrNull() },
    restore = { runCatching { formStateJson.decodeFromString<HueRuleFormState>(it) }.getOrNull() }
)

internal val formStateJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// --- interne Helfer --------------------------------------------------------------------------

private fun namenFuer(
    id: String,
    istGruppe: Boolean,
    lightNames: Map<String, String>,
    groupNames: Map<String, String>,
    storedNames: Map<String, String>
): String? = (if (istGruppe) groupNames[id] else lightNames[id]) ?: storedNames[id]

private fun HueRuleFormState.zieleAlsAktionen(
    baue: (id: String, typ: TargetType, istGruppe: Boolean) -> HueLightAction
): List<HueLightAction> =
    selectedLightIds.map { baue(it, TargetType.LIGHT, false) } +
        selectedGroupIds.map { baue(it, TargetType.GROUP, true) }
