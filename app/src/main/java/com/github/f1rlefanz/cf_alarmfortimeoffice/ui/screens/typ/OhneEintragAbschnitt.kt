package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.TypListeTexte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.StatusTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.tonPaar
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichttypBlock
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeitereOptionenStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.listenCode
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel

/**
 * Die Texte der festen Zeile „Ohne Kalendereintrag“ und ihrer Wahl (Prototyp `typ.fest`,
 * `BILDSCHIRME.md` B3o). Pflichttexte wortgleich zur Vorlage; was die Vorlage fuer die spaeteren
 * Etappen verspricht, steht hier nur, soweit es heute stimmt - Wecker, Dimmen und „Nicht stoeren“
 * richten sich nach der Wahl, Licht haengt bis Etappe 4 noch an Regeln statt am Typ.
 */
object OhneEintragTexte {
    const val TITEL = "Ohne Kalendereintrag"

    // Freigegebene Tage (SPEC 4): der Satz nennt NUR Dimmen und „Nicht stoeren“. Einen Wecker „wie <Typ>“
    // bekommt ein freigegebener Tag nicht (`tageOhneEintrag` laesst ihn aus).
    const val ERKLAERUNG =
        "Feste Zeile: gilt für jeden Tag, an dem im Kalender gar nichts steht – etwa wenn kein „+“ " +
            "eingetragen wurde oder der Dienstplan noch nicht veröffentlicht ist. Beim Dimmen und bei " +
            "„Nicht stören“ verhalten sich freigegebene Tage („Dienst fällt aus“, Krank) genauso. Steht " +
            "in der ganzen Kalender-Vorausschau kein einziger Termin, stellt die App vorsichtshalber " +
            "keinen Wecker."
    const val WAHL_TITEL = "Tage ohne Eintrag verhalten sich"
    const val EIGENE_EINSTELLUNGEN = "Eigene Einstellungen"

    /** Die Vorlage sagt hier „Wecker, Dimmen usw. direkt hier einstellen“ - einen Wecker gibt es hier nicht. */
    const val EIGENE_EINSTELLUNGEN_BESCHREIBUNG = "Kein Wecker an diesen Tagen."

    const val KEIN_WECKER = "kein Wecker"

    /** Oben im Blatt „Weitere Optionen“: anders als auf einer Typ-Seite gibt es hier kein „Speichern“. */
    const val SOFORT_GESPEICHERT = "Änderungen hier werden sofort gespeichert."

    /** Zusatz in der Rueckfrage vor dem Loeschen des Typs, auf den die feste Zeile verweist. */
    const val LOESCHEN_FOLGE =
        "„Ohne Kalendereintrag“ fällt dabei auf „Eigene Einstellungen“ zurück: an Tagen ohne Eintrag klingelt dann nichts mehr."

    /** „Wie <Name> (<Code>)“ - die Beschriftung der Wahl (Prototyp `wieOptionen`). */
    fun wieLabel(typ: ShiftDefinition): String = "Wie ${typ.name} (${typ.listenCode()})"

    /**
     * Die Zeile unter der Beschriftung: die Kategorie (Prototyp) und - weil man es der Kategorie nicht
     * ansieht - ob der Typ ueberhaupt einen Wecker stellt ([OhneEintragWahl.weckt]).
     */
    fun beschreibung(typ: ShiftDefinition): String =
        "Kategorie ${typ.kategorie.titel()}" + if (OhneEintragWahl.weckt(typ)) "" else " · $KEIN_WECKER"

    /**
     * Die Kurzform der festen Zeile in der Typ-Liste (Prototyp `zeile`): „verhält sich wie <Name>
     * (<Code>)“, solange eine Wahl steht. Stellt der gewaehlte Typ keinen Wecker, steht das dabei: die
     * Zeile sagt, was an einem leeren Tag geschieht, nicht nur, wohin sie verweist. Bei „Eigene
     * Einstellungen“: „kein Wecker“ und die Dimm-Kurzform der eigenen Fenster ([eigeneDimmKurzform]),
     * etwa „kein Wecker · dimmt 22:00–07:00“.
     */
    fun kurzform(config: ShiftConfig): String {
        val typ = OhneEintragWahl.gewaehlterTyp(config)
            ?: return listOfNotNull(
                SchichttypBlock.FesteZeile.kurzform,
                eigeneDimmKurzform(config.ohneKalendereintrag.dimmen)
            ).joinToString(" · ")
        return "verhält sich wie ${typ.name} (${typ.listenCode()})" +
            if (OhneEintragWahl.weckt(typ)) "" else " · $KEIN_WECKER"
    }

