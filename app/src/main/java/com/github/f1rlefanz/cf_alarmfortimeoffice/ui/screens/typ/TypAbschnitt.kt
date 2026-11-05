package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SwitchRow
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.KategorieMarke
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel

/** Nutzertexte der Karte „Typ“ (Prototyp `zeigeTyp`, `BILDSCHIRME.md` B3) - wortgleich zur Vorlage. */
internal object TypAbschnittTexte {
    const val ABSCHNITT = "Typ"
    const val NAME = "Name"
    const val MUSTER_TITEL = "Muster im Kalender – alle Codes, die zu diesem Typ gehören"
    const val MUSTER_PLATZHALTER = "Weiteres Muster, z. B. IMCF"
    const val MUSTER_DAZU = "Muster hinzufügen"
    const val KATEGORIE = "Kategorie"

    fun musterEntfernen(muster: String) = "Muster $muster entfernen"

    /** Steht unter dem Eingabefeld, solange dort ein schon vorhandenes Muster steht - „+“ tut dann nichts. */
    const val MUSTER_DOPPELT = "Dieses Muster gibt es schon."

    /** Ohne Muster ist der Typ nicht speicherbar (`TypEntwurf.speicherbar`); ohne den Satz bliebe „Speichern“ grundlos grau. */
    const val KEIN_MUSTER = "Ein Typ braucht mindestens ein Muster."
}

/**
 * Die Rechnung der Karte „Typ“, ohne Compose (Umbau #188). Jede Funktion nimmt einen Entwurf und
 * liefert einen Entwurf - geschrieben wird erst mit „Speichern“ (`TypSeiteSpeicherung`), nie hier.
 */
internal object TypAbschnittLogik {

    /** Die Chips: alles, was nicht leer ist. Ein Typ ohne Muster traegt den Platzhalter `""` (`TypEntwurf.aus`). */
    fun sichtbareMuster(entwurf: TypEntwurf): List<String> = entwurf.muster.filter { it.isNotBlank() }

    /**
     * Gibt es [eingabe] schon? Massstab ist der Vergleich der Erkennung (`equals(ignoreCase = true)`,
     * getrimmt) - „f“ neben „F“ waere dasselbe Muster und braucht keinen zweiten Chip.
     */
    fun istDoppelt(entwurf: TypEntwurf, eingabe: String): Boolean {
        val neu = eingabe.trim()
        return neu.isNotEmpty() && entwurf.muster.any { it.trim().equals(neu, ignoreCase = true) }
    }

    /**
     * „+“: haengt [eingabe] (getrimmt) als Muster an. Ein leeres Muster und ein Duplikat (Gross/klein
     * egal) aendern NICHTS - es kommt derselbe Entwurf zurueck. Der Platzhalter `""` eines Typs ohne
     * Muster weicht dem ersten echten Muster.
     */
    fun musterHinzufuegen(entwurf: TypEntwurf, eingabe: String): TypEntwurf {
        val neu = eingabe.trim()
        if (neu.isEmpty() || istDoppelt(entwurf, neu)) return entwurf
        return entwurf.copy(muster = sichtbareMuster(entwurf) + neu)
    }

    /**
     * „×“: nimmt GENAU dieses Muster heraus (das erste mit diesem Wortlaut) und laesst alle anderen
     * stehen. Bleibt keins uebrig, steht wieder der Platzhalter `""` da - so ist „hinzugefuegt und
     * gleich wieder entfernt“ bei einem Typ ohne Muster derselbe Stand wie vorher (die Rueckfrage
     * „Aenderungen verwerfen?“ vergleicht Listen).
     */
    fun musterEntfernen(entwurf: TypEntwurf, muster: String): TypEntwurf {
        val index = entwurf.muster.indexOf(muster)
        if (index < 0) return entwurf
        val rest = entwurf.muster.filterIndexed { i, _ -> i != index }.filter { it.isNotBlank() }
        return entwurf.copy(muster = rest.ifEmpty { listOf("") })
    }

    /**
     * Der Entwurf, wie er gespeichert wuerde: mit dem Muster, das im „+“-Feld steht und noch nicht
     * hinzugefuegt ist. Sonst ginge getippter Text beim Speichern stumm verloren.
     */
    fun mitEingabe(entwurf: TypEntwurf, eingabe: String): TypEntwurf = musterHinzufuegen(entwurf, eingabe)

    /**
     * Fehlt dem Typ ein Muster - gerechnet wie beim Speichern, also MIT dem Text im „+“-Feld? Nur dann
     * ist „Ein Typ braucht mindestens ein Muster.“ wahr: der Text im Feld wird mitgespeichert.
     */
    fun fehltMuster(entwurf: TypEntwurf, eingabe: String): Boolean =
        sichtbareMuster(mitEingabe(entwurf, eingabe)).isEmpty()

