package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.BLOCKPOSITIONEN
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.ZeitKnopf
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.blockpositionUmschalten
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.pickTime
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Die Rechnung des Abschnitts „Bildschirm dimmen“, ohne Compose (Umbau Etappe 3, #221). Jede Aenderung ist eine
 * Umformung des Dimm-Teils im Entwurf; geschrieben wird erst mit „Speichern“. Wie „Wann“ zu Ankern wird, steht
 * allein in [DimFensterAbbildung] - hier gibt es keine zweite Fensterlogik.
 */
internal object DimmenAbschnittLogik {

    /** Die Vorgabe von „+ Weiteres Fenster“ (Prototyp, Festlegung in #221). */
    private const val NEU_VON = 22 * 60
    private const val NEU_BIS = 7 * 60
    private const val NEU_PROZENT = 40

    /** Die Chips der Blocktage in ihrer Reihenfolge; `null` = „Alle“. */
    val CHIPS: List<Blockposition?> = listOf<Blockposition?>(null) + BLOCKPOSITIONEN

    /** Der Abschnitt fehlt, wo die Karte „Wecker“ fehlt: bei Krank (gibt den Tag frei) und Ignorieren. */
    fun zeigtAbschnitt(kategorie: ShiftKategorie): Boolean = WeckerAbschnittLogik.zeigtWeckerKarte(kategorie)

    /** Blocktage nur, wo ein Block mehrerer Tage vorkommt - dieselbe Frage wie bei den Chips der Tagesleiste. */
    fun zeigtBlocktage(kategorie: ShiftKategorie): Boolean = TagesleisteKarteLogik.zeigtBlockwahl(kategorie)

    /** Nutzt „Ohne Kalendereintrag“ diesen Typ („wie <Typ>“)? Dann gelten seine Fenster auch an Tagen ohne Eintrag. */
    fun giltOhneEintrag(config: ShiftConfig?, typId: String): Boolean =
        config != null && OhneEintragWahl.gewaehlterTyp(config)?.id == typId

    /** Nacht davor 22:00–07:00, 40 %; bei einem Typ mit Wecker endet es schon beim Wecker (Prototyp `fensterDazu`). */
    fun neuesFenster(weckerAn: Boolean): TypDimmFenster = TypDimmFenster(
        fenster = DimFensterAbbildung.zuFenster(
            DimFensterForm(DimWann.NACHT_DAVOR, NEU_VON, NEU_BIS, NEU_PROZENT, beimWecker = weckerAn)
        ),
        staerke = NEU_PROZENT
    )

    fun fensterDazu(dimmen: DimmenEntwurf, weckerAn: Boolean): DimmenEntwurf =
        dimmen.copy(fenster = dimmen.fenster + neuesFenster(weckerAn))

    fun fensterEntfernen(dimmen: DimmenEntwurf, index: Int): DimmenEntwurf =
        dimmen.copy(fenster = dimmen.fenster.filterIndexed { i, _ -> i != index })

    fun fensterAendern(dimmen: DimmenEntwurf, index: Int, aenderung: (TypDimmFenster) -> TypDimmFenster): DimmenEntwurf =
        dimmen.copy(fenster = dimmen.fenster.mapIndexed { i, f -> if (i == index) aenderung(f) else f })

    /** Aendert ein Fenster ueber seine Form. Ein Fenster ohne Form bleibt, wie es ist. */
    fun formAendern(f: TypDimmFenster, aenderung: (DimFensterForm) -> DimFensterForm): TypDimmFenster =
        when (val ansicht = DimFensterAbbildung.deute(f)) {
            is DimFensterAnsicht.Darstellbar -> DimFensterAbbildung.mitForm(f, aenderung(ansicht.form))
            is DimFensterAnsicht.NichtDarstellbar -> f
        }

    /** Von „Nach dem Dienst“ zurueck gilt wieder das Von, das das Fenster noch traegt (Prototyp: `von` bleibt). */
    fun wannWaehlen(f: TypDimmFenster, wann: DimWann): TypDimmFenster =
        formAendern(f) { it.copy(wann = wann, vonMinuten = it.vonMinuten ?: f.fenster.startClockMinutes) }

    /**
     * Ein Tipp auf einen Chip der Blocktage. „Alle“ ([wahl] `null`) waehlt alle; von „Alle“ aus waehlt ein Tag
     * genau diesen (Prototyp), sonst kommt er dazu oder geht - das letzte Haekchen bleibt stehen.
     */
    fun blockTageWaehlen(aktuell: Set<Blockposition>, wahl: Blockposition?): Set<Blockposition> = when {
        wahl == null -> Blockposition.ALLE
        aktuell == Blockposition.ALLE -> setOf(wahl)
        else -> blockpositionUmschalten(aktuell, wahl)
    }

    /** Leuchtet der Chip? Bei „Alle“ leuchtet kein einzelner Tag. */
    fun chipAn(aktuell: Set<Blockposition>, chip: Blockposition?): Boolean =
        if (chip == null) aktuell == Blockposition.ALLE else aktuell != Blockposition.ALLE && chip in aktuell

    /** „Nacht davor“ mit „Von“ vor „Bis“ liegt am Tag selbst (E14) - der Satz „Vom Vorabend …“ waere dort falsch. */
    private fun liegtAmTagSelbst(form: DimFensterForm): Boolean =
        form.wann == DimWann.NACHT_DAVOR && form.vonMinuten != null && form.vonMinuten < form.bisMinuten

    fun erklaerung(form: DimFensterForm): String =
        if (liegtAmTagSelbst(form)) DimmenTexte.NACHT_DAVOR_AM_TAG_SELBST else form.wann.erklaerung

    /** Nur diese zwei Formen haengen an einem Kalendereintrag: an Tagen ohne Eintrag gibt es sie nicht (#227). */
    fun ohneEintragHinweis(form: DimFensterForm, giltOhneEintrag: Boolean): String? =
        DimmenTexte.NICHT_OHNE_EINTRAG.takeIf {
            giltOhneEintrag && (form.wann == DimWann.NACH_DEM_DIENST || liegtAmTagSelbst(form))
        }

    /**
     * „Mit %“: ganze Zahlen bis zur Grenze des Reglers im Regel-Editor ([DimOverlayPrefs.STRENGTH_MAX]).
     * Liefert den Feldtext und den zu uebernehmenden Wert (`null` = leeres Feld, nichts uebernehmen) - oder `null`,
     * wenn die Eingabe gar nicht angenommen wird.
     */
    fun prozentEingabe(eingabe: String): Pair<String, Int?>? {
        if (eingabe.isEmpty()) return "" to null
        if (eingabe.length > 3 || !eingabe.all { it in '0'..'9' }) return null
        val wert = eingabe.toInt().coerceAtMost(DimOverlayPrefs.STRENGTH_MAX)
        return wert.toString() to wert
    }
}

/**
 * Die Karte „Bildschirm dimmen“ der Typ-Seite (SPEC E6 und 3.3 Punkt 6, Prototyp `zeigeTyp`): oben „Dimmen an
 * diesem Tag“, darunter je Fenster „Wann“, „Von“, „Bis“, „Mit %“, „Schon beim Wecker enden“ und die Blocktage,
 * am Ende „+ Weiteres Fenster“. Ein Fenster, das die Form nicht ausdrueckt, steht nur mit seiner Kurzform da.
 * Ausgeschaltet bleiben die Fenster sichtbar, aber ausgegraut und gesperrt. Zustandslos - der Entwurf gehoert der
 * Seite. Nur fuer Typen mit Abschnitt ([DimmenAbschnittLogik.zeigtAbschnitt]).
 */
@Composable
internal fun DimmenAbschnitt(
    entwurf: TypEntwurf,
    giltOhneEintrag: Boolean,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    // Jeder Entwurf der Seite traegt den Dimm-Teil (`TypEntwurf.aus`); ohne ihn gibt es nichts zu bearbeiten.
    val dimmen = entwurf.dimmen ?: return
    val aendereDimmen: ((DimmenEntwurf) -> DimmenEntwurf) -> Unit = { aenderung ->
        aendere { e -> e.dimmen?.let { e.copy(dimmen = aenderung(it)) } ?: e }
    }
    val zeilenRand = Modifier.padding(
        horizontal = SpacingConstants.SPACING_LARGE,
        vertical = SpacingConstants.SPACING_MEDIUM
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                DimmenTexte.TITEL,
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

            Column(modifier = zeilenRand) {
                SwitchRow(
                    title = DimmenTexte.AN_TITEL,
                    description = if (dimmen.an) DimmenTexte.AN_HINWEIS else DimmenTexte.AUS_HINWEIS,
                    checked = dimmen.an,
                    onCheckedChange = { neu -> aendereDimmen { it.copy(an = neu) } },
                    titleFontWeight = FontWeight.Normal
                )
            }

            if (dimmen.fenster.isEmpty()) {
                Text(
                    DimmenTexte.KEIN_FENSTER,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = zeilenRand
                )
            }

            dimmen.fenster.forEachIndexed { index, fenster ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FensterBlock(
                    nummer = index + 1,
                    fenster = fenster,
                    an = dimmen.an,
                    weckerAn = entwurf.weckerAn,
                    mitBlocktagen = DimmenAbschnittLogik.zeigtBlocktage(entwurf.kategorie),
                    giltOhneEintrag = giltOhneEintrag,
                    aendere = { aenderung -> aendereDimmen { DimmenAbschnittLogik.fensterAendern(it, index, aenderung) } },
                    entferne = { aendereDimmen { DimmenAbschnittLogik.fensterEntfernen(it, index) } }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                DimmenTexte.FENSTER_DAZU,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = dimmen.an, role = Role.Button) {
                        aendereDimmen { DimmenAbschnittLogik.fensterDazu(it, entwurf.weckerAn) }
                    }
                    .alpha(if (dimmen.an) 1f else AUSGEGRAUT_ALPHA)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM)
            )
        }
    }
}

