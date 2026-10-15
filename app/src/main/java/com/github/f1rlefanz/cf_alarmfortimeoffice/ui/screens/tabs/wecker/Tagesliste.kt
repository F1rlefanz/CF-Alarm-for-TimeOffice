package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.reinerSchichtname
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactContentPadding
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.FreigabeArt
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.Kaertchen
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
    val onZuruecknehmen: (LocalDate) -> Unit,
    /** #142: oeffnet den Zeitraum-Dialog mit diesem Tag als Beginn. */
    val onMehrereTage: (LocalDate) -> Unit
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

            // #142: je Quelle eigener Text. „Zurücknehmen“ nur, wo es eine eigene Freigabe gibt -
            // den Kalender-Schluessel schreibt allein der Sync, ein Knopf dort taete nichts.
            when (tag.freigabe) {
                FreigabeArt.HAND -> InhaltUndBedienung(
                    inhalt = { modifier ->
                        Text(
                            DIENST_FAELLT_AUS_KURZ,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = modifier
                        )
                    },
                    untereinanderAusrichtung = Alignment.Start
                ) {
                    KartenKnopf(
                        text = "Zurücknehmen",
                        laeuft = false,
                        enabled = !aktionen.gesperrt,
                        onClick = { aktionen.onZuruecknehmen(tag.datum) }
                    )
                }
                FreigabeArt.KALENDER -> {
                    GedaempfterText(KRANK_KALENDER_KURZ)
                    GedaempfterText(KRANK_KALENDER_ZURUECK)
                }
                FreigabeArt.BEIDE -> {
                    InhaltUndBedienung(
                        inhalt = { modifier -> GedaempfterText(KRANK_KALENDER_KURZ, modifier) },
                        untereinanderAusrichtung = Alignment.Start
                    ) {
                        KartenKnopf(
                            text = "Zurücknehmen",
                            laeuft = false,
                            enabled = !aktionen.gesperrt,
                            onClick = { aktionen.onZuruecknehmen(tag.datum) }
                        )
                    }
                    // Direkt unter dem Knopf (Skill: ein Hinweis zu einem Knopf steht unter IHM).
                    GedaempfterText(BEIDE_KNOPF_HINWEIS)
                }
                null -> Unit
            }

            if (tag.kaertchen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    tag.kaertchen.forEach { Etikett(kaertchenText(it), EtikettTon.WIRKUNG, kaertchenSymbol(it)) }
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
        // #142: BEIDE traegt das Kalender-Etikett - wirksam bleibt der Tag, solange der Termin steht.
        when (tag.freigabe) {
            FreigabeArt.HAND -> Etikett(DIENST_FAELLT_AUS_ETIKETT, EtikettTon.GEDAEMPFT)
            FreigabeArt.KALENDER, FreigabeArt.BEIDE -> Etikett(KRANK_KALENDER_ETIKETT, EtikettTon.GEDAEMPFT)
            null -> Unit
        }
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
        is WeckerEintrag.Schlummert -> eintrag.uhrzeit
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
                // Zeilenabstand wie im TagKopf: bei 320 dp und Schrift 1,3 brechen die gefuellten
                // Etiketten um und stiessen sonst Flaeche an Flaeche aneinander.
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    if (wecker != null) {
                        Text(
                            weckerArt(wecker, mehrereSchichten),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (eintrag is WeckerEintrag.Schlummert) {
                        Text(
                            SCHLUMMERT_ART,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    weckerEtiketten(eintrag).forEach { Etikett(it.text, it.ton) }
                }
            }
        },
        untereinanderAusrichtung = Alignment.Start
    ) {
        when {
            wecker?.nichtWeckenMoeglich == true -> KartenKnopf(
                text = "Nicht wecken",
                laeuft = aktionen.skipLaeuft,
                enabled = !aktionen.gesperrt,
                onClick = { aktionen.onNichtWecken(wecker.triggerTime) }
            )
            uebersprungen -> KartenKnopf(
                text = "Doch wecken",
                laeuft = aktionen.skipLaeuft,
                enabled = !aktionen.gesperrt,
                onClick = aktionen.onDochWecken
            )
        }
    }
}