    /**
     * Ein Wechsel der Kategorie behaelt ALLES andere (SPEC E2): Muster, Weckzeit, Wecker an/aus,
     * stummer Wecker. `isSickDay` und `isOnCall` setzt erst das Speichern (`mitKategorie`).
     */
    fun kategorieWechseln(entwurf: TypEntwurf, neu: ShiftKategorie): TypEntwurf = entwurf.copy(kategorie = neu)

    /**
     * Die Warnung zu einem einbuchstabigen Muster - an einem Chip oder im „+“-Feld. Ihr Text haengt
     * nur an Kategorie und Wecker-Schalter, nicht am Muster: ein Satz unter der Chip-Reihe genuegt.
     */
    fun musterWarnung(entwurf: TypEntwurf, eingabe: String): String? =
        (sichtbareMuster(entwurf) + eingabe).firstNotNullOfOrNull {
            ShiftDefinitionTexte.einBuchstabeWarnung(it, entwurf.kategorie, entwurf.weckerAn)
        }
}

/**
 * Die Karte „Typ“ ganz oben auf der Typ-Seite (SPEC 3.3 Punkt 2, Prototyp `zeigeTyp`): „Name“, die Muster
 * als Chips mit × samt Feld und „+“, die Zeile „Kategorie“ mit Farbmarke - und am Ende der Karte der
 * Schalter „Schichtdefinition aktiviert“, den der Prototyp nicht kennt (er bleibt bis Etappe 9).
 *
 * Zustandslos: der Entwurf und der Text im „+“-Feld gehoeren der Seite. [onKategorieAendern] oeffnet nur
 * die Wahl; die Wahl selbst ist [KategorieWahlDialog].
 */