/** Ein Fenster: Titel mit ×, darunter - wenn die Form es ausdrueckt - seine Felder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FensterBlock(
    nummer: Int,
    fenster: TypDimmFenster,
    an: Boolean,
    weckerAn: Boolean,
    mitBlocktagen: Boolean,
    giltOhneEintrag: Boolean,
    aendere: ((TypDimmFenster) -> TypDimmFenster) -> Unit,
    entferne: () -> Unit
) {
    // Nur die Texte werden blass - die gesperrten Bedienelemente faerbt Material selbst.
    val blass = Modifier.alpha(if (an) 1f else AUSGEGRAUT_ALPHA)
    val ansicht = DimFensterAbbildung.deute(fenster)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_SMALL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                DimFensterAbbildung.titel(nummer, fenster, weckerAn),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).then(blass)
            )
            IconButton(onClick = entferne, enabled = an) {
                Icon(Icons.Default.Close, contentDescription = DimmenTexte.entfernen(nummer))
            }
        }

        val form = (ansicht as? DimFensterAnsicht.Darstellbar)?.form ?: return@Column

        Text(DimmenTexte.WANN, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = blass)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimWann.entries.forEach { wann ->
                WahlChip(text = wann.label, gewaehlt = form.wann == wann, an = an) {
                    aendere { DimmenAbschnittLogik.wannWaehlen(it, wann) }
                }
            }
        }
        Text(
            DimmenAbschnittLogik.erklaerung(form),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = blass
        )

        val von = form.vonMinuten
        if (form.wann.hatVon && von != null) {
            ZeitZeile(DimmenTexte.VON, hinweis = null, minuten = von, an = an) { neu ->
                aendere { f -> DimmenAbschnittLogik.formAendern(f) { it.copy(vonMinuten = neu) } }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(blass),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(DimmenTexte.VON, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(DimmenTexte.DIENSTENDE, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
        ZeitZeile(DimmenTexte.BIS, hinweis = form.wann.bisHinweis, minuten = form.bisMinuten, an = an) { neu ->
            aendere { f -> DimmenAbschnittLogik.formAendern(f) { it.copy(bisMinuten = neu) } }
        }
        ProzentZeile(prozent = form.prozent, an = an) { neu ->
            aendere { f -> DimmenAbschnittLogik.formAendern(f) { it.copy(prozent = neu) } }
        }
        if (DimFensterAbbildung.zeigeBeimWecker(form.wann, weckerAn)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = form.beimWecker, enabled = an, role = Role.Switch) { neu ->
                        aendere { f -> DimmenAbschnittLogik.formAendern(f) { it.copy(beimWecker = neu) } }
                    }
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(DimmenTexte.BEIM_WECKER, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).then(blass))
                Switch(checked = form.beimWecker, onCheckedChange = null, enabled = an)
            }
        }
        DimmenAbschnittLogik.ohneEintragHinweis(form, giltOhneEintrag)?.let { hinweis ->
            Text(hinweis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = blass)
        }

        if (mitBlocktagen) {
            Text(DimmenTexte.BLOCKTAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = blass)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimmenAbschnittLogik.CHIPS.forEach { chip ->
                    WahlChip(
                        text = DimmenTexte.blockTag(chip),
                        gewaehlt = DimmenAbschnittLogik.chipAn(fenster.fenster.blockPositionen, chip),
                        an = an
                    ) {
                        aendere { f ->
                            val neu = DimmenAbschnittLogik.blockTageWaehlen(f.fenster.blockPositionen, chip)
                            f.copy(fenster = f.fenster.copy(blockPositionen = neu))
                        }
                    }
                }
            }
        }
    }
}

/** Ein Auswahl-Chip wie in der Tagesleiste (Prototyp `.wchip`): Schrift in Textfarbe, gewaehlt mit rotem Rand. */
@Composable
private fun WahlChip(text: String, gewaehlt: Boolean, an: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = gewaehlt,
        onClick = onClick,
        enabled = an,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
        border = FilterChipDefaults.filterChipBorder(
            enabled = an,
            selected = gewaehlt,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 2.dp
        )
    )
}

