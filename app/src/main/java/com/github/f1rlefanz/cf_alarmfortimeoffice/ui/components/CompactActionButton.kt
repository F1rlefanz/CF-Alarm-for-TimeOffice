package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Aktions-Buttons für Karten, in denen sich mehrere Buttons eine Zeile teilen.
 *
 * WARUM ES DIE GIBT: Material3 gibt jedem Button 24dp Innenabstand pro Seite (ButtonDefaults
 * .ContentPadding). In einer Kartenzeile frisst das die Breite, die der Beschriftung fehlt — und
 * passt ein Wort nicht hinein, bricht Compose es kommentarlos mitten durch ("Bea/rbei/ten").
 * Diese Varianten nehmen den Innenabstand zurück und lassen nur eine Zeile zu.
 *
 * NEBENEINANDER gehören gleichrangige Knöpfe in [KnopfZeile], NICHT in `Row` + `weight(1f)`: die
 * Gewichtung teilt die Breite unabhängig von der Beschriftung, und dann kürzt dieser Knopf mit "…"
 * ("Bea…", "Neue R…" bei 320 dp, Schrift 1,3) — das ist der Befund, nicht das Soll. Die Kürzung
 * bleibt nur der Notausgang, wenn ein EINZELNER Knopf allein schon breiter als die Zeile ist.
 *
 * NICHT für ganzbreite Buttons verwenden: dort ist ein Umbruch über zwei Zeilen gewollt (etwa bei
 * "Verbindung trennen / Bridge vergessen") und eine erzwungene Kürzung wäre die schlechtere Wahl.
 */
internal val CompactContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
fun CompactButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = CompactContentPadding
    ) {
        CompactLabel(icon = icon, text = text)
    }
}

@Composable
fun CompactOutlinedButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(),
        contentPadding = CompactContentPadding
    ) {
        CompactLabel(icon = icon, text = text)
    }
}

/**
 * Textbutton fuer die einzelne sekundaere Aktion einer Status-Karte (meist der Sprung zu einer
 * Android-Systemeinstellung wie Akku-Optimierung, Bedienungshilfen oder TimeOffice-App-Info; siehe
 * [StatusTabContent] fuer alle Verwendungsstellen). Kein Pill-Button: die Karte beschreibt bereits
 * den Zustand, der Text ist nur der Sprung dorthin — dafuer ohne Innenabstand, damit er buendig
 * unter der Beschreibung sitzt statt einen eigenen Block zu bilden.
 *
 * WARUM [RectangleShape] (Vorfall 29.08.2026): Material3 gibt `TextButton` die Pillen-Form
 * `ButtonDefaults.textShape`, und das darunterliegende `Surface` **clippt** seinen Inhalt an
 * dieser Form. Solange die Beschriftung in EINE Zeile passt, faellt das nicht auf — der Text sitzt
 * dann auf halber Hoehe, wo die Pille am breitesten ist. Bricht sie auf ZWEI Zeilen um, liegen
 * erste und letzte Zeile genau dort, wo die Rundung nach innen zieht: zusammen mit dem hier
 * bewusst auf 0 gesetzten Innenabstand schnitt die Ecke jeweils das fuehrende Zeichen an. Am
 * Geraet gesehen an "Bedienungshilfen-Dienst aktivieren" in der Schicht-Dimmer-Karte, wo aus dem
 * "B" und dem "a" angebissene Buchstaben wurden.
 *
 * Der Innenabstand ist NICHT die Stellschraube — er traegt die buendige Ausrichtung unter der
 * Beschreibung, die diesen Button ueberhaupt ausmacht. Eine Form ohne Ecken loest den Konflikt
 * ohne sie aufzugeben, und da der Button randlos ist, ist die eckige Ripple-Flaeche kein
 * sichtbarer Unterschied.
 */
@Composable
fun SettingsLinkButton(onClick: () -> Unit, text: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RectangleShape,
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text)
    }
}

@Composable
private fun CompactLabel(icon: ImageVector?, text: String) {
    if (icon != null) {
        // dekorativ: das Icon steht hier nie allein - direkt daneben folgt immer `text`,
        // die Beschriftung des Knopfes. Eine eigene Beschreibung liesse den Screenreader
        // dieselbe Aktion zweimal vorlesen.
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
    }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
