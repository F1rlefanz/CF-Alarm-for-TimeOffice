package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.ShiftDefinitionTexte
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggestion
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.CodeMarke
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.KategorieMarke
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.SonderfallMarke
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.kategorieFarben
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.LayoutFractions
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.listenCode
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.titel
import java.time.format.DateTimeFormatter

/**
 * Die Texte der Kategorie-Wahl (Prototyp `zeigeKatWahl`, `BILDSCHIRME.md` B2) - wortgleich zur
 * Vorlage, ausser wo die Vorlage mehr verspricht, als der Typ in Etappe 1 kann.
 */
object EinordnenTexte {
    const val TITEL = "Einordnen"
    const val GEHOERT_ZU_TITEL = "Gehört zu einem bestehenden Typ"
    const val GEHOERT_ZU_HINWEIS =
        "Zum Beispiel F und IMCF: fürs Aufstehen dasselbe. Der Code wird dort als weiteres Muster eingetragen."
    const val NEUER_TYP_TITEL = "Oder als neuer Typ in der Kategorie"
    /**
     * Die Vorlage nennt hier auch Dimmen, „Nicht stören“ und Licht - die kommen erst mit Etappe 3/4
     * an den Typ (SPEC 3.3 Punkte 6-8), bis dahin bietet der Editor nur den Wecker. Dann den Satz
     * der Vorlage zurueckholen.
     */
    const val KATEGORIE_HINWEIS =
        "Die Kategorie sortiert nur. Wecker und Weckzeit stellst du danach am Typ selbst ein."

    /** Ein ausgeschalteter Typ wird durch „Gehört zu“ eingeschaltet ([com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig.withCodeAssignedTo]). */
    const val AUSGESCHALTET_WIRD_EINGESCHALTET = "ausgeschaltet – wird dabei eingeschaltet"

    // Der Fund oben in der Liste (Prototyp `zeigeListe`, B1).
    const val FUND_TITEL = "Im Kalender gefunden, noch nicht eingeordnet"
    const val EINORDNEN = "Einordnen …"
    const val IGNORIEREN = "Ignorieren"

    /** Ab so vielen Zeichen kuerzt die Code-Marke (96 dp): dann steht der ganze Code zusaetzlich in der Zeile. */
    private const val LANGER_CODE = 8

    /**
     * Die Zeile neben der Code-Marke: wie oft und wann zuerst. Bewusst KEIN „in den naechsten Wochen“ -
     * die Vorausschau ist eine Einstellung, und die Liste kann nur ein Ausschnitt sein.
     */
    fun fundZeile(vorschlag: ShiftCodeSuggestion): String {
        val tag = vorschlag.firstDate.format(DateTimeFormatter.ofPattern("dd.MM."))
        val wie = if (vorschlag.occurrences > 1) {
            "${vorschlag.occurrences}× im Kalender, zuerst am $tag"
        } else {
            "1× im Kalender, am $tag"
        }
        return if (vorschlag.code.length > LANGER_CODE) "„${vorschlag.code}“ · $wie" else wie
    }

    fun einleitung(code: String, gibtBestehende: Boolean): String =
        if (gibtBestehende) {
            "Wohin gehört „$code“? Zu einem Typ, den es schon gibt – oder als neuer Typ. " +
                "Die Kategorie gibt nur Voreinstellungen vor."
        } else {
            "Wohin gehört „$code“? Es gibt noch keinen Typ, dem es sich anschließen könnte – " +
                "lege einen neuen an. Die Kategorie gibt nur Voreinstellungen vor."
        }
}

/**
 * Die Typen, denen ein Kalender-Code als weiteres Muster angehaengt werden kann (Prototyp :771):
 * alle ausser Ignorieren. Bewusst NICHT `istWaehlbareSchicht()` - Krank und ausgeschaltete Typen
 * bleiben waehlbar (SPEC E2; `withCodeAssignedTo` schaltet ein ausgeschaltetes Ziel ein). Ein Code,
 * der uebergangen werden soll, gehoert ueber „Ignorieren“ zum Ignorieren-Typ, nicht ueber diese Liste.
 */
internal fun gehoertZuZiele(definitionen: List<ShiftDefinition>): List<ShiftDefinition> =
    definitionen.filter { it.kategorie != ShiftKategorie.IGNORIEREN }

/**
 * Die Kategorie-Wahl fuer einen im Kalender gefundenen [code] (SPEC 3.2): erst „Gehört zu einem
 * bestehenden Typ“, dann „Oder als neuer Typ in der Kategorie“.
 *
 * Schreibt nichts. [onGehoertZu] und [onNeuerTyp] melden nur die Wahl; der Aufrufer speichert (und
 * oeffnet bei [onNeuerTyp] erst den Editor - angelegt wird mit „Erstellen“).
 *
 * EIN DIALOG, damit Zurueck ihn schliesst: ein Dialog hat ein eigenes Fenster und bekommt die
 * Zurueck-Taste vor dem `BackHandler` in `MainScreen` - der beendet die App nicht, solange er offen
 * ist (Skill cfalarm-ui-und-navigation).
 */
