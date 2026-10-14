package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtWirkung
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeckerEintrag
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeckerTag
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.alsText
import java.time.LocalDate
import java.time.ZoneId

/**
 * Was eine Tageskarte und das Bottom-Sheet bedienen koennen. Die Aktionen rufen die BESTEHENDEN
 * ViewModel-Funktionen; [gesperrt] spiegelt deren gemeinsame Wiedereintrittssperre
 * (`skipVorgangLaeuft`) - ein Knopf, der bedienbar aussieht und still nichts tut, ist schlimmer
 * als ein ausgegrauter.
 */
internal class TagesAktionen(
    val gesperrt: Boolean,
    /** Laeuft gerade „Nicht wecken“/„Doch wecken“? Dann dreht sich deren Knopf. */
    val skipLaeuft: Boolean,
    val onNichtWecken: (angezeigtesZiel: Long) -> Unit,
    val onDochWecken: () -> Unit,
    val onDienstFaelltAus: (LocalDate) -> Unit,
    val onZuruecknehmen: (LocalDate) -> Unit
)

/**
 * Eine Karte je Tag: Datum, Dienst(e), die Wecker und die Kaertchen. Antippen oeffnet das
 * Bottom-Sheet ([TagDetails]); „Nicht wecken“/„Doch wecken“ und „Zurücknehmen“ stehen direkt an
 * ihrer Zeile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagKarte(
    tag: WeckerTag,
    heute: LocalDate,
    aktionen: TagesAktionen,
    onOeffnen: () -> Unit
) {
    val ueberschrift = tagUeberschrift(tag.datum)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = "Details zu $ueberschrift", role = Role.Button, onClick = onOeffnen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // Ein freigegebener Tag wird ADDITIV gezeigt (Rand statt Erhebung), nie durch Wegnehmen
        // der Flaeche - Skill cfalarm-ui-und-navigation.
        elevation = CardDefaults.cardElevation(defaultElevation = if (tag.freigegeben) 0.dp else 2.dp),
        border = if (tag.freigegeben) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            TagKopf(tag, heute)

            val mehrereSchichten = tag.schichten.size > 1
            tag.eintraege.forEach { eintrag ->
                EintragZeile(eintrag, mehrereSchichten, aktionen)
            }

            if (tag.freigegeben) {
                InhaltUndBedienung(
                    inhalt = { modifier ->
                        Text(
                            DIENST_FAELLT_AUS_KURZ,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = modifier
                        )
                    }
                ) {
                    TextButton(
                        onClick = { aktionen.onZuruecknehmen(tag.datum) },
                        enabled = !aktionen.gesperrt
                    ) { Text("Zurücknehmen") }
                }
            }

            if (tag.kaertchen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    tag.kaertchen.forEach { Etikett(kaertchenText(it)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagKopf(tag: WeckerTag, heute: LocalDate) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            tagUeberschrift(tag.datum),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        relativerTag(tag.datum, heute)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tag.freigegeben) Etikett("Dienst fällt aus", hervorgehoben = true)
    }
    if (tag.schichten.isNotEmpty()) {
        Text(
            schichtenText(tag.schichten, ZoneId.systemDefault()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Eine Wecker-Zeile: Uhrzeit, was er tut, und - nur am naechsten - „Nicht wecken“. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EintragZeile(eintrag: WeckerEintrag, mehrereSchichten: Boolean, aktionen: TagesAktionen) {
    val wecker = eintrag as? WeckerEintrag.Wecker
    val uebersprungen = eintrag is WeckerEintrag.Uebersprungen
    // Stumm, entfallen oder abgeschaltet: die Uhrzeit wird durchgestrichen - additiv zum Text, der
    // es ausschreibt (die Farbe allein truege die Aussage nicht fuer jeden).
    val klingeltNicht = uebersprungen || wecker?.still == true || wecker?.entfaelltDurchFreigabe == true
    val uhrzeit = when (eintrag) {
        is WeckerEintrag.Wecker -> eintrag.uhrzeit
        is WeckerEintrag.Uebersprungen -> eintrag.uhrzeit
    }

    InhaltUndBedienung(
        inhalt = { modifier ->
            Row(
                modifier = modifier,
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tabellarische Ziffern statt Monospace: die Zeiten stehen buendig untereinander,
                // ohne dass der Doppelpunkt eine volle Zeichenbreite bekommt ("05 : 30").
                Text(
                    uhrzeit(uhrzeit),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (klingeltNicht) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (klingeltNicht) TextDecoration.LineThrough else null
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    if (wecker != null) {
                        Text(
                            weckerArt(wecker, mehrereSchichten),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (wecker.naechster) Etikett("nächster", hervorgehoben = true)
                    } else {
                        Text(
                            UEBERSPRUNGEN_TEXT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.warning
                        )
                    }
                }
            }
        }
    ) {
        when {
            wecker?.nichtWeckenMoeglich == true -> SkipKnopf(
                text = "Nicht wecken",
                laeuft = aktionen.skipLaeuft,
                enabled = !aktionen.gesperrt,
                onClick = { aktionen.onNichtWecken(wecker.triggerTime) }
            )
            uebersprungen -> SkipKnopf(
                text = "Doch wecken",
                laeuft = aktionen.skipLaeuft,
                enabled = !aktionen.gesperrt,
                onClick = aktionen.onDochWecken
            )
        }
    }
}

@Composable
private fun SkipKnopf(text: String, laeuft: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (laeuft) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}

/**
 * Das Bottom-Sheet eines Tages: die Wecker, je Dienst die Wirkungen (aus [SchichtFolgen.alsText],
 * derselben Zeile wie bei den Schichttypen - keine Nachbildung) und „Dienst fällt aus“ bzw.
 * „Zurücknehmen“ mit der Erklaerung direkt am Knopf.
 *
 * `ModalBottomSheet` ist ein eigenes Fenster und behandelt Zurueck selbst - ein neuer
 * `NavigationState` entsteht dadurch nicht.
 *
 * @param onSchliessen nach jeder Aktion: deren Ausgang (Hinweis im Tab) steht unter dem Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagDetails(
    tag: WeckerTag,
    heute: LocalDate,
    wirkungen: Map<String, SchichtWirkung>,
    aktionen: TagesAktionen,
    onSchliessen: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onSchliessen,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpacingConstants.PADDING_SCREEN_HORIZONTAL)
                .padding(bottom = SpacingConstants.SPACING_LARGE)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
        ) {
            TagKopf(tag, heute)

            tag.eintraege.forEach { eintrag ->
                val uhrzeit = when (eintrag) {
                    is WeckerEintrag.Wecker -> eintrag.uhrzeit
                    is WeckerEintrag.Uebersprungen -> eintrag.uhrzeit
                }
                InhaltUndBedienung(
                    inhalt = { modifier ->
                        Column(modifier = modifier) {
                            Text("Wecker ${uhrzeit(uhrzeit)}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                weckerBeschreibung(eintrag),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                ) {
                    when {
                        eintrag is WeckerEintrag.Wecker && eintrag.nichtWeckenMoeglich -> SkipKnopf(
                            text = "Nicht wecken",
                            laeuft = aktionen.skipLaeuft,
                            enabled = !aktionen.gesperrt,
                            onClick = {
                                aktionen.onNichtWecken(eintrag.triggerTime)
                                onSchliessen()
                            }
                        )
                        eintrag is WeckerEintrag.Uebersprungen -> SkipKnopf(
                            text = "Doch wecken",
                            laeuft = aktionen.skipLaeuft,
                            enabled = !aktionen.gesperrt,
                            onClick = {
                                aktionen.onDochWecken()
                                onSchliessen()
                            }
                        )
                    }
                }
            }

            WirkungenDesTages(tag, wirkungen)

            HorizontalDivider()

            if (tag.freigegeben) {
                Text(ZURUECKNEHMEN_ERKLAERUNG, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        aktionen.onZuruecknehmen(tag.datum)
                        onSchliessen()
                    },
                    enabled = !aktionen.gesperrt
                ) { Text("Zurücknehmen") }
            } else {
                Text("Dienst fällt aus", style = MaterialTheme.typography.titleSmall)
                Text(
                    DIENST_FAELLT_AUS_ERKLAERUNG,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        aktionen.onDienstFaelltAus(tag.datum)
                        onSchliessen()
                    },
                    enabled = !aktionen.gesperrt
                ) { Text("Dienst fällt aus") }
            }
        }
    }
}

/**
 * Je bekanntem Dienst des Tages die Statuszeile der Schichttypen. Ein freigegebener Tag hat keine
 * Wirkungen mehr (`FreieTageStore.filtereSpannen`) - dort steht nichts, statt einer Zeile, die
 * nicht eintritt.
 */
@Composable
private fun WirkungenDesTages(tag: WeckerTag, wirkungen: Map<String, SchichtWirkung>) {
    if (tag.freigegeben) return
    val bekannte = tag.schichten
        .map { reinerSchichtname(it.name) }
        .distinct()
        .mapNotNull { name -> wirkungen[name]?.let { name to it } }
    if (bekannte.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
        Text("Was der Dienst sonst auslöst", style = MaterialTheme.typography.titleSmall)
        bekannte.forEach { (name, wirkung) ->
            Text(
                if (bekannte.size > 1) "$name: ${wirkung.folgen.alsText()}" else wirkung.folgen.alsText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (wirkung.definition.isOnCall) {
                Text(
                    RUFBEREITSCHAFT_BESCHREIBUNG,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