    /**
     * Wortlaut wie in den Typ-Zeilen (`dimmKurzform` in `ShiftConfigScreen.kt`): „Dimmen aus“ bei
     * `an = false`, `null` ohne wirksames Fenster. Ohne Wecker gerechnet - an diesen Tagen gibt es
     * keinen eigenen, ein Fenster steht deshalb mit seiner Uhrzeit da, nie mit „Wecker“. Mit
     * geschuetzten Leerzeichen wie dort: umbrochen wird nur hinter dem Trenner (320 dp).
     */
    private fun eigeneDimmKurzform(dimmen: TypDimmen): String? =
        (if (!dimmen.an) TypListeTexte.DIMMEN_AUS else DimFensterAbbildung.kurzform(dimmen, weckerAn = false))
            ?.replace(' ', ' ')

    /** Der Kasten unter der Wahl, solange ein Typ gewaehlt ist (Prototyp `wieAktiv`; Licht erst ab Etappe 4). */
    fun richtetSichNach(typName: String): String =
        "Wecker, Dimmen und „Nicht stören“ kommen dann vom Typ „$typName“."

    fun oeffnen(typName: String): String = "„$typName“ öffnen"
}

/** Eine Zeile der Wahl „Tage ohne Eintrag verhalten sich“; [typId] `null` = „Eigene Einstellungen“. */
internal data class WahlZeile(
    val typId: String?,
    val titel: String,
    val beschreibung: String,
    val ausgewaehlt: Boolean
)

/** Was das Blatt „Weitere Optionen“ von der festen Zeile bekommt ([OhneEintragAbschnittLogik.blattWerte]). */
internal data class BlattWerte(
    val dimmen: DimmenEntwurf,
    val weckerAn: Boolean,
    val mitBlocktagen: Boolean,
    val gespeicherteWaerme: Int?
)

/**
 * Die Rechnung der Seite, ohne Compose (Umbau #191, #223, #238): welche Zeilen die Wahl hat, welcher Typ
 * „<Name> öffnen“ bekommt, dass ein Tipp genau EINMAL schreibt - und was die wiederverwendeten Karten
 * „Bildschirm dimmen“ und „Nicht stören“ sowie das Blatt „Weitere Optionen“ von der festen Zeile bekommen
 * und zurueckgeben. Geschrieben wird nie hier, sondern ueber `ShiftViewModel.waehleOhneEintrag` (der entscheidet,
 * [OhneEintragWahl.waehle], ob sich ueberhaupt etwas aendert) bzw. `ShiftViewModel.aendereOhneEintrag`.
 */
internal object OhneEintragAbschnittLogik {

    /**
     * „Eigene Einstellungen“ zuerst, dann „Wie <Typ>“ fuer jeden verweisbaren Typ in gespeicherter
     * Reihenfolge ([OhneEintragWahl.verweisbareTypen]); gehakt ist, was die Konfiguration meint
     * ([OhneEintragWahl.gewaehlterTyp] - ein Verweis ins Leere liest sich als „Eigene Einstellungen“).
     */
    fun zeilen(config: ShiftConfig): List<WahlZeile> {
        val gewaehlt = OhneEintragWahl.gewaehlterTyp(config)
        val eigene = WahlZeile(
            typId = null,
            titel = OhneEintragTexte.EIGENE_EINSTELLUNGEN,
            beschreibung = OhneEintragTexte.EIGENE_EINSTELLUNGEN_BESCHREIBUNG,
            ausgewaehlt = gewaehlt == null
        )
        return listOf(eigene) + OhneEintragWahl.verweisbareTypen(config.definitions).map { typ ->
            WahlZeile(
                typId = typ.id,
                titel = OhneEintragTexte.wieLabel(typ),
                beschreibung = OhneEintragTexte.beschreibung(typ),
                ausgewaehlt = gewaehlt?.id == typ.id
            )
        }
    }

