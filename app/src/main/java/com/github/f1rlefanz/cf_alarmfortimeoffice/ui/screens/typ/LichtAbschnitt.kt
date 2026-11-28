package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.modus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtSchreiben
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker.InhaltUndBedienung
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/** Was ueber die Bridge-Einrichtung bekannt ist - aus der strikt gelesenen Quelle der Tagesleiste (#253). */
internal enum class BridgeStand { WIRD_GELESEN, NICHT_LESBAR, EINGERICHTET, NICHT_EINGERICHTET }

/** Was unter den Regeln steht - genau eines davon. */
internal enum class LichtFuss {
    /** „+ Lichtregel hinzufügen“. */
    HINZU,

    /** „+“ gesperrt, darunter der Grund. */
    HOECHSTZAHL,
    OHNE_WECKER,
    VERDECKT,
    WIRD_GELESEN,
    OHNE_BRIDGE,
    BRIDGE_NICHT_LESBAR,
    NEUANLAGE
}

/** Eine Regel in der Karte. */
internal data class LichtZeile(
    val regel: HueSchedule,
    val unterzeile: String,
    /** Ziele, die die verbundene Bridge nicht kennt (`UnresolvedRuleTarget`). */
    val zielHinweis: String?,
    /** Schalter und Antippen (oeffnet den Editor). */
    val bedienbar: Boolean,
    val entfernbar: Boolean
)

internal data class LichtKarte(val zeilen: List<LichtZeile>, val ausgegraut: Boolean, val fuss: LichtFuss)

/**
 * Was die Typ-Seite fuer das Licht vom Hue-Teil braucht (`HueViewModel` und Navigation).
 *
 * @param unaufgeloest die Ziele, die die verbundene Bridge nicht kennt (`HueUiState.unresolvedTargets`).
 * @param fehler `HueUiState.error`; die Seite raeumt ihn beim Betreten und Verlassen (`MainScreen`).
 * @param frageZieleAb stoesst den Ziel-Abgleich an (`HueViewModel.refreshLightTargets`).
 * @param oeffneEditor `null` = neue Regel.
 */
internal class LichtAnbindung(
    val unaufgeloest: List<UnresolvedRuleTarget>,
    val fehler: String?,
    val speichere: (HueSchedule) -> Unit,
    val entferne: (regelId: String) -> Unit,
    val schliesseFehler: () -> Unit,
    val frageZieleAb: () -> Unit,
    val oeffneEditor: (regelId: String?) -> Unit
)

/**
 * Die Rechnung der Karte „Licht (Hue)“, ohne Compose (#254). Die Regeln kommen aus dem GESPEICHERTEN Typ
 * (`ShiftDefinition.licht`), nie aus dem Entwurf: sie werden sofort geschrieben. Aus dem Entwurf kommt nur
 * „Wecker an diesem Tag“ (wie bei der Tagesleiste).
 */
internal object LichtAbschnittLogik {

    /**
     * Spiegelt `HueRuleUseCase.MAX_RULES_PER_SHIFT` (dort privat). `LichtAbschnittTest` legt am echten Use-Case
     * bis an die Grenze an und faengt so ein Auseinanderlaufen.
     */
    const val HOECHSTZAHL = 10

    /** Wie die Karte „Wecker“: nicht bei Krank und Ignorieren - ohne Weckzeit gibt es kein Licht (E8). */
    fun zeigtAbschnitt(kategorie: ShiftKategorie): Boolean = WeckerAbschnittLogik.zeigtWeckerKarte(kategorie)

    /** Ein Lesefehler ist „nicht lesbar“, nie „keine Bridge“ (#253); `null` = noch nicht gelesen. */
    fun bridgeStand(quellen: TagesleisteQuellen?): BridgeStand = when (val licht = quellen?.licht) {
        null -> BridgeStand.WIRD_GELESEN
        Quelle.Unbekannt -> BridgeStand.NICHT_LESBAR
        is Quelle.Bekannt ->
            if (licht.wert.konfiguriert) BridgeStand.EINGERICHTET else BridgeStand.NICHT_EINGERICHTET
    }

