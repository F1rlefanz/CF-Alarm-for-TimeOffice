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
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.KnopfZeile
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.StatusTon
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.tonPaar
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichttypBlock
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.listenCode
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel

/**
 * Die Texte der festen Zeile „Ohne Kalendereintrag“ und ihrer Wahl (Prototyp `typ.fest`,
 * `BILDSCHIRME.md` B3o). Pflichttexte wortgleich zur Vorlage; was die Vorlage fuer die spaeteren
 * Etappen verspricht, steht hier nur, soweit es heute stimmt - in Etappe 2 richtet sich an diesen
 * Tagen nur der WECKER nach dem Typ (Dimmen, „Nicht stoeren“ und Licht kommen am Typ spaeter).
 */
object OhneEintragTexte {
    const val TITEL = "Ohne Kalendereintrag"
    const val ERKLAERUNG =
        "Feste Zeile: gilt für jeden Tag, an dem im Kalender gar nichts steht – etwa wenn kein „+“ " +
            "eingetragen wurde oder der Dienstplan noch nicht veröffentlicht ist. Steht in der ganzen " +
            "Kalender-Vorausschau kein einziger Termin, stellt die App vorsichtshalber keinen Wecker."
    const val WAHL_TITEL = "Tage ohne Eintrag verhalten sich"
    const val EIGENE_EINSTELLUNGEN = "Eigene Einstellungen"

    /** Die Vorlage sagt hier „Wecker, Dimmen usw. direkt hier einstellen“ - eine Oberflaeche, die es noch nicht gibt. */
    const val EIGENE_EINSTELLUNGEN_BESCHREIBUNG = "Kein Wecker an diesen Tagen."

    const val KEIN_WECKER = "kein Wecker"

    /** Zusatz in der Rueckfrage vor dem Loeschen des Typs, auf den die feste Zeile verweist. */
    const val LOESCHEN_FOLGE =
        "„Ohne Kalendereintrag“ fällt dabei auf „Eigene Einstellungen“ zurück: an Tagen ohne Eintrag klingelt dann nichts mehr."

    /** „Wie <Name> (<Code>)“ - die Beschriftung der Wahl (Prototyp `wieOptionen`). */
    fun wieLabel(typ: ShiftDefinition): String = "Wie ${typ.name} (${typ.listenCode()})"

    /**
     * Die Zeile unter der Beschriftung: die Kategorie (Prototyp) und - weil die Wahl in Etappe 2 nur
     * den Wecker bewirkt - ob der Typ ueberhaupt einen stellt ([OhneEintragWahl.weckt]).
     */
    fun beschreibung(typ: ShiftDefinition): String =
        "Kategorie ${typ.kategorie.titel()}" + if (OhneEintragWahl.weckt(typ)) "" else " · $KEIN_WECKER"

    /**
     * Die Kurzform der festen Zeile in der Typ-Liste (Prototyp `zeile`): „verhält sich wie <Name>
     * (<Code>)“, solange eine Wahl steht, sonst „kein Wecker“ - der Zustand „Eigene Einstellungen“ in
     * Etappe 2. Stellt der gewaehlte Typ keinen Wecker, steht das dabei: die Zeile sagt, was an einem
     * leeren Tag geschieht, nicht nur, wohin sie verweist.
     */
    fun kurzform(config: ShiftConfig): String {
        val typ = OhneEintragWahl.gewaehlterTyp(config) ?: return SchichttypBlock.FesteZeile.kurzform
        return "verhält sich wie ${typ.name} (${typ.listenCode()})" +
            if (OhneEintragWahl.weckt(typ)) "" else " · $KEIN_WECKER"
    }

    /** Der Kasten unter der Wahl, solange ein Typ gewaehlt ist (Prototyp `wieAktiv`). */
    fun richtetSichNach(typName: String): String = "Der Wecker richtet sich dann nach dem Typ „$typName“."

    fun oeffnen(typName: String): String = "„$typName“ öffnen"
}

/** Eine Zeile der Wahl „Tage ohne Eintrag verhalten sich“; [typId] `null` = „Eigene Einstellungen“. */
internal data class WahlZeile(
    val typId: String?,
    val titel: String,
    val beschreibung: String,
    val ausgewaehlt: Boolean
)

/**
 * Die Rechnung der Wahl, ohne Compose (Umbau #191): welche Zeilen es gibt, welcher Typ „<Name> öffnen“
 * bekommt, und dass ein Tipp genau EINMAL schreibt. Geschrieben wird nie hier, sondern ueber den
 * uebergebenen `ShiftViewModel.waehleOhneEintrag` - der entscheidet ([OhneEintragWahl.waehle]), ob sich
 * ueberhaupt etwas aendert.
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
}

/**
 * Der Inhalt der Seite „Ohne Kalendereintrag“ (SPEC 3.3 Punkt 2, Prototyp `typ.fest`, B3o): die Karte
 * „Typ“ mit dem Namen und der Erklaerung, die Karte „Tage ohne Eintrag verhalten sich“ mit der Wahl und -
 * solange ein Typ gewaehlt ist - der Kasten mit „<Name> öffnen“. Eine Wahl wirkt SOFORT ([onWaehle] schreibt);
 * die Seite hat deshalb weder Entwurf noch „Speichern“. Zustandslos: [config] kommt von der Seite.
 */
internal fun LazyListScope.ohneEintragAbschnitt(
    config: ShiftConfig,
    onWaehle: (typId: String?) -> Unit,
    onTypOeffnen: (ShiftDefinition) -> Unit
) {
    item(key = "ohne-eintrag-typ") { TypKarte() }
    item(key = "ohne-eintrag-wahl") { WahlKarte(OhneEintragAbschnittLogik.zeilen(config), onWaehle) }
    OhneEintragAbschnittLogik.oeffnenZiel(config)?.let { typ ->
        item(key = "ohne-eintrag-richtet-sich-nach") { RichtetSichNach(typ, onOeffnen = { onTypOeffnen(typ) }) }
    }
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