/**
 * Die Knoepfe an Wecker- und Tageszeilen („Nicht wecken“, „Doch wecken“, „Zurücknehmen“): ein
 * umrandeter Kompaktknopf wie „Probehören“ und „Dienst fällt aus“ - als reiner Textknopf sah er am
 * Fairphone anders aus als alle uebrigen und stand allein am Rand (14.10.2026). Das 48-dp-Ziel
 * bringt `OutlinedButton` ueber `minimumInteractiveComponentSize` selbst mit.
 *
 * Laeuft der Vorgang, dreht sich ein Kreis UEBER der unsichtbar weiter gesetzten Beschriftung: der
 * Knopf behaelt seine Breite, die Zeile springt nicht.
 */
@Composable
private fun KartenKnopf(text: String, laeuft: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = CompactContentPadding) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (laeuft) 0f else 1f)
            )
            if (laeuft) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
                val titel = when (eintrag) {
                    is WeckerEintrag.Wecker -> "Wecker ${uhrzeit(eintrag.uhrzeit)}"
                    is WeckerEintrag.Uebersprungen -> "Wecker ${uhrzeit(eintrag.uhrzeit)}"
                    is WeckerEintrag.Schlummert -> "Schlummert bis ${uhrzeit(eintrag.uhrzeit)}"
                }
                InhaltUndBedienung(
                    inhalt = { modifier ->
                        Column(modifier = modifier) {
                            Text(titel, style = MaterialTheme.typography.titleSmall)
                            Text(
                                weckerBeschreibung(eintrag),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    untereinanderAusrichtung = Alignment.Start
                ) {
                    when {
                        eintrag is WeckerEintrag.Wecker && eintrag.nichtWeckenMoeglich -> KartenKnopf(
                            text = "Nicht wecken",
                            laeuft = aktionen.skipLaeuft,
                            enabled = !aktionen.gesperrt,
                            onClick = {
                                aktionen.onNichtWecken(eintrag.triggerTime)
                                onSchliessen()
                            }
                        )
                        eintrag is WeckerEintrag.Uebersprungen -> KartenKnopf(
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

            when (tag.freigabe) {
                FreigabeArt.HAND, FreigabeArt.BEIDE -> {
                    Text(
                        // #142: bei BEIDEN Quellen sagt der Text, dass der Tag trotzdem frei bleibt.
                        if (tag.freigabe == FreigabeArt.BEIDE) ZURUECKNEHMEN_ERKLAERUNG_BEIDE else ZURUECKNEHMEN_ERKLAERUNG,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            aktionen.onZuruecknehmen(tag.datum)
                            onSchliessen()
                        },
                        enabled = !aktionen.gesperrt
                    ) { Text("Zurücknehmen") }
                }
                // #142: kein Knopf - den Kalender-Schluessel schreibt allein der Sync.
                FreigabeArt.KALENDER -> {
                    Text(KRANK_KALENDER_ERKLAERUNG, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        KRANK_KALENDER_ZURUECK,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                null -> {
                    Text("Dienst fällt aus", style = MaterialTheme.typography.titleSmall)
                    Text(
                        DIENST_FAELLT_AUS_ERKLAERUNG,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // KnopfZeile statt Row + weight: bei 320 dp mit Schrift 1,3 stehen die beiden
                    // Knoepfe untereinander, statt „Mehrere T…“ zu kuerzen (Skill UI).
                    KnopfZeile {
                        OutlinedButton(
                            onClick = {
                                aktionen.onDienstFaelltAus(tag.datum)
                                onSchliessen()
                            },
                            enabled = !aktionen.gesperrt
                        ) { Text("Dienst fällt aus") }
                        // #142: derselbe Anlass fuer mehrere Tage - der Dialog beginnt mit DIESEM Tag.
                        OutlinedButton(
                            onClick = {
                                aktionen.onMehrereTage(tag.datum)
                                onSchliessen()
                            },
                            enabled = !aktionen.gesperrt
                        ) { Text(MEHRERE_TAGE_KNOPF) }
                    }
                }
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

/** Ein Satz in der gedaempften Farbe der Kartentexte. */
@Composable
private fun GedaempfterText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Dieselben Symbole wie an der Stelle, die die Funktion einstellt (Tab bzw. Einstellung). */
private fun kaertchenSymbol(kaertchen: Kaertchen): ImageVector = when (kaertchen) {
    Kaertchen.NICHT_STOEREN -> Icons.Default.DoNotDisturbOn
    Kaertchen.DIMMEN -> Icons.Default.DarkMode
    Kaertchen.LICHT -> Icons.Default.Lightbulb
    Kaertchen.RUFBEREITSCHAFT -> Icons.Default.PhoneInTalk
}
