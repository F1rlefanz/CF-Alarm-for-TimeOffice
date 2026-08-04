package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Aktions-Buttons für Karten, in denen sich mehrere Buttons eine Zeile teilen.
 *
 * WARUM ES DIE GIBT: Material3 gibt jedem Button 24dp Innenabstand pro Seite (ButtonDefaults
 * .ContentPadding). Teilen sich zwei `weight(1f)`-Buttons und ein IconButton die Breite einer
 * Karte, bleiben auf einem 360dp-Gerät je ~48dp für die Beschriftung übrig — nach Abzug von Icon
 * und Spacer. Passt ein Wort dort nicht hinein, bricht Compose es kommentarlos mitten durch: aus
 * "Bearbeiten" wurde so "Bea/rbei/ten", aus "Einstellungen" "Einstell/ungen".
 *
 * Diese Varianten nehmen den Innenabstand zurück und lassen nur eine Zeile zu. Reicht der Platz
 * dann immer noch nicht — etwa bei großer Systemschrift —, kürzt Android mit "…". Das ist unschön,
 * aber lesbar, und anders als ein zerlegtes Wort ein ehrliches Signal, dass es eng wird.
 *
 * NICHT für ganzbreite Buttons verwenden: dort ist ein Umbruch über zwei Zeilen gewollt (etwa bei
 * "Verbindung trennen / Bridge vergessen") und eine erzwungene Kürzung wäre die schlechtere Wahl.
 */
private val CompactContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

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
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors()
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
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
 */
@Composable
fun SettingsLinkButton(onClick: () -> Unit, text: String, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Text(text)
    }
}

@Composable
private fun CompactLabel(icon: ImageVector?, text: String) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
    }
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