    /** Der Typ, dessen Seite „<Name> öffnen“ zeigt - `null`, solange „Eigene Einstellungen“ gilt (dann gibt es den Kasten nicht). */
    fun oeffnenZiel(config: ShiftConfig): ShiftDefinition? = OhneEintragWahl.gewaehlterTyp(config)

    /** Ein Tipp auf [zeile]: ein einziger Aufruf von [waehle] mit ihrem Typ - sofort, ohne Entwurf. */
    fun tippe(zeile: WahlZeile, waehle: (typId: String?) -> Unit) = waehle(zeile.typId)

    /**
     * Die eigenen Karten „Bildschirm dimmen“ und „Nicht stören“ gibt es nur bei „Eigene Einstellungen“
     * (Prototyp `zeigeTag`); bei „wie <Typ>“ gilt der Typ. Ein Verweis ins Leere ist „Eigene
     * Einstellungen“ - wie in der Wahl darueber und zur Laufzeit (`TypDimmQuelle`).
     */
    fun zeigtEigeneEinstellungen(config: ShiftConfig): Boolean = OhneEintragWahl.gewaehlterTyp(config) == null

    /**
     * Was die Karte „Bildschirm dimmen“ (und ueber [blattWerte] ihr Blatt) von der festen Zeile bekommt. Die Karte
     * liest nur Dimmen, Wecker und
     * Kategorie: ohne Wecker fehlt „Schon beim Wecker enden“ (an diesen Tagen gibt es keinen eigenen),
     * eine Kategorie ohne Dienstblock laesst die Blocktage weg. Ein gespeichertes „bis Wecker“ bleibt
     * dabei stehen - die Karte schreibt nur, was sie zeigt.
     */
    fun dimmEntwurf(eigene: OhneEintragEinstellungen): TypEntwurf = TypEntwurf(
        name = OhneEintragTexte.TITEL,
        muster = emptyList(),
        weckzeitText = "",
        isEnabled = true,
        isSilent = false,
        weckerAn = false,
        kategorie = ShiftKategorie.FREI,
        dimmen = DimmenEntwurf.aus(eigene.dimmen)
    )

    /**
     * [eigene] nach einer Aenderung der Karte „Bildschirm dimmen“ oder ihres Blatts „Weitere Optionen“. Ueber
     * [DimmenEntwurf.anwenden] auf dem Gespeicherten: undeutbare Fenster bleiben, und die Farbwaerme bleibt,
     * solange das Blatt sie nicht aendert.
     */
    fun nachDimmAenderung(
        eigene: OhneEintragEinstellungen,
        aenderung: (TypEntwurf) -> TypEntwurf
    ): OhneEintragEinstellungen {
        val neu = aenderung(dimmEntwurf(eigene)).dimmen ?: return eigene
        return eigene.copy(dimmen = neu.anwenden(eigene.dimmen))
    }

    /**
     * Die Werte fuer das Blatt „Weitere Optionen“, aus demselben Entwurf wie die Karte ([dimmEntwurf]) -
     * `null` bei „wie <Typ>“: dort gilt der Typ, und es gibt kein Blatt ([zeigtEigeneEinstellungen]).
     */
    fun blattWerte(config: ShiftConfig): BlattWerte? {
        if (!zeigtEigeneEinstellungen(config)) return null
        val eigene = config.ohneKalendereintrag
        val entwurf = dimmEntwurf(eigene)
        return BlattWerte(
            dimmen = DimmenEntwurf.aus(eigene.dimmen),
            weckerAn = entwurf.weckerAn,
            mitBlocktagen = DimmenAbschnittLogik.zeigtBlocktage(entwurf.kategorie),
            gespeicherteWaerme = eigene.dimmen.farbwaerme
        )
    }

    /**
     * Der Weg der Aenderungen aus dem Blatt: jede geht SOFORT an [schreibe]
     * (`ShiftViewModel.aendereOhneEintrag`) und dort ueber [nachDimmAenderung] - kein Entwurf, kein „Speichern“.
     */
    fun blattSchreibweg(
        schreibe: ((OhneEintragEinstellungen) -> OhneEintragEinstellungen) -> Unit
    ): ((DimmenEntwurf) -> DimmenEntwurf) -> Unit = { aenderung ->
        schreibe { eigene -> nachDimmAenderung(eigene) { e -> e.copy(dimmen = e.dimmen?.let(aenderung)) } }
    }