/** „Von“ bzw. „Bis“: Titel (und Hinweis) links, der Uhrzeit-Knopf rechts. */
@Composable
private fun ZeitZeile(titel: String, hinweis: String?, minuten: Int, an: Boolean, onNeu: (Int) -> Unit) {
    val context = LocalContext.current
    // Ein Wert ausserhalb des Tages (Import) darf weder als „25:00“ erscheinen noch die Auswahl stoeren.
    val imTag = Math.floorMod(minuten, 24 * 60)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (an) 1f else AUSGEGRAUT_ALPHA)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge)
            if (hinweis != null) {
                Text(hinweis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Prototyp `.zeitfeld`: helle Flaeche, fette Schrift in Textfarbe - ohne sie saehe der Knopf gesperrt aus.
        ZeitKnopf(
            minuten = imTag,
            onClick = { pickTime(context, imTag, onNeu) },
            enabled = an,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            shape = FELD_FORM,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

/** „Mit %“: eine Zahl bis zur Grenze des Reglers; ein leeres Feld uebernimmt nichts und fuellt sich beim Verlassen wieder. */
@Composable
private fun ProzentZeile(prozent: Int, an: Boolean, onProzent: (Int) -> Unit) {
    var text by remember(prozent) { mutableStateOf(prozent.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (an) 1f else AUSGEGRAUT_ALPHA)) {
            Text(DimmenTexte.MIT, style = MaterialTheme.typography.bodyLarge)
            Text(DimmenTexte.MIT_HINWEIS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { eingabe ->
                DimmenAbschnittLogik.prozentEingabe(eingabe)?.let { (neuerText, wert) ->
                    text = neuerText
                    if (wert != null) onProzent(wert)
                }
            },
            modifier = Modifier
                .width(PROZENTFELD_BREITE)
                .onFocusChanged { if (!it.isFocused && text.isEmpty()) text = prozent.toString() },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            shape = FELD_FORM,
            singleLine = true,
            enabled = an,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

/** Deckkraft ausgegrauter Texte, wie in der Karte „Wecker“: blass, aber lesbar. */
private const val AUSGEGRAUT_ALPHA = 0.6f

/** Breit genug fuer drei Ziffern in Schrift 1,3. */
private val PROZENTFELD_BREITE = 96.dp

/** Die Ecken der Eingabefelder eines Fensters (Prototyp `.zeitfeld`). */
private val FELD_FORM = RoundedCornerShape(12.dp)