@Composable
internal fun TypAbschnitt(
    entwurf: TypEntwurf,
    bestehenderName: String?,
    neuesMuster: String,
    onNeuesMuster: (String) -> Unit,
    onKategorieAendern: () -> Unit,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    // Weisse Flaeche mit Rand statt `surfaceVariant` (hell identisch mit dem Hintergrund) - ui-texte-und-layout.md.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                TypAbschnittTexte.ABSCHNITT,
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

            OutlinedTextField(
                value = entwurf.name,
                onValueChange = { neu -> aendere { it.copy(name = neu) } },
                label = { Text(TypAbschnittTexte.NAME) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingConstants.SPACING_LARGE),
                singleLine = true,
                isError = entwurf.name.isBlank(),
                supportingText = if (entwurf.istUmbenennungVon(bestehenderName)) {
                    {
                        Text(
                            text = ShiftDefinitionTexte.umbenennungHinweis(bestehenderName.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    null
                }
            )

            MusterBereich(entwurf, neuesMuster, onNeuesMuster, aendere)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KategorieWahlZeile(kategorie = entwurf.kategorie, onClick = onKategorieAendern)
            // Der Text erklaert das WARUM, nicht nur das Was: welches Kuerzel eine Rufbereitschaft ist,
            // weiss nur der Nutzer seiner Station, und ohne die Begruendung („kein Stups vom
            // Kalender“) wirkt der stuendliche Abruf wie Batterieverschwendung (#174).
            if (entwurf.kategorie == ShiftKategorie.RUFBEREITSCHAFT) {
                Text(
                    text = ShiftDefinitionTexte.RUFBEREITSCHAFT_HINWEIS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = SpacingConstants.SPACING_LARGE,
                        end = SpacingConstants.SPACING_LARGE,
                        bottom = SpacingConstants.SPACING_MEDIUM
                    )
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.padding(
                    horizontal = SpacingConstants.SPACING_LARGE,
                    vertical = SpacingConstants.SPACING_MEDIUM
                )
            ) {
                AktiviertSchalter(entwurf, aendere)
            }
        }
    }
}

/** „Muster im Kalender …“: Chips mit ×, darunter das Feld mit „+“, die Warnung und der Hinweis. */
@Composable
private fun MusterBereich(
    entwurf: TypEntwurf,
    neuesMuster: String,
    onNeuesMuster: (String) -> Unit,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    val muster = TypAbschnittLogik.sichtbareMuster(entwurf)
    val doppelt = TypAbschnittLogik.istDoppelt(entwurf, neuesMuster)
    val hinzufuegen = {
        // Ein Duplikat oder ein leeres Feld aendert nichts - der Text bleibt stehen, die Meldung darunter sagt warum.
        if (neuesMuster.isNotBlank() && !doppelt) {
            aendere { TypAbschnittLogik.musterHinzufuegen(it, neuesMuster) }
            onNeuesMuster("")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_MEDIUM),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
    ) {
        Text(
            TypAbschnittTexte.MUSTER_TITEL,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (TypAbschnittLogik.fehltMuster(entwurf, neuesMuster)) {
            Text(
                TypAbschnittTexte.KEIN_MUSTER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (muster.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                muster.forEach { text ->
                    MusterChip(text = text, onEntfernen = { aendere { TypAbschnittLogik.musterEntfernen(it, text) } })
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = neuesMuster,
                onValueChange = onNeuesMuster,
                placeholder = { Text(TypAbschnittTexte.MUSTER_PLATZHALTER) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = doppelt,
                supportingText = if (doppelt) {
                    { Text(TypAbschnittTexte.MUSTER_DOPPELT) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { hinzufuegen() })
            )
            // Mit dem Feld buendig: es ist 56 dp hoch, der Knopf 48 dp (ein Fehlertext darunter schiebt nur das Feld).
            // Neutral wie der Prototyp (`.btn` ohne `pri`): „Speichern“ bleibt der einzige Hauptknopf der Seite.
            OutlinedIconButton(
                onClick = hinzufuegen,
                modifier = Modifier
                    .padding(top = SpacingConstants.SPACING_EXTRA_SMALL)
                    .size(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Icon(Icons.Default.Add, contentDescription = TypAbschnittTexte.MUSTER_DAZU)
            }
        }

        // Ein Satz genuegt: der Text haengt nur an Kategorie und Wecker, nicht am einzelnen Muster.
        TypAbschnittLogik.musterWarnung(entwurf, neuesMuster)?.let { warnung ->
            Text(warnung, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Text(
            ShiftDefinitionTexte.musterHinweis(namensfeld = TypAbschnittTexte.NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Ein Muster als Chip (Prototyp `.muster`): Text und × zum Entfernen. */
@Composable
private fun MusterChip(text: String, onEntfernen: () -> Unit) {
    // Wie `.muster`: Seitenhintergrund auf der weissen Karte, ohne Rand, das × in Grau (`.musterweg`).
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(start = 14.dp, end = SpacingConstants.SPACING_EXTRA_SMALL),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // weight(fill = false): ein langer Code bricht um, statt das × aus der Zeile zu druecken.
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            IconButton(onClick = onEntfernen, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = TypAbschnittTexte.musterEntfernen(text),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Die Zeile „Kategorie“ (Prototyp `.row` mit `typ.katAendern`): Farbmarke, Name der Kategorie, Pfeil. */
@Composable
private fun KategorieWahlZeile(kategorie: ShiftKategorie, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_SMALL),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KategorieMarke(kategorie, modifier = Modifier.size(16.dp))
        // weight(1f): sonst schiebt ein langer Kategoriename den Pfeil aus der Zeile.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                TypAbschnittTexte.KATEGORIE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(kategorie.titel(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        // dekorativ: die ganze Zeile ist der Knopf, ihr Text sagt „Kategorie <Name>“
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/**
 * Der Schalter „Schichtdefinition aktiviert“ - das Ende der Karte „Typ“ (Betreuer #188: der Prototyp kennt ihn nicht, er bleibt bis Etappe 9, weil
 * „aus“ sonst eine Funktion ohne Oberflaeche waere).
 */
@Composable
private fun AktiviertSchalter(
    entwurf: TypEntwurf,
    aendere: ((TypEntwurf) -> TypEntwurf) -> Unit
) {
    // Der Schalter daneben hat eine Erklaerung, dieser hatte keine - und
    // genau daran ist am 19.08.2026 eine Rufbereitschaft gescheitert: sie
    // wurde AUSGESCHALTET angelegt, damit sie nicht klingelt, sollte aber
    // weiterhin "Nicht stoeren" steuern. Ausschalten beendet jedoch die
    // ERKENNUNG, und ohne erkannte Schicht gibt es keine Schichtspanne -
    // also auch kein DND- und kein Dimmer-Fenster. Wer "kein Wecker, aber
    // Zeitfenster" will, schaltet „Wecker an diesem Tag“ aus (#168).
    SwitchRow(
        title = "Schichtdefinition aktiviert",
        description = ShiftDefinitionTexte.aktiviertHinweis(entwurf.kategorie),
        checked = entwurf.isEnabled,
        onCheckedChange = { neu -> aendere { it.copy(isEnabled = neu) } },
        titleFontWeight = FontWeight.Normal
    )
}