    /**
     * Die Karte zum Typ [definition]. Welche Zeile bedienbar ist, sagen dieselben Funktionen wie beim Schreiben
     * ([TypLichtSchreiben]): geschrieben wird an den ERSTEN Typ eines Namens. An einem verdeckten Typ gleichen
     * Namens zoege der Schalter die Regel sonst dorthin (#261); entfernen laesst sie sich, weil das ueber die
     * id geht.
     */
    fun karte(
        config: ShiftConfig?,
        definition: ShiftDefinition,
        neu: Boolean,
        weckerAn: Boolean,
        bridge: BridgeStand,
        unaufgeloest: List<UnresolvedRuleTarget>
    ): LichtKarte {
        val stelle = if (neu) -1 else config?.definitions?.indexOfFirst { it.id == definition.id } ?: -1
        val wirksam = config != null && stelle >= 0 &&
            TypLichtSchreiben.zielTyp(config, config.definitions[stelle].name) == stelle
        val regeln = if (neu) emptyList() else definition.licht.regeln
        val zeilen = regeln.map { regel ->
            val traegt = config != null && stelle >= 0 && TypLichtSchreiben.traeger(config, regel.id) == stelle
            val bedienbar = weckerAn && wirksam && traegt
            val unbekannt = unaufgeloest.filter { it.ruleId == regel.id }.map { it.label }.distinct()
            LichtZeile(
                regel = regel,
                unterzeile = unterzeile(regel),
                zielHinweis = if (unbekannt.isEmpty()) null else LichtTexte.unbekannteZiele(unbekannt, bedienbar),
                bedienbar = bedienbar,
                entfernbar = weckerAn && traegt
            )
        }
        val fuss = when {
            !weckerAn -> LichtFuss.OHNE_WECKER
            !neu && !wirksam -> LichtFuss.VERDECKT
            else -> when (bridge) {
                BridgeStand.WIRD_GELESEN -> LichtFuss.WIRD_GELESEN
                BridgeStand.NICHT_LESBAR -> LichtFuss.BRIDGE_NICHT_LESBAR
                BridgeStand.NICHT_EINGERICHTET -> LichtFuss.OHNE_BRIDGE
                BridgeStand.EINGERICHTET -> when {
                    neu -> LichtFuss.NEUANLAGE
                    regeln.size >= HOECHSTZAHL -> LichtFuss.HOECHSTZAHL
                    else -> LichtFuss.HINZU
                }
            }
        }
        return LichtKarte(zeilen = zeilen, ausgegraut = !weckerAn, fuss = fuss)
    }

    /** „Ziele · Modus“, etwa „Bad und Flur · Sonnenaufgang 30 min“. */
    fun unterzeile(regel: HueSchedule): String {
        val ziele = LichtTexte.aufzaehlung(regel.lightActions.map(::zielName).distinct())
        val modus = when (regel.modus) {
            HueRuleModus.SONNENAUFGANG -> LichtTexte.sonnenaufgang(regel.sunrise?.durationMinutes ?: 0)
            HueRuleModus.SZENE -> LichtTexte.szenen(
                regel.lightActions.filter { it.isScene }
                    .mapNotNull { aktion -> aktion.sceneName?.takeIf { it.isNotBlank() } }
                    .distinct()
            )
            HueRuleModus.MANUELL ->
                if (regel.lightActions.any { it.on == true }) LichtTexte.EINSCHALTEN else LichtTexte.AUSSCHALTEN
        }
        return "${ziele.ifEmpty { LichtTexte.KEIN_ZIEL }} · $modus"
    }

    private fun zielName(aktion: HueLightAction): String =
        aktion.targetName?.takeIf { it.isNotBlank() }
            ?: if (aktion.isGroup) LichtTexte.gruppe(aktion.targetId) else LichtTexte.licht(aktion.targetId)

    /**
     * Die Regel, die der Schalter speichert. [typName] ist der GESPEICHERTE Name: das an der Regel gespeicherte
     * `shiftPattern` hat am Typ keine Bedeutung (#246, bei Kopien einer frueher allgemeinen Regel "ALL"), der
     * Schreiber ordnet aber danach zu (`TypLichtSchreiben.geaenderteRegel`) - unveraendert lehnte er ab oder
     * zoege die Regel an einen anderen Typ.
     */
    fun geschaltet(regel: HueSchedule, an: Boolean, typName: String): HueSchedule =
        regel.copy(enabled = an, shiftPattern = typName)
}

/**
 * Die Karte „Licht (Hue)“ der Typ-Seite (SPEC E8 und 3.3 Punkt 8, Prototyp `zeigeTyp`): je Regel Name,
 * Unterzeile, Schalter an/aus und „×“; Tippen oeffnet den Editor; darunter „+ Lichtregel hinzufügen“ oder der
 * Grund, warum es das gerade nicht gibt. Zustandslos - die Rueckfrage zum Entfernen gehoert der Seite
 * ([LichtEntfernenDialog]).
 *
 * Abweichung vom Prototyp (Festlegung in #254): der Schalter je Regel, und ohne Wecker bleiben die Regeln
 * sichtbar (ausgegraut, gesperrt wie „Weckzeit“), statt zu verschwinden.
 */