    /** Was die Karte „Nicht stören“ zeigt; „Während des Kalendereintrags“ gibt es an der festen Zeile nicht. */
    fun dndWerte(eigene: OhneEintragEinstellungen): NichtStoerenEntwurf =
        NichtStoerenEntwurf.aus(TypNichtStoeren(solangeGedimmt = eigene.nichtStoerenSolangeGedimmt))

    /** [eigene] nach einer Aenderung der Karte „Nicht stören“: gespeichert wird nur „Solange gedimmt wird“. */
    fun nachDndAenderung(
        eigene: OhneEintragEinstellungen,
        aenderung: (NichtStoerenEntwurf) -> NichtStoerenEntwurf
    ): OhneEintragEinstellungen =
        eigene.copy(nichtStoerenSolangeGedimmt = aenderung(dndWerte(eigene)).solangeGedimmt)
}

/**
 * Der Inhalt der Seite „Ohne Kalendereintrag“ (SPEC 3.3 Punkt 2, Prototyp `typ.fest`, B3o): die Karte
 * „Typ“ mit dem Namen und der Erklaerung, die Karte „Tage ohne Eintrag verhalten sich“ mit der Wahl und
 * darunter - bei „Eigene Einstellungen“ die Karten „Bildschirm dimmen“ ([DimmenAbschnitt]) und „Nicht
 * stören“ ([NichtStoerenAbschnitt]), bei „wie <Typ>“ der Kasten mit „<Name> öffnen“. Jede Aenderung wirkt
 * SOFORT ([onWaehle] bzw. [onAendere] schreiben); die Seite hat deshalb weder Entwurf noch „Speichern“.
 * Zustandslos: [config] kommt von der Seite.
 *
 * [onAendere] bekommt eine UMFORMUNG der eigenen Einstellungen, keinen fertigen Wert: angewandt wird sie
 * auf den zuletzt beauftragten Stand, auch wenn der noch gespeichert wird.
 *
 * @param onWeitereOptionen oeffnet aus der Karte „Bildschirm dimmen“ das Blatt „Weitere Optionen“
 *   ([OhneEintragWeitereOptionen]); `null` = keine Zeile.
 */
internal fun LazyListScope.ohneEintragAbschnitt(
    config: ShiftConfig,
    onWaehle: (typId: String?) -> Unit,
    onAendere: ((OhneEintragEinstellungen) -> OhneEintragEinstellungen) -> Unit,
    onTypOeffnen: (ShiftDefinition) -> Unit,
    onWeitereOptionen: (() -> Unit)? = null
) {
    item(key = "ohne-eintrag-typ") { TypKarte() }
    item(key = "ohne-eintrag-wahl") { WahlKarte(OhneEintragAbschnittLogik.zeilen(config), onWaehle) }
    if (OhneEintragAbschnittLogik.zeigtEigeneEinstellungen(config)) {
        val eigene = config.ohneKalendereintrag
        item(key = "ohne-eintrag-dimmen") {
            DimmenAbschnitt(
                entwurf = OhneEintragAbschnittLogik.dimmEntwurf(eigene),
                giltOhneEintrag = true,
                aendere = { aenderung ->
                    onAendere { OhneEintragAbschnittLogik.nachDimmAenderung(it, aenderung) }
                },
                onWeitereOptionen = onWeitereOptionen
            )
        }
        item(key = "ohne-eintrag-nichtstoeren") {
            NichtStoerenAbschnitt(
                werte = OhneEintragAbschnittLogik.dndWerte(eigene),
                // `null` = die feste Zeile: „Während des Kalendereintrags“ steht gesperrt da.
                kategorie = null,
                dimmen = DimmenEntwurf.aus(eigene.dimmen),
                aendere = { aenderung ->
                    onAendere { OhneEintragAbschnittLogik.nachDndAenderung(it, aenderung) }
                }
            )
        }
    }
    OhneEintragAbschnittLogik.oeffnenZiel(config)?.let { typ ->
        item(key = "ohne-eintrag-richtet-sich-nach") { RichtetSichNach(typ, onOeffnen = { onTypOeffnen(typ) }) }
    }
}

