package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants

/**
 * Unterhalb dieser Breite - gemessen in "Schrift-dp", also durch die Schriftskalierung geteilt -
 * stehen Text und Bedienelemente einer Zeile untereinander statt nebeneinander.
 *
 * GEMESSEN (Emulator, 13.10.2026): Die Kartenzeile ist bei 411 dp Anzeigebreite 347 dp breit, bei
 * 320 dp (Fairphone-Einstellung) 256 dp. Bei 320 dp UND Schrift 1,3 (= 197) blieb neben dem Knopf
 * "Überspringen" so wenig Platz, dass Compose mitten im Wort brach ("überspri|ngen",
 * "17.10. 0|5:30"). Bei 320 dp mit normaler Schrift (= 256) und bei 411 dp mit Schrift 1,3 (= 267)
 * brach zwar nur zwischen Woertern, aber in vier schmale Zeilen neben einem breiten Knopf - deshalb
 * die Schwelle darueber. 360-dp-Geraete mit normaler Schrift (= 296) bleiben nebeneinander.
 */
internal val UNTEREINANDER_UNTER = 270.dp

/**
 * Inhalt links, Bedienelemente rechts - und UNTEREINANDER (Bedienelemente rechtsbuendig darunter),
 * wenn daneben keine ganzen Woerter mehr Platz haetten (siehe [UNTEREINANDER_UNTER]).
 *
 * @param inhalt bekommt den Modifier, den es tragen MUSS: nebeneinander `weight(1f)` (sonst schiebt
 *   ein langer Text den Knopf aus der Zeile), untereinander `fillMaxWidth()`.
 */
@Composable
internal fun InhaltUndBedienung(
    inhalt: @Composable (Modifier) -> Unit,
    bedienung: @Composable RowScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val untereinander = maxWidth / LocalDensity.current.fontScale < UNTEREINANDER_UNTER
        if (untereinander) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
            ) {
                inhalt(Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                    verticalAlignment = Alignment.CenterVertically,
                    content = bedienung
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL),
                verticalAlignment = Alignment.CenterVertically
            ) {
                inhalt(Modifier.weight(1f))
                bedienung()
            }
        }
    }
}

/** Ueberschrift + Beschreibung - die Textspalte der Einstellungszeilen. */
@Composable
internal fun TitelUndBeschreibung(titel: String, beschreibung: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(titel, style = MaterialTheme.typography.titleMedium)
        Text(
            beschreibung,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Beschriftete Zeile mit Auswahlknopf rechts - eine Bauart fuer die Werte des Weckton-Anstiegs.
 *
 * @param aktuellerWert wird auch dann angezeigt, wenn er nicht in [optionen] steht - der Wert kann
 *   aus einem Import oder einer aelteren Version stammen. Ihn stillschweigend als eine der
 *   Optionen darzustellen, waere eine Anzeige, die luegt.
 *
 * Bei sehr schmaler Zeile ([UNTEREINANDER_UNTER]) steht der Auswahlknopf UNTER dem Text - bei
 * 320 dp mit Schrift 1,3 brach die Beschreibung sonst mitten im Wort ("Benachrichtigu|ng").
 */
@Composable
internal fun AuswahlRow(
    titel: String,
    beschreibung: String,
    optionen: List<Int>,
    beschriftung: (Int) -> String,
    aktuellerWert: Int,
    aenderungsBeschreibung: String,
    onAuswahl: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    InhaltUndBedienung(
        inhalt = { modifier -> TitelUndBeschreibung(titel, beschreibung, modifier) }
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(beschriftung(aktuellerWert))
                // Nicht dekorativ: die Beschriftung des Knopfes ist nur der Wert - die
                // Ueberschrift steht daneben in einer eigenen Spalte und wird nicht mitgelesen.
                // Ohne diese Beschreibung meldet der Screenreader den Knopf als blosses "30 s",
                // ohne zu sagen, was er aendert.
                Icon(Icons.Default.ArrowDropDown, contentDescription = aenderungsBeschreibung)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                optionen.forEach { wert ->
                    DropdownMenuItem(
                        text = { Text(beschriftung(wert)) },
                        onClick = {
                            onAuswahl(wert)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** Abschnittsueberschrift ueber einer Karte ("Gestellte Wecker", "Wie der Wecker klingt"). */
@Composable
internal fun Abschnittstitel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = SpacingConstants.SPACING_SMALL)
            .semantics { heading() }
    )
}

/**
 * Kleines Etikett ("nächster", ein Kaertchen). Flaeche `surface` + Rand statt `surfaceVariant` -
 * das ist in der hellen Palette dieselbe Farbe wie der Hintergrund (Skill
 * cfalarm-ui-und-navigation).
 */
@Composable
internal fun Etikett(
    text: String,
    hervorgehoben: Boolean = false
) {
    val farbe = if (hervorgehoben) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        contentColor = farbe,
        border = BorderStroke(1.dp, if (hervorgehoben) farbe else MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