@Composable
internal fun LichtAbschnitt(
    karte: LichtKarte,
    typName: String,
    fehler: String?,
    onSchalten: (HueSchedule, Boolean) -> Unit,
    onOeffnen: (HueSchedule) -> Unit,
    onEntfernen: (HueSchedule) -> Unit,
    onHinzu: () -> Unit,
    onZurHue: () -> Unit,
    onFehlerSchliessen: () -> Unit
) {
    // Weisse Flaeche mit Rand wie die uebrigen Karten der Seite.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = SpacingConstants.SPACING_SMALL)) {
            Text(
                LichtTexte.TITEL,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(
                        start = SpacingConstants.SPACING_LARGE,
                        end = SpacingConstants.SPACING_LARGE,
                        top = SpacingConstants.SPACING_LARGE,
                        bottom = SpacingConstants.SPACING_SMALL
                    )
                    .semantics { heading() }
            )

            karte.zeilen.forEachIndexed { index, zeile ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RegelZeile(zeile, karte.ausgegraut, onSchalten, onOeffnen, onEntfernen)
            }
            if (karte.zeilen.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Die Seite zeigt sonst keine Hue-Fehler: ein gescheitertes Schalten oder Entfernen bliebe unsichtbar.
            if (fehler != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SpacingConstants.SPACING_LARGE)) {
                    Text(
                        fehler,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = SpacingConstants.SPACING_MEDIUM)
                    )
                    TextButton(onClick = onFehlerSchliessen, modifier = Modifier.align(Alignment.End)) {
                        Text(LichtTexte.FEHLER_SCHLIESSEN)
                    }
                }
            }

            when (karte.fuss) {
                LichtFuss.HINZU -> AktionsZeile(LichtTexte.HINZU, aktiv = true, onClick = onHinzu)
                LichtFuss.HOECHSTZAHL -> {
                    AktionsZeile(LichtTexte.HINZU, aktiv = false, onClick = onHinzu)
                    Hinweis(LichtTexte.hoechstzahl(LichtAbschnittLogik.HOECHSTZAHL))
                }
                LichtFuss.OHNE_WECKER -> Hinweis(LichtTexte.OHNE_WECKER)
                LichtFuss.VERDECKT -> Hinweis(LichtTexte.verdeckt(typName))
                LichtFuss.WIRD_GELESEN -> Unit
                LichtFuss.OHNE_BRIDGE -> {
                    Hinweis(LichtTexte.OHNE_BRIDGE)
                    AktionsZeile(LichtTexte.ZU_HUE, aktiv = true, onClick = onZurHue)
                }
                LichtFuss.BRIDGE_NICHT_LESBAR -> Hinweis(LichtTexte.BRIDGE_NICHT_LESBAR)
                LichtFuss.NEUANLAGE -> Hinweis(LichtTexte.neuanlage(TypSeiteTexte.ERSTELLEN))
            }
        }
    }
}

/** Die Rueckfrage zum „×“ einer Regel. */
@Composable
internal fun LichtEntfernenDialog(name: String, onEntfernen: () -> Unit, onAbbrechen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text(LichtTexte.ENTFERNEN_TITEL) },
        // Scrollbar: bei 320 dp mit Schrift 1,3 ist der Satz mit langem Regelnamen hoch.
        text = { Text(LichtTexte.entfernenText(name), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onEntfernen) {
                Text(LichtTexte.ENTFERNEN, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onAbbrechen) { Text(LichtTexte.ABBRECHEN) }
        }
    )
}

/** Name und Unterzeile links, Schalter und „×“ rechts - auf schmalen Anzeigen darunter ([InhaltUndBedienung]). */
@Composable
private fun RegelZeile(
    zeile: LichtZeile,
    ausgegraut: Boolean,
    onSchalten: (HueSchedule, Boolean) -> Unit,
    onOeffnen: (HueSchedule) -> Unit,
    onEntfernen: (HueSchedule) -> Unit
) {
    val regel = zeile.regel
    val blass = Modifier.alpha(if (ausgegraut) AUSGEGRAUT_ALPHA else 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = zeile.bedienbar, role = Role.Button) { onOeffnen(regel) }
            .heightIn(min = 48.dp)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM)
    ) {
        InhaltUndBedienung(
            inhalt = { modifier ->
                Column(modifier = modifier.then(blass)) {
                    Text(regel.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        zeile.unterzeile,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) {
            Switch(
                checked = regel.enabled,
                onCheckedChange = { an -> onSchalten(regel, an) },
                enabled = zeile.bedienbar,
                modifier = Modifier.semantics { contentDescription = LichtTexte.schalterBeschreibung(regel.name) }
            )
            IconButton(onClick = { onEntfernen(regel) }, enabled = zeile.entfernbar) {
                Icon(Icons.Default.Close, contentDescription = LichtTexte.entfernenBeschreibung(regel.name))
            }
        }
        if (zeile.zielHinweis != null) {
            Text(
                zeile.zielHinweis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Eine antippbare Zeile in Akzentfarbe, wie „+ Weiteres Fenster“. */
@Composable
private fun AktionsZeile(text: String, aktiv: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = aktiv, role = Role.Button, onClick = onClick)
            .alpha(if (aktiv) 1f else AUSGEGRAUT_ALPHA)
            .heightIn(min = 48.dp)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM)
    )
}

@Composable
private fun Hinweis(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = SpacingConstants.SPACING_LARGE,
            vertical = SpacingConstants.SPACING_MEDIUM
        )
    )
}

/** Deckkraft ausgegrauter Zeilen, wie in der Karte „Wecker“. */
private const val AUSGEGRAUT_ALPHA = 0.6f