/**
 * Das Blatt „Weitere Optionen“ der festen Zeile (#238): dasselbe wie auf einer Typ-Seite
 * ([WeitereOptionenBlatt]), nur schreibt hier jede Aenderung sofort ([OhneEintragAbschnittLogik.blattSchreibweg]).
 * [werte] kommen aus dem zuletzt beauftragten Stand; die uebrigen Parameter wie dort.
 */
@Composable
internal fun OhneEintragWeitereOptionen(
    werte: BlattWerte,
    stand: WeitereOptionenStand?,
    tageslicht: TageslichtStand?,
    ankerBeimOeffnen: Collection<Int>,
    onAendere: ((OhneEintragEinstellungen) -> OhneEintragEinstellungen) -> Unit,
    onSchliessen: () -> Unit
) {
    WeitereOptionenBlatt(
        dimmen = werte.dimmen,
        weckerAn = werte.weckerAn,
        mitBlocktagen = werte.mitBlocktagen,
        gespeicherteWaerme = werte.gespeicherteWaerme,
        stand = stand,
        tageslicht = tageslicht,
        speicherHinweis = OhneEintragTexte.SOFORT_GESPEICHERT,
        ankerBeimOeffnen = ankerBeimOeffnen,
        aendere = OhneEintragAbschnittLogik.blattSchreibweg(onAendere),
        onSchliessen = onSchliessen
    )
}

/** Weisse Flaeche mit Rand statt `surfaceVariant` (hell identisch mit dem Hintergrund) - ui-texte-und-layout.md. */
@Composable
private fun AbschnittKarte(titel: String, inhalt: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                titel,
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
            inhalt()
        }
    }
}

/** Karte „Typ“: der feste Name (kein Feld - die Zeile laesst sich weder umbenennen noch loeschen) und die Erklaerung. */
@Composable
private fun TypKarte() {
    AbschnittKarte(titel = TypAbschnittTexte.ABSCHNITT) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL)
        ) {
            Text(
                TypAbschnittTexte.NAME,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                OhneEintragTexte.TITEL,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            OhneEintragTexte.ERKLAERUNG,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(SpacingConstants.SPACING_LARGE)
        )
    }
}

/** Karte „Tage ohne Eintrag verhalten sich“ (Prototyp `.opt`): Radio-Kreis, Beschriftung, Beschreibung je Zeile. */
@Composable
private fun WahlKarte(zeilen: List<WahlZeile>, onWaehle: (typId: String?) -> Unit) {
    AbschnittKarte(titel = OhneEintragTexte.WAHL_TITEL) {
        zeilen.forEachIndexed { index, zeile ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Option(zeile, onClick = { OhneEintragAbschnittLogik.tippe(zeile, onWaehle) })
        }
    }
}

/** Eine Zeile der Wahl. Ganze Zeile antippbar. */
@Composable
private fun Option(zeile: WahlZeile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48 dp: ein Radio mit `onClick = null` bringt die Mindesthoehe nicht selbst mit.
            .heightIn(min = 48.dp)
            .selectable(selected = zeile.ausgewaehlt, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_SMALL),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = zeile.ausgewaehlt, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(zeile.titel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                zeile.beschreibung,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Der Kasten unter der Wahl (Prototyp `.banner`): derselbe Warnton wie der Banner der Neuanlage
 * ([NeuAnlageBanner]) - hell die Schrift des Prototyps (#4D2E00), dunkel das Paar.
 */
@Composable
private fun RichtetSichNach(typ: ShiftDefinition, onOeffnen: () -> Unit) {
    val dunkel = isSystemInDarkTheme()
    val paar = tonPaar(StatusTon.WARNUNG, dunkel)
    Surface(shape = RoundedCornerShape(14.dp), color = paar.flaeche, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                OhneEintragTexte.richtetSichNach(typ.name),
                style = MaterialTheme.typography.bodyMedium,
                color = if (dunkel) paar.text else SCHRIFT_BANNER_HELL
            )
            KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = SpacingConstants.SPACING_SMALL) {
                CompactOutlinedButton(onClick = onOeffnen, text = OhneEintragTexte.oeffnen(typ.name))
            }
        }
    }
}
