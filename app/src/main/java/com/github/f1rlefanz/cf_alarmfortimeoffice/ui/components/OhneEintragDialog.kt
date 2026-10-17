package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.kategorieFarben
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.LayoutFractions
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichttypBlock
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.listenCode
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel

/**
 * Die Texte der festen Zeile „Ohne Kalendereintrag“ und ihrer Wahl (Prototyp `typ.fest`,
 * `BILDSCHIRME.md` B3o). Pflichttexte wortgleich zur Vorlage; was die Vorlage fuer die spaeteren
 * Etappen verspricht, steht hier nur, soweit es heute stimmt - in Etappe 1 richtet sich an diesen
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
    const val FERTIG = "Fertig"

    /** „Wie <Name> (<Code>)“ - die Beschriftung der Wahl (Prototyp `wieOptionen`). */
    fun wieLabel(typ: ShiftDefinition): String = "Wie ${typ.name} (${typ.listenCode()})"

    /**
     * Die Zeile unter der Beschriftung: die Kategorie (Prototyp) und - weil die Wahl in Etappe 1 nur
     * den Wecker bewirkt - ob der Typ ueberhaupt einen stellt ([OhneEintragWahl.weckt]).
     */
    fun beschreibung(typ: ShiftDefinition): String =
        "Kategorie ${typ.kategorie.titel()}" + if (OhneEintragWahl.weckt(typ)) "" else " · $KEIN_WECKER"

    /**
     * Die Kurzform der festen Zeile in der Typ-Liste (Prototyp `zeile`): „verhält sich wie <Name>
     * (<Code>)“, solange eine Wahl steht, sonst „kein Wecker“ - der Zustand „Eigene Einstellungen“ in
     * Etappe 1. Stellt der gewaehlte Typ keinen Wecker, steht das dabei: die Zeile sagt, was an einem
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

/**
 * Die Wahl „Tage ohne Eintrag verhalten sich“ (SPEC 3.3 Punkt 2): „Eigene Einstellungen“ oder
 * „Wie <Typ>“ fuer jeden Typ ausser Ignorieren. Sie steht hier, bis die Typ-Seite des Umbaus
 * (Etappe 2) sie uebernimmt.
 *
 * Eine Wahl wirkt SOFORT ([onWaehle] schreibt) und der Dialog bleibt offen, damit der Kasten mit
 * „<Typ> öffnen“ zu sehen ist; geschlossen wird mit „Fertig“, dem X oder Zurueck.
 *
 * EIN DIALOG, damit Zurueck ihn schliesst: ein Dialog hat ein eigenes Fenster und bekommt die
 * Zurueck-Taste vor dem `BackHandler` in `MainScreen` - der beendet die App nicht, solange er offen
 * ist (Skill cfalarm-ui-und-navigation).
 */
@Composable
fun OhneEintragDialog(
    config: ShiftConfig,
    onWaehle: (typId: String?) -> Unit,
    onTypOeffnen: (ShiftDefinition) -> Unit,
    onDismiss: () -> Unit
) {
    val ziele = OhneEintragWahl.verweisbareTypen(config.definitions)
    val gewaehlt = OhneEintragWahl.gewaehlterTyp(config)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(modifier = Modifier.fillMaxWidth(LayoutFractions.DIALOG_WIDTH)) {
            Column(modifier = Modifier.padding(SpacingConstants.SPACING_LARGE)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = OhneEintragTexte.TITEL,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_SMALL))

                // weight(1f, fill = false): die Liste waechst mit den Typen, ueberschreitet aber nie den
                // Dialog - sonst schiebt sie „Fertig“ aus dem Bild (Skill: LazyColumn in einer Column).
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
                ) {
                    item(key = "erklaerung") {
                        Text(
                            OhneEintragTexte.ERKLAERUNG,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item(key = "wahl-titel") {
                        Text(
                            OhneEintragTexte.WAHL_TITEL,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { heading() }
                        )
                    }

                    item(key = "eigene") {
                        Option(
                            titel = OhneEintragTexte.EIGENE_EINSTELLUNGEN,
                            beschreibung = OhneEintragTexte.EIGENE_EINSTELLUNGEN_BESCHREIBUNG,
                            ausgewaehlt = gewaehlt == null,
                            onClick = { onWaehle(null) }
                        )
                    }

                    items(ziele, key = { it.id }) { typ ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Option(
                            titel = OhneEintragTexte.wieLabel(typ),
                            beschreibung = OhneEintragTexte.beschreibung(typ),
                            ausgewaehlt = gewaehlt?.id == typ.id,
                            onClick = { onWaehle(typ.id) }
                        )
                    }

                    if (gewaehlt != null) {
                        item(key = "richtet-sich-nach") {
                            RichtetSichNach(typ = gewaehlt, onOeffnen = { onTypOeffnen(gewaehlt) })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

                KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = SpacingConstants.SPACING_SMALL) {
                    CompactOutlinedButton(onClick = onDismiss, text = OhneEintragTexte.FERTIG)
                }
            }
        }
    }
}

/** Eine Zeile der Wahl (Prototyp `.opt`): Radio-Kreis, Beschriftung, Beschreibung. Ganze Zeile antippbar. */
@Composable
private fun Option(titel: String, beschreibung: String, ausgewaehlt: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 48 dp: ein Radio mit `onClick = null` bringt die Mindesthoehe nicht selbst mit.
            .heightIn(min = 48.dp)
            .selectable(selected = ausgewaehlt, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = SpacingConstants.SPACING_SMALL),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = ausgewaehlt, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                beschreibung,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Der Kasten unter der Wahl (Prototyp `.banner`, dieselbe Bernsteinfamilie wie im Einordnen-Dialog). */
@Composable
private fun RichtetSichNach(typ: ShiftDefinition, onOeffnen: () -> Unit) {
    val farben = kategorieFarben(ShiftKategorie.KRANK, isSystemInDarkTheme())
    Surface(shape = RoundedCornerShape(14.dp), color = farben.flaeche, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SpacingConstants.SPACING_LARGE),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                OhneEintragTexte.richtetSichNach(typ.name),
                style = MaterialTheme.typography.bodyMedium,
                color = farben.text
            )
            KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = SpacingConstants.SPACING_SMALL) {
                CompactOutlinedButton(onClick = onOeffnen, text = OhneEintragTexte.oeffnen(typ.name))
            }
        }
    }
}