@Composable
fun EinordnenDialog(
    code: String,
    definitionen: List<ShiftDefinition>,
    onGehoertZu: (ShiftDefinition) -> Unit,
    onNeuerTyp: (ShiftKategorie) -> Unit,
    onDismiss: () -> Unit
) {
    val ziele = gehoertZuZiele(definitionen)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(LayoutFractions.DIALOG_WIDTH)
                .fillMaxHeight(LayoutFractions.DIALOG_HEIGHT)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(SpacingConstants.SPACING_LARGE)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = EinordnenTexte.TITEL,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Schließen")
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

                // weight(1f): sonst schiebt die Liste den Knopf darunter aus dem Dialog.
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM)
                ) {
                    item(key = "einleitung") {
                        Einleitung(EinordnenTexte.einleitung(code, gibtBestehende = ziele.isNotEmpty()))
                    }

                    if (ziele.isNotEmpty()) {
                        item(key = "gehoert-zu") {
                            DialogKarte {
                                Abschnittstitel(EinordnenTexte.GEHOERT_ZU_TITEL)
                                Text(
                                    EinordnenTexte.GEHOERT_ZU_HINWEIS,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = SpacingConstants.SPACING_LARGE,
                                        end = SpacingConstants.SPACING_LARGE,
                                        bottom = SpacingConstants.SPACING_SMALL
                                    )
                                )
                                ziele.forEach { ziel ->
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    BestehenderTyp(ziel = ziel, onClick = { onGehoertZu(ziel) })
                                }
                            }
                        }
                        item(key = "neuer-typ-titel") {
                            Text(
                                EinordnenTexte.NEUER_TYP_TITEL,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = SpacingConstants.SPACING_EXTRA_SMALL)
                                    .semantics { heading() }
                            )
                        }
                    }

                    item(key = "kategorien") {
                        DialogKarte {
                            ShiftKategorie.entries.forEachIndexed { index, kategorie ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                KategorieOption(kategorie = kategorie, onClick = { onNeuerTyp(kategorie) })
                            }
                        }
                    }

                    item(key = "hinweis") {
                        Text(
                            EinordnenTexte.KATEGORIE_HINWEIS,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

                KnopfZeile(modifier = Modifier.fillMaxWidth(), abstand = SpacingConstants.SPACING_SMALL) {
                    CompactOutlinedButton(onClick = onDismiss, text = "Abbrechen")
                }
            }
        }
    }
}

/** Der Kasten oben (Prototyp `.banner`): dieselbe Bernsteinfamilie wie die Marke „Krank“. */
@Composable
private fun Einleitung(text: String) {
    val farben = kategorieFarben(ShiftKategorie.KRANK, isSystemInDarkTheme())
    Surface(shape = RoundedCornerShape(14.dp), color = farben.flaeche, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = farben.text,
            modifier = Modifier.padding(SpacingConstants.SPACING_LARGE)
        )
    }
}

/** Dezente Karte (Skill: nie `surfaceVariant` als Flaeche). */
@Composable
private fun DialogKarte(inhalt: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { inhalt() }
    }
}

@Composable
private fun Abschnittstitel(text: String) {
    Text(
        text,
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
}

/** Eine Zeile „Gehört zu“: Code-Marke, Name, bisherige Muster (Prototyp `zuTyp`). */
@Composable
private fun BestehenderTyp(ziel: ShiftDefinition, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_SMALL),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CodeMarke(code = ziel.listenCode(), kategorie = ziel.kategorie)
        Column(modifier = Modifier.weight(1f)) {
            Text(ziel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val muster = ziel.keywords.filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "–" }
            Text(
                "Muster: $muster" + if (ziel.isEnabled) "" else " · ${EinordnenTexte.AUSGESCHALTET_WIRD_EINGESCHALTET}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Eine Kategorie zum Anlegen (Prototyp `katWahl`): Radio-Kreis (unausgewaehlt wie im Prototyp -
 * ein Tipp waehlt und geht sofort weiter), Farbquadrat, Name mit „Sonderfall“-Marke, Beschreibung.
 */
@Composable
private fun KategorieOption(kategorie: ShiftKategorie, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SpacingConstants.SPACING_LARGE, vertical = SpacingConstants.SPACING_SMALL),
        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_MEDIUM),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Kein Haken im Prototyp (`aria-pressed` ist hier immer aus): der Kreis zeigt „zur Wahl“.
        RadioButton(selected = false, onClick = null)
        KategorieMarke(kategorie, modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            // FlowRow: die Marke „Sonderfall“ bricht bei 320 dp unter den Namen, statt ihn zu quetschen.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_EXTRA_SMALL),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(kategorie.titel(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // Dieselbe Regel wie `schichttypBloecke` (SPEC E3): Rufbereitschaft und Krank.
                if (kategorie == ShiftKategorie.RUFBEREITSCHAFT || kategorie == ShiftKategorie.KRANK) {
                    SonderfallMarke()
                }
            }
            Text(
                ShiftDefinitionTexte.kategorieBeschreibung(kategorie),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
